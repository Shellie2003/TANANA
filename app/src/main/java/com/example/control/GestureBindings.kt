package com.example.control

import com.example.gesture.GestureType
import com.example.input.CommandWheel

/**
 * Catalogue des actions assignables à un geste ou à une direction de la roue.
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
    COMMAND_WHEEL(
        "wheel", "Roue de commandes",
        "Ouvre les huit commandes, choisies à la direction", SystemAction.None
    );

    /**
     * Vrai pour les rôles qui s'exercent tant que la pose est tenue, par opposition aux actions
     * déclenchées une fois la pose confirmée. La distinction décide de la façon dont la politique
     * de commande traite l'association, et des choix proposés pour une pose donnée.
     */
    val isContinuous: Boolean
        get() = this == CURSOR || this == COMMAND_WHEEL

    companion object {
        fun fromId(id: String): BindableAction? = entries.firstOrNull { it.id == id }

        /** Actions proposables pour une pose : les trois poses acceptent tout. */
        fun assignableTo(gesture: GestureType): List<BindableAction> =
            if (gesture in GestureBindings.CONFIGURABLE_POSES) entries.toList()
            else entries.filterNot { it.isContinuous }

        /**
         * Actions proposables pour une direction de la roue. Un secteur est une cible qu'on vise
         * puis qu'on valide : il n'a pas de durée, donc ne peut porter de rôle continu.
         */
        fun assignableToSector(): List<BindableAction> = entries.filterNot { it.isContinuous }
    }
}

/**
 * Association entre gestes et actions.
 *
 * Deux niveaux, et c'est ce qui a changé. **Trois poses** seulement portent un rôle — elles sont
 * maximalement éloignées, donc jamais en conflit — et l'une d'elles ouvre une roue de **huit
 * directions**, chacune assignable. La personnalisation ne perd donc rien à la réduction du
 * vocabulaire : elle gagne au contraire des emplacements, puisque les secteurs sont lisibles à
 * l'écran et n'exigent aucune mémorisation.
 *
 * Kotlin pur : la logique de résolution et de sérialisation est testable sans Android.
 */
data class GestureBindings(
    private val map: Map<GestureType, BindableAction> = DEFAULTS,
    private val wheel: List<BindableAction> = WHEEL_DEFAULTS
) {

    operator fun get(gesture: GestureType): BindableAction =
        map[gesture] ?: BindableAction.NONE

    fun with(gesture: GestureType, action: BindableAction): GestureBindings =
        copy(map = map + (gesture to action))

    /** Action d'un secteur de la roue, numéroté dans le sens horaire depuis le haut. */
    fun sector(index: Int): BindableAction =
        wheel.getOrNull(index) ?: BindableAction.NONE

    fun withSector(index: Int, action: BindableAction): GestureBindings {
        if (index !in 0 until CommandWheel.SECTORS) return this
        return copy(wheel = wheel.toMutableList().also { it[index] = action })
    }

    /** Libellés des huit secteurs, pour l'affichage de la roue. */
    fun wheelLabels(): List<String> = wheel.map { it.label }

    /** Secteurs configurables, dans l'ordre d'affichage. */
    fun wheelSlots(): List<Pair<Int, BindableAction>> =
        (0 until CommandWheel.SECTORS).map { it to sector(it) }

    /** Poses configurables, dans l'ordre d'affichage. */
    fun assignable(): List<Pair<GestureType, BindableAction>> =
        CONFIGURABLE_POSES.map { it to get(it) }

    /** Sérialise en une chaîne compacte, adaptée aux préférences partagées. */
    fun serialize(): String {
        val poses = CONFIGURABLE_POSES.joinToString(";") { "${it.name}=${get(it).id}" }
        val sectors = (0 until CommandWheel.SECTORS).joinToString(";") { "$WHEEL_PREFIX$it=${sector(it).id}" }
        return "$poses;$sectors"
    }

    companion object {
        /** Préfixe distinguant un secteur de roue d'une pose dans la forme sérialisée. */
        private const val WHEEL_PREFIX = "w"

        /**
         * Poses que l'utilisateur peut réaffecter. Il n'y en a plus que trois, et c'est le fond de
         * la refonte : zéro doigt, un doigt, cinq doigts. Aucune paire ne se distingue par un seul
         * doigt, et les décomptes intermédiaires ne sont plus des poses — les traverser ne peut
         * donc plus rien déclencher.
         *
         * Les balayages ont disparu avec le « V » qui les portait. Ils demandaient un mouvement
         * ample et rapide, indiscernable d'un déplacement de main pressé, et la roue fait le même
         * travail en étant à la fois plus sûre et visible.
         */
        val CONFIGURABLE_POSES = listOf(
            GestureType.POINT,
            GestureType.FIST,
            GestureType.OPEN_PALM
        )

        /**
         * Une pose par rôle, et le reste passe par la roue.
         *
         * L'index pilote le curseur, parce que c'est la pose qu'on tient le plus longtemps et la
         * plus naturelle à viser. Le poing porte le retour : c'est l'action la plus fréquente
         * d'Android, et la pose la plus franche. La main ouverte ouvre la roue, où vivent toutes
         * les autres commandes.
         *
         * Aucune pose ne porte le défilement ni le glisser, et c'est délibéré : le contact
         * pouce-index maintenu pose le doigt, qui suit ensuite la main. Défiler, glisser et
         * balayer en découlent, interprétés par le système. Il n'y a donc rien à apprendre de
         * plus, et surtout aucune pose supplémentaire à distinguer.
         */
        val DEFAULTS: Map<GestureType, BindableAction> = mapOf(
            GestureType.POINT to BindableAction.CURSOR,
            GestureType.FIST to BindableAction.BACK,
            GestureType.OPEN_PALM to BindableAction.COMMAND_WHEEL
        )

        /**
         * Les huit commandes de la roue, dans le sens horaire depuis le haut.
         *
         * L'ordre n'est pas arbitraire : les directions cardinales — haut, droite, bas, gauche —
         * sont les plus faciles à viser, donc portent les commandes les plus fréquentes, et les
         * diagonales les plus rares. Les deux bascules d'application se font face, sur la gauche
         * et la droite, pour que leur sens soit déductible plutôt que mémorisé.
         */
        val WHEEL_DEFAULTS: List<BindableAction> = listOf(
            BindableAction.HOME,            // 0 — haut
            BindableAction.RECENTS,         // 1 — haut-droite
            BindableAction.SWITCH_NEXT,     // 2 — droite
            BindableAction.PLAY_PAUSE,      // 3 — bas-droite
            BindableAction.NOTIFICATIONS,   // 4 — bas
            BindableAction.QUICK_SETTINGS,  // 5 — bas-gauche
            BindableAction.SWITCH_PREVIOUS, // 6 — gauche
            BindableAction.KEYBOARD         // 7 — haut-gauche
        )

        /**
         * Relit une configuration enregistrée. Toute entrée inconnue — pose supprimée, action
         * renommée — retombe silencieusement sur la valeur par défaut : une préférence corrompue
         * ne doit jamais empêcher l'application de démarrer.
         *
         * Ce point compte particulièrement ici : les configurations déjà enregistrées sur les
         * appareils nomment des poses qui n'existent plus. Elles doivent être ignorées sans bruit,
         * pas provoquer une erreur au démarrage.
         */
        fun deserialize(raw: String?): GestureBindings {
            if (raw.isNullOrBlank()) return GestureBindings()

            val poses = mutableMapOf<GestureType, BindableAction>()
            val wheel = WHEEL_DEFAULTS.toMutableList()

            for (entry in raw.split(';')) {
                val parts = entry.split('=')
                if (parts.size != 2) continue
                val action = BindableAction.fromId(parts[1]) ?: continue
                val key = parts[0]

                if (key.startsWith(WHEEL_PREFIX)) {
                    val index = key.removePrefix(WHEEL_PREFIX).toIntOrNull() ?: continue
                    if (index !in 0 until CommandWheel.SECTORS) continue
                    if (action.isContinuous) continue
                    wheel[index] = action
                } else {
                    val pose = runCatching { GestureType.valueOf(key) }.getOrNull() ?: continue
                    if (pose !in CONFIGURABLE_POSES) continue
                    poses[pose] = action
                }
            }

            return GestureBindings(DEFAULTS + poses, wheel)
        }
    }
}
