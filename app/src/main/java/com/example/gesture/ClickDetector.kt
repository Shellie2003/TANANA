package com.example.gesture

/**
 * Détection du clic par **impulsion du pouce**.
 *
 * ## Pourquoi une impulsion et non un état
 *
 * La première version reconnaissait le pincement à un état : « le pouce est proche de l'index ».
 * Or c'est la position naturelle de la main qui pointe — d'où des clics involontaires permanents.
 * Le clic par immobilité corrigeait la fiabilité mais imposait d'attendre, ce qui interdit toute
 * cadence rapide.
 *
 * Ici, le clic est un **mouvement bref** : le pouce se rapproche vivement de l'index puis
 * s'écarte, en moins de [Config.maxDurationMs]. Trois conséquences directes :
 *
 *  - **Immédiat** : le clic part au relâchement du pouce, soit environ 150 ms, sans attente
 *    imposée.
 *  - **Non déclenchable au repos** : un pouce simplement posé près de l'index ne produit aucune
 *    vitesse de fermeture, donc aucun clic. C'est le critère qui manquait.
 *  - **Le curseur ne bouge pas** : seul le pouce se déplace, alors que le curseur suit le bout de
 *    l'index. Contrairement au clic de souris par flexion du doigt de visée, la cible reste donc
 *    parfaitement immobile pendant la validation.
 *
 * Maintenir le pouce fermé au-delà de la durée d'impulsion bascule en mode maintenu : c'est ce
 * qui distingue le clic du glisser et de l'appui long, sans geste supplémentaire à apprendre.
 *
 * Kotlin pur, sans dépendance Android : entièrement testable en JVM.
 */
class ClickDetector(private val config: Config = Config()) {

    data class Config(
        /** Ratio pouce/index sous lequel le pouce est considéré fermé. */
        val closeRatio: Float = 0.34f,
        /** Ratio au-dessus duquel il est de nouveau ouvert. Écart = hystérésis. */
        val openRatio: Float = 0.46f,
        /**
         * Vitesse minimale de fermeture, en unités de ratio par seconde. C'est le critère
         * décisif : il sépare un pouce que l'on approche volontairement d'un pouce déjà là.
         */
        val minCloseSpeed: Float = 0.9f,
        /** Au-delà de cette durée, le pouce fermé n'est plus un clic mais un maintien. */
        val maxDurationMs: Long = 400L,
        /** Intervalle minimal entre deux clics, pour absorber les rebonds. */
        val cooldownMs: Long = 180L
    )

    /** Ce que la frame courante produit. */
    enum class Event {
        /** Rien. */
        NONE,

        /** Impulsion complète : clic immédiat. */
        CLICK,

        /** Le pouce reste fermé : début d'un maintien (glisser ou appui long). */
        HOLD_START,

        /** Fin du maintien. */
        HOLD_END
    }

    private var closed = false
    private var closedAt = 0L
    private var holding = false
    private var lastRatio = Float.NaN
    private var lastTime = Long.MIN_VALUE
    private var lastClickAt = Long.MIN_VALUE

    /** Position du curseur figée à l'instant où le pouce a commencé à se fermer. */
    var anchorX: Float = 0f
        private set
    var anchorY: Float = 0f
        private set

    /** Progression du maintien, 0..1, pour un retour visuel avant bascule en glisser. */
    var holdProgress: Float = 0f
        private set

    fun reset() {
        closed = false
        holding = false
        lastRatio = Float.NaN
        lastTime = Long.MIN_VALUE
        holdProgress = 0f
    }

    /**
     * @param pinchRatio distance pouce-index normalisée par la taille de paume
     * @param cursorX,cursorY position courante du curseur
     * @param nowMs horloge monotone
     */
    fun update(pinchRatio: Float, cursorX: Float, cursorY: Float, nowMs: Long): Event {
        val previousRatio = lastRatio
        val previousTime = lastTime
        lastRatio = pinchRatio
        lastTime = nowMs

        if (previousRatio.isNaN() || previousTime == Long.MIN_VALUE) return Event.NONE
        val dt = ((nowMs - previousTime).coerceAtLeast(1L)).toFloat() / 1000f

        // Vitesse de fermeture : positive quand le pouce se rapproche de l'index.
        val closingSpeed = (previousRatio - pinchRatio) / dt

        if (!closed) {
            if (pinchRatio < config.closeRatio && closingSpeed >= config.minCloseSpeed) {
                closed = true
                closedAt = nowMs
                holding = false
                holdProgress = 0f
                // Le curseur est figé ici : la validation portera sur la cible visée au moment
                // où le geste a commencé, pas sur celle atteinte après une éventuelle dérive.
                anchorX = cursorX
                anchorY = cursorY
            }
            return Event.NONE
        }

        // Pouce fermé depuis assez longtemps : ce n'est plus un clic mais un maintien.
        val heldMs = nowMs - closedAt
        holdProgress = (heldMs.toFloat() / config.maxDurationMs).coerceIn(0f, 1f)
        if (!holding && heldMs > config.maxDurationMs) {
            holding = true
            return Event.HOLD_START
        }

        if (pinchRatio > config.openRatio) {
            closed = false
            holdProgress = 0f
            if (holding) {
                holding = false
                return Event.HOLD_END
            }
            if (lastClickAt != Long.MIN_VALUE && nowMs - lastClickAt < config.cooldownMs) {
                return Event.NONE
            }
            lastClickAt = nowMs
            return Event.CLICK
        }

        return Event.NONE
    }

    /** Vrai tant qu'un maintien est en cours. */
    val isHolding: Boolean get() = holding
}
