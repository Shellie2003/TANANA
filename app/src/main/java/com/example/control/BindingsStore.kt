package com.example.control

import android.content.Context

/**
 * Persistance des associations geste → action.
 *
 * Volontairement adossée aux préférences partagées plutôt qu'à une base : la configuration tient
 * en une chaîne, elle est lue au démarrage du service et à chaque modification, et rien ne
 * justifierait ici le coût d'une couche supplémentaire.
 */
class BindingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): GestureBindings = GestureBindings.deserialize(prefs.getString(KEY_BINDINGS, null))

    fun save(bindings: GestureBindings) {
        prefs.edit().putString(KEY_BINDINGS, bindings.serialize()).apply()
    }

    fun reset() {
        prefs.edit().remove(KEY_BINDINGS).apply()
    }

    private companion object {
        const val PREFS_NAME = "nexus_bindings"
        const val KEY_BINDINGS = "gesture_bindings"
    }
}
