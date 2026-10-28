package com.example.control

import kotlin.math.abs

/**
 * Défilement par les bords : poser le curseur contre un bord de l'écran y fait défiler, en continu,
 * **sans pincer**.
 *
 * ## Pourquoi une seconde méthode, et pourquoi celle-ci
 *
 * Le défilement existant est bon dans son principe — on pince, on déplace la main, le système voit
 * un doigt posé et en fait ce qu'il sait faire — et il n'est pas question de le remplacer : c'est
 * lui qui donne un défilement fluide, continu, à l'amplitude qu'on veut. Mais il hérite de deux
 * coûts qu'aucun réglage ne retire.
 *
 * Le premier est le **pincement maintenu**. Tant qu'on défile, le pouce doit rester sur l'index, et
 * c'est précisément l'instant où la caméra voit le moins bien la main : les deux extrémités se
 * confondent dans l'image. Tout le défilement dépend donc de la mesure la plus fragile de la
 * chaîne, pendant toute sa durée — là où un appui ne lui demande qu'un instant.
 *
 * Le second est l'**effort par unité de distance**. Un écran de liste se parcourt en déplaçant la
 * main d'autant de fois qu'il y a d'écrans, pincement tenu, avec un recentrage à chaque butée. Pour
 * un article long, c'est dix gestes là où un pouce sur une vitre en demande deux.
 *
 * Le défilement par les bords supprime les deux. On amène le curseur dans une bande de bord, on
 * **ne fait plus rien**, et le contenu défile tant qu'on y reste. Aucun doigt à articuler, aucun
 * geste à répéter, aucune posture à tenir : seulement une position, c'est-à-dire la grandeur la
 * mieux mesurée de toute la chaîne.
 *
 * ## Pourquoi par les actions d'accessibilité, et non par un tracé
 *
 * Le défilement au pincement synthétise un doigt : il faut trouver la zone défilable, y poser un
 * point, le déplacer, le lever. Ici rien de tout cela — on demande au nœud défilable de défiler,
 * par `ACTION_SCROLL_*`. Trois conséquences, toutes bonnes : le bruit de suivi de la main ne peut
 * rien perturber puisqu'aucune trajectoire n'est transmise ; le pas de défilement est celui que
 * l'application a choisi pour elle-même, donc toujours cohérent avec son contenu ; et il n'y a
 * aucun doigt synthétique susceptible de rester posé si le geste tourne mal.
 *
 * ## Ce qui empêche le déclenchement accidentel
 *
 * Une bande de bord est traversée en permanence, ne serait-ce que pour viser ce qui s'y trouve. Un
 * délai d'amorçage ([Config.dwellMs]) est donc exigé : il faut **rester** dans la bande, non la
 * traverser. Et le bord droit est laissé au panneau de commandes, qui s'y ouvre déjà — deux
 * mécanismes sur le même bord seraient indistinguables pour la main.
 *
 * Kotlin pur, sans dépendance Android : entièrement testable en JVM.
 */
class EdgeScroller(private val config: Config = Config()) {

    data class Config(
        /**
         * Épaisseur des bandes de bord, en fraction d'écran.
         *
         * Assez large pour être atteinte sans précision — c'est tout l'intérêt, puisque viser un
         * bord ne demande alors aucune finesse — et assez fine pour laisser le reste de l'écran
         * libre.
         */
        val band: Float = 0.08f,

        /**
         * Durée de présence dans la bande avant que le défilement commence.
         *
         * C'est ce qui sépare « se poser dans la bande » de « la traverser ». Sans ce délai, viser
         * un bouton situé en haut de l'écran déclencherait un défilement avant qu'on l'atteigne.
         */
        val dwellMs: Long = 320L,

        /** Intervalle entre deux pas de défilement, au régime le plus lent. */
        val slowestIntervalMs: Long = 320L,

        /** Intervalle entre deux pas au régime le plus rapide, curseur au ras du bord. */
        val fastestIntervalMs: Long = 90L,

        /**
         * Marge haute et basse exclue des bandes verticales, en fraction d'écran.
         *
         * Les coins appartiendraient sinon à deux bandes à la fois, et la direction obtenue
         * dépendrait de l'ordre d'évaluation — donc serait imprévisible pour qui s'y trouve.
         */
        val cornerMargin: Float = 0.12f
    )

    /** Sens d'un pas de défilement, dans le vocabulaire du contenu et non celui de la main. */
    enum class Direction { UP, DOWN, LEFT, RIGHT }

    /**
     * État observable, pour que la superposition puisse le montrer.
     *
     * Un défilement qui part sans que rien ne l'annonce serait vécu comme une dérive de
     * l'application. La progression de l'amorçage alimente donc l'anneau du curseur, celui-là même
     * qui porte l'appui long et le clic par maintien : les trois sont mutuellement exclusifs, et ce
     * sont pour l'œil la même information — un compte à rebours qu'un mouvement annule.
     */
    data class State(
        val direction: Direction,
        /** Progression de l'amorçage, 0..1. À 1, le défilement est en cours. */
        val progress: Float
    )

    var state: State? = null
        private set

    private var zone: Direction? = null
    private var since = 0L
    private var lastStepAt = Long.MIN_VALUE

    fun reset() {
        state = null
        zone = null
        lastStepAt = Long.MIN_VALUE
    }

    /**
     * Traite une image et dit s'il faut défiler d'un pas.
     *
     * @param pressed vrai si le bouton est enfoncé. Le défilement par les bords est alors **inhibé**
     *   : un pincement en cours signifie que l'utilisateur mène un geste à la main, et deux
     *   défilements simultanés se combattraient.
     * @return la direction d'un pas à effectuer, ou null.
     */
    fun update(x: Float, y: Float, pressed: Boolean, nowMs: Long): Direction? {
        val current = if (pressed) null else zoneAt(x, y)

        if (current == null) {
            reset()
            return null
        }

        if (current != zone) {
            zone = current
            since = nowMs
            lastStepAt = Long.MIN_VALUE
        }

        val held = nowMs - since
        if (held < config.dwellMs) {
            state = State(current, held.toFloat() / config.dwellMs)
            return null
        }
        state = State(current, 1f)

        val interval = intervalFor(x, y, current)
        if (lastStepAt != Long.MIN_VALUE && nowMs - lastStepAt < interval) return null
        lastStepAt = nowMs
        return current
    }

    /**
     * À quelle bande ce point appartient-il ?
     *
     * Le bord **droit** est délibérément absent : le panneau de commandes s'y ouvre
     * ([com.example.input.CommandPanel]). Deux mécanismes différents sur le même bord seraient
     * indistinguables pour la main, et le panneau y était le premier.
     *
     * Le défilement horizontal se fait donc par le seul bord gauche, et la direction y dépend de la
     * **hauteur** : moitié haute pour revenir en arrière, moitié basse pour avancer. C'est un
     * compromis assumé — un seul bord pour deux sens — mais le défilement horizontal est rare, et
     * la solution de rechange serait de prendre au panneau le bord qu'il occupe.
     */
    private fun zoneAt(x: Float, y: Float): Direction? {
        // Bandes verticales d'abord, et sans leurs coins : ce sont les plus utilisées, et leur
        // direction est la plus évidente — on pousse vers le haut, le contenu monte.
        if (y <= config.band && x > config.cornerMargin && x < 1f - config.cornerMargin) {
            return Direction.UP
        }
        if (y >= 1f - config.band && x > config.cornerMargin && x < 1f - config.cornerMargin) {
            return Direction.DOWN
        }
        if (x <= config.band) {
            return if (y < 0.5f) Direction.LEFT else Direction.RIGHT
        }
        return null
    }

    /**
     * Cadence du défilement : d'autant plus rapide que le curseur est enfoncé dans la bande.
     *
     * C'est ce qui rend la méthode utilisable pour les deux usages opposés. Au bord de la bande, le
     * contenu avance ligne à ligne, de quoi chercher quelque chose. Au ras du bord de l'écran, il
     * défile vite, de quoi traverser un article. Et le passage de l'un à l'autre est continu, piloté
     * par la seule position de la main — donc sans réglage à ouvrir.
     */
    private fun intervalFor(x: Float, y: Float, direction: Direction): Long {
        val depth = when (direction) {
            Direction.UP -> 1f - (y / config.band)
            Direction.DOWN -> 1f - ((1f - y) / config.band)
            Direction.LEFT, Direction.RIGHT -> 1f - (x / config.band)
        }.coerceIn(0f, 1f)

        val span = (config.slowestIntervalMs - config.fastestIntervalMs).toFloat()
        return (config.slowestIntervalMs - span * depth).toLong()
            .coerceAtLeast(config.fastestIntervalMs)
    }

    /** Vrai si ce point est dans une bande de bord : sert à ne pas y masquer le curseur. */
    fun isInBand(x: Float, y: Float): Boolean = zoneAt(x, y) != null

    companion object {
        /** Les deux bandes verticales se touchent-elles ? Garde-fou de cohérence de réglage. */
        fun bandsAreDisjoint(config: Config): Boolean = abs(1f - 2f * config.band) > 1e-3f
    }
}
