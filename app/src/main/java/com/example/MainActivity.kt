package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.gesture.GestureType
import com.example.gesture.HandGestureAnalyzer
import com.example.gesture.HandGestureListener
import com.example.gesture.HandGestureState
import com.example.service.StarkAccessibilityService
import com.example.ui.StarkHUDLauncher
import com.example.ui.theme.StarkHUDTheme
import java.util.concurrent.Executors

/**
 * Activity principale du Stark HUD Launcher.
 * Initialise CameraX en arrière-plan, gère le binding de l'analyseur de gestes
 * et rend l'interface Jetpack Compose futuriste Stark Tech avec aperçu vidéo PIP.
 */
class MainActivity : ComponentActivity(), HandGestureListener {

    private var gestureState by mutableStateOf(HandGestureState())
    private var gestureAnalyzer: HandGestureAnalyzer? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var activePreviewView: PreviewView? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCameraAnalysis()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        gestureAnalyzer = HandGestureAnalyzer(this, this)

        checkAndRequestPermissions()

        setContent {
            StarkHUDTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    StarkHUDLauncher(
                        gestureState = gestureState,
                        onBindPreview = { previewView ->
                            activePreviewView = previewView
                            startCameraAnalysis()
                        },
                        onToggleCameraPreview = {
                            // Basculer la caméra si nécessaire
                        },
                        onSimulatePosition = { x, y, isPinch, isFist ->
                            gestureAnalyzer?.setSimulationMode(true)
                            gestureAnalyzer?.updateSimulatedPosition(x, y, isPinch, isFist)
                        }
                    )
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCameraAnalysis()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCameraAnalysis() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                gestureAnalyzer?.let { analyzer ->
                    imageAnalysis.setAnalyzer(cameraExecutor, analyzer)
                }

                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                cameraProvider.unbindAll()

                val previewView = activePreviewView
                if (previewView != null) {
                    val previewUseCase = Preview.Builder()
                        .setTargetResolution(Size(640, 480))
                        .build()
                    previewUseCase.setSurfaceProvider(previewView.surfaceProvider)

                    cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        previewUseCase,
                        imageAnalysis
                    )
                } else {
                    cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        imageAnalysis
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onHandGestureUpdated(state: HandGestureState) {
        runOnUiThread {
            this.gestureState = state

            // Action de Retour Globale sur geste Poing (Fist)
            if (state.gesture == GestureType.FIST) {
                val intent = Intent(this, StarkAccessibilityService::class.java).apply {
                    action = StarkAccessibilityService.ACTION_GLOBAL_BACK
                }
                try { startService(intent) } catch (_: Exception) {}
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        gestureAnalyzer?.close()
        cameraExecutor.shutdown()
    }
}
