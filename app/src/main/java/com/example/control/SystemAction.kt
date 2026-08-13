package com.example.control

/**
 * Action système décidée à partir d'un geste.
 *
 * Séparer la décision de son exécution rend toute la politique de commande testable en JVM :
 * on vérifie qu'un geste produit la bonne action sans avoir besoin d'un appareil, d'un service
 * d'accessibilité ni d'une caméra.
 */
sealed interface SystemAction {

    /** Déplacement du curseur, en coordonnées écran normalisées 0..1. */
    data class MoveCursor(val x: Float, val y: Float) : SystemAction

    /** Appui bref à la position indiquée. */
    data class Tap(val x: Float, val y: Float) : SystemAction

    /** Début d'un glisser : le pincement est maintenu. */
    data class DragStart(val x: Float, val y: Float) : SystemAction

    /** Poursuite du glisser. */
    data class DragMove(val x: Float, val y: Float) : SystemAction

    /**
     * Glisser complet, du point de départ au point d'arrivée. Émis au relâchement : le système
     * a besoin du trajet entier pour synthétiser un geste, pas d'une suite de positions.
     */
    data class Drag(
        val fromX: Float,
        val fromY: Float,
        val toX: Float,
        val toY: Float
    ) : SystemAction

    /**
     * Défilement vertical. [amount] est exprimé en fraction de hauteur d'écran ;
     * positif = le contenu descend (la main monte).
     */
    data class Scroll(val amount: Float) : SystemAction

    /** Retour système. */
    data object Back : SystemAction

    /** Écran d'accueil. */
    data object Home : SystemAction

    /** Ouverture du sélecteur d'applications récentes. */
    data object Recents : SystemAction

    /** Lecture / pause du média en cours. */
    data object PlayPause : SystemAction

    /** Commutation vers l'application suivante ou précédente. */
    data object SwitchNext : SystemAction
    data object SwitchPrevious : SystemAction
}
