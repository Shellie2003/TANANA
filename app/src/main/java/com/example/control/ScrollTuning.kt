package com.example.control

/**
 * Vivacité demandée au défilement.
 *
 * Le reste du geste s'apprend seul : la course du pincement, le seuil de contact, la zone
 * défilable. Il reste **une** variable que seul l'utilisateur peut trancher, parce qu'elle n'est
 * pas mesurable mais préférentielle : de combien le contenu doit avancer pour un déplacement de
 * main donné.
 *
 * C'est un arbitrage, pas un optimum :
 *
 *  - un gain faible rend le défilement précis et prévisible, au prix de gestes répétés pour
 *    parcourir une longue liste ;
 *  - un gain élevé traverse une page d'un mouvement, au prix d'une visée plus difficile quand on
 *    cherche à s'arrêter sur un élément précis.
 *
 * Trois positions plutôt qu'un curseur continu, pour la même raison que la fermeté du pincement :
 * entre deux valeurs voisines, personne ne peut sentir la différence ni choisir en connaissance de
 * cause. Celles-ci sont assez éloignées pour que le choix se fasse à l'usage, en une tentative.
 *
 * Le rapport reste **1:1 au début du geste** quelle que soit la position retenue : un petit
 * ajustement est toujours exact, et seul le prolongement du mouvement est amplifié.
 */
enum class ScrollTuning(
    val id: String,
    val label: String,
    val description: String,
    /** Gain maximal appliqué au prolongement du mouvement. */
    private val maxGain: Float
) {
    /** Pour viser un élément précis dans une liste plutôt que la parcourir. */
    PRECISE(
        "precise", "Précis",
        "Le contenu suit la main de près. À choisir si le défilement dépasse la cible.",
        2.0f
    ),

    STANDARD(
        "standard", "Standard",
        "Le meilleur compromis entre parcourir et viser.",
        3.4f
    ),

    /** Pour parcourir de longues listes sans répéter le geste. */
    SWIFT(
        "swift", "Rapide",
        "Une page d'un seul mouvement. À choisir si défiler demande trop de gestes.",
        5.0f
    );

    /** Cinématique correspondant à ce réglage. */
    fun toConfig(): StrokeKinematics.Config = StrokeKinematics.Config(maxGain = maxGain)

    companion object {
        val DEFAULT = STANDARD

        /** Relit un réglage enregistré. Une valeur inconnue retombe sur la valeur par défaut. */
        fun fromId(id: String?): ScrollTuning = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
