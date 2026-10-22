package com.example.control

/**
 * Action système décidée à partir d'un geste.
 *
 * Séparer la décision de son exécution rend toute la décision testable en JVM : on vérifie qu'un
 * geste produit la bonne action sans appareil, sans caméra et sans service d'accessibilité.
 *
 * La liste a perdu le déplacement de curseur, le double appui et les deux défilements. Aucun
 * n'était encore émis : le curseur se dessine sans passer par une action, et **le défilement n'est
 * plus une action du tout** — c'est un tracé continu que le système interprète lui-même, comme il
 * le fait d'un doigt sur la vitre. Les laisser dans cette liste laissait croire à des capacités
 * qui n'existaient plus.
 */
sealed interface SystemAction {

    // --- Pointage ---------------------------------------------------------------------------

    /** Appui bref. */
    data class Tap(val x: Float, val y: Float) : SystemAction

    /** Appui long : ouvre les menus contextuels, sélectionne du texte. */
    data class LongPress(val x: Float, val y: Float) : SystemAction

    /** Début d'un glisser : signalé à la superposition, pas au système. */
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

    // --- Navigation -------------------------------------------------------------------------

    data object Back : SystemAction
    data object Home : SystemAction
    data object Recents : SystemAction
    data object SwitchNext : SystemAction
    data object SwitchPrevious : SystemAction

    /** Volet des notifications. */
    data object Notifications : SystemAction

    /**
     * Réglages rapides : c'est de là que se basculent données mobiles, Wi-Fi, lampe, mode avion.
     * Sans cette action, une part importante du téléphone restait hors d'atteinte.
     */
    data object QuickSettings : SystemAction

    data object Screenshot : SystemAction
    data object LockScreen : SystemAction
    data object SplitScreen : SystemAction
    data object PowerDialog : SystemAction

    // --- Média et volume --------------------------------------------------------------------

    data object PlayPause : SystemAction
    data object MediaNext : SystemAction
    data object MediaPrevious : SystemAction
    data object VolumeUp : SystemAction
    data object VolumeDown : SystemAction
    data object VolumeMute : SystemAction

    // --- Saisie de texte --------------------------------------------------------------------

    /** Ouvre ou ferme le clavier gestuel. */
    data object ToggleKeyboard : SystemAction

    /** Insère un caractère dans le champ de saisie actif. */
    data class TypeText(val text: String) : SystemAction

    /** Efface le caractère précédent. */
    data object Backspace : SystemAction

    /** Aucune action : sert à désactiver explicitement un geste dans la personnalisation. */
    data object None : SystemAction
}
