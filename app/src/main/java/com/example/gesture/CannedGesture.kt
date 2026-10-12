package com.example.gesture

/**
 * Traduction des étiquettes du classifieur entraîné de MediaPipe vers le vocabulaire de NEXUS.
 *
 * ## Pourquoi un classifieur entraîné plutôt que notre géométrie
 *
 * Les poses étaient jusqu'ici reconnues par un score géométrique maison : extension de chaque
 * doigt, comparaison à des modèles, marge de rejet. Cette approche a une limite qu'aucun réglage
 * ne franchit — elle juge une main sur des angles calculés à partir de points de repère qui, sur
 * les doigts repliés, sont eux-mêmes devinés par le modèle de suivi. Nous raisonnions donc sur des
 * mesures dérivées d'une inférence, en concurrence avec un réseau entraîné sur des dizaines de
 * milliers de mains réelles, avec leurs occlusions, leurs morphologies et leurs approximations.
 *
 * Le classifieur de MediaPipe travaille au contraire sur la représentation interne du modèle de
 * main, avant qu'elle ne soit réduite à des coordonnées. C'est la même raison qui fait qu'un
 * détecteur appris bat une cascade de règles écrites à la main : il a vu les cas que nous
 * n'imaginons pas.
 *
 * ## Ce que la géométrie garde
 *
 * Tout ce qui n'est pas une pose. Le curseur suit le bout de l'index, le clic mesure l'écart
 * pouce-index : deux grandeurs continues que le classifieur ne fournit pas, et pour lesquelles nos
 * points de repère restent la bonne source. La migration ne remplace donc que la reconnaissance
 * des poses.
 *
 * ## Le repli, et pourquoi il est conservé
 *
 * Le classifieur répond `None` dès qu'une main ne ressemble à aucune de ses catégories, ce qui
 * arrive pour toute pose intermédiaire. Sans repli, l'utilisateur n'aurait alors plus rien. Le
 * score géométrique reste donc en second rideau : il décide quand le classifieur s'abstient. Les
 * deux voies sont d'accord sur les poses franches, et le repli ne se manifeste que dans la zone
 * grise — exactement là où il est utile.
 *
 * Kotlin pur, sans dépendance Android : entièrement testable en JVM.
 */
object CannedGesture {

    /**
     * Confiance minimale pour retenir l'étiquette du classifieur.
     *
     * Volontairement haute. Sous ce seuil, mieux vaut laisser le score géométrique décider : une
     * étiquette peu sûre n'apporte rien de plus qu'une mesure peu sûre, et deux avis faibles ne
     * valent pas mieux qu'un. Le seuil ne sert donc pas à filtrer le bruit — le verrou temporel
     * s'en charge — mais à choisir laquelle des deux voies est la mieux placée pour trancher.
     */
    const val MIN_CONFIDENCE = 0.65f

    /**
     * Catégories du classifieur « canned » de MediaPipe, telles que déclarées par la bibliothèque :
     * `["None", "Closed_Fist", "Open_Palm", "Pointing_Up", "Thumb_Down", "Thumb_Up", "Victory",
     * "ILoveYou"]`.
     *
     * Cinq d'entre elles n'ont volontairement **aucune** correspondance, et c'est un choix de
     * conception et non un oubli :
     *
     *  - `None` signifie « aucune catégorie » : c'est une information, pas une pose ;
     *  - `Victory`, `Thumb_Up`, `Thumb_Down` et `ILoveYou` désignent des formes que le vocabulaire
     *    a écartées parce qu'elles se disputaient le voisinage d'une autre.
     *
     * Les ignorer ici est ce qui rend ces formes **inertes** : les traverser en changeant de pose
     * ne produit plus rien, puisque ni le classifieur ni le score géométrique ne leur répondent.
     */
    private val LABELS = mapOf(
        "Closed_Fist" to GestureType.FIST,
        "Open_Palm" to GestureType.OPEN_PALM,
        "Pointing_Up" to GestureType.POINT
    )

    /**
     * Pose correspondant à une étiquette du classifieur, ou null s'il n'y en a pas — étiquette
     * inconnue, catégorie sans équivalent, ou confiance insuffisante.
     */
    fun from(label: String?, confidence: Float): GestureType? {
        if (label == null) return null
        if (confidence < MIN_CONFIDENCE) return null
        return LABELS[label]
    }

    /** Étiquettes reconnues, pour les tests de couverture. */
    val recognizedLabels: Set<String> get() = LABELS.keys
}
