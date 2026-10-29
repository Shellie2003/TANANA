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
import com.example.control.Command
import com.example.control.CommandList
import com.example.control.EdgeScroller
import com.example.control.ScrollRate
import com.example.control.PinchTelemetry
import com.example.control.Pointer
import com.example.control.PointerEvent
import com.example.control.SystemAction
import com.example.gesture.HandGestureAnalyzer
import com.example.gesture.HandGestureListener
import com.example.gesture.HandGestureState
import com.example.gesture.PinchDetector
import com.example.input.CommandPanel
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

    /**
     * Le modèle d'interaction. Reconstruit quand la fermeté du pincement change : le détecteur
     * perd alors la course apprise, ce qui est voulu — l'utilisateur vient de changer de réglage
     * et va enchaîner quelques gestes d'essai, qui doivent servir de nouvelle référence.
     */
    private var pointer = Pointer()
    private val panel = CommandPanel()

    /**
     * Défilement par les bords : un second chemin, qui ne demande aucun pincement maintenu.
     *
     * Hors du [pointer] à dessein — il ne produit pas d'évènement de pointage et n'a rien à
     * arbitrer avec les trois issues du pincement. Il lit la position, et c'est tout.
     */
    private val edgeScroller = EdgeScroller()

    /**
     * Défilement à vitesse commandée : la troisième méthode, et la seule qui ne borne pas la
     * distance défilée par la distance que le bras peut parcourir.
     */
    private val scrollRate = ScrollRate()
    private var commands = CommandList()

    /**
     * Libellés du panneau, calculés une fois par changement de configuration.
     *
     * `commands.labels()` allouait une liste à **chaque image**, pour un contenu qui ne change
     * qu'au réglage. Trente listes par seconde dont rien ne diffère : invisible à la lecture,
     * mais c'est la somme de ce genre de détails qui finit par se voir.
     */
    private var commandLabels: List<String> = commands.labels()

    /** Dernière publication de la télémétrie : elle n'est lue qu'à 5 Hz par l'écran. */
    private var telemetryAt = 0L
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

    /**
     * Démarre la vision **sans bloquer le thread principal**, et en parallèle de la caméra.
     *
     * Les deux initialisations sont indépendantes et duraient l'une après l'autre : le modèle
     * d'abord, sur le thread principal, puis la caméra. Le coût total était leur somme, et il
     * atteignait cinq secondes pendant lesquelles rien ne s'affichait. Elles courent maintenant
     * ensemble, et le coût est celui de la plus lente.
     *
     * L'ordre compte : la caméra est liée d'abord, pour que ses premières images arrivent au plus
     * tôt. Celles qui précèdent la disponibilité du modèle sont ignorées sans frais.
     */
    private fun startCamera() {
        if (analyzer == null) {
            analyzer = HandGestureAnalyzer(applicationContext, this)
            refreshPowerState()
        }
        bindCamera()
        analyzer?.let { engine -> cameraExecutor.execute { engine.prepare() } }
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
        commands = bindingsStore.loadCommands()
        commandLabels = commands.labels()
        pointer = Pointer(detector = PinchDetector(bindingsStore.loadClickTuning().toConfig()))
        panel.reset()
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

        val now = SystemClock.uptimeMillis()
        val events = pointer.update(
            handPresent = state.isHandDetected,
            quality = state.confidence,
            x = cursorX,
            y = cursorY,
            pinchRatio = state.pinchRatio,
            nowMs = now,
            // Le déplacement de la main se mesure sur la paume, que la fermeture des doigts ne
            // bouge pas — là où le bout de l'index, dont vient le curseur, est justement l'endroit
            // que le pouce vient perturber en pinçant.
            palmX = state.palmX,
            palmY = state.palmY,
            // Poser le curseur contre un bord est la façon de défiler : ce ne doit pas être aussi
            // la façon d'appuyer sur ce qui s'y trouve. Les deux mécanismes se déclenchent sur la
            // même chose — l'immobilité — et seul l'appelant sait où sont les bandes.
            dwellInhibited = edgeScroller.isInBand(cursorX, cursorY) || scrollRate.isActive
        )
        // L'écran ne lit cette mesure qu'à 5 Hz : la recalculer à chaque image allouerait trente
        // objets par seconde dont vingt-cinq ne seraient jamais lus.
        if (now - telemetryAt >= TELEMETRY_INTERVAL_MS) {
            telemetryAt = now
            pinchTelemetry = pointer.telemetry()
        }

        // Le panneau suit la position de la main : il n'a pas de bascule, donc rien à
        // synchroniser, et on ne peut pas y rester coincé. Il reste fermé pendant la saisie, où
        // le clavier occupe déjà l'écran.
        if (!keyboardOpen) {
            panel.update(cursorX, cursorY, pointer.isPressed, commands.size, now)
        }

        overlayView?.update(
            x = cursorX,
            y = cursorY,
            pressed = pointer.isPressed,
            // L'anneau affiche la progression réelle du pincement : la même grandeur que celle
            // qui décide. Ce que l'utilisateur voit se remplir correspond donc à ce qui se passe.
            // Pendant le défilement continu, l'anneau porte la **vitesse commandée** plutôt que la
            // progression du pincement : c'est la seule grandeur qui compte à cet instant, et un
            // mode sans retour visible est un piège. On voit donc ce qu'on commande, et la zone
            // morte se reconnaît à un anneau vide.
            pinchProgress = scrollRate.state?.rate ?: pointer.pinchProgress,
            // holdProgress, et non longPressProgress : l'anneau porte l'appui long quand le bouton
            // est enfoncé, et le maintien quand il ne l'est pas. Les deux ne peuvent pas courir
            // ensemble, et ce sont la même information pour qui regarde — un compte à rebours
            // qu'un mouvement annule.
            // L'amorçage du défilement par les bords partage le même anneau, et pour la même
            // raison : c'est encore un compte à rebours qu'un mouvement annule. Les trois sont
            // mutuellement exclusifs — l'appui long exige le bouton enfoncé, le maintien l'exige
            // relâché, et le défilement par les bords est inhibé pendant un pincement.
            longPressProgress = maxOf(pointer.holdProgress, edgeScroller.state?.progress ?: 0f),
            visible = state.isHandDetected
        )
        // Panneau entièrement fermé et sans aperçu : rien à transmettre. C'est le cas courant —
        // la main n'est pas près du bord droit — et il évite d'allouer un état et d'en comparer
        // les libellés à chaque image.
        val panelState = when {
            keyboardOpen -> null
            panel.isOpen -> panel.state(commandLabels)
            else -> panel.state(commandLabels).takeIf { it.openProgress > 0f }
        }
        overlayView?.setPanel(panelState)

        when {
            keyboardOpen -> routeToKeyboard(events, now)
            panel.isOpen -> routeToPanel(events)
            // Le défilement à vitesse commandée capture tout : c'est un mode, et pendant qu'il
            // tourne le déplacement de la main signifie « défiler », pas « viser ». Laisser passer
            // les appuis produirait des clics à chaque fois qu'on règle la vitesse.
            scrollRate.isActive -> rateStep(now)
            else -> {
                events.forEach { dispatch(it) }
                // Le défilement par les bords ne vit que dans l'interface du dessous : le clavier
                // gestuel et le panneau de commandes occupent déjà les bords et ont leurs propres
                // règles, où un défilement n'aurait aucun sens.
                edgeStep(cursorX, cursorY, now)
            }
        }
    }

    /**
     * Un pas de défilement à vitesse commandée.
     *
     * Le pas s'applique au **point neutre** et non au curseur : c'est là que se trouve le contenu
     * qu'on lit, alors que le curseur s'en est écarté pour commander la vitesse. Viser le curseur
     * ferait défiler ce qui se trouve en bout de course, parfois un autre conteneur.
     */
    private fun rateStep(now: Long) {
        val direction = scrollRate.update(cursorX, cursorY, pointer.isPressed, now) ?: return
        // L'état est lu **après** la mise à jour : c'est elle qui fixe le point neutre, lequel
        // n'existe pas encore au premier appel — le mode s'arme puis attend que la main se pose.
        val neutral = scrollRate.state ?: return
        StarkAccessibilityService.perform(
            SystemAction.ScrollStep(neutral.neutralX, neutral.neutralY, direction),
            screenWidth,
            screenHeight
        )
    }

    /**
     * Un pas de défilement par les bords, s'il y a lieu.
     *
     * Le curseur posé contre un bord suffit : aucun pincement à tenir, donc aucune dépendance à la
     * mesure la plus fragile de la chaîne — l'écart pouce-index, que le pincement lui-même
     * perturbe. Voir [EdgeScroller] pour le détail du raisonnement.
     */
    private fun edgeStep(x: Float, y: Float, now: Long) {
        val direction = edgeScroller.update(x, y, pointer.isPressed, now) ?: return
        StarkAccessibilityService.perform(
            SystemAction.ScrollStep(x, y, direction), screenWidth, screenHeight
        )
    }

    /**
     * Panneau ouvert : il capture tout.
     *
     * Rien ne doit partir dans l'application du dessous pendant qu'on choisit une commande — ni
     * l'appui qui valide, ni un tracé entamé par mégarde. C'est la même règle que pour le clavier,
     * et pour la même raison.
     */
    private fun routeToPanel(events: List<PointerEvent>) {
        if (events.none { it is PointerEvent.Tap }) return
        val index = panel.commit() ?: return
        val command = commands[index] ?: return
        Log.i(TAG, "Commande : ${command.label}")
        runCommand(command)
    }

    private fun runCommand(command: Command) {
        if (command.action is SystemAction.ToggleKeyboard) {
            toggleKeyboard()
            return
        }
        if (command.action is SystemAction.ToggleScrollRate) {
            // Le point neutre est pris là où la main se trouve, et non au centre de l'écran : on
            // active ce mode en lisant, donc la main est déjà dans une position tenable.
            val active = scrollRate.toggle(cursorX, cursorY)
            Log.i(TAG, if (active) "Défilement continu activé" else "Défilement continu arrêté")
            return
        }
        if (!StarkAccessibilityService.perform(command.action, screenWidth, screenHeight)) {
            Log.i(TAG, "Service d'accessibilité inactif : ${command.label} ignorée")
        }
    }

    /** Traduit un évènement de pointeur en action système, et l'exécute. */
    private fun dispatch(event: PointerEvent) {
        val action = when (event) {
            is PointerEvent.Tap -> SystemAction.Tap(event.x, event.y)
            is PointerEvent.LongPress -> SystemAction.LongPress(event.x, event.y)
            is PointerEvent.StrokeBegin ->
                SystemAction.DragStart(event.x, event.y, event.driveX, event.driveY)
            is PointerEvent.StrokeMove ->
                SystemAction.DragMove(event.x, event.y, event.driveX, event.driveY)
            // Le trajet complet, et non seulement le point d'arrivée : quand la diffusion
            // continue n'est pas disponible, le service d'accessibilité rejoue le glisser d'un
            // bloc, et il lui faut alors les deux extrémités.
            is PointerEvent.StrokeEnd ->
                SystemAction.Drag(event.fromX, event.fromY, event.toX, event.toY)
        }

        val resolved = snapToTarget(action)
        if (!StarkAccessibilityService.perform(resolved, screenWidth, screenHeight)) {
            Log.i(TAG, "Service d'accessibilité inactif : $resolved ignorée")
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
     *
     * **Un appui déjà dans la cible n'est pas déplacé.** Il l'était, ramené au centre, et c'était
     * le principal défaut de précision : dans un champ de texte le curseur partait toujours au
     * milieu, sur un réglage de volume on ne pouvait atteindre que la position médiane, et dans une
     * carte ou une image on visait le centre quoi qu'on fasse.
     */
    private fun snapToTarget(action: SystemAction): SystemAction {
        val (x, y) = when (action) {
            is SystemAction.Tap -> action.x to action.y
            is SystemAction.LongPress -> action.x to action.y
            // Les autres actions n'ont pas de point d'application, ou en ont deux (un glisser) :
            // il n'y a rien à aimanter.
            else -> return action
        }

        val snap = StarkAccessibilityService.snapTarget(x, y, screenWidth, screenHeight)
        val px = snap?.x ?: x
        val py = snap?.y ?: y
        overlayView?.flashTap(px, py, snap?.target)

        return when (action) {
            is SystemAction.Tap -> SystemAction.Tap(px, py)
            is SystemAction.LongPress -> SystemAction.LongPress(px, py)
            else -> action
        }
    }

    /**
     * Sélection au clavier radial.
     *
     * **L'appui long en est la sortie.** Le poing servait à cela, et il n'existe plus. Réutiliser
     * l'appui long, qui fait déjà partie du modèle et ne sert à rien d'autre pendant la saisie,
     * évite d'inventer un geste pour une seule situation — c'est précisément le genre d'ajout qui
     * a fini par rendre le vocabulaire incompréhensible.
     *
     * Le clavier valide au même pincement que le reste de l'application, et par le même détecteur :
     * la course apprise pour cette main survit donc à l'ouverture du clavier.
     */
    private fun routeToKeyboard(events: List<PointerEvent>, nowMs: Long) {
        if (events.any { it is PointerEvent.LongPress }) {
            toggleKeyboard()
            return
        }

        val emission = keyboard.update(
            cursorX = cursorX,
            cursorY = cursorY,
            nowMs = nowMs,
            commit = events.any { it is PointerEvent.Tap },
            clickProgress = pointer.pinchProgress
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
        pointer.reset()
        panel.reset()
        overlayView?.setKeyboard(if (keyboardOpen) keyboard.state() else null)
        overlayView?.setPanel(null)
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
        pinchTelemetry = PinchTelemetry()
    }

    companion object {
        private const val TAG = "StarkVisionService"
        private const val CHANNEL_ID = "stark_vision"
        private const val NOTIFICATION_ID = 42

        /** Période de publication de la télémétrie du pincement, en millisecondes. */
        private const val TELEMETRY_INTERVAL_MS = 150L

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
         * État courant du pincement, publié pour l'écran de réglage.
         *
         * C'est désormais le **seul** geste, et son échec est silencieux : rien ne distingue, vu
         * de l'utilisateur, un geste mal formé d'une cible manquée. Exposer la course apprise, le
         * taux de réussite et la cause du dernier rejet est ce qui rend le geste corrigible — et
         * donc ce qui permet de lui faire confiance, puisqu'il n'y a aucun repli.
         */
        @Volatile
        var pinchTelemetry: PinchTelemetry = PinchTelemetry()
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
