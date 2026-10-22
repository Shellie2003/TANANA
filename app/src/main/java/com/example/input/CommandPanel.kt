package com.example.input

/**
 * Le panneau de commandes : **toutes les commandes du téléphone, sans un geste de plus.**
 *
 * ## Pourquoi un panneau et pas des gestes
 *
 * Chaque commande a eu sa pose, puis sa direction dans une roue. Les deux approches partagent le
 * même défaut : elles font payer le nombre de commandes en fiabilité. Une pose de plus, c'est une
 * forme de main de plus à distinguer ; une direction de plus, c'est un secteur plus étroit. Dans
 * les deux cas, ajouter une fonction dégrade celles qui existent déjà — et il faut en mémoriser
 * une de plus.
 *
 * Ce panneau rompt ce lien. On amène le curseur au bord droit de l'écran, il s'ouvre, on choisit
 * une ligne, on pince. Il n'y a **aucun geste nouveau** : le curseur se déplace comme toujours,
 * et le pincement valide comme partout ailleurs.
 *
 * Trois propriétés en découlent, et ce sont elles qui font la différence :
 *
 *  - **Le nombre de commandes ne coûte plus rien.** Elles sont écrites, les unes sous les autres.
 *    En ajouter une n'ajoute ni forme à distinguer, ni secteur à rétrécir.
 *  - **Il n'y a rien à mémoriser.** La liste se lit. Un vocabulaire de gestes, lui, doit se
 *    retenir avant de servir — et c'est précisément ce qu'on n'arrive pas à faire quand les
 *    signes se ressemblent.
 *  - **La visée est à une seule dimension.** Choisir une ligne dans une colonne ne demande que de
 *    monter ou descendre. C'est beaucoup plus facile que de viser un angle, et sans commune mesure
 *    avec le fait de tenir une forme de main.
 *
 * ## On ne peut pas s'y retrouver coincé, ni l'ouvrir sans le vouloir
 *
 * Le panneau suit la position de la main : il s'ouvre quand on reste au bord, se referme dès
 * qu'on s'en éloigne. Il n'y a pas de bascule, donc pas d'état dans lequel rester bloqué, et
 * s'éloigner sans pincer n'exécute rien — c'est la façon d'annuler, et elle ne demande aucun
 * geste supplémentaire.
 *
 * Deux garde-fous empêchent l'ouverture accidentelle : la zone d'ouverture est étroite, et il
 * faut y rester un court instant ([Config.openDwellMs]). Un simple passage de la main vers la
 * droite ne suffit donc pas. Et le panneau ne s'ouvre **jamais pendant un pincement** : un
 * défilement qui longe le bord droit ne peut pas le déclencher.
 *
 * Kotlin pur, sans dépendance Android : entièrement testable en JVM.
 */
class CommandPanel(private val config: Config = Config()) {

    data class Config(
        /**
         * Largeur de la bande, au bord droit, qui déclenche l'ouverture. Étroite à dessein : c'est
         * ce qui distingue « aller chercher le panneau » de « déplacer la main vers la droite ».
         */
        val openZone: Float = 0.05f,

        /**
         * Temps à passer dans la bande avant l'ouverture. Court, mais suffisant pour qu'un
         * mouvement qui traverse la bande ne l'ouvre pas.
         *
         * Ce n'est pas une pose à tenir : la main garde la forme qu'elle veut, bras baissé. Ce
         * qu'on tient, c'est une position, et seulement le temps d'un battement de paupière.
         */
        val openDwellMs: Long = 220L,

        /**
         * En deçà de cette abscisse, le panneau se referme. Nettement à gauche de la bande
         * d'ouverture : cette hystérésis évite qu'il papillonne quand la main tremble au bord.
         */
        val closeAt: Float = 0.60f,

        /** Marge haute et basse : les lignes n'occupent pas toute la hauteur. */
        val verticalMargin: Float = 0.06f
    )

    /** État destiné à l'affichage. */
    data class State(
        /** Ligne visée, ou -1 si le curseur est en dehors de la colonne. */
        val highlighted: Int,
        /** Libellés des commandes, de haut en bas. */
        val labels: List<String>,
        /**
         * Ouverture, 0..1. Intermédiaire pendant l'approche : le panneau se montre avant de
         * s'ouvrir, de sorte qu'on ne le découvre pas par surprise.
         */
        val openProgress: Float
    )

    private var openedAt = Long.MIN_VALUE
    private var atEdgeSince = Long.MIN_VALUE
    private var open = false
    private var highlighted = -1
    private var approach = 0f

    val isOpen: Boolean get() = open

    /** Ligne actuellement visée, ou -1. */
    val highlightedIndex: Int get() = if (open) highlighted else -1

    fun reset() {
        openedAt = Long.MIN_VALUE
        atEdgeSince = Long.MIN_VALUE
        open = false
        highlighted = -1
        approach = 0f
    }

    fun state(labels: List<String>): State = State(
        highlighted = highlightedIndex,
        labels = labels,
        openProgress = if (open) 1f else approach
    )

    /**
     * Met à jour l'ouverture et la ligne visée.
     *
     * @param x,y position du curseur en coordonnées écran normalisées
     * @param pressed vrai tant que le pouce touche l'index — le panneau ne s'ouvre pas alors
     * @param itemCount nombre de commandes dans le panneau
     */
    fun update(x: Float, y: Float, pressed: Boolean, itemCount: Int, nowMs: Long) {
        if (itemCount <= 0) {
            reset()
            return
        }

        if (open) {
            // Refermeture : la main s'éloigne franchement du bord.
            if (x < config.closeAt) {
                reset()
                return
            }
            highlighted = rowAt(y, itemCount)
            return
        }

        // Le panneau ne s'ouvre pas pendant un pincement : un tracé qui longe le bord droit
        // resterait sinon impossible à terminer.
        if (pressed) {
            atEdgeSince = Long.MIN_VALUE
            approach = 0f
            return
        }

        val edge = 1f - config.openZone
        if (x < edge) {
            atEdgeSince = Long.MIN_VALUE
            // Aperçu pendant l'approche, sur les quinze derniers pour cent de l'écran.
            approach = ((x - (edge - 0.15f)) / 0.15f).coerceIn(0f, 0.6f)
            return
        }

        if (atEdgeSince == Long.MIN_VALUE) atEdgeSince = nowMs
        val waited = nowMs - atEdgeSince
        approach = (0.6f + 0.4f * (waited.toFloat() / config.openDwellMs)).coerceIn(0f, 1f)
        if (waited < config.openDwellMs) return

        open = true
        openedAt = nowMs
        approach = 1f
        highlighted = rowAt(y, itemCount)
    }

    /**
     * Valide la ligne visée et retourne son indice, ou null s'il n'y a rien à valider.
     *
     * Appelée quand un appui survient alors que le panneau est ouvert : l'appui lui revient, et ne
     * part donc pas dans l'application du dessous.
     */
    fun commit(): Int? {
        if (!open) return null
        val index = highlighted
        if (index < 0) return null
        reset()
        return index
    }

    /** Ligne correspondant à une ordonnée, ou -1 en dehors des marges. */
    private fun rowAt(y: Float, itemCount: Int): Int {
        val top = config.verticalMargin
        val height = 1f - 2f * config.verticalMargin
        if (height <= 0f) return -1
        val row = ((y - top) / height * itemCount).toInt()
        return row.coerceIn(0, itemCount - 1)
    }

    /**
     * Bornes verticales d'une ligne, en coordonnées écran normalisées. Exposées pour que
     * l'affichage et la sélection ne puissent pas se contredire : une ligne dessinée ailleurs
     * qu'à l'endroit où elle se sélectionne serait un piège.
     */
    fun rowBounds(index: Int, itemCount: Int): Pair<Float, Float> {
        val top = config.verticalMargin
        val height = (1f - 2f * config.verticalMargin) / itemCount
        return (top + index * height) to (top + (index + 1) * height)
    }

    /** Abscisse du bord gauche de la colonne, pour l'affichage. */
    val leftEdge: Float get() = config.closeAt
}
