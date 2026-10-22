package com.example.control

import android.content.Context

/**
 * Persistance des préférences.
 *
 * Il n'y en a plus que deux, et c'est le signe le plus net de la refonte : la liste des commandes
 * du panneau, et la fermeté du pincement. Les associations geste → action ont disparu avec les
 * poses qu'elles associaient.
 *
 * Volontairement adossée aux préférences partagées plutôt qu'à une base : la configuration tient
 * en deux chaînes, elle est lue au démarrage du service et à chaque modification, et rien ne
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

    fun reset() {
        prefs.edit().remove(KEY_COMMANDS).remove(KEY_CLICK_TUNING).apply()
    }

    private companion object {
        const val PREFS_NAME = "nexus_bindings"
        const val KEY_COMMANDS = "panel_commands"
        const val KEY_CLICK_TUNING = "click_tuning"
    }
}
