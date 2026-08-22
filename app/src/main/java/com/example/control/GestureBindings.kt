package com.example.control

import com.example.gesture.GestureType

/**
 * Catalogue des actions assignables à un geste.
 *
 * Chaque entrée porte un identifiant stable — jamais le nom de l'enum, qui changerait au premier
 * renommage et casserait les préférences déjà enregistrées — ainsi qu'un libellé destiné à
 * l'interface de personnalisation.
 */
enum class BindableAction(
    val id: String,
    val label: String,
    val description: String,
    val action: SystemAction
) {
    NONE("none", "Aucune", "Le geste ne déclenche rien", SystemAction.None),

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
    SCREENSHOT("screenshot", "Capture d'écran", "Photographie l'écran", SystemAction.Screenshot),
    LOCK("lock", "Verrouiller", "Éteint et verrouille l'écran", SystemAction.LockScreen),
    SPLIT_SCREEN("split", "Écran partagé", "Bascule l'affichage en deux volets", SystemAction.SplitScreen),
    POWER_DIALOG("power", "Menu marche/arrêt", "Ouvre le menu d'extinction", SystemAction.PowerDialog),

    PLAY_PAUSE("play_pause", "Lecture / pause", "Contrôle le média en cours", SystemAction.PlayPause),
    MEDIA_NEXT("media_next", "Piste suivante", "Passe au morceau suivant", SystemAction.MediaNext),
    MEDIA_PREVIOUS("media_prev", "Piste précédente", "Revient au morceau précédent", SystemAction.MediaPrevious),
    VOLUME_UP("vol_up", "Volume +", "Augmente le volume", SystemAction.VolumeUp),
    VOLUME_DOWN("vol_down", "Volume −", "Diminue le volume", SystemAction.VolumeDown),
    VOLUME_MUTE("vol_mute", "Couper le son", "Bascule le silence", SystemAction.VolumeMute),

    KEYBOARD("keyboard", "Clavier gestuel", "Ouvre ou ferme la saisie de texte", SystemAction.ToggleKeyboard),

    // Rôles continus : ils ne se déclenchent pas ponctuellement mais définissent le mode
    // d'interaction tant que la pose est tenue.
    CURSOR("cursor", "Curseur", "Déplace le pointeur à l'écran", SystemAction.MoveCursor(0f, 0f)),
    SCROLL("scroll", "Défilement", "Fait défiler en suivant la main", SystemAction.Scroll(0f)),
    SCROLL_HORIZONTAL(
        "scroll_h", "Défilement horizontal",
        "Parcourt onglets et carrousels", SystemAction.ScrollHorizontal(0f)
    ),
    DRAG("drag", "Glisser", "Saisit et déplace un élément", SystemAction.DragStart(0f, 0f));

    /**
     * Vrai pour les rôles qui s'exercent tant que la pose est tenue, par opposition aux actions
     * déclenchées une fois au front montant. La distinction décide de la façon dont la politique
     * de commande traite l'association, et des choix proposés pour un geste donné.
     */
    val isContinuous: Boolean
        get() = this == CURSOR || this == SCROLL || this == SCROLL_HORIZONTAL || this == DRAG

    companion object {
        fun fromId(id: String): BindableAction? = entries.firstOrNull { it.id == id }

        /** Actions proposables pour un geste : un balayage n'accepte que les actions ponctuelles. */
        fun assignableTo(gesture: GestureType): List<BindableAction> =
            if (GestureBindings.isSwipe(gesture)) entries.filterNot { it.isContinuous }
            else entries.toList()
    }
}

/**
 * Association entre poses et actions.
 *
 * Les valeurs par défaut placent les rôles continus les plus fréquents sur les poses les plus
 * confortables à tenir, et réservent les poses franches — poing, pouce levé — aux actions
 * ponctuelles, dont un déclenchement accidentel serait le plus gênant.
 *
 * Kotlin pur : la logique de résolution et de sérialisation est testable sans Android.
 */
data class GestureBindings(
    private val map: Map<GestureType, BindableAction> = DEFAULTS
) {

    operator fun get(gesture: GestureType): BindableAction =
        map[gesture] ?: BindableAction.NONE

    fun with(gesture: GestureType, action: BindableAction): GestureBindings =
        GestureBindings(map + (gesture to action))

    /** Poses configurables, dans l'ordre d'affichage. */
    fun assignable(): List<Pair<GestureType, BindableAction>> =
        CONFIGURABLE_POSES.map { it to get(it) }

    /** Sérialise en une chaîne compacte, adaptée aux préférences partagées. */
    fun serialize(): String =
        CONFIGURABLE_POSES.joinToString(";") { "${it.name}=${get(it).id}" }

    companion object {
        /**
         * Gestes que l'utilisateur peut réaffecter, dans l'ordre d'affichage : les poses tenues
         * d'abord, les balayages ensuite.
         *
         * Les balayages sont désormais configurables au même titre que les poses. Ils étaient
         * jusqu'ici câblés en dur dans la politique de commande, ce qui les rendait seuls
         * inaccessibles à la personnalisation sans qu'aucune raison ne le justifie.
         */
        val CONFIGURABLE_POSES = listOf(
            GestureType.POINT,
            GestureType.TWO_FINGER,
            GestureType.THREE_FINGER,
            GestureType.OPEN_PALM,
            GestureType.FIST,
            GestureType.THUMB_UP,
            GestureType.PINKY_UP,
            GestureType.ROCK,
            GestureType.SWIPE_LEFT,
            GestureType.SWIPE_RIGHT,
            GestureType.SWIPE_UP,
            GestureType.SWIPE_DOWN
        )

        /** Vrai pour les gestes de mouvement, qui n'ont pas de durée et ne peuvent porter de rôle continu. */
        fun isSwipe(gesture: GestureType): Boolean = gesture in setOf(
            GestureType.SWIPE_LEFT,
            GestureType.SWIPE_RIGHT,
            GestureType.SWIPE_UP,
            GestureType.SWIPE_DOWN
        )

        /**
         * Les rôles continus vont aux poses les plus confortables à tenir ; les actions
         * ponctuelles, dont un déclenchement accidentel coûte le plus cher, aux poses les plus
         * franches.
         *
         * Aucune pose ne porte le glisser, et c'est délibéré : le glisser est le clic maintenu.
         * Le contact pouce-index prolongé saisit l'élément visé et le suit ; il n'y a donc rien à
         * apprendre de plus, et surtout aucune pose supplémentaire à distinguer. L'action
         * « Glisser » reste assignable pour qui préfère un geste dédié.
         *
         * Les deux poses ajoutées comblent les manques signalés à l'usage : le volet des réglages
         * rapides — d'où se basculent données mobiles et Wi-Fi — et le clavier gestuel, qui
         * n'avait jusqu'ici aucun geste pour l'ouvrir.
         */
        val DEFAULTS: Map<GestureType, BindableAction> = mapOf(
            GestureType.POINT to BindableAction.CURSOR,
            GestureType.TWO_FINGER to BindableAction.SCROLL,
            GestureType.THREE_FINGER to BindableAction.RECENTS,
            GestureType.OPEN_PALM to BindableAction.PLAY_PAUSE,
            GestureType.FIST to BindableAction.BACK,
            GestureType.THUMB_UP to BindableAction.HOME,
            GestureType.PINKY_UP to BindableAction.KEYBOARD,
            GestureType.ROCK to BindableAction.QUICK_SETTINGS,
            GestureType.SWIPE_LEFT to BindableAction.SWITCH_PREVIOUS,
            GestureType.SWIPE_RIGHT to BindableAction.SWITCH_NEXT,
            GestureType.SWIPE_UP to BindableAction.HOME,
            GestureType.SWIPE_DOWN to BindableAction.NOTIFICATIONS
        )

        /**
         * Relit une configuration enregistrée. Toute entrée inconnue — pose supprimée, action
         * renommée — retombe silencieusement sur la valeur par défaut : une préférence corrompue
         * ne doit jamais empêcher l'application de démarrer.
         */
        fun deserialize(raw: String?): GestureBindings {
            if (raw.isNullOrBlank()) return GestureBindings()

            val parsed = raw.split(';').mapNotNull { entry ->
                val parts = entry.split('=')
                if (parts.size != 2) return@mapNotNull null
                val pose = runCatching { GestureType.valueOf(parts[0]) }.getOrNull()
                    ?: return@mapNotNull null
                if (pose !in CONFIGURABLE_POSES) return@mapNotNull null
                val action = BindableAction.fromId(parts[1]) ?: return@mapNotNull null
                pose to action
            }.toMap()

            return GestureBindings(DEFAULTS + parsed)
        }
    }
}
