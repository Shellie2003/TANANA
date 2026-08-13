package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.gesture.GestureType
import com.example.gesture.HandGestureAnalyzer
import com.example.gesture.HandGestureListener
import com.example.gesture.HandGestureState
import com.example.service.StarkAccessibilityService
import com.example.ui.StarkHUDLauncher
import com.example.ui.theme.StarkHUDTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Activity principale du Stark HUD Launcher.
 *
 * Responsabilités : cycle de vie de CameraX, publication de l'état gestuel vers Compose et
 * traduction des gestes globaux en actions d'accessibilité.
 */
class MainActivity : ComponentActivity(), HandGestureListener {

    private companion object {
        const val TAG = "StarkLauncher"

        /**
         * Résolution d'analyse. 640x480 est le meilleur compromis mesuré : au-dessus, la latence
         * d'inférence croît sans gain de précision (le détecteur de paume redimensionne l'entrée
         * en interne) ; en dessous, les extrémités des doigts deviennent trop bruitées.
         */
        const val ANALYSIS_WIDTH = 640
        const val ANALYSIS_HEIGHT = 480

        /** Intervalle minimal entre deux actions système déclenchées au geste. */
        const val GLOBAL_ACTION_COOLDOWN_MS = 900L
    }

    /**
     * État gestuel exposé comme [androidx.compose.runtime.MutableState] et non comme valeur :
     * l'interface le lit en phase de dessin, ce qui évite de recomposer l'arbre 30 fois par seconde.
     */
    private val gestureState = mutableStateOf(HandGestureState())
    private var gestureAnalyzer: HandGestureAnalyzer? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private var cameraProvider: ProcessCameraProvider? = null
    private var previewView: PreviewView? = null
    private var hasCameraPermission = false

    /** Front montant du geste « poing » : l'action ne doit partir qu'une fois par geste. */
    private var lastGesture = GestureType.IDLE
    private var lastGlobalActionAt = 0L

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (isGranted) bindCamera()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        gestureAnalyzer = HandGestureAnalyzer(applicationContext, this)

        hasCameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasCameraPermission) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setContent {
            StarkHUDTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    StarkHUDLauncher(
                        gestureState = gestureState,
                        onBindPreview = { view ->
                            // L'aperçu apparaît et disparaît avec la fenêtre PIP : on relie
                            // la surface au cas déjà lié plutôt que de reconstruire la caméra.
                            previewView = view
                            bindCamera()
                        },
                        onReleasePreview = {
                            previewView = null
                            bindCamera()
                        },
                        onSimulatePosition = { x, y, isPinch, isFist ->
                            gestureAnalyzer?.updateSimulatedPosition(x, y, isPinch, isFist)
                        },
                        onSimulationModeChanged = { enabled ->
                            gestureAnalyzer?.setSimulationMode(enabled)
                        }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (hasCameraPermission) bindCamera()
    }

    /**
     * (Re)lie les cas d'usage caméra. Idempotent : un appel supplémentaire reconstruit la
     * liaison proprement au lieu d'empiler des `unbindAll()` concurrents comme précédemment.
     */
    private fun bindCamera() {
        if (!hasCameraPermission) return

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
                    // Seule la frame la plus récente compte : accumuler du retard rendrait le
                    // curseur inutilisable.
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    // Sortie RGBA : évite une conversion YUV -> RGB logicielle par image.
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                gestureAnalyzer?.let { imageAnalysis.setAnalyzer(cameraExecutor, it) }

                provider.unbindAll()

                val view = previewView
                if (view != null) {
                    val preview = Preview.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .build()
                    preview.setSurfaceProvider(view.surfaceProvider)
                    provider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalysis
                    )
                } else {
                    provider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_FRONT_CAMERA, imageAnalysis
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Liaison caméra impossible", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onHandGestureUpdated(state: HandGestureState) {
        // Le callback arrive sur le thread d'analyse : on repasse sur le thread principal, seul
        // habilité à écrire un MutableState lu par Compose.
        lifecycleScope.launch(Dispatchers.Main.immediate) {
            gestureState.value = state
            handleGlobalGesture(state.gesture)
        }
    }

    /**
     * Le geste « poing » déclenche un retour système. Il est traité en front montant et avec un
     * temps de garde : maintenu, il produisait auparavant une action par image, soit une trentaine
     * de retours par seconde.
     */
    private fun handleGlobalGesture(gesture: GestureType) {
        val previous = lastGesture
        lastGesture = gesture

        if (gesture != GestureType.FIST || previous == GestureType.FIST) return

        val now = SystemClock.uptimeMillis()
        if (now - lastGlobalActionAt < GLOBAL_ACTION_COOLDOWN_MS) return
        lastGlobalActionAt = now

        // On s'adresse directement à l'instance connectée du service ; si l'utilisateur ne l'a
        // pas activé dans les réglages d'accessibilité, l'action est simplement sans effet.
        if (!StarkAccessibilityService.performGlobalAction(StarkAccessibilityService.ACTION_GLOBAL_BACK)) {
            Log.i(TAG, "Service d'accessibilité inactif : action « Retour » ignorée")
        }
    }

    override fun onStop() {
        super.onStop()
        // La caméra est libérée dès que le launcher passe en arrière-plan : aucune raison de
        // continuer à consommer batterie et capteur pendant qu'une autre application est au premier plan.
        cameraProvider?.unbindAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraProvider?.unbindAll()
        cameraProvider = null
        gestureAnalyzer?.close()
        gestureAnalyzer = null
        cameraExecutor.shutdown()
    }
}
