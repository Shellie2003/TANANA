package com.example.control

import com.example.gesture.PinchDetector

/**
 * Instantané de l'état du pincement, destiné à l'affichage.
 *
 * Rendre ces mesures visibles n'est pas un luxe de diagnostic. Le pincement est le seul geste de
 * l'application, et son échec est **silencieux et indiscernable d'une erreur de visée** : quand
 * rien ne se passe, l'utilisateur ne peut pas savoir s'il a mal visé ou si son geste n'a pas été
 * reconnu. Sans retour, il ne peut ni corriger ni faire confiance. Avec, il voit sa propre course,
 * son taux de réussite, et la cause exacte de ce qui n'a pas marché.
 */
data class PinchTelemetry(
    val isCalibrated: Boolean = false,
    /** Course pouce-index apprise pour cette main, en multiples de paume. */
    val baseline: Float = 0f,
    /**
     * Plus faible écart que cette main a réellement produit, en multiples de paume.
     *
     * C'est le nombre qui manquait, et son absence a coûté trois tours de réglage. Le seuil de
     * contact était une fraction de la course **supposée**, et quand le plancher réel de la mesure
     * se trouvait au-dessus de ce seuil, l'appui était impossible — sans que rien ne permette de
     * le voir. En l'affichant à côté du seuil, la comparaison devient immédiate : si le plancher
     * est au-dessus du seuil, ce n'est pas le geste qui est en cause.
     */
    val deepest: Float = 0f,
    /** Seuil de contact courant, dérivé de la course. */
    val contactThreshold: Float = 0f,
    /** Progression de la fermeture en cours, 0..1. */
    val progress: Float = 0f,
    /** Nombre d'enfoncements aboutis. */
    val presses: Int = 0,
    val rejected: Int = 0,
    val lastRejection: PinchDetector.Rejection = PinchDetector.Rejection.NONE,
    /** Durée de la dernière fermeture mesurée, en millisecondes. */
    val lastCloseMs: Long = 0L
) {
    /** Part des gestes aboutis, ou null tant qu'aucun geste n'a été tenté. */
    val successRate: Float?
        get() = (presses + rejected).takeIf { it > 0 }?.let { presses.toFloat() / it }

    /**
     * Correction à proposer à l'utilisateur, ou null si le dernier geste a abouti.
     *
     * Traduit la cause technique du rejet en une action qu'il peut appliquer : afficher
     * « TOO_SLOW » n'aiderait personne, ce qu'il faut dire c'est quoi faire autrement.
     *
     * Ce texte vit ici, et non dans l'écran qui l'affiche, pour qu'il soit couvert par les tests :
     * c'est la seule phrase de l'application dont l'exactitude conditionne la capacité de
     * l'utilisateur à se servir du seul geste qui existe.
     */
    val advice: String?
        get() = when (lastRejection) {
            // Formulé sur la **mesure**, et plus sur une faute supposée. La version précédente
            // affirmait « votre pouce s'approche sans toucher l'index » : elle était adressée, dans
            // le cas le plus fréquent, à quelqu'un dont les doigts se touchaient parfaitement et
            // dont le seuil était simplement hors d'atteinte. Un conseil faux est pire qu'aucun
            // conseil, parce qu'il envoie corriger un geste qui était correct.
            // Formulé sans nommer le geste, puisqu'il y en a deux — pincement ou main ouverte — et
            // qu'un conseil qui parlerait du pouce à qui ouvre la main serait à nouveau un conseil
            // adressé à une faute non commise.
            PinchDetector.Rejection.TOO_SHALLOW ->
                "Le geste s'est arrêté avant le seuil. S'il vous a paru complet, c'est que votre " +
                    "course n'est pas encore apprise : réessayez une ou deux fois, ou choisissez " +
                    "la fermeté « Souple »."

            PinchDetector.Rejection.COOLDOWN ->
                "Deux appuis très rapprochés ont été détectés ; le second a été ignoré."

            PinchDetector.Rejection.NONE -> null
        }

    companion object {
        fun of(detector: PinchDetector) = PinchTelemetry(
            isCalibrated = detector.isCalibrated,
            baseline = detector.baseline.takeIf { !it.isNaN() } ?: 0f,
            deepest = detector.deepest.takeIf { !it.isNaN() } ?: 0f,
            contactThreshold = detector.contactThreshold,
            progress = detector.travelProgress,
            presses = detector.pressCount,
            rejected = detector.rejectedCount,
            lastRejection = detector.lastRejection,
            lastCloseMs = detector.lastCloseDurationMs
        )
    }
}
