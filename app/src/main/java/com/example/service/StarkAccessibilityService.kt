package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.control.SystemAction
import com.example.control.TargetSnapper
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

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

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
            is SystemAction.LongPress -> longPress(action.x * width, action.y * height)
            is SystemAction.DoubleTap -> {
                tap(action.x * width, action.y * height)
                mainHandler.postDelayed({ tap(action.x * width, action.y * height) }, DOUBLE_TAP_GAP_MS)
            }

            is SystemAction.Drag -> drag(
                action.fromX * width, action.fromY * height,
                action.toX * width, action.toY * height
            )

            is SystemAction.Scroll -> scroll(action.amount, width, height, vertical = true)
            is SystemAction.ScrollHorizontal -> scroll(action.amount, width, height, vertical = false)

            SystemAction.Back -> global(GLOBAL_ACTION_BACK)
            SystemAction.Home -> global(GLOBAL_ACTION_HOME)
            SystemAction.Recents -> global(GLOBAL_ACTION_RECENTS)
            SystemAction.Notifications -> global(GLOBAL_ACTION_NOTIFICATIONS)

            // Les réglages rapides donnent accès aux données mobiles, au Wi-Fi et à la lampe :
            // sans cette action, une part entière du téléphone restait hors d'atteinte.
            SystemAction.QuickSettings -> global(GLOBAL_ACTION_QUICK_SETTINGS)

            SystemAction.PowerDialog -> global(GLOBAL_ACTION_POWER_DIALOG)
            SystemAction.SplitScreen -> global(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)

            SystemAction.Screenshot ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    global(GLOBAL_ACTION_TAKE_SCREENSHOT)
                } else {
                    Log.i(TAG, "Capture d'écran indisponible avant Android 11")
                }

            SystemAction.LockScreen ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    global(GLOBAL_ACTION_LOCK_SCREEN)
                } else {
                    Log.i(TAG, "Verrouillage indisponible avant Android 9")
                }

            SystemAction.PlayPause -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            SystemAction.MediaNext -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            SystemAction.MediaPrevious -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

            SystemAction.VolumeUp -> adjustVolume(AudioManager.ADJUST_RAISE)
            SystemAction.VolumeDown -> adjustVolume(AudioManager.ADJUST_LOWER)
            SystemAction.VolumeMute -> adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE)

            is SystemAction.TypeText -> typeText(action.text)
            SystemAction.Backspace -> backspace()

            SystemAction.SwitchNext -> quickSwitch(forward = true, width = width, height = height)
            SystemAction.SwitchPrevious -> quickSwitch(forward = false, width = width, height = height)

            // Purement visuelles ou traitées ailleurs.
            is SystemAction.MoveCursor,
            is SystemAction.DragStart,
            is SystemAction.DragMove,
            SystemAction.ToggleKeyboard,
            SystemAction.None -> Unit
        }
    }

    // --- Saisie de texte ---------------------------------------------------------------------

    /**
     * Trouve le champ de saisie actif et y insère du texte.
     *
     * `ACTION_SET_TEXT` remplace intégralement le contenu : on relit donc l'existant et on
     * réécrit la concaténation. C'est la seule voie disponible sans écrire un clavier système
     * complet, et elle suppose de pouvoir lire le contenu de la fenêtre.
     */
    private fun typeText(text: String) {
        val node = findFocusedEditable() ?: run {
            Log.i(TAG, "Aucun champ de saisie actif")
            return
        }
        val current = node.text?.toString() ?: ""
        setText(node, current + text)
        node.recycle()
    }

    private fun backspace() {
        val node = findFocusedEditable() ?: return
        val current = node.text?.toString() ?: ""
        if (current.isNotEmpty()) setText(node, current.dropLast(1))
        node.recycle()
    }

    private fun setText(node: AccessibilityNodeInfo, value: String) {
        val arguments = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) }
            .onFailure { Log.w(TAG, "Écriture refusée", it) }
    }

    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        val focused = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        return if (focused?.isEditable == true) focused else null
    }

    private fun global(action: Int) {
        runCatching { performGlobalAction(action) }
            .onFailure { Log.w(TAG, "Action globale $action refusée", it) }
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatch(path, durationMs = TAP_DURATION_MS)
    }

    /** L'appui long se distingue du bref par la seule durée du contact. */
    private fun longPress(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatch(path, durationMs = LONG_PRESS_MS)
    }

    private fun mediaKey(keyCode: Int) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val now = android.os.SystemClock.uptimeMillis()
        runCatching {
            audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        }.onFailure { Log.w(TAG, "Commande média refusée", it) }
    }

    private fun adjustVolume(direction: Int) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                direction,
                AudioManager.FLAG_SHOW_UI
            )
        }.onFailure { Log.w(TAG, "Réglage du volume refusé", it) }
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
    private fun scroll(amount: Float, width: Float, height: Float, vertical: Boolean) {
        if (abs(amount) < 1e-3f) return

        val span = if (vertical) height else width
        val travel = (amount * span).coerceIn(-span * 0.6f, span * 0.6f)

        // Le trajet est centré pour rester dans la zone défilable quelle que soit la mise en page,
        // et ne jamais empiéter sur les zones de gestes système des bords.
        val path = Path().apply {
            if (vertical) {
                val startY = (height / 2f - travel / 2f).coerceIn(height * 0.2f, height * 0.8f)
                moveTo(width / 2f, startY)
                lineTo(width / 2f, (startY + travel).coerceIn(height * 0.15f, height * 0.85f))
            } else {
                val startX = (width / 2f - travel / 2f).coerceIn(width * 0.2f, width * 0.8f)
                moveTo(startX, height / 2f)
                lineTo((startX + travel).coerceIn(width * 0.15f, width * 0.85f), height / 2f)
            }
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

    /**
     * Rectangles des éléments cliquables de la fenêtre au premier plan, en pixels.
     *
     * Le parcours est borné en profondeur et en nombre de nœuds. Une hiérarchie de vues peut être
     * profonde et large — une liste longue en compte des centaines — et ce parcours a lieu au
     * moment du clic, sur le thread principal : une exploration exhaustive s'y verrait.
     *
     * Le parcours est itératif plutôt que récursif : la profondeur d'une hiérarchie de vues n'est
     * pas bornée par construction, et une pile d'appels ne doit pas dépendre de ce qu'affiche
     * l'application au premier plan.
     */
    private fun collectTargets(): List<TargetSnapper.Target> {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return emptyList()

        val targets = ArrayList<TargetSnapper.Target>(MAX_TARGETS)
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.addLast(root to 0)
        var visited = 0
        val bounds = android.graphics.Rect()

        while (queue.isNotEmpty() && visited < MAX_NODES && targets.size < MAX_TARGETS) {
            val (node, depth) = queue.removeFirst()
            visited++

            if (node.isClickable && node.isVisibleToUser && node.isEnabled) {
                node.getBoundsInScreen(bounds)
                targets += TargetSnapper.Target(
                    bounds.left.toFloat(), bounds.top.toFloat(),
                    bounds.right.toFloat(), bounds.bottom.toFloat()
                )
            }

            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                    queue.addLast(child to depth + 1)
                }
            }
        }

        return targets
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
        private const val LONG_PRESS_MS = 700L
        private const val DOUBLE_TAP_GAP_MS = 120L
        private const val MIN_DRAG_MS = 120L
        private const val MAX_DRAG_MS = 600L
        private const val SCROLL_DURATION_MS = 180L
        private const val QUICK_SWITCH_MS = 220L

        /** Distance au bord inférieur pour la commutation rapide, en pixels. */
        private const val EDGE_MARGIN_PX = 2f

        // Bornes du parcours de l'arbre d'accessibilité. Il a lieu au moment du clic, sur le
        // thread principal : une exploration exhaustive d'une liste longue s'y verrait.
        private const val MAX_NODES = 400
        private const val MAX_DEPTH = 24
        private const val MAX_TARGETS = 120

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

        /**
         * Cible interactive vers laquelle aimanter un appui, en coordonnées écran normalisées,
         * ou null s'il n'y en a aucune assez proche — l'appui doit alors partir exactement là où
         * l'utilisateur a pointé.
         *
         * Retourne le rectangle complet et non son seul centre : l'appelant en a besoin pour
         * *montrer* la cible retenue. Une aimantation invisible se comporterait comme un curseur
         * qui ment, et détruirait la confiance qu'elle est censée créer.
         */
        fun snapTarget(
            xNorm: Float,
            yNorm: Float,
            screenWidth: Float,
            screenHeight: Float
        ): TargetSnapper.Target? {
            val service = instance ?: return null
            if (screenWidth <= 0f || screenHeight <= 0f) return null

            val targets = TargetSnapper.usable(
                service.collectTargets(), screenWidth, screenHeight
            )
            val snapped = TargetSnapper.snap(
                x = xNorm * screenWidth,
                y = yNorm * screenHeight,
                targets = targets,
                radius = TargetSnapper.defaultRadius(screenWidth, screenHeight)
            ) ?: return null

            return TargetSnapper.Target(
                left = snapped.left / screenWidth,
                top = snapped.top / screenHeight,
                right = snapped.right / screenWidth,
                bottom = snapped.bottom / screenHeight
            )
        }
    }
}
