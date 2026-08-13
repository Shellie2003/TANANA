package com.example.gesture

import kotlin.math.abs

enum class GestureType {
    IDLE,
    OPEN_PALM,
    POINT,
    PINCH,
    FIST,
    SWIPE_LEFT,
    SWIPE_RIGHT
}

/** Résultat d'une passe de classification. */
data class GestureResult(
    val gesture: GestureType,
    /** Confiance globale 0..1 (qualité géométrique x stabilité temporelle). */
    val confidence: Float = 0f,
    /** Force du pincement 0..1 (1 = doigts joints), utilisable comme valeur analogique. */
    val pinchStrength: Float = 0f,
    /** Vrai uniquement sur la frame où un swipe est émis (évènement ponctuel). */
    val isSwipeEvent: Boolean = false
)

/** Seuils du classifieur. Extraits en data class pour être ajustables et testables. */
data class GestureConfig(
    // Pincement : deux seuils (déclenchement / relâchement) formant un trigger de Schmitt.
    val pinchEnterRatio: Float = 0.34f,
    val pinchExitRatio: Float = 0.48f,
    // Poing : extension moyenne des doigts longs.
    val fistEnterExtension: Float = 0.30f,
    val fistExitExtension: Float = 0.48f,
    // Pointage : index tendu, les autres repliés.
    val pointIndexExtension: Float = 0.70f,
    val pointOtherExtension: Float = 0.40f,
    // Nombre de frames consécutives requises pour valider / invalider un état.
    val framesToLatch: Int = 2,
    val framesToRelease: Int = 3,
    // Swipe.
    val swipeWindowMs: Long = 400L,
    val swipeMinDistance: Float = 0.22f,
    val swipeMinVelocity: Float = 0.85f,
    val swipeMaxVerticalRatio: Float = 0.6f,
    val swipeCooldownMs: Long = 650L,
    // En deçà de cette qualité géométrique, aucune action n'est déclenchée.
    val minQuality: Float = 0.35f,
    // Perte de main tolérée avant remise à zéro complète de la machine à états.
    val handLossGraceMs: Long = 250L
)

/**
 * Machine à états de reconnaissance gestuelle.
 *
 * Trois mécanismes assurent la précision là où une classification « frame par frame » échoue :
 *
 *  1. **Hystérésis** — un seuil d'entrée et un seuil de sortie distincts. Sans cela, un ratio
 *     de pincement oscillant autour d'un seuil unique produit des dizaines de clics parasites
 *     par seconde.
 *  2. **Confirmation temporelle** — un état ne bascule qu'après N frames cohérentes, ce qui
 *     élimine les sursauts d'inférence isolés sans ajouter de latence perceptible (~30 ms).
 *  3. **Filtrage par qualité** — les poses géométriquement invraisemblables sont ignorées
 *     plutôt que classées au hasard.
 *
 * La classe est du Kotlin pur, sans dépendance Android : entièrement testable en JVM.
 */
class GestureClassifier(private val config: GestureConfig = GestureConfig()) {

    private var pinchActive = false
    private var fistActive = false
    private var pinchFrames = 0
    private var releaseFrames = 0
    private var fistFrames = 0
    private var fistReleaseFrames = 0

    private val historyTimes = ArrayDeque<Long>()
    private val historyX = ArrayDeque<Float>()
    private val historyY = ArrayDeque<Float>()
    private var lastSwipeTime = Long.MIN_VALUE
    private var lastSeenTime = Long.MIN_VALUE

    /** Position de départ verrouillée au moment du pincement, pour un « drag » stable. */
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
        pinchActive = false
        fistActive = false
        pinchFrames = 0
        releaseFrames = 0
        fistFrames = 0
        fistReleaseFrames = 0
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

        if (metrics == null || metrics.quality < config.minQuality) {
            // Courte tolérance : un trou d'une ou deux frames ne doit pas casser un geste en cours.
            if (lastSeenTime != Long.MIN_VALUE &&
                timestampMs - lastSeenTime <= config.handLossGraceMs
            ) {
                return GestureResult(
                    gesture = if (pinchActive) GestureType.PINCH
                    else if (fistActive) GestureType.FIST
                    else GestureType.IDLE,
                    confidence = 0.3f,
                    pinchStrength = if (pinchActive) 1f else 0f
                )
            }
            if (pinchActive) pinchJustOpened = true
            reset()
            lastSeenTime = Long.MIN_VALUE
            return GestureResult(GestureType.IDLE, 0f)
        }

        lastSeenTime = timestampMs

        // --- Pincement, avec trigger de Schmitt + confirmation ---
        val wasPinching = pinchActive
        if (!pinchActive) {
            if (metrics.pinchRatio < config.pinchEnterRatio) {
                pinchFrames++
                if (pinchFrames >= config.framesToLatch) {
                    pinchActive = true
                    releaseFrames = 0
                    pinchAnchorX = metrics.pointerX
                    pinchAnchorY = metrics.pointerY
                }
            } else {
                pinchFrames = 0
            }
        } else {
            if (metrics.pinchRatio > config.pinchExitRatio) {
                releaseFrames++
                if (releaseFrames >= config.framesToRelease) {
                    pinchActive = false
                    pinchFrames = 0
                }
            } else {
                releaseFrames = 0
            }
        }
        pinchJustClosed = pinchActive && !wasPinching
        pinchJustOpened = !pinchActive && wasPinching

        // --- Poing ---
        val meanExtension = metrics.fingerExtension.average().toFloat()
        val wasFist = fistActive
        if (!fistActive) {
            // Un poing exige aussi que le pouce soit rabattu : sans ce test, un pincement
            // avec les autres doigts repliés est classé « poing ».
            if (meanExtension < config.fistEnterExtension && metrics.thumbExtension < 0.5f) {
                fistFrames++
                if (fistFrames >= config.framesToLatch) {
                    fistActive = true
                    fistReleaseFrames = 0
                }
            } else {
                fistFrames = 0
            }
        } else {
            if (meanExtension > config.fistExitExtension) {
                fistReleaseFrames++
                if (fistReleaseFrames >= config.framesToRelease) {
                    fistActive = false
                    fistFrames = 0
                }
            } else {
                fistReleaseFrames = 0
            }
        }

        // --- Swipe (évènement ponctuel) ---
        pushHistory(timestampMs, metrics.pointerX, metrics.pointerY)
        val swipe = detectSwipe(timestampMs, allowed = !pinchActive && !fistActive && !wasFist)

        // --- Résolution de priorité ---
        val indexExt = metrics.fingerExtension[0]
        val othersExt = (metrics.fingerExtension[1] + metrics.fingerExtension[2] +
            metrics.fingerExtension[3]) / 3f

        val gesture = when {
            swipe != null -> swipe
            pinchActive -> GestureType.PINCH
            fistActive -> GestureType.FIST
            indexExt > config.pointIndexExtension && othersExt < config.pointOtherExtension ->
                GestureType.POINT
            metrics.extendedFingerCount >= 3 -> GestureType.OPEN_PALM
            else -> GestureType.OPEN_PALM
        }

        // Force analogique du pincement : 1 quand les doigts se touchent, 0 au seuil de sortie.
        val pinchStrength = (1f - (metrics.pinchRatio - 0.08f) /
            (config.pinchExitRatio - 0.08f)).coerceIn(0f, 1f)

        val stability = when {
            pinchActive && releaseFrames == 0 -> 1f
            fistActive && fistReleaseFrames == 0 -> 1f
            else -> 0.85f
        }

        return GestureResult(
            gesture = gesture,
            confidence = (metrics.quality * stability).coerceIn(0f, 1f),
            pinchStrength = pinchStrength,
            isSwipeEvent = swipe != null
        )
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
     * Un swipe n'est reconnu que si le mouvement est **à la fois** ample, rapide et
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

        // Cohérence de direction : tous les pas intermédiaires doivent aller dans le même sens,
        // ce qui distingue un balayage franc d'un aller-retour.
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
