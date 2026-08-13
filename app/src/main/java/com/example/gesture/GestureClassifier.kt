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

    /** Pouce et index joints. Clic, et glisser tant que le pincement est maintenu. */
    PINCH,

    /** Balayages horizontaux, émis comme évènements ponctuels. */
    SWIPE_LEFT,
    SWIPE_RIGHT
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
    /** Force du pincement 0..1 (1 = doigts joints), utilisable comme valeur analogique. */
    val pinchStrength: Float = 0f,
    /** Vrai uniquement sur la frame où un balayage est émis. */
    val isSwipeEvent: Boolean = false
)

/** Seuils du classifieur. Extraits en data class pour être ajustables et testables. */
data class GestureConfig(
    // Pincement : deux seuils formant un trigger de Schmitt.
    val pinchEnterRatio: Float = 0.34f,
    val pinchExitRatio: Float = 0.48f,
    // Un doigt est considéré tendu au-dessus de ce seuil, replié en dessous de l'autre.
    // L'écart entre les deux empêche un doigt à mi-course de faire osciller la pose.
    val fingerExtendedThreshold: Float = 0.62f,
    val fingerFoldedThreshold: Float = 0.42f,
    val thumbExtendedThreshold: Float = 0.55f,
    // Nombre de frames consécutives requises pour valider un changement de pose.
    val framesToLatch: Int = 3,
    // Balayage.
    val swipeWindowMs: Long = 400L,
    val swipeMinDistance: Float = 0.20f,
    val swipeMinVelocity: Float = 0.80f,
    val swipeMaxVerticalRatio: Float = 0.6f,
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
 *  3. **Trigger de Schmitt sur le pincement** — seuils d'entrée et de sortie distincts.
 *  4. **Filtrage par qualité** — les poses géométriquement invraisemblables sont ignorées.
 *
 * Kotlin pur, sans dépendance Android : entièrement testable en JVM.
 */
class GestureClassifier(private val config: GestureConfig = GestureConfig()) {

    private var latchedPose = GestureType.IDLE
    private var candidatePose = GestureType.IDLE
    private var candidateFrames = 0

    private var pinchActive = false

    private val historyTimes = ArrayDeque<Long>()
    private val historyX = ArrayDeque<Float>()
    private val historyY = ArrayDeque<Float>()
    private var lastSwipeTime = Long.MIN_VALUE
    private var lastSeenTime = Long.MIN_VALUE

    /** Position du curseur au moment où le pincement s'est refermé, pour un glisser stable. */
    var pinchAnchorX: Float = 0f
        private set
    var pinchAnchorY: Float = 0f
        private set

    /** Vrai sur la seule frame où le pincement se referme (front montant = clic). */
    var pinchJustClosed: Boolean = false
        private set

    /** Vrai sur la seule frame où le pincement se rouvre (front descendant = relâchement). */
    var pinchJustOpened: Boolean = false
        private set

    fun reset() {
        latchedPose = GestureType.IDLE
        candidatePose = GestureType.IDLE
        candidateFrames = 0
        pinchActive = false
        pinchJustClosed = false
        pinchJustOpened = false
        historyTimes.clear()
        historyX.clear()
        historyY.clear()
    }

    /**
     * Classe une frame. [metrics] à null signifie « aucune main détectée ».
     * [timestampMs] doit être monotone croissant.
     */
    fun update(metrics: HandMetrics?, timestampMs: Long): GestureResult {
        pinchJustClosed = false
        pinchJustOpened = false
        val previous = latchedPose

        if (metrics == null || metrics.quality < config.minQuality) {
            // Courte tolérance : un trou d'une ou deux frames ne doit pas casser un geste en cours.
            if (lastSeenTime != Long.MIN_VALUE &&
                timestampMs - lastSeenTime <= config.handLossGraceMs
            ) {
                return GestureResult(
                    gesture = latchedPose,
                    previousGesture = previous,
                    confidence = 0.3f,
                    pinchStrength = if (pinchActive) 1f else 0f
                )
            }
            if (pinchActive) pinchJustOpened = true
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

        // Fronts du pincement, dérivés de la pose verrouillée.
        val nowPinching = latchedPose == GestureType.PINCH
        if (nowPinching && !pinchActive) {
            pinchActive = true
            pinchJustClosed = true
            pinchAnchorX = metrics.pointerX
            pinchAnchorY = metrics.pointerY
        } else if (!nowPinching && pinchActive) {
            pinchActive = false
            pinchJustOpened = true
        }

        // Balayage : seulement en pose à trois doigts, dédiée à la navigation entre applications.
        pushHistory(timestampMs, metrics.pointerX, metrics.pointerY)
        val swipe = detectSwipe(timestampMs, allowed = latchedPose == GestureType.THREE_FINGER)

        val pinchStrength = (1f - (metrics.pinchRatio - 0.08f) /
            (config.pinchExitRatio - 0.08f)).coerceIn(0f, 1f)

        // La confiance chute pendant une transition de pose : une action déclenchée à cet instant
        // serait probablement une pose intermédiaire mal interprétée.
        val stability = if (candidateFrames >= config.framesToLatch) 1f else 0.6f

        return GestureResult(
            gesture = swipe ?: latchedPose,
            previousGesture = previous,
            isNewPose = poseChanged || swipe != null,
            confidence = (metrics.quality * stability).coerceIn(0f, 1f),
            pinchStrength = pinchStrength,
            isSwipeEvent = swipe != null
        )
    }

    /**
     * Détermine la pose brute d'une frame, sans mémoire autre que l'hystérésis du pincement.
     *
     * L'ordre des tests encode les priorités : le pincement l'emporte sur tout, car c'est le seul
     * geste d'action directe ; le poing vient ensuite, car il est géométriquement le plus éloigné
     * des autres ; les poses à doigts comptés se départagent enfin.
     */
    private fun classifyPose(m: HandMetrics): GestureType {
        // Pincement : seuil d'entrée strict, seuil de sortie plus large.
        val pinchThreshold = if (pinchActive) config.pinchExitRatio else config.pinchEnterRatio
        if (m.pinchRatio < pinchThreshold) return GestureType.PINCH

        val index = m.fingerExtension[0]
        val middle = m.fingerExtension[1]
        val ring = m.fingerExtension[2]
        val pinky = m.fingerExtension[3]
        val thumb = m.thumbExtension

        val extended = { v: Float -> v > config.fingerExtendedThreshold }
        val folded = { v: Float -> v < config.fingerFoldedThreshold }

        // Poing : les quatre doigts repliés et le pouce rabattu sur la paume.
        if (folded(index) && folded(middle) && folded(ring) && folded(pinky) &&
            thumb < config.thumbExtendedThreshold
        ) {
            return GestureType.FIST
        }

        // Pouce levé : le pouce seul sort d'un poing.
        if (folded(index) && folded(middle) && folded(ring) && folded(pinky) &&
            thumb >= config.thumbExtendedThreshold
        ) {
            return GestureType.THUMB_UP
        }

        // Main ouverte : au moins quatre doigts longs tendus.
        if (extended(index) && extended(middle) && extended(ring) && extended(pinky)) {
            return GestureType.OPEN_PALM
        }

        // Trois doigts : index, majeur, annulaire tendus, auriculaire replié.
        if (extended(index) && extended(middle) && extended(ring) && folded(pinky)) {
            return GestureType.THREE_FINGER
        }

        // Deux doigts : index et majeur tendus, annulaire et auriculaire repliés.
        if (extended(index) && extended(middle) && folded(ring) && folded(pinky)) {
            return GestureType.TWO_FINGER
        }

        // Index seul.
        if (extended(index) && folded(middle) && folded(ring) && folded(pinky)) {
            return GestureType.POINT
        }

        // Pose ambiguë : on conserve la pose courante plutôt que de retomber sur une valeur
        // arbitraire, ce qui éviterait un clignotement pendant les transitions.
        return latchedPose
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
     * Un balayage n'est reconnu que si le mouvement est **à la fois** ample, rapide et
     * majoritairement horizontal. Exiger les trois critères simultanément supprime les
     * déclenchements accidentels quand la main entre ou sort du champ.
     */
    private fun detectSwipe(now: Long, allowed: Boolean): GestureType? {
        if (!allowed) return null
        if (historyTimes.size < 3) return null
        if (lastSwipeTime != Long.MIN_VALUE && now - lastSwipeTime < config.swipeCooldownMs) return null

        val dtMs = historyTimes.last() - historyTimes.first()
        if (dtMs <= 0) return null

        val dx = historyX.last() - historyX.first()
        val dy = historyY.last() - historyY.first()
        if (abs(dx) < config.swipeMinDistance) return null
        if (abs(dy) > abs(dx) * config.swipeMaxVerticalRatio) return null

        val velocity = abs(dx) / (dtMs / 1000f)
        if (velocity < config.swipeMinVelocity) return null

        // Cohérence de direction : distingue un balayage franc d'un aller-retour.
        val sign = if (dx > 0) 1f else -1f
        var coherent = 0
        var steps = 0
        val xs = historyX.toList()
        for (i in 1 until xs.size) {
            val step = xs[i] - xs[i - 1]
            steps++
            if (step * sign >= -0.005f) coherent++
        }
        if (steps == 0 || coherent.toFloat() / steps < 0.75f) return null

        lastSwipeTime = now
        historyTimes.clear()
        historyX.clear()
        historyY.clear()

        return if (dx > 0) GestureType.SWIPE_RIGHT else GestureType.SWIPE_LEFT
    }
}
