package com.example.control

import com.example.gesture.PinchDetector

/**
 * Fermeté demandée au pincement.
 *
 * La morphologie de la main est désormais apprise seule : l'écart pouce-index au repos, la course
 * disponible et le plancher réellement atteint s'ajustent sans intervention. Il reste **une**
 * variable que seul l'utilisateur peut trancher, parce qu'elle n'est pas mesurable mais
 * préférentielle : jusqu'où faut-il descendre, dans sa propre course, pour que ça compte comme une
 * intention d'appuyer.
 *
 * C'est un arbitrage, pas un réglage optimal :
 *
 *  - exiger un contact profond écarte les fermetures accidentelles, mais rejette les gestes
 *    appliqués de qui débute ou a la main peu assurée ;
 *  - accepter un contact partiel laisse tout le monde appuyer, au prix de quelques appuis partis
 *    en refermant simplement la main.
 *
 * Le réglage portait auparavant sur la **vivacité** de la fermeture, et ce critère a été retiré :
 * mesuré en images, il dépendait de la cadence réelle de la caméra et rejetait les gestes posés sur
 * tout téléphone ordinaire. La profondeur, elle, ne dépend d'aucune horloge.
 *
 * Proposer trois positions plutôt qu'un curseur continu est délibéré : entre deux fractions
 * voisines, personne ne peut sentir la différence ni choisir en connaissance de cause. Ces
 * trois-là sont assez éloignées pour que le choix se fasse à l'usage, en une tentative.
 */
enum class ClickTuning(
    val id: String,
    val label: String,
    val description: String,
    /**
     * Fraction de la course mesurée à laquelle le contact est déclaré. Plus elle est **haute**,
     * plus l'appui part tôt — donc facilement.
     */
    private val contactFraction: Float
) {
    /** Pour les gestes appliqués, l'apprentissage, ou une main peu assurée. */
    RELAXED(
        "relaxed", "Souple",
        "Déclenche dès que le pouce s'est franchement rapproché, sans exiger le contact " +
            "complet. À choisir si vos appuis ne partent pas.",
        0.38f
    ),

    STANDARD(
        "standard", "Standard",
        "Le meilleur compromis pour la plupart des mains.",
        0.25f
    ),

    /** Pour une cadence soutenue, au prix d'un geste qui doit être net. */
    BRISK(
        "brisk", "Vif",
        "N'accepte qu'un contact franc, tout au fond de votre course. À choisir si des appuis " +
            "partent tout seuls.",
        0.15f
    );

    /** Configuration du détecteur correspondant à ce réglage. */
    fun toConfig(): PinchDetector.Config =
        PinchDetector.Config(contactFraction = contactFraction)

    companion object {
        val DEFAULT = STANDARD

        /** Relit un réglage enregistré. Une valeur inconnue retombe sur la valeur par défaut. */
        fun fromId(id: String?): ClickTuning = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
