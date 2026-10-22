package com.example.control

/**
 * Catalogue des commandes du téléphone.
 *
 * Chaque entrée porte un identifiant stable — jamais le nom de l'enum, qui changerait au premier
 * renommage et casserait les préférences déjà enregistrées — ainsi qu'un libellé, affiché tel quel
 * dans le panneau.
 *
 * Il n'y a plus de notion de « geste assignable » : aucune commande n'est liée à un signe. Toutes
 * vivent dans la même liste, et c'est ce qui permet d'en avoir autant qu'on veut sans que leur
 * nombre pèse sur la fiabilité de quoi que ce soit.
 */
enum class Command(
    val id: String,
    val label: String,
    val description: String,
    val action: SystemAction
) {
    BACK("back", "Retour", "Revient à l'écran précédent", SystemAction.Back),
    HOME("home", "Accueil", "Retourne à l'écran d'accueil", SystemAction.Home),
    RECENTS("recents", "Applications récentes", "Ouvre le sélecteur d'applications", SystemAction.Recents),
    SWITCH_NEXT(
        "switch_next", "Application suivante",
        "Bascule vers l'application suivante", SystemAction.SwitchNext
    ),
    SWITCH_PREVIOUS(
        "switch_prev", "Application précédente",
        "Revient à l'application précédente", SystemAction.SwitchPrevious
    ),
    NOTIFICATIONS(
        "notifications", "Notifications",
        "Déroule le volet des notifications", SystemAction.Notifications
    ),
    QUICK_SETTINGS(
        "quick_settings", "Réglages rapides",
        "Données mobiles, Wi-Fi, lampe, mode avion", SystemAction.QuickSettings
    ),
    KEYBOARD("keyboard", "Clavier", "Ouvre ou ferme la saisie de texte", SystemAction.ToggleKeyboard),

    VOLUME_UP("vol_up", "Volume +", "Augmente le volume", SystemAction.VolumeUp),
    VOLUME_DOWN("vol_down", "Volume −", "Diminue le volume", SystemAction.VolumeDown),
    VOLUME_MUTE("vol_mute", "Couper le son", "Bascule le silence", SystemAction.VolumeMute),
    PLAY_PAUSE("play_pause", "Lecture / pause", "Contrôle le média en cours", SystemAction.PlayPause),
    MEDIA_NEXT("media_next", "Piste suivante", "Passe au morceau suivant", SystemAction.MediaNext),
    MEDIA_PREVIOUS("media_prev", "Piste précédente", "Revient au morceau précédent", SystemAction.MediaPrevious),

    SCREENSHOT("screenshot", "Capture d'écran", "Photographie l'écran", SystemAction.Screenshot),
    SPLIT_SCREEN("split", "Écran partagé", "Bascule l'affichage en deux volets", SystemAction.SplitScreen),
    LOCK("lock", "Verrouiller", "Éteint et verrouille l'écran", SystemAction.LockScreen),
    POWER_DIALOG("power", "Menu marche/arrêt", "Ouvre le menu d'extinction", SystemAction.PowerDialog);

    companion object {
        fun fromId(id: String): Command? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Les commandes présentes dans le panneau, dans l'ordre d'affichage.
 *
 * ## Ce que l'utilisateur règle, et ce qu'il n'a plus à régler
 *
 * La personnalisation portait jusqu'ici sur des associations geste → action : il fallait choisir
 * quelle forme de main déclenchait quoi. C'était la partie la plus difficile de l'application, et
 * pour une mauvaise raison — elle demandait d'anticiper quels signes on arriverait à produire de
 * façon fiable, ce que personne ne peut savoir à l'avance.
 *
 * Il ne reste qu'une question, et elle est de simple préférence : **quelles commandes dans le
 * panneau, et dans quel ordre.** Aucun réglage ne peut plus rendre un geste impossible, puisqu'il
 * n'y a qu'un geste et qu'il n'est pas configurable.
 *
 * Le nombre de lignes reste libre. Plus il y en a, plus elles sont étroites : c'est le seul
 * arbitrage, il est visible immédiatement, et il est réversible.
 *
 * Kotlin pur : la logique de résolution et de sérialisation est testable sans Android.
 */
data class CommandList(val commands: List<Command> = DEFAULTS) {

    val size: Int get() = commands.size

    operator fun get(index: Int): Command? = commands.getOrNull(index)

    /** Libellés dans l'ordre d'affichage, pour le panneau. */
    fun labels(): List<String> = commands.map { it.label }

    operator fun contains(command: Command): Boolean = command in commands

    /**
     * Ajoute une commande à la fin, ou la retire si elle est déjà présente.
     *
     * Un seul geste d'interface pour les deux opérations : toucher une ligne la fait entrer ou
     * sortir du panneau. C'est plus direct qu'une liste réordonnable, et l'ordre reste prévisible
     * puisqu'il suit l'ordre d'ajout.
     */
    fun toggled(command: Command): CommandList =
        if (command in commands) CommandList(commands - command)
        else CommandList(commands + command)

    fun serialize(): String = commands.joinToString(",") { it.id }

    companion object {
        /**
         * Ordre par défaut : les commandes les plus fréquentes en haut.
         *
         * « Retour » vient en premier parce que c'est, de loin, l'action la plus utilisée
         * d'Android. Les dix suivantes couvrent la navigation, les notifications, les réglages
         * rapides, la saisie de texte et le volume — soit tout ce qu'on fait sans ouvrir
         * d'application. Le reste du catalogue reste disponible, à ajouter d'un geste.
         */
        val DEFAULTS: List<Command> = listOf(
            Command.BACK,
            Command.HOME,
            Command.RECENTS,
            Command.NOTIFICATIONS,
            Command.QUICK_SETTINGS,
            Command.KEYBOARD,
            Command.VOLUME_UP,
            Command.VOLUME_DOWN,
            Command.PLAY_PAUSE,
            Command.SCREENSHOT
        )

        /**
         * Relit une liste enregistrée. Toute entrée inconnue est ignorée en silence, et une liste
         * vide retombe sur les valeurs par défaut : une préférence corrompue ne doit jamais
         * laisser l'utilisateur sans aucune commande, ce qui l'enfermerait hors de son téléphone.
         */
        fun deserialize(raw: String?): CommandList {
            if (raw.isNullOrBlank()) return CommandList()
            val parsed = raw.split(',')
                .mapNotNull { Command.fromId(it.trim()) }
                .distinct()
            return if (parsed.isEmpty()) CommandList() else CommandList(parsed)
        }
    }
}
