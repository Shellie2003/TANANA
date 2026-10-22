package com.example.control

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Cinématique du doigt synthétique pendant un tracé.
 *
 * ## Le défaut qu'elle corrige
 *
 * Le doigt synthétique était **collé à la position absolue du curseur**. Trois symptômes en
 * découlaient, et ils n'en faisaient qu'un :
 *
 *  - **Capacité.** Ce qu'on pouvait défiler d'un pincement était borné par la distance entre le
 *    point de départ et le bord de l'écran. Pincer au milieu donnait une demi-page, d'où des
 *    pincer-tirer-relâcher à répétition — le défaut classique du défilement par position absolue,
 *    quand la course de la main est plus courte que le contenu.
 *  - **Élan.** Une fois le curseur buté sur le bord, le déplacement devenait nul et plus aucun
 *    segment n'était émis. Android mesurait donc une vitesse nulle au relâchement : **aucune
 *    inertie**. On balayait, et ça s'arrêtait net.
 *  - **Effort.** Le rapport était de 1:1 : défiler une page demandait de déplacer la main d'une
 *    page.
 *
 * ## Ce qui les règle
 *
 * Le doigt est **décollé** du curseur. Il avance par incréments amplifiés, dans une zone bornée
 * dont il ne sort jamais : quand il atteint le bord, il est **relevé et reposé de l'autre côté**
 * pour continuer dans le même sens.
 *
 * La conséquence la plus importante n'est pas la capacité, c'est l'élan. Comme le doigt ne se fige
 * plus jamais, le dernier segment porte toujours une vraie vitesse, et **Android lance l'inertie**
 * comme il le fait pour un doigt sur la vitre. La capacité vient alors de l'élan, et non de la
 * course du bras : un balayage franc fait défiler plusieurs pages, puis le contenu continue seul.
 *
 * ## L'amplification ne s'applique pas à tout
 *
 * Amplifier un glisser d'icône ou le curseur d'un réglage serait faux : là, le doigt doit aller
 * exactement là où la main l'emmène. Le mode est donc choisi par **ce qui se trouve sous le
 * doigt**, demandé au système lui-même, et non par un geste de l'utilisateur :
 *
 *  - sur une zone défilable, [Mode.SCROLL] — amplification et repositionnement ;
 *  - partout ailleurs, [Mode.DIRECT] — rapport 1:1 strict, exactement le comportement précédent.
 *
 * C'est délibérément une information que le système fournit, pas un mode à apprendre. Quand elle
 * manque — l'application ne déclare pas ses zones défilables — on retombe sur le mode direct,
 * c'est-à-dire sur le comportement d'avant : jamais pire.
 *
 * Même en mode défilement, le rapport reste **1:1 sur le premier centimètre** ([Config.linearTravel])
 * avant que le gain ne monte progressivement. Un petit ajustement reste donc exact, et il n'y a
 * aucun seuil où le comportement bascule — c'est une courbe, comme l'accélération d'une souris, et
 * non une décision.
 *
 * Kotlin pur, sans dépendance Android : entièrement testable en JVM.
 */
class StrokeKinematics(private val config: Config = Config()) {

    data class Config(
        /**
         * Déplacement du curseur pendant lequel le rapport reste strictement 1:1, en fraction
         * d'écran. En deçà, un ajustement fin doit rester exact.
         */
        val linearTravel: Float = 0.09f,

        /** Déplacement sur lequel le gain monte de 1 à [maxGain]. */
        val rampTravel: Float = 0.16f,

        /**
         * Gain maximal. À 3,4 un déplacement de main de 25 % d'écran fait défiler environ 85 % —
         * et l'élan rendu au relâchement prolonge le mouvement bien au-delà.
         */
        val maxGain: Float = 3.4f,

        /**
         * Marge intérieure de la zone défilable dans laquelle le doigt reste confiné. Le doigt est
         * repositionné avant d'atteindre le bord réel : un doigt posé exactement sur la bordure
         * d'une liste tombe souvent hors de sa zone tactile.
         */
        val margin: Float = 0.10f,

        /**
         * Déplacement minimal garanti avant toute levée, en fraction d'écran.
         *
         * Sans cette garantie, un tracé repositionné puis relâché aussitôt serait un contact posé
         * et relevé presque au même endroit — c'est-à-dire **un appui**. Sur une liste, cela
         * ouvrirait l'élément sous le doigt au lieu de défiler : le pire défaut possible, puisqu'il
         * agit au lieu de ne rien faire.
         */
        val minLiftTravel: Float = 0.05f,

        /**
         * Déplacement maximal du curseur pris en compte sur une seule image.
         *
         * Garde-fou contre les discontinuités : après un décrochage bref du suivi, le curseur peut
         * réapparaître loin de sa dernière position. Amplifié, un tel saut enverrait le contenu à
         * l'autre bout de la liste — un défilement incontrôlé, c'est-à-dire exactement ce qu'on
         * cherche à éviter. Au-delà de cette borne, le saut est tronqué : on perd un peu de
         * mouvement là où, sans elle, on perdrait sa place.
         */
        val maxCursorStep: Float = 0.12f
    )

    enum class Mode { DIRECT, SCROLL }

    /**
     * Zone dans laquelle le doigt peut circuler, en coordonnées écran normalisées, et axes le long
     * desquels le défilement est possible.
     */
    data class Area(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val horizontal: Boolean = true,
        val vertical: Boolean = true
    ) {
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
    }

    /** Ce que le service doit faire de cette image. */
    data class Advance(
        val x: Float,
        val y: Float,
        /**
         * Vrai quand il faut **lever le doigt et le reposer** en (x, y) avant de continuer. Le
         * tracé précédent est clos par un mouvement, jamais sur place, de sorte qu'il ne puisse
         * pas être lu comme un appui.
         */
        val recenter: Boolean = false
    )

    var mode = Mode.DIRECT
        private set

    private var area: Area? = null
    private var fingerX = 0f
    private var fingerY = 0f
    private var lastCursorX = 0f
    private var lastCursorY = 0f

    /** Déplacement cumulé du curseur depuis le début du geste : pilote la courbe de gain. */
    private var cursorTravel = 0f

    /** Déplacement du doigt depuis la dernière pose : garantit qu'aucun tracé n'est un appui. */
    private var travelSincePress = 0f

    /** Dernière direction non nulle, pour savoir où pousser le doigt avant de le lever. */
    private var lastDirX = 0f
    private var lastDirY = 1f

    /**
     * Ouvre un tracé.
     *
     * @param area zone défilable sous le point de départ, ou null si le système n'en signale
     *   aucune — auquel cas le tracé reste en rapport 1:1
     * @return la position où poser le doigt
     */
    fun begin(cursorX: Float, cursorY: Float, area: Area? = null): Advance {
        this.area = area
        mode = if (area == null) Mode.DIRECT else Mode.SCROLL
        lastCursorX = cursorX
        lastCursorY = cursorY
        cursorTravel = 0f
        travelSincePress = 0f
        lastDirX = 0f
        lastDirY = 1f

        if (area == null) {
            fingerX = cursorX
            fingerY = cursorY
        } else {
            // Au centre de la zone défilable, et non au point pincé : c'est ce qui donne la même
            // course dans les quatre directions, au lieu d'une course qui dépend de l'endroit où
            // la main se trouvait quand le pincement a commencé.
            fingerX = clampX(area.centerX)
            fingerY = clampY(area.centerY)
        }
        return Advance(fingerX, fingerY)
    }

    /** Avance le doigt pour la position de curseur courante. */
    fun move(cursorX: Float, cursorY: Float): Advance {
        val area = this.area
        if (area == null) {
            fingerX = cursorX
            fingerY = cursorY
            lastCursorX = cursorX
            lastCursorY = cursorY
            return Advance(cursorX, cursorY)
        }

        var dx = (cursorX - lastCursorX).coerceIn(-config.maxCursorStep, config.maxCursorStep)
        var dy = (cursorY - lastCursorY).coerceIn(-config.maxCursorStep, config.maxCursorStep)
        lastCursorX = cursorX
        lastCursorY = cursorY

        cursorTravel += hypot(dx, dy)
        val gain = gainAt(cursorTravel)

        // Les axes que la zone ne sait pas défiler sont neutralisés : une main qui dérive en
        // diagonale ne doit pas lutter contre une liste qui ne défile que verticalement.
        if (!area.horizontal) dx = 0f
        if (!area.vertical) dy = 0f

        val stepX = dx * gain
        val stepY = dy * gain
        if (stepX != 0f || stepY != 0f) {
            lastDirX = stepX
            lastDirY = stepY
        }

        val targetX = fingerX + stepX
        val targetY = fingerY + stepY

        // Débordement : le doigt est relevé et reposé de l'autre côté. Le pas qui débordait est
        // perdu — le contenu ne défile pas pendant une levée — mais cela ne représente qu'une
        // image, et un repositionnement reste rare dès lors que le gain fait son travail.
        val overflowX = targetX < minX() || targetX > maxX()
        val overflowY = targetY < minY() || targetY > maxY()

        if (overflowX || overflowY) {
            fingerX = if (overflowX) oppositeX(stepX) else clampX(targetX)
            fingerY = if (overflowY) oppositeY(stepY) else clampY(targetY)
            travelSincePress = 0f
            return Advance(fingerX, fingerY, recenter = true)
        }

        travelSincePress += hypot(stepX, stepY)
        fingerX = targetX
        fingerY = targetY
        return Advance(fingerX, fingerY)
    }

    /**
     * Dernier mouvement avant la levée, ou null s'il n'y a rien à garantir.
     *
     * N'intervient qu'en mode défilement, et seulement si le tracé courant n'a presque pas bougé
     * depuis sa pose : il serait alors indistinguable d'un appui.
     */
    fun endAdjustment(): Advance? {
        val area = this.area ?: return null
        if (travelSincePress >= config.minLiftTravel) return null

        val norm = hypot(lastDirX, lastDirY)
        val (ux, uy) = if (norm < 1e-6f) {
            if (area.vertical) 0f to 1f else 1f to 0f
        } else {
            lastDirX / norm to lastDirY / norm
        }

        val push = config.minLiftTravel
        val x = clampX(fingerX + ux * push)
        val y = clampY(fingerY + uy * push)
        // Si la zone est trop étroite pour le dégagement, pousser dans l'autre sens.
        val moved = hypot(x - fingerX, y - fingerY)
        if (moved >= config.minLiftTravel * 0.5f) {
            fingerX = x
            fingerY = y
            return Advance(x, y)
        }

        val backX = clampX(fingerX - ux * push)
        val backY = clampY(fingerY - uy * push)
        if (hypot(backX - fingerX, backY - fingerY) < 1e-4f) return null
        fingerX = backX
        fingerY = backY
        return Advance(backX, backY)
    }

    fun reset() {
        area = null
        mode = Mode.DIRECT
        cursorTravel = 0f
        travelSincePress = 0f
    }

    /**
     * Courbe de gain : 1 jusqu'à [Config.linearTravel], puis montée progressive vers
     * [Config.maxGain].
     *
     * Une **courbe**, et non un seuil, et la distinction est le fond du sujet. Une tentative
     * précédente décidait si un geste « était » un défilement à partir de sa vitesse : le critère
     * est intenable, parce qu'une traversée d'écran rapide le satisfait sur n'importe lequel de ses
     * segments, et le geste partait tout seul. Ici rien n'est décidé — le rapport varie de façon
     * continue, il n'y a aucun instant où le comportement bascule, et un déplacement lent et long
     * est amplifié comme un déplacement rapide et long.
     */
    private fun gainAt(travel: Float): Float {
        if (travel <= config.linearTravel) return 1f
        if (config.rampTravel <= 0f) return config.maxGain
        val t = ((travel - config.linearTravel) / config.rampTravel).coerceIn(0f, 1f)
        return 1f + (config.maxGain - 1f) * t
    }

    private fun minX() = area?.let { it.left + (it.right - it.left) * config.margin } ?: 0f
    private fun maxX() = area?.let { it.right - (it.right - it.left) * config.margin } ?: 1f
    private fun minY() = area?.let { it.top + (it.bottom - it.top) * config.margin } ?: 0f
    private fun maxY() = area?.let { it.bottom - (it.bottom - it.top) * config.margin } ?: 1f

    private fun clampX(v: Float) = v.coerceIn(minX(), maxX())
    private fun clampY(v: Float) = v.coerceIn(minY(), maxY())

    /** Bord opposé au sens de progression : d'où repartir pour continuer dans le même sens. */
    private fun oppositeX(step: Float) = if (step > 0f) minX() else maxX()
    private fun oppositeY(step: Float) = if (step > 0f) minY() else maxY()

    /** Vrai quand le pas est assez net pour mériter un segment. Évite les segments vides. */
    fun isSignificant(advance: Advance, previousX: Float, previousY: Float): Boolean =
        abs(advance.x - previousX) > 1e-4f || abs(advance.y - previousY) > 1e-4f
}
