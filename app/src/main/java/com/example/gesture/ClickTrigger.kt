package com.example.gesture

/**
 * Quel geste enfonce le bouton.
 *
 * ## Pourquoi c'est un choix et non une constante
 *
 * Le pincement a été le seul geste de l'application, et son raisonnement tient toujours : c'est la
 * mesure la plus franche de la main, elle se sent au doigt, elle ne fatigue pas, et elle ne demande
 * de tenir aucune forme. Mais elle a un défaut qui n'apparaît qu'à l'usage, et qui n'est pas dans la
 * mesure : **le pouce qui vient se poser sur l'index est à la fois le signal et une perturbation du
 * curseur**, puisque le curseur venait de ce bout d'index. Plusieurs correctifs ont visé ce point.
 *
 * L'ouverture de la main n'a pas ce défaut une fois le curseur reculé à la base du doigt, mais elle
 * a les siens, et ils sont mesurés : signal trois fois plus faible que le pincement, pente qui
 * s'écrase en fin de course, et une forme à tenir pendant toute la durée d'un tracé.
 *
 * Aucun des deux ne domine l'autre sur le papier, et je n'ai pas d'appareil pour trancher. Le choix
 * est donc exposé — c'est la seule façon honnête de régler une question dont la réponse se mesure
 * sur une main réelle, et il permet de comparer les deux en quelques secondes au lieu d'attendre un
 * build.
 *
 * ## L'inversion du signal, et pourquoi elle suffit
 *
 * [PinchDetector] ne sait rien du pincement, et c'est la propriété qui rend ce choix peu coûteux :
 * il juge **un scalaire continu qui descend vers un seuil appris**, avec ligne de base adaptative,
 * plancher appris, hystérésis et anti-rebond. Toute cette mécanique — y compris la correction qui a
 * rendu le seuil atteignable — s'applique à n'importe quel signal orienté dans ce sens.
 *
 * L'étendue de la main, elle, **monte** quand on s'approche du déclenchement. Il suffit donc de la
 * retourner autour d'un pivot pour que le détecteur la traite sans une ligne de modification. C'est
 * l'unique rôle de ce fichier.
 */
enum class ClickTrigger(
    val id: String,
    val label: String,
    val description: String,
    /**
     * Plancher supposé du signal avant tout geste observé, en fraction de la ligne de base.
     *
     * **Il doit dépendre du signal, et un test l'a imposé.** La valeur était une constante du
     * détecteur, calibrée sur le pincement : un pincement descend jusqu'à environ 30 % de l'écart
     * au repos. L'ouverture de la main, retournée, ne descend qu'à **73 %** du sien. Avec la
     * supposition du pincement, le seuil se trouvait donc très au-dessous de ce que le geste peut
     * atteindre, et aucune ouverture ne déclenchait d'appui — exactement le défaut d'origine du
     * clic, réapparu par une autre porte parce qu'une constante tenue pour universelle ne l'était
     * pas.
     */
    val deepestBootstrap: Float,
    /**
     * Profondeur de base exigée, en fraction de la course mesurée. La fermeté choisie par
     * l'utilisateur la multiplie.
     *
     * Plus basse pour l'ouverture que pour le pincement, et c'est le prix de sa faiblesse : son
     * signal ne sépare que d'un facteur 1,6 contre plus de 3 pour le pincement, si bien qu'une main
     * à demi ouverte n'est pas loin du seuil. Exiger plus de profondeur est la seule façon de
     * garder une marge.
     */
    val baseContactFraction: Float,
    /**
     * Plafond absolu du seuil de contact, en fraction de la ligne de base.
     *
     * Garde-fou contre une course mesurée quasi nulle, où le moindre frémissement déclencherait.
     * Il doit lui aussi dépendre du geste, **et un test l'a encore imposé** : la valeur du
     * pincement, 70 %, se trouve juste *au* plancher de l'ouverture de la main, qui est à 69 %. Le
     * plafond écrasait donc le seuil sur le plancher, et l'appui ne partait pas davantage qu'avec
     * un mauvais plancher supposé. Deux constantes du pincement, deux fois le même défaut : rien
     * de ce détecteur ne devait rester calibré sur un seul signal.
     */
    val maxContactFraction: Float
) {
    /**
     * Main ouverte : les doigts s'écartent de la paume.
     *
     * Le curseur venant de la base de l'index, ce geste ne le déplace pas — c'est la condition qui
     * rend ce déclencheur utilisable, et elle est mesurée à 0 px contre 122 px si le curseur venait
     * du bout du doigt.
     */
    OPEN_HAND(
        "open_hand", "Main ouverte",
        "Ouvrez la main pour cliquer. Le curseur suit la base de l'index, donc ouvrir la main " +
            "ne le déplace pas.",
        deepestBootstrap = 0.72f,
        baseContactFraction = 0.15f,
        maxContactFraction = 0.82f
    ) {
        override fun signalFrom(metrics: HandMetrics): Float = PIVOT - metrics.openness
        override fun signalFrom(openness: Float, pinchRatio: Float): Float = PIVOT - openness
    },

    /** Pincement : le pouce rejoint l'index. Le geste historique de l'application. */
    PINCH(
        "pinch", "Pincement",
        "Joignez le pouce et l'index pour cliquer. Signal plus franc, mais le pouce se pose " +
            "sur l'index, ce qui perturbe l'estimation de sa position.",
        deepestBootstrap = 0.30f,
        baseContactFraction = 0.25f,
        maxContactFraction = 0.70f
    ) {
        override fun signalFrom(metrics: HandMetrics): Float = metrics.pinchRatio
        override fun signalFrom(openness: Float, pinchRatio: Float): Float = pinchRatio
    };

    /**
     * Signal à transmettre au détecteur, orienté « plus bas = plus proche du déclenchement ».
     */
    abstract fun signalFrom(metrics: HandMetrics): Float

    /** Même conversion à partir des deux mesures brutes, pour la simulation et les tests. */
    abstract fun signalFrom(openness: Float, pinchRatio: Float): Float

    /**
     * Configuration du détecteur adaptée à **ce** signal.
     *
     * Exposée ici, et non reconstituée par l'appelant, parce que deux constantes du détecteur se
     * sont révélées calibrées sur le pincement seul — le plancher supposé et le plafond du seuil —
     * et que chacune a produit, séparément, un clic qui ne partait jamais. Rassembler ces valeurs
     * sur le déclencheur rend l'oubli difficile : on ne peut plus construire un détecteur pour un
     * geste sans dire lequel.
     *
     * @param depthMultiplier module la profondeur exigée, pour la fermeté choisie par
     *   l'utilisateur. 1 correspond au réglage de référence.
     */
    fun detectorConfig(depthMultiplier: Float = 1f): PinchDetector.Config =
        PinchDetector.Config(
            contactFraction = baseContactFraction * depthMultiplier,
            deepestBootstrap = deepestBootstrap,
            maxContactFraction = maxContactFraction
        )

    companion object {
        /**
         * Pivot de retournement de l'étendue.
         *
         * Choisi au-dessus de l'étendue maximale observée (1,17 main grande ouverte) pour que le
         * signal retourné reste positif, et assez bas pour qu'il tombe dans les bornes de ligne de
         * base du détecteur. Une main détendue donne alors environ 1,14, une main ouverte 0,83 —
         * soit la même plage qu'un pincement, où le détecteur a été réglé.
         */
        const val PIVOT = 2.0f

        val DEFAULT = OPEN_HAND

        fun fromId(id: String?): ClickTrigger = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
