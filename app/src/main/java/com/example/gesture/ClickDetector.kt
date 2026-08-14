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
 *  - **Non déclenchable au repos** : un pouce simplement posé près de l'index n'a parcouru aucune
 *    amplitude, donc ne produit aucun clic. C'est le critère qui manquait.
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
         * Amplitude minimale de fermeture : écart entre l'ouverture maximale récente et la
         * position fermée. Une impulsion franche parcourt environ 0,7 ; une main qui se
         * repositionne ne franchit pas 0,3 d'un coup.
         */
        val minAmplitude: Float = 0.30f,
        /**
         * Durée maximale de la fermeture. C'est le second critère, et le plus discriminant :
         * une impulsion volontaire dure une centaine de millisecondes, un repositionnement
         * plusieurs centaines.
         *
         * Amplitude et durée remplacent la vitesse instantanée, qui était mesurée sur une seule
         * image et donc trop bruitée à 30 images par seconde pour servir de critère unique.
         */
        val maxCloseMs: Long = 260L,
        /** Fenêtre sur laquelle on retient l'ouverture maximale servant de référence. */
        val amplitudeWindowMs: Long = 400L,
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
    private var lastClickAt = Long.MIN_VALUE

    // Ouverture maximale récente et instant où elle a été observée : référence de l'amplitude.
    private var recentMax = Float.NEGATIVE_INFINITY
    private var recentMaxAt = Long.MIN_VALUE

    /** Amplitude et durée de la dernière fermeture mesurée, pour le diagnostic et le réglage. */
    var lastAmplitude: Float = 0f
        private set
    var lastCloseDurationMs: Long = 0L
        private set

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
        recentMax = Float.NEGATIVE_INFINITY
        recentMaxAt = Long.MIN_VALUE
        holdProgress = 0f
    }

    /**
     * @param pinchRatio distance pouce-index normalisée par la taille de paume
     * @param cursorX,cursorY position courante du curseur
     * @param nowMs horloge monotone
     */
    fun update(pinchRatio: Float, cursorX: Float, cursorY: Float, nowMs: Long): Event {
        // L'ouverture maximale récente sert de point de départ à l'amplitude. Elle est oubliée
        // après amplitudeWindowMs, sans quoi une main ouverte il y a plusieurs secondes
        // justifierait encore une fermeture lente.
        if (pinchRatio > recentMax || nowMs - recentMaxAt > config.amplitudeWindowMs) {
            recentMax = pinchRatio
            recentMaxAt = nowMs
        }

        if (!closed) {
            if (pinchRatio >= config.closeRatio) return Event.NONE

            val amplitude = recentMax - pinchRatio
            val duration = nowMs - recentMaxAt
            if (amplitude < config.minAmplitude || duration > config.maxCloseMs) {
                // Fermeture trop faible ou trop lente : repositionnement, pas intention de cliquer.
                return Event.NONE
            }

            closed = true
            closedAt = nowMs
            holding = false
            holdProgress = 0f
            lastAmplitude = amplitude
            lastCloseDurationMs = duration
            // Le curseur est figé ici : la validation portera sur la cible visée au moment où le
            // geste a commencé, pas sur celle atteinte après une éventuelle dérive.
            anchorX = cursorX
            anchorY = cursorY
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
            recentMax = pinchRatio
            recentMaxAt = nowMs
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
