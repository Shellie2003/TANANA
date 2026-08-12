package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Service d'accessibilité Stark HUD permettant de déclencher des commandes système
 * globales (Bouton Retour, Accueil, Notifications) depuis les gestes de main sans contact.
 */
class StarkAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Traitement optionnel des événements d'accessibilité système
    }

    override fun onInterrupt() {}

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                ACTION_GLOBAL_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
                ACTION_GLOBAL_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
                ACTION_GLOBAL_RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                ACTION_GLOBAL_NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            }
        }
        return START_STICKY
    }

    companion object {
        const val ACTION_GLOBAL_BACK = "com.example.stark.ACTION_BACK"
        const val ACTION_GLOBAL_HOME = "com.example.stark.ACTION_HOME"
        const val ACTION_GLOBAL_RECENTS = "com.example.stark.ACTION_RECENTS"
        const val ACTION_GLOBAL_NOTIFICATIONS = "com.example.stark.ACTION_NOTIFICATIONS"

        var instance: StarkAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
