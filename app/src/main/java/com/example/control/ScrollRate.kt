package com.example.control

import kotlin.math.abs

/**
 * Défilement à **vitesse commandée** : un petit décalage de la main, tenu, défile indéfiniment.
 *
 * ## Pourquoi une troisième méthode, et pourquoi celle-ci est d'une autre nature
 *
 * Les deux méthodes précédentes sont en **contrôle de position** : la main se déplace, le contenu
 * se déplace d'autant. C'est le modèle du doigt sur la vitre, et il hérite d'une limite que ni l'une
 * ni l'autre ne peut lever — la distance défilable est bornée par la distance que le bras peut
 * parcourir. Un article long demande donc de répéter le geste, et répéter un geste aérien fatigue
 * bien plus vite que répéter un geste appuyé sur du verre.
 *
 * Le défilement par les bords contourne la borne par une répétition automatique, mais au prix de
 * garder le curseur dans une bande étroite au bord de l'écran — donc loin de ce qu'on lit, et sur
 * une zone où se trouvent souvent des barres d'onglets.
 *
 * Ici le **décalage commande une vitesse**, pas une distance. Tenir la main dix pour cent plus bas
 * que son point neutre fait défiler lentement et sans fin ; trente pour cent plus bas, vite. Trois
 * propriétés en découlent, et aucune n'est atteignable en contrôle de position :
 *
 *  1. **La distance défilée est sans rapport avec la distance parcourue par la main.** Un livre
 *     entier se lit sans déplacer le bras de plus de quelques centimètres.
 *  2. **La vitesse est réglée en continu, pendant le défilement.** On ralentit en approchant de ce
 *     qu'on cherche, exactement comme on relâche un accélérateur — là où un geste de position est
 *     joué puis constaté.
 *  3. **Le point neutre est là où la main se trouvait**, donc au milieu de l'écran si on lisait.
 *     Rien à viser, aucun bord à atteindre.
 *
 * Son coût propre est d'être un **mode** : on y entre et on en sort. C'est assumé, et c'est la
 * raison pour laquelle il n'est pas lié à un geste mais à une commande du panneau — un mode dans
 * lequel on tombe par accident est bien pire qu'un mode qu'on choisit. Tout pincement en sort
 * également, de sorte qu'il existe toujours une façon de sortir qui ne demande pas de retrouver le
 * panneau.
 *
 * Kotlin pur, sans dépendance Android : entièrement testable en JVM.
 */
class ScrollRate(private val config: Config = Config()) {

    data class Config(
        /**
         * Rayon autour du point neutre dans lequel rien ne défile, en fraction d'écran.
         *
         * Indispensable, et pas seulement confortable : sans zone morte, le tremblement d'une main
         * tenue en l'air commanderait une vitesse non nulle, et le contenu dériverait en
         * permanence. Sa taille est celle de la dérive involontaire sur quelques secondes, et non
         * celle du bruit par image — ce qu'il faut absorber est la lente descente du bras qui
         * fatigue, pas le frémissement.
         */
        val deadZone: Float = 0.06f,

        /** Décalage au-delà duquel la vitesse est maximale. */
        val fullScaleOffset: Float = 0.28f,

        /** Intervalle entre deux pas juste à la sortie de la zone morte. */
        val slowestIntervalMs: Long = 420L,

        /** Intervalle entre deux pas à pleine échelle. */
        val fastestIntervalMs: Long = 70L,

        /**
         * Avantage donné à la verticale pour départager l'axe.
         *
         * L'horizontale doit l'emporter nettement, et non de peu : la quasi-totalité du contenu
         * défile verticalement, et une main qui descend ne descend jamais parfaitement droit. Sans
         * cet avantage, un défilement vertical un peu oblique partirait de côté.
         */
        val verticalBias: Float = 1.6f,

        /**
         * Rayon dans lequel la main doit se tenir pour que le point neutre soit pris.
         *
         * **Le point neutre ne peut pas être pris à l'instant de l'activation**, et c'est un défaut
         * qu'il a fallu voir avant qu'il n'existe : la commande se choisit dans le panneau, donc au
         * bord **droit** de l'écran. Fixer le point neutre là ferait commander un défilement vers
         * la gauche dès que la main revient vers ce qu'elle lit — le mode se mettrait à défiler tout
         * seul, dans le mauvais sens, au moment même de son activation.
         *
         * Le mode s'arme donc, puis attend que la main se **pose** : la première position tenue
         * [settleMs] devient le point neutre. Cela correspond exactement au geste réel — on choisit
         * la commande, on ramène la main devant ce qu'on lit, on s'arrête, et c'est là que zéro est
         * défini.
         */
        val settleRadius: Float = 0.035f,

        /** Durée d'immobilité qui fixe le point neutre. */
        val settleMs: Long = 300L
    )

    /** État observable, pour l'afficher : sans retour visible, un mode est un piège. */
    data class State(
        val neutralX: Float,
        val neutralY: Float,
        val direction: EdgeScroller.Direction?,
        /** Vitesse commandée, 0..1. Nulle dans la zone morte. */
        val rate: Float,
        /**
         * Faux tant que le point neutre n'est pas fixé : le mode est armé et attend que la main se
         * pose. Rien ne défile dans cet intervalle.
         */
        val ready: Boolean = true
    )

    var state: State? = null
        private set

    val isActive: Boolean get() = state != null

    private var neutralX = 0.5f
    private var neutralY = 0.5f
    private var lastStepAt = Long.MIN_VALUE

    /** Le point neutre n'est pas encore fixé : le mode attend que la main se pose. */
    private var awaitingNeutral = false
    private var settleX = 0.5f
    private var settleY = 0.5f
    private var settleSince = 0L

    /**
     * Arme le mode. Le point neutre n'est **pas** pris ici.
     *
     * Il est pris à la première position que la main tient [Config.settleMs], parce que l'instant
     * de l'activation est le pire moment possible : la commande se choisit dans le panneau, au bord
     * droit de l'écran. Voir [Config.settleRadius].
     */
    fun enter(x: Float, y: Float) {
        awaitingNeutral = true
        settleX = x
        settleY = y
        settleSince = Long.MIN_VALUE
        lastStepAt = Long.MIN_VALUE
        state = State(x, y, null, 0f, ready = false)
    }

    fun exit() {
        state = null
        awaitingNeutral = false
        settleSince = Long.MIN_VALUE
        lastStepAt = Long.MIN_VALUE
    }

    /** Entre ou sort, et dit si le mode est actif ensuite. */
    fun toggle(x: Float, y: Float): Boolean {
        if (isActive) exit() else enter(x, y)
        return isActive
    }

    /**
     * Traite une image et dit s'il faut défiler d'un pas.
     *
     * @param pressed vrai si le bouton est enfoncé : le mode se **termine** alors. C'est la sortie
     *   de secours — elle ne demande pas de retrouver le panneau, et elle est le geste que la main
     *   fait spontanément quand on veut reprendre la main sur le contenu.
     */
    fun update(x: Float, y: Float, pressed: Boolean, nowMs: Long): EdgeScroller.Direction? {
        if (!isActive) return null
        if (pressed) {
            exit()
            return null
        }

        if (awaitingNeutral) {
            settleNeutral(x, y, nowMs)
            return null
        }

        val dx = x - neutralX
        val dy = y - neutralY
        val direction = axisOf(dx, dy)

        if (direction == null) {
            state = State(neutralX, neutralY, null, 0f)
            return null
        }

        val offset = if (direction == EdgeScroller.Direction.UP ||
            direction == EdgeScroller.Direction.DOWN
        ) {
            abs(dy)
        } else {
            abs(dx)
        }

        val rate = ((offset - config.deadZone) / (config.fullScaleOffset - config.deadZone))
            .coerceIn(0f, 1f)
        state = State(neutralX, neutralY, direction, rate)

        val span = (config.slowestIntervalMs - config.fastestIntervalMs).toFloat()
        val interval = (config.slowestIntervalMs - span * rate).toLong()
            .coerceAtLeast(config.fastestIntervalMs)

        if (lastStepAt != Long.MIN_VALUE && nowMs - lastStepAt < interval) return null
        lastStepAt = nowMs
        return direction
    }

    /**
     * Cherche le point neutre : la première position que la main tient.
     *
     * Rien ne défile pendant ce temps, et l'état le dit ([State.ready] faux) pour que l'affichage
     * puisse le montrer. Un mode armé qu'on croirait actif serait aussi déroutant qu'un mode actif
     * qu'on croirait fermé.
     */
    private fun settleNeutral(x: Float, y: Float, nowMs: Long) {
        val moved = abs(x - settleX) > config.settleRadius || abs(y - settleY) > config.settleRadius
        if (moved || settleSince == Long.MIN_VALUE) {
            settleX = x
            settleY = y
            settleSince = nowMs
            state = State(x, y, null, 0f, ready = false)
            return
        }

        if (nowMs - settleSince < config.settleMs) {
            state = State(settleX, settleY, null, 0f, ready = false)
            return
        }

        neutralX = settleX
        neutralY = settleY
        awaitingNeutral = false
        lastStepAt = Long.MIN_VALUE
        state = State(neutralX, neutralY, null, 0f)
    }

    /**
     * Quel axe, et dans quel sens ?
     *
     * Le sens suit celui du doigt sur la vitre, et c'est le seul choix défendable : baisser la main
     * fait descendre le contenu vers le bas, comme un doigt qui tire la page. L'inverse — « la main
     * pousse la vue » — se défend aussi en théorie, mais contredirait le défilement au tracé qui
     * existe déjà dans l'application, et deux conventions opposées pour la même action seraient
     * pires que n'importe quelle convention.
     */
    private fun axisOf(dx: Float, dy: Float): EdgeScroller.Direction? {
        val vertical = abs(dy)
        val horizontal = abs(dx)

        if (vertical >= config.deadZone && vertical * config.verticalBias >= horizontal) {
            return if (dy < 0f) EdgeScroller.Direction.UP else EdgeScroller.Direction.DOWN
        }
        if (horizontal >= config.deadZone) {
            return if (dx < 0f) EdgeScroller.Direction.LEFT else EdgeScroller.Direction.RIGHT
        }
        return null
    }
}
