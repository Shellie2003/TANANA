package com.example.control

import com.example.gesture.ClickDetector

/**
 * Fermeté demandée au geste de clic.
 *
 * La morphologie de la main est désormais apprise seule : l'écart pouce-index au repos, la course
 * disponible et le seuil de contact s'ajustent sans intervention. Il reste **une** variable que
 * seul l'utilisateur peut trancher, parce qu'elle n'est pas mesurable mais préférentielle : à
 * partir de quelle vivacité un mouvement doit-il compter comme une intention de cliquer.
 *
 * C'est un arbitrage, pas un réglage optimal :
 *
 *  - exiger un geste vif écarte les fermetures accidentelles, mais rejette les gestes appliqués
 *    de qui débute ou a la main peu assurée ;
 *  - accepter un geste lent laisse tout le monde cliquer, au prix de quelques appuis partis en
 *    repositionnant la main.
 *
 * Proposer trois positions plutôt qu'un curseur continu est délibéré : entre deux valeurs voisines
 * de millisecondes, personne ne peut sentir la différence ni choisir en connaissance de cause. Ces
 * trois-là sont assez éloignées pour que le choix se fasse à l'usage, en une tentative.
 */
enum class ClickTuning(
    val id: String,
    val label: String,
    val description: String,
    /** Durée maximale de la fermeture, en millisecondes. */
    private val maxCloseMs: Long
) {
    /** Pour les gestes appliqués, l'apprentissage, ou une main peu assurée. */
    RELAXED(
        "relaxed", "Souple",
        "Accepte les gestes lents. À choisir si vos clics ne partent pas.",
        420L
    ),

    STANDARD(
        "standard", "Standard",
        "Le meilleur compromis pour la plupart des mains.",
        240L
    ),

    /** Pour une cadence soutenue, au prix d'un geste qui doit être net. */
    BRISK(
        "brisk", "Vif",
        "N'accepte qu'un contact franc. À choisir si des clics partent tout seuls.",
        170L
    );

    /** Configuration du détecteur correspondant à ce réglage. */
    fun toConfig(): ClickDetector.Config = ClickDetector.Config(maxCloseMs = maxCloseMs)

    companion object {
        val DEFAULT = STANDARD

        /** Relit un réglage enregistré. Une valeur inconnue retombe sur la valeur par défaut. */
        fun fromId(id: String?): ClickTuning = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
