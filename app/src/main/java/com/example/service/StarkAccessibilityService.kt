package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Service d'accessibilité exécutant les actions système globales (Retour, Accueil, Récents,
 * Notifications) déclenchées par les gestes de la main.
 *
 * L'appelant passe par [performGlobalAction], qui s'adresse directement à l'instance connectée.
 * L'ancien chemin — un `startService` par action — reste accepté pour compatibilité, mais il
 * démarrait le service en parallèle de sa liaison système à chaque geste.
 */
class StarkAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Aucun traitement : le service n'est utilisé que pour émettre des actions globales.
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { dispatch(it) }
        return START_NOT_STICKY
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    private fun dispatch(action: String) {
        val globalAction = ACTIONS[action] ?: return
        runCatching { performGlobalAction(globalAction) }
            .onFailure { Log.w(TAG, "Action $action refusée", it) }
    }

    companion object {
        private const val TAG = "StarkA11yService"

        const val ACTION_GLOBAL_BACK = "com.example.stark.ACTION_BACK"
        const val ACTION_GLOBAL_HOME = "com.example.stark.ACTION_HOME"
        const val ACTION_GLOBAL_RECENTS = "com.example.stark.ACTION_RECENTS"
        const val ACTION_GLOBAL_NOTIFICATIONS = "com.example.stark.ACTION_NOTIFICATIONS"

        private val ACTIONS = mapOf(
            ACTION_GLOBAL_BACK to GLOBAL_ACTION_BACK,
            ACTION_GLOBAL_HOME to GLOBAL_ACTION_HOME,
            ACTION_GLOBAL_RECENTS to GLOBAL_ACTION_RECENTS,
            ACTION_GLOBAL_NOTIFICATIONS to GLOBAL_ACTION_NOTIFICATIONS
        )

        @Volatile
        var instance: StarkAccessibilityService? = null
            private set

        /** Vrai si l'utilisateur a activé le service dans les réglages d'accessibilité. */
        val isConnected: Boolean get() = instance != null

        /**
         * Exécute une action système. Retourne false si le service n'est pas activé, ce qui
         * permet à l'appelant de proposer l'activation plutôt que d'échouer en silence.
         */
        fun performGlobalAction(action: String): Boolean {
            val service = instance ?: return false
            service.dispatch(action)
            return true
        }
    }
}
