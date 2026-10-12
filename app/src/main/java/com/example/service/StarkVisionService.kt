package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.BatteryManager
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.MainActivity
import com.example.R
import com.example.control.BindingsStore
import com.example.control.GesturePolicy
import com.example.control.SystemAction
import com.example.gesture.ClickDetector
import com.example.gesture.GestureType
import com.example.gesture.HandGestureAnalyzer
import com.example.gesture.HandGestureListener
import com.example.gesture.HandGestureState
import com.example.input.RadialKeyboard
import com.example.ui.overlay.CursorOverlayView
import java.util.concurrent.Executors

/**
 * Service qui étend le contrôle gestuel à l'ensemble du téléphone.
 *
 * Trois responsabilités, indissociables :
 *
 *  1. **Garder la caméra active en arrière-plan.** Seul un service de premier plan de type
 *     `camera` en a le droit ; il impose en contrepartie une notification permanente, qu'Android
 *     exige pour que l'utilisateur sache que sa caméra est utilisée.
 *  2. **Dessiner le curseur au-dessus des autres applications**, via une fenêtre de superposition.
 *  3. **Traduire les gestes en actions système**, exécutées par le service d'accessibilité.
 *
 * Le service implémente [LifecycleOwner] parce que CameraX lie ses cas d'usage à un cycle de vie ;
 * un `LifecycleRegistry` piloté à la main évite d'ajouter une dépendance pour ce seul besoin.
 */
class StarkVisionService : Service(), LifecycleOwner, HandGestureListener {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    // getMainExecutor() n'existe qu'à partir de l'API 28 ; le projet descend jusqu'à l'API 24.
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var analyzer: HandGestureAnalyzer? = null

    private val policy = GesturePolicy()
    private val keyboard = RadialKeyboard()
    private var keyboardOpen = false
    private lateinit var bindingsStore: BindingsStore

    private var windowManager: WindowManager? = null
    private var overlayView: CursorOverlayView? = null

    private var screenWidth = 1080f
    private var screenHeight = 2400f

    /** Dernière position connue du curseur, conservée entre deux poses. */
    private var cursorX = 0.5f
    private var cursorY = 0.5f

    private var cameraBound = false

    /**
     * Suivi de l'état énergétique de l'appareil.
     *
     * L'écran éteint est le cas le plus important : la caméra est alors intégralement déliée,
     * ce qui éteint le capteur et son processeur d'image. Se contenter d'ignorer les images
     * laisserait tourner toute la chaîne d'acquisition, de loin le poste le plus coûteux.
     */
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    analyzer?.screenOn = false
                    unbindCamera()
                }
                Intent.ACTION_SCREEN_ON -> {
                    analyzer?.screenOn = true
                    refreshPowerState()
                    bindCamera()
                }
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED,
                Intent.ACTION_BATTERY_CHANGED -> refreshPowerState()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        readScreenMetrics()

        bindingsStore = BindingsStore(this)
        applyPreferences()

        registerReceiver(
            powerReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }
        )
    }

    /** Relit le mode économie d'énergie et le niveau de batterie. */
    private fun refreshPowerState() {
        val analyzer = this.analyzer ?: return
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        analyzer.powerSaveMode = powerManager?.isPowerSaveMode == true

        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        analyzer.batteryPercent =
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // Les préférences peuvent avoir changé pendant que le service tournait : on les relit à
        // chaque démarrage plutôt que de dépendre d'un redémarrage complet.
        applyPreferences()

        startInForeground()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        attachOverlay()
        startCamera()

        // START_STICKY : si le système tue le service sous pression mémoire, il le relance et le
        // contrôle gestuel revient sans intervention de l'utilisateur.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- Premier plan --------------------------------------------------------------------------

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.vision_channel_name),
                    // IMPORTANCE_LOW : la notification est obligatoire mais ne doit ni sonner
                    // ni s'imposer visuellement à chaque démarrage.
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = getString(R.string.vision_channel_description) }
            )
        }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, StarkVisionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.vision_notification_title))
            .setContentText(getString(R.string.vision_notification_text))
            .setSmallIcon(R.drawable.ic_stat_stark)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.vision_notification_stop), stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // --- Superposition -------------------------------------------------------------------------

    private fun attachOverlay() {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Permission de superposition absente : le curseur ne sera pas visible.")
            return
        }

        val view = CursorOverlayView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // NOT_TOUCHABLE est essentiel : la superposition couvre tout l'écran et intercepterait
            // sinon chaque appui, rendant le téléphone inutilisable au doigt.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        runCatching { windowManager?.addView(view, params) }
            .onSuccess { overlayView = view }
            .onFailure { Log.e(TAG, "Superposition impossible", it) }
    }

    private fun detachOverlay() {
        overlayView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        overlayView = null
    }

    private fun readScreenMetrics() {
        val wm = windowManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            screenWidth = bounds.width().toFloat()
            screenHeight = bounds.height().toFloat()
        } else {
            @Suppress("DEPRECATION")
            val display = wm.defaultDisplay
            @Suppress("DEPRECATION")
            val point = android.graphics.Point().also { display.getRealSize(it) }
            screenWidth = point.x.toFloat()
            screenHeight = point.y.toFloat()
        }
    }

    // --- Caméra --------------------------------------------------------------------------------

    private fun startCamera() {
        if (analyzer == null) {
            analyzer = HandGestureAnalyzer(applicationContext, this)
            refreshPowerState()
        }
        bindCamera()
    }

    private fun unbindCamera() {
        if (!cameraBound) return
        cameraBound = false
        cameraProvider?.unbindAll()
        overlayView?.hide()
        Log.i(TAG, "Caméra déliée : écran éteint, aucune consommation de capteur")
    }

    private fun bindCamera() {
        if (cameraBound) return
        val handAnalyzer = analyzer ?: return

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val resolutionSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            android.util.Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                val imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                imageAnalysis.setAnalyzer(cameraExecutor, handAnalyzer)

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, imageAnalysis)
                lifecycleRegistry.currentState = Lifecycle.State.RESUMED
                cameraBound = true
                Log.i(TAG, "Contrôle gestuel système actif")
            } catch (e: Exception) {
                Log.e(TAG, "Liaison caméra impossible", e)
                stopSelf()
            }
        }, { command -> mainHandler.post(command) })
    }

    /** Relit les préférences et les applique à la politique de commande. */
    private fun applyPreferences() {
        policy.updateBindings(bindingsStore.load())
        policy.updateClickTuning(bindingsStore.loadClickTuning())
    }

    // --- Réception des gestes ------------------------------------------------------------------

    override fun onHandGestureUpdated(state: HandGestureState) {
        // Le callback arrive sur le thread d'analyse ; la superposition et WindowManager exigent
        // le thread principal.
        mainHandler.post { handleGesture(state) }
    }

    private fun handleGesture(state: HandGestureState) {
        latestState = state

        if (state.isHandDetected) {
            cursorX = state.cursorX
            cursorY = state.cursorY
        }

        overlayView?.update(
            x = cursorX,
            y = cursorY,
            gesture = state.gesture,
            // L'anneau du curseur affiche la progression réelle du geste de clic, et non plus la
            // force brute du pincement. C'est la même grandeur que celle qui déclenche l'appui :
            // ce que l'utilisateur voit se remplir correspond donc exactement à ce qui décide.
            clickProgress = policy.clickProgress,
            holdProgress = policy.holdProgress,
            visible = state.isHandDetected && state.gesture != GestureType.IDLE,
            poseProgress = policy.poseProgress
        )

        // Clavier ouvert : le curseur ne pilote plus le système mais la sélection des caractères.
        // Court-circuiter la politique ici évite qu'un appui parte dans l'application sous-jacente
        // pendant qu'on écrit.
        if (keyboardOpen) {
            handleKeyboard(state)
            return
        }

        val actions = policy.onGesture(
            gesture = state.gesture,
            cursorX = cursorX,
            cursorY = cursorY,
            isNewPose = state.isNewPose,
            timestampMs = SystemClock.uptimeMillis(),
            pinchRatio = state.pinchRatio
        )

        clickTelemetry = policy.clickTelemetry()

        // La roue suit la pose : elle n'a pas de bascule à gérer, donc rien à synchroniser. On lui
        // demande son état à chaque image et l'affichage s'aligne — ce qui est précisément ce qui
        // rend impossible d'y rester coincé.
        overlayView?.setWheel(policy.wheelState())

        for (action in actions) {
            if (action is SystemAction.MoveCursor) continue
            if (action is SystemAction.ToggleKeyboard) {
                toggleKeyboard()
                continue
            }

            val resolved = snapToTarget(action)
            if (!StarkAccessibilityService.perform(resolved, screenWidth, screenHeight)) {
                Log.i(TAG, "Service d'accessibilité inactif : $resolved ignorée")
            }
        }
    }

    /**
     * Ramène un appui sur la cible interactive la plus proche, et le confirme à l'écran.
     *
     * L'aimantation franchit la limite que le filtrage ne peut pas franchir : le tremblement de
     * la main tendue couvre plusieurs millimètres, et aucun lissage ne rend un pointage aérien
     * aussi précis qu'un doigt posé. Viser des objets plutôt que des pixels lève la contrainte.
     *
     * La cible retenue est dessinée : une aimantation invisible se comporterait comme un curseur
     * qui ment. En la montrant, l'utilisateur voit ce qui a réellement été touché — et apprend
     * du même coup jusqu'où il peut se permettre d'être approximatif.
     */
    private fun snapToTarget(action: SystemAction): SystemAction {
        val (x, y) = when (action) {
            is SystemAction.Tap -> action.x to action.y
            is SystemAction.LongPress -> action.x to action.y
            // Les autres actions n'ont pas de point d'application, ou en ont deux (un glisser) :
            // il n'y a rien à aimanter.
            else -> return action
        }

        val target = StarkAccessibilityService.snapTarget(x, y, screenWidth, screenHeight)
        val px = target?.centerX ?: x
        val py = target?.centerY ?: y
        overlayView?.flashTap(px, py, target)

        return when (action) {
            is SystemAction.Tap -> SystemAction.Tap(px, py)
            is SystemAction.LongPress -> SystemAction.LongPress(px, py)
            else -> action
        }
    }

    /**
     * Sélection au clavier radial.
     *
     * Le poing sert de sortie : c'est la pose la plus éloignée du pointage, donc la moins
     * susceptible d'être formée par accident en écrivant.
     */
    private fun handleKeyboard(state: HandGestureState) {
        if (state.gesture == GestureType.FIST && state.isNewPose) {
            toggleKeyboard()
            return
        }

        val now = SystemClock.uptimeMillis()
        // Le clavier valide au même clic que le reste de l'application, et par le même détecteur :
        // la course apprise pour cette main survit donc à l'ouverture du clavier.
        val click = policy.clickOnly(state.pinchRatio, cursorX, cursorY, now)
        val emission = keyboard.update(
            cursorX = cursorX,
            cursorY = cursorY,
            nowMs = now,
            commit = click == ClickDetector.Event.CLICK,
            clickProgress = policy.clickProgress
        )
        overlayView?.setKeyboard(keyboard.state())

        when {
            emission.backspace ->
                StarkAccessibilityService.perform(SystemAction.Backspace, screenWidth, screenHeight)
            emission.text != null ->
                StarkAccessibilityService.perform(
                    SystemAction.TypeText(emission.text), screenWidth, screenHeight
                )
        }
    }

    private fun toggleKeyboard() {
        keyboardOpen = !keyboardOpen
        keyboard.reset()
        policy.reset()
        overlayView?.setKeyboard(if (keyboardOpen) keyboard.state() else null)
        // La roue et le clavier occupent la même couronne : les laisser coexister superposerait
        // deux menus illisibles.
        overlayView?.setWheel(null)
        Log.i(TAG, if (keyboardOpen) "Clavier gestuel ouvert" else "Clavier gestuel fermé")
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(powerReceiver) }
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        cameraBound = false
        cameraProvider?.unbindAll()
        cameraProvider = null
        analyzer?.close()
        analyzer = null
        cameraExecutor.shutdown()
        detachOverlay()
        isRunning = false
        latestState = null
        clickTelemetry = GesturePolicy.ClickTelemetry()
    }

    companion object {
        private const val TAG = "StarkVisionService"
        private const val CHANNEL_ID = "stark_vision"
        private const val NOTIFICATION_ID = 42

        private const val ANALYSIS_WIDTH = 640
        private const val ANALYSIS_HEIGHT = 480

        const val ACTION_STOP = "com.example.stark.STOP_VISION"

        /** Vrai tant que le contrôle gestuel système est actif. */
        @Volatile
        var isRunning = false
            private set

        /**
         * Dernier état publié, lu par l'interface pour afficher la télémétrie sans dupliquer
         * une seconde instance de caméra.
         */
        @Volatile
        var latestState: HandGestureState? = null
            private set

        /**
         * État courant du clic, publié pour l'écran de réglage.
         *
         * Le clic est le seul geste dont l'échec est silencieux : rien ne distingue, vu de
         * l'utilisateur, un geste mal formé d'une cible manquée. Exposer la course apprise, le
         * taux de réussite et la cause du dernier rejet est ce qui rend le geste corrigible — et
         * donc ce qui permet de lui faire confiance.
         */
        @Volatile
        var clickTelemetry: GesturePolicy.ClickTelemetry = GesturePolicy.ClickTelemetry()
            private set

        fun start(context: Context) {
            val intent = Intent(context, StarkVisionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            isRunning = true
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, StarkVisionService::class.java).setAction(ACTION_STOP)
            )
            isRunning = false
        }
    }
}
