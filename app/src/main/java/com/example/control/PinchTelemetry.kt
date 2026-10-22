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
            PinchDetector.Rejection.TOO_SHALLOW ->
                "Votre pouce s'approche sans toucher l'index. Laissez-les se rejoindre " +
                    "franchement : le contact est ce qui déclenche l'appui, et il se sent au doigt."

            PinchDetector.Rejection.TOO_SLOW ->
                "Le mouvement est trop lent pour être distingué d'un repositionnement de la " +
                    "main. Frappez d'un coup sec plutôt que de refermer progressivement."

            PinchDetector.Rejection.COOLDOWN ->
                "Deux appuis très rapprochés ont été détectés ; le second a été ignoré."

            PinchDetector.Rejection.NONE -> null
        }

    companion object {
        fun of(detector: PinchDetector) = PinchTelemetry(
            isCalibrated = detector.isCalibrated,
            baseline = detector.baseline.takeIf { !it.isNaN() } ?: 0f,
            contactThreshold = detector.contactThreshold,
            progress = detector.travelProgress,
            presses = detector.pressCount,
            rejected = detector.rejectedCount,
            lastRejection = detector.lastRejection,
            lastCloseMs = detector.lastCloseDurationMs
        )
    }
}
