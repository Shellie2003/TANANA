package com.example.input

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Roue de commandes : huit commandes choisies à la direction, tenue ouverte par la pose.
 *
 * ## Pourquoi une roue plutôt que des poses
 *
 * Chaque commande avait sa pose, et c'est ce qui rendait le vocabulaire instable. Non parce qu'il y
 * en avait trop, mais parce que des poses distinguées au nombre de doigts tendus sont
 * structurellement fragiles : les doigts repliés sont ceux que la caméra ne voit pas, et la main
 * étant un continuum, aller d'une pose à l'autre traverse tous les décomptes intermédiaires.
 *
 * La roue coupe ce lien. Une seule pose — la main ouverte — l'ouvre, et la commande se choisit
 * ensuite par la **direction**, dont la tolérance est angulaire : elle ne dépend ni de la taille de
 * l'écran, ni de la distance de la main, ni de sa stabilité. C'est le même principe que le clavier
 * gestuel, et pour la même raison.
 *
 * Deux bénéfices qui ne viennent pas du hasard :
 *
 *  - **plus rien à mémoriser.** Les huit commandes sont écrites à l'écran. Un vocabulaire de poses
 *    exige de se souvenir d'un signe par commande ; une roue se lit.
 *  - **le nombre de commandes cesse de peser sur la fiabilité.** Ajouter une commande n'ajoute plus
 *    une pose à distinguer, donc n'augmente plus le risque de confusion.
 *
 * ## Tenue ouverte, jamais verrouillée
 *
 * La roue n'est pas un mode qu'on ouvre et qu'on ferme : elle est visible **tant que la pose est
 * tenue**, et disparaît dès qu'on la quitte. On ne peut donc pas y rester coincé, ce qui est la
 * mauvaise surprise habituelle des menus gestuels. Quitter la pose sans valider n'exécute rien :
 * c'est la façon d'annuler, et elle ne demande aucun geste supplémentaire.
 *
 * Kotlin pur, sans dépendance Android : entièrement testable en JVM.
 */
class CommandWheel(private val config: Config = Config()) {

    data class Config(
        /**
         * Distance minimale au centre pour qu'une direction compte. En deçà, la direction est trop
         * bruitée pour être significative — et c'est aussi la zone de repos, où ramener la main
         * sans rien désigner.
         */
        val deadZone: Float = 0.14f,

        /**
         * Durée pendant laquelle une direction doit être tenue avant qu'un clic puisse la valider.
         * Sans ce délai, un clic tombant pendant que la main traverse un secteur y validerait une
         * commande au passage.
         */
        val minHoverMs: Long = 90L
    )

    /** État destiné à l'affichage. */
    data class State(
        /** Secteur pointé, ou -1 quand la main est au repos au centre. */
        val hoveredSector: Int,
        /** Libellés des huit secteurs, dans l'ordre horaire depuis le haut. */
        val labels: List<String>,
        /** Progression du geste de validation, 0..1, pour l'anneau du secteur pointé. */
        val progress: Float
    )

    private var hoveredSector = -1
    private var hoverSince = Long.MIN_VALUE
    private var progress = 0f

    fun reset() {
        hoveredSector = -1
        hoverSince = Long.MIN_VALUE
        progress = 0f
    }

    fun state(labels: List<String>): State = State(
        hoveredSector = hoveredSector,
        labels = labels,
        progress = progress
    )

    /**
     * Met à jour la sélection et retourne le secteur validé, ou null.
     *
     * @param cursorX,cursorY position du curseur en coordonnées écran normalisées, le centre de la
     *   roue étant à (0.5, 0.5)
     * @param commit vrai sur la seule image où un clic est émis
     * @param clickProgress progression du geste de clic, 0..1, pour l'anneau du secteur pointé
     */
    fun update(
        cursorX: Float,
        cursorY: Float,
        nowMs: Long,
        commit: Boolean = false,
        clickProgress: Float = 0f
    ): Int? {
        val dx = cursorX - 0.5f
        val dy = cursorY - 0.5f

        if (hypot(dx, dy) < config.deadZone) {
            hoveredSector = -1
            hoverSince = Long.MIN_VALUE
            progress = 0f
            return null
        }

        val sector = sectorOf(dx, dy)
        if (sector != hoveredSector) {
            hoveredSector = sector
            hoverSince = nowMs
            progress = 0f
            return null
        }

        progress = clickProgress.coerceIn(0f, 1f)
        if (!commit || nowMs - hoverSince < config.minHoverMs) return null

        // Validé : on réarme la visée pour qu'un second clic ne réexécute pas la même commande
        // sans avoir d'abord quitté puis retrouvé le secteur.
        hoverSince = nowMs
        progress = 0f
        return sector
    }

    /**
     * Secteur pointé, numéroté dans le sens horaire à partir du haut.
     *
     * L'axe Y de l'écran pointe vers le bas : on l'inverse pour raisonner dans le repère
     * mathématique usuel, faute de quoi haut et bas seraient permutés.
     */
    private fun sectorOf(dx: Float, dy: Float): Int {
        val angle = atan2(dx.toDouble(), -dy.toDouble())
        val normalized = (angle + 2 * PI) % (2 * PI)
        return ((normalized / (2 * PI / SECTORS)).toInt()) % SECTORS
    }

    companion object {
        const val SECTORS = 8
    }
}
