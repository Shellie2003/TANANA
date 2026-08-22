package com.example.gesture

import kotlin.math.abs

/**
 * Vocabulaire gestuel.
 *
 * Les poses ont été choisies pour être **mutuellement discriminables** par le seul décompte des
 * doigts tendus : c'est le signal le plus robuste que fournisse un suivi de main monoculaire.
 * Deux poses qui ne diffèrent que par un angle subtil seraient confondues dès que la main pivote.
 */
enum class GestureType {
    /** Aucune main exploitable. */
    IDLE,

    /** Index seul tendu. Pilote le curseur. */
    POINT,

    /** Index et majeur tendus. Pilote le défilement vertical. */
    TWO_FINGER,

    /** Index, majeur et annulaire tendus. Commutation entre applications. */
    THREE_FINGER,

    /** Main grande ouverte. Lecture / pause du média en cours. */
    OPEN_PALM,

    /** Poing fermé. Retour. */
    FIST,

    /** Pouce seul tendu. Accueil. */
    THUMB_UP,

    /**
     * Auriculaire seul tendu. Se distingue de toutes les autres poses par un doigt entier, ce qui
     * en fait un signe franc malgré son apparente discrétion.
     */
    PINKY_UP,

    /**
     * Index et auriculaire tendus, majeur et annulaire repliés — le signe « cornes ». Deux doigts
     * non adjacents : aucune pose du vocabulaire ne s'en approche.
     */
    ROCK,

    /** Balayages, émis comme évènements ponctuels sur les quatre directions. */
    SWIPE_LEFT,
    SWIPE_RIGHT,
    SWIPE_UP,
    SWIPE_DOWN
}

/** Résultat d'une passe de classification. */
data class GestureResult(
    val gesture: GestureType,
    /** Pose de la frame précédente, pour distinguer maintien et changement. */
    val previousGesture: GestureType = GestureType.IDLE,
    /** Vrai sur la seule frame où la pose change : sert à déclencher les actions ponctuelles. */
    val isNewPose: Boolean = false,
    /** Confiance globale 0..1 (qualité géométrique x stabilité temporelle). */
    val confidence: Float = 0f,
    /** Vrai uniquement sur la frame où un balayage est émis. */
    val isSwipeEvent: Boolean = false
)

/** Seuils du classifieur. Extraits en data class pour être ajustables et testables. */
data class GestureConfig(
    // Nombre de frames consécutives requises pour valider un changement de pose.
    val framesToLatch: Int = 3,
    // Balayage.
    val swipeWindowMs: Long = 400L,
    val swipeMinDistance: Float = 0.20f,
    val swipeMinVelocity: Float = 0.80f,
    /**
     * Rapport maximal entre l'axe secondaire et l'axe principal. Un balayage doit être franchement
     * aligné : c'est ce qui empêche un mouvement oblique de produire une direction au hasard, et
     * donc de confondre « notifications » avec « application suivante ».
     */
    val swipeMaxCrossRatio: Float = 0.6f,
    val swipeCooldownMs: Long = 600L,
    // En deçà de cette qualité géométrique, aucune action n'est déclenchée.
    val minQuality: Float = 0.35f,
    // Perte de main tolérée avant remise à zéro complète.
    val handLossGraceMs: Long = 250L
)

/**
 * Machine à états de reconnaissance gestuelle.
 *
 * Quatre mécanismes assurent la stabilité, indispensable dès lors que les gestes pilotent le
 * système entier et non plus une seule application :
 *
 *  1. **Hystérésis sur l'extension des doigts** — un doigt à mi-course ne fait plus osciller la
 *     pose entre deux interprétations.
 *  2. **Verrou temporel** — une pose ne bascule qu'après N frames cohérentes. Le coût, environ
 *     100 ms, est imperceptible ; sans lui, les poses intermédiaires traversées en changeant de
 *     geste déclencheraient des actions parasites.
 *  3. **Filtrage par qualité** — les poses géométriquement invraisemblables sont ignorées.
 *
 * Kotlin pur, sans dépendance Android : entièrement testable en JVM.
 */
class GestureClassifier(private val config: GestureConfig = GestureConfig()) {

    private val scorer = PoseScorer()

    private var latchedPose = GestureType.IDLE
    private var candidatePose = GestureType.IDLE
    private var candidateFrames = 0

    /** Netteté du geste courant : écart entre la meilleure et la deuxième hypothèse. */
    var poseMargin: Float = 0f
        private set

    private val historyTimes = ArrayDeque<Long>()
    private val historyX = ArrayDeque<Float>()
    private val historyY = ArrayDeque<Float>()
    private var lastSwipeTime = Long.MIN_VALUE
    private var lastSeenTime = Long.MIN_VALUE

    fun reset() {
        latchedPose = GestureType.IDLE
        candidatePose = GestureType.IDLE
        candidateFrames = 0
        historyTimes.clear()
        historyX.clear()
        historyY.clear()
    }

    /**
     * Classe une frame. [metrics] à null signifie « aucune main détectée ».
     * [timestampMs] doit être monotone croissant.
     */
    fun update(metrics: HandMetrics?, timestampMs: Long): GestureResult {
        val previous = latchedPose

        if (metrics == null || metrics.quality < config.minQuality) {
            // Courte tolérance : un trou d'une ou deux frames ne doit pas casser un geste en cours.
            if (lastSeenTime != Long.MIN_VALUE &&
                timestampMs - lastSeenTime <= config.handLossGraceMs
            ) {
                return GestureResult(
                    gesture = latchedPose,
                    previousGesture = previous,
                    confidence = 0.3f
                )
            }
            reset()
            lastSeenTime = Long.MIN_VALUE
            return GestureResult(GestureType.IDLE, previous, isNewPose = previous != GestureType.IDLE)
        }

        lastSeenTime = timestampMs

        val rawPose = classifyPose(metrics)

        // Verrou temporel : la pose ne change qu'après N frames cohérentes.
        if (rawPose == candidatePose) {
            candidateFrames++
        } else {
            candidatePose = rawPose
            candidateFrames = 1
        }
        val poseChanged = candidateFrames >= config.framesToLatch && latchedPose != candidatePose
        if (poseChanged) latchedPose = candidatePose

        // Balayage : seulement en pose à trois doigts, dédiée à la navigation entre applications.
        pushHistory(timestampMs, metrics.pointerX, metrics.pointerY)
        val swipe = detectSwipe(timestampMs, allowed = latchedPose == GestureType.THREE_FINGER)

        // La confiance chute pendant une transition de pose : une action déclenchée à cet instant
        // serait probablement une pose intermédiaire mal interprétée.
        val stability = if (candidateFrames >= config.framesToLatch) 1f else 0.6f

        return GestureResult(
            gesture = swipe ?: latchedPose,
            previousGesture = previous,
            isNewPose = poseChanged || swipe != null,
            confidence = (metrics.quality * stability).coerceIn(0f, 1f),
            isSwipeEvent = swipe != null
        )
    }

    /**
     * Détermine la pose brute d'une frame.
     *
     * **Le contact pouce-index ne produit plus de pose.** Il en produisait une — PINCH — et
     * c'était un conflit direct avec le clic, qui est ce même contact : fermer le pouce pour
     * cliquer faisait basculer la pose, donc changer de rôle en pleine action. Selon que le
     * contact durait plus ou moins que le verrou temporel de trois images, le même geste partait
     * en clic ou en glisser. Le contact est désormais lu uniquement par le détecteur de clic, à
     * partir de la distance pouce-index, indépendamment de la pose.
     *
     * Les poses sont donc toutes départagées par score avec marge de rejet : une main ambiguë
     * conserve la pose courante au lieu d'en inventer une.
     */
    private fun classifyPose(m: HandMetrics): GestureType {
        poseMargin = scorer.margin(m)

        // null = observation ambiguë. On conserve la pose courante : c'est précisément ce
        // refus de trancher qui supprime les conflits entre signes voisins.
        return scorer.classify(m) ?: latchedPose
    }

    private fun pushHistory(timestampMs: Long, x: Float, y: Float) {
        historyTimes.addLast(timestampMs)
        historyX.addLast(x)
        historyY.addLast(y)
        while (historyTimes.isNotEmpty() &&
            timestampMs - historyTimes.first() > config.swipeWindowMs
        ) {
            historyTimes.removeFirst()
            historyX.removeFirst()
            historyY.removeFirst()
        }
    }

    /**
     * Un balayage n'est reconnu que si le mouvement est **à la fois** ample, rapide, franchement
     * aligné sur un axe et cohérent en direction. Exiger les quatre critères simultanément
     * supprime les déclenchements accidentels quand la main entre ou sort du champ.
     *
     * Les quatre directions sont traitées par le même code : l'axe dominant est choisi d'abord,
     * puis les critères sont appliqués à cet axe. Deux implémentations parallèles auraient
     * fatalement divergé, et les balayages verticaux se seraient comportés autrement que les
     * horizontaux sans qu'aucun test ne le signale.
     */
    private fun detectSwipe(now: Long, allowed: Boolean): GestureType? {
        if (!allowed) return null
        if (historyTimes.size < 3) return null
        if (lastSwipeTime != Long.MIN_VALUE && now - lastSwipeTime < config.swipeCooldownMs) return null

        val dtMs = historyTimes.last() - historyTimes.first()
        if (dtMs <= 0) return null

        val dx = historyX.last() - historyX.first()
        val dy = historyY.last() - historyY.first()

        val horizontal = abs(dx) >= abs(dy)
        val main = if (horizontal) dx else dy
        val cross = if (horizontal) dy else dx
        val samples = if (horizontal) historyX.toList() else historyY.toList()

        if (abs(main) < config.swipeMinDistance) return null
        if (abs(cross) > abs(main) * config.swipeMaxCrossRatio) return null
        if (abs(main) / (dtMs / 1000f) < config.swipeMinVelocity) return null

        // Cohérence de direction : distingue un balayage franc d'un aller-retour.
        val sign = if (main > 0) 1f else -1f
        var coherent = 0
        for (i in 1 until samples.size) {
            if ((samples[i] - samples[i - 1]) * sign >= -0.005f) coherent++
        }
        val steps = samples.size - 1
        if (steps <= 0 || coherent.toFloat() / steps < 0.75f) return null

        lastSwipeTime = now
        historyTimes.clear()
        historyX.clear()
        historyY.clear()

        return when {
            horizontal && main > 0 -> GestureType.SWIPE_RIGHT
            horizontal -> GestureType.SWIPE_LEFT
            // L'axe Y de l'image croît vers le bas : une main qui monte produit un delta négatif.
            main > 0 -> GestureType.SWIPE_DOWN
            else -> GestureType.SWIPE_UP
        }
    }
}
