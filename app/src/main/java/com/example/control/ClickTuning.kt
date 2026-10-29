package com.example.control

import com.example.gesture.ClickTrigger
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
     * Multiplicateur de la profondeur exigée par le déclencheur. Plus il est **haut**, plus l'appui
     * part tôt — donc facilement.
     *
     * Un multiplicateur et non une fraction absolue, parce que la profondeur atteignable dépend du
     * **geste** : un pincement descend à 30 % de l'écart au repos, une ouverture de main à 73 % du
     * sien. Une fraction écrite ici serait donc juste pour l'un et hors d'atteinte pour l'autre —
     * c'est le défaut qu'un test a pris en flagrant délit. La valeur de référence appartient au
     * déclencheur ; ce réglage ne fait que la moduler.
     */
    private val depthMultiplier: Float
) {
    /** Pour les gestes appliqués, l'apprentissage, ou une main peu assurée. */
    RELAXED(
        "relaxed", "Souple",
        "Déclenche dès que le pouce s'est franchement rapproché, sans exiger le contact " +
            "complet. À choisir si vos appuis ne partent pas.",
        1.5f
    ),

    STANDARD(
        "standard", "Standard",
        "Le meilleur compromis pour la plupart des mains.",
        1.0f
    ),

    /** Pour une cadence soutenue, au prix d'un geste qui doit être net. */
    BRISK(
        "brisk", "Vif",
        "N'accepte qu'un contact franc, tout au fond de votre course. À choisir si des appuis " +
            "partent tout seuls.",
        0.6f
    );

    /**
     * Configuration du détecteur, pour ce réglage **et** ce déclencheur.
     *
     * Les deux sont nécessaires et ne jouent pas le même rôle : le déclencheur fixe ce que son
     * signal peut atteindre, l'utilisateur module l'exigence à l'intérieur de cette plage.
     */
    fun toConfig(trigger: ClickTrigger = ClickTrigger.DEFAULT): PinchDetector.Config =
        trigger.detectorConfig(depthMultiplier)

    companion object {
        val DEFAULT = STANDARD

        /** Relit un réglage enregistré. Une valeur inconnue retombe sur la valeur par défaut. */
        fun fromId(id: String?): ClickTuning = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
