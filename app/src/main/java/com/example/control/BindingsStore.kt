package com.example.control

import android.content.Context

/**
 * Persistance des préférences.
 *
 * Il n'y en a que trois, et c'est le signe le plus net de la refonte : la liste des commandes du
 * panneau, la fermeté du pincement et la vivacité du défilement. Les associations geste → action
 * ont disparu avec les poses qu'elles associaient, et les deux réglages restants portent sur la
 * **sensation** du geste, jamais sur sa reconnaissance — aucun ne peut donc rendre un geste
 * impossible.
 *
 * Volontairement adossée aux préférences partagées plutôt qu'à une base : la configuration tient
 * en trois chaînes, elle est lue au démarrage du service et à chaque modification, et rien ne
 * justifierait ici le coût d'une couche supplémentaire.
 *
 * La clé des commandes est **nouvelle**, et ce n'est pas un détail : les configurations déjà
 * enregistrées nomment des poses qui n'existent plus. Les ignorer par un changement de clé est
 * plus sûr que de tenter de les convertir — il n'y a rien à convertir, l'ancienne notion a
 * disparu.
 */
class BindingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadCommands(): CommandList = CommandList.deserialize(prefs.getString(KEY_COMMANDS, null))

    fun saveCommands(commands: CommandList) {
        prefs.edit().putString(KEY_COMMANDS, commands.serialize()).apply()
    }

    fun loadClickTuning(): ClickTuning = ClickTuning.fromId(prefs.getString(KEY_CLICK_TUNING, null))

    fun saveClickTuning(tuning: ClickTuning) {
        prefs.edit().putString(KEY_CLICK_TUNING, tuning.id).apply()
    }

    fun loadScrollTuning(): ScrollTuning = ScrollTuning.fromId(prefs.getString(KEY_SCROLL_TUNING, null))

    fun saveScrollTuning(tuning: ScrollTuning) {
        prefs.edit().putString(KEY_SCROLL_TUNING, tuning.id).apply()
    }

    fun reset() {
        prefs.edit()
            .remove(KEY_COMMANDS)
            .remove(KEY_CLICK_TUNING)
            .remove(KEY_SCROLL_TUNING)
            .apply()
    }

    private companion object {
        /**
         * Volontairement laissé tel quel au renommage de l'application. Ce nom n'est jamais
         * affiché, et le changer ferait repartir les préférences enregistrées de zéro : on perdrait
         * des réglages pour gagner une cohérence que personne ne peut voir.
         */
        const val PREFS_NAME = "nexus_bindings"
        const val KEY_COMMANDS = "panel_commands"
        const val KEY_CLICK_TUNING = "click_tuning"
        const val KEY_SCROLL_TUNING = "scroll_tuning"
    }
}
