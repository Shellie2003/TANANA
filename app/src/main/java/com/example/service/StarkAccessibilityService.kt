package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.example.control.SystemAction
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Exécute les actions système décidées par la politique gestuelle.
 *
 * C'est la seule voie par laquelle une application ordinaire peut agir en dehors d'elle-même :
 * `dispatchGesture` synthétise des appuis et des glissers n'importe où à l'écran, et
 * `performGlobalAction` déclenche Retour, Accueil et Applications récentes.
 *
 * Le service doit être activé manuellement par l'utilisateur dans les réglages d'accessibilité ;
 * aucune permission ne peut s'y substituer.
 */
class StarkAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Aucun traitement : le service n'est utilisé que pour émettre des actions.
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Service d'accessibilité connecté")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    // --- Exécution des actions ---------------------------------------------------------------

    /** Exécute une action. [width] et [height] sont les dimensions de l'écran en pixels. */
    private fun execute(action: SystemAction, width: Float, height: Float) {
        when (action) {
            is SystemAction.Tap -> tap(action.x * width, action.y * height)

            is SystemAction.Drag -> drag(
                action.fromX * width, action.fromY * height,
                action.toX * width, action.toY * height
            )

            is SystemAction.Scroll -> scroll(action.amount, width, height)

            SystemAction.Back -> global(GLOBAL_ACTION_BACK)
            SystemAction.Home -> global(GLOBAL_ACTION_HOME)
            SystemAction.Recents -> global(GLOBAL_ACTION_RECENTS)
            SystemAction.PlayPause -> mediaPlayPause()

            SystemAction.SwitchNext -> quickSwitch(forward = true, width = width, height = height)
            SystemAction.SwitchPrevious -> quickSwitch(forward = false, width = width, height = height)

            // Purement visuelles : consommées par la superposition, pas par le système.
            is SystemAction.MoveCursor,
            is SystemAction.DragStart,
            is SystemAction.DragMove -> Unit
        }
    }

    private fun global(action: Int) {
        runCatching { performGlobalAction(action) }
            .onFailure { Log.w(TAG, "Action globale $action refusée", it) }
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatch(path, durationMs = TAP_DURATION_MS)
    }

    private fun drag(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        // Durée proportionnelle à la distance : un glisser instantané est interprété comme un
        // lancer (fling) par la plupart des vues, ce qui n'est presque jamais l'intention.
        val distance = hypot(toX - fromX, toY - fromY)
        val duration = (distance / 2f).toLong().coerceIn(MIN_DRAG_MS, MAX_DRAG_MS)
        dispatch(path, duration)
    }

    /**
     * Défilement synthétisé par un glisser vertical au centre de l'écran.
     *
     * [amount] est une fraction de hauteur d'écran ; positif fait descendre le contenu, donc
     * correspond à un glisser du haut vers le bas.
     */
    private fun scroll(amount: Float, width: Float, height: Float) {
        if (abs(amount) < 1e-3f) return

        val travel = (amount * height).coerceIn(-height * 0.6f, height * 0.6f)
        val centerX = width / 2f
        // Le trajet est centré verticalement pour rester dans la zone défilable quelle que soit
        // la mise en page, et ne jamais empiéter sur les zones de gestes système des bords.
        val startY = (height / 2f - travel / 2f).coerceIn(height * 0.2f, height * 0.8f)
        val endY = (startY + travel).coerceIn(height * 0.15f, height * 0.85f)

        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }
        dispatch(path, SCROLL_DURATION_MS)
    }

    /**
     * Commutation rapide entre applications.
     *
     * Sur un appareil en navigation gestuelle, un balayage horizontal le long du bord inférieur
     * fait défiler les applications récentes sans passer par leur écran de sélection : c'est le
     * geste le plus fluide, et le seul qu'une application puisse synthétiser. En navigation par
     * boutons ce geste n'existe pas ; l'écran des récents sert alors de repli.
     */
    private fun quickSwitch(forward: Boolean, width: Float, height: Float) {
        val y = height - EDGE_MARGIN_PX
        val from = if (forward) width * 0.25f else width * 0.75f
        val to = if (forward) width * 0.85f else width * 0.15f

        val path = Path().apply {
            moveTo(from, y)
            lineTo(to, y)
        }
        val dispatched = dispatch(path, QUICK_SWITCH_MS)
        if (!dispatched) global(GLOBAL_ACTION_RECENTS)
    }

    private fun mediaPlayPause() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        // Deux évènements sont nécessaires : les lecteurs réagissent au relâchement de la touche.
        val now = android.os.SystemClock.uptimeMillis()
        runCatching {
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0)
            )
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0)
            )
        }.onFailure { Log.w(TAG, "Commande média refusée", it) }
    }

    private fun dispatch(path: Path, durationMs: Long): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return runCatching { dispatchGesture(gesture, null, null) }
            .onFailure { Log.w(TAG, "Geste refusé", it) }
            .getOrDefault(false)
    }

    companion object {
        private const val TAG = "StarkA11yService"

        private const val TAP_DURATION_MS = 60L
        private const val MIN_DRAG_MS = 120L
        private const val MAX_DRAG_MS = 600L
        private const val SCROLL_DURATION_MS = 180L
        private const val QUICK_SWITCH_MS = 220L

        /** Distance au bord inférieur pour la commutation rapide, en pixels. */
        private const val EDGE_MARGIN_PX = 2f

        @Volatile
        var instance: StarkAccessibilityService? = null
            private set

        /** Vrai si l'utilisateur a activé le service dans les réglages d'accessibilité. */
        val isConnected: Boolean get() = instance != null

        /**
         * Exécute une action système. Retourne false si le service n'est pas activé, ce qui
         * permet à l'appelant de proposer l'activation plutôt que d'échouer en silence.
         */
        fun perform(action: SystemAction, screenWidth: Float, screenHeight: Float): Boolean {
            val service = instance ?: return false
            service.execute(action, screenWidth, screenHeight)
            return true
        }

        /** Raccourci pour les actions globales sans géométrie. */
        fun perform(action: SystemAction): Boolean = perform(action, 1f, 1f)
    }
}
