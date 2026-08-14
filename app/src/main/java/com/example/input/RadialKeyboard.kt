package com.example.input

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Clavier radial à deux niveaux, piloté à la direction.
 *
 * ## Pourquoi une direction plutôt qu'une position
 *
 * Écrire sans voix ni écran tactile impose de choisir parmi une trentaine de symboles. Reproduire
 * un clavier AZERTY et le pointer serait le réflexe évident, et le pire choix : chaque touche
 * occuperait quelques millimètres, très en deçà de la précision qu'un suivi de main peut garantir
 * à bout de bras.
 *
 * Ce clavier renonce donc à la position pour n'utiliser que **la direction**. L'écran est divisé
 * en huit secteurs de 45°, et il suffit de pointer *vers* un secteur : à distance égale du centre,
 * la tolérance angulaire est absolue et ne dépend ni de la taille de l'écran ni de la stabilité de
 * la main. Deux niveaux — un secteur pour le groupe, un secteur pour la lettre — donnent 64
 * symboles, soit l'alphabet, les chiffres et la ponctuation courante.
 *
 * L'utilisateur apprend vite : chaque lettre devient un couple de directions, mémorisé comme un
 * geste plutôt que cherché comme une touche. C'est le principe des menus gestuels, dont l'efficacité
 * est établie de longue date pour les commandes fréquentes.
 *
 * Kotlin pur, sans dépendance Android : la logique de sélection est entièrement testable.
 */
class RadialKeyboard(private val config: Config = Config()) {

    data class Config(
        /**
         * Distance minimale au centre pour qu'une direction soit prise en compte. En deçà, la
         * direction est trop bruitée pour être significative — et c'est aussi la zone de repos.
         */
        val deadZone: Float = 0.12f,
        /** Durée d'immobilité dans un secteur avant de valider. */
        val dwellMs: Long = 500L
    )

    /** Un niveau de sélection. */
    enum class Level { GROUP, CHARACTER }

    /** État courant, destiné à l'affichage. */
    data class State(
        val level: Level,
        /** Secteur pointé, ou -1 si le curseur est au repos au centre. */
        val hoveredSector: Int,
        /** Progression de l'immobilité dans le secteur pointé, 0..1. */
        val progress: Float,
        /** Libellés des huit secteurs du niveau courant. */
        val labels: List<String>,
        /** Groupe déjà choisi, quand on est au second niveau. */
        val selectedGroup: Int
    )

    /** Résultat d'une mise à jour : texte produit, s'il y en a. */
    data class Emission(val text: String? = null, val backspace: Boolean = false)

    private var level = Level.GROUP
    private var selectedGroup = -1
    private var hoveredSector = -1
    private var hoverSince = Long.MIN_VALUE
    private var progress = 0f

    fun reset() {
        level = Level.GROUP
        selectedGroup = -1
        hoveredSector = -1
        hoverSince = Long.MIN_VALUE
        progress = 0f
    }

    fun state(): State = State(
        level = level,
        hoveredSector = hoveredSector,
        progress = progress,
        labels = currentLabels(),
        selectedGroup = selectedGroup
    )

    /**
     * Met à jour la sélection à partir de la position du curseur, en coordonnées écran
     * normalisées, le centre du clavier étant à (0.5, 0.5).
     */
    fun update(cursorX: Float, cursorY: Float, nowMs: Long): Emission {
        val dx = cursorX - 0.5f
        val dy = cursorY - 0.5f
        val distance = hypot(dx, dy)

        if (distance < config.deadZone) {
            hoveredSector = -1
            hoverSince = Long.MIN_VALUE
            progress = 0f
            return Emission()
        }

        val sector = sectorOf(dx, dy)
        if (sector != hoveredSector) {
            hoveredSector = sector
            hoverSince = nowMs
            progress = 0f
            return Emission()
        }

        val elapsed = nowMs - hoverSince
        progress = (elapsed.toFloat() / config.dwellMs).coerceIn(0f, 1f)
        if (elapsed < config.dwellMs) return Emission()

        // Validation : on réarme l'immobilité pour éviter une répétition immédiate.
        hoverSince = nowMs
        progress = 0f
        return commit(sector)
    }

    private fun commit(sector: Int): Emission = when (level) {
        Level.GROUP -> {
            selectedGroup = sector
            level = Level.CHARACTER
            hoveredSector = -1
            Emission()
        }

        Level.CHARACTER -> {
            val group = GROUPS.getOrNull(selectedGroup)
            val symbol = group?.symbols?.getOrNull(sector)
            level = Level.GROUP
            selectedGroup = -1
            hoveredSector = -1
            when (symbol) {
                null -> Emission()
                BACKSPACE -> Emission(backspace = true)
                else -> Emission(text = symbol)
            }
        }
    }

    private fun currentLabels(): List<String> = when (level) {
        Level.GROUP -> GROUPS.map { it.label }
        Level.CHARACTER -> GROUPS.getOrNull(selectedGroup)?.symbols?.map {
            if (it == BACKSPACE) "⌫" else it
        } ?: GROUPS.map { it.label }
    }

    /**
     * Secteur pointé, numéroté dans le sens horaire à partir du haut.
     *
     * L'axe Y de l'écran pointe vers le bas : on l'inverse pour raisonner en repère mathématique
     * usuel, faute de quoi haut et bas seraient permutés.
     */
    private fun sectorOf(dx: Float, dy: Float): Int {
        val angle = atan2(dx.toDouble(), -dy.toDouble())
        val normalized = (angle + 2 * PI) % (2 * PI)
        return ((normalized / (2 * PI / SECTORS)).toInt()) % SECTORS
    }

    private data class Group(val label: String, val symbols: List<String>)

    companion object {
        const val SECTORS = 8
        const val BACKSPACE = ""

        /**
         * Répartition des symboles. Les lettres suivent l'ordre alphabétique : chercher une lettre
         * inconnue reste possible par déduction, sans avoir à mémoriser une disposition arbitraire.
         * L'espace occupe un groupe entier — c'est le caractère le plus fréquent, il mérite le
         * geste le plus court.
         */
        private val GROUPS = listOf(
            Group("ABC", listOf("A", "B", "C", "D", "E", "F", "G", "H")),
            Group("IJK", listOf("I", "J", "K", "L", "M", "N", "O", "P")),
            Group("QRS", listOf("Q", "R", "S", "T", "U", "V", "W", "X")),
            Group("YZ", listOf("Y", "Z", "É", "È", "À", "Ç", "Ù", "Ô")),
            Group("123", listOf("1", "2", "3", "4", "5", "6", "7", "8")),
            Group("90.", listOf("9", "0", ".", ",", "?", "!", ":", "-")),
            Group("␣", listOf(" ", " ", " ", " ", " ", " ", " ", " ")),
            Group("⌫", listOf(BACKSPACE, BACKSPACE, BACKSPACE, BACKSPACE, BACKSPACE, BACKSPACE, BACKSPACE, BACKSPACE))
        )

        /** Libellés des groupes, pour l'affichage initial. */
        val GROUP_LABELS: List<String> get() = GROUPS.map { it.label }
    }
}
