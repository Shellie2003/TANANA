package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.example.gesture.GestureType
import com.example.service.StarkAccessibilityService
import com.example.service.StarkVisionService
import com.example.ui.EngineStatus
import com.example.ui.LauncherAppItem
import com.example.ui.SetupStep
import com.example.ui.StarkControlCenter
import com.example.ui.theme.StarkHUDTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Écran principal : centre de contrôle et lanceur d'applications.
 *
 * L'activité ne pilote plus la caméra elle-même. Toute la vision vit désormais dans
 * [StarkVisionService], pour deux raisons : le contrôle gestuel doit survivre à la sortie de
 * l'application, et deux clients caméra simultanés seraient de toute façon refusés par le système.
 * L'activité se contente donc de configurer, d'allumer, et d'observer.
 */
class MainActivity : ComponentActivity() {

    private var cameraGranted by mutableStateOf(false)
    private var overlayGranted by mutableStateOf(false)
    private var accessibilityGranted by mutableStateOf(false)
    private var notificationsGranted by mutableStateOf(true)

    private var status by mutableStateOf(
        EngineStatus(false, GestureType.IDLE, 0f, 0, 0, null)
    )
    private var apps by mutableStateOf<List<LauncherAppItem>>(emptyList())

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> cameraGranted = granted }

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notificationsGranted = granted }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshPermissions()

        setContent {
            StarkHUDTheme {
                // Rafraîchissement de la télémétrie. 5 Hz suffit à l'affichage : le service, lui,
                // traite les gestes à la cadence de la caméra.
                LaunchedEffect(Unit) {
                    while (true) {
                        val state = StarkVisionService.latestState
                        status = EngineStatus(
                            isRunning = StarkVisionService.isRunning,
                            gesture = state?.gesture ?: GestureType.IDLE,
                            confidence = state?.confidence ?: 0f,
                            fps = state?.fps ?: 0,
                            latencyMs = state?.latencyMs ?: 0,
                            visionError = state?.visionError
                        )
                        delay(200)
                    }
                }

                LaunchedEffect(Unit) {
                    apps = withContext(Dispatchers.IO) { loadLauncherApps() }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    StarkControlCenter(
                        status = status,
                        setupSteps = buildSetupSteps(),
                        apps = apps,
                        onToggleEngine = ::toggleEngine,
                        onLaunchApp = ::launchApp
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Les autorisations de superposition et d'accessibilité se règlent dans les paramètres
        // système : leur état ne peut être constaté qu'au retour dans l'application.
        refreshPermissions()
    }

    private fun refreshPermissions() {
        cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        overlayGranted = Settings.canDrawOverlays(this)
        accessibilityGranted = isAccessibilityServiceEnabled()
        notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Détermine si le service d'accessibilité est activé.
     *
     * `StarkAccessibilityService.isConnected` ne suffirait pas : le service n'est instancié
     * qu'une fois activé, or l'activité doit connaître l'état *avant* cela pour afficher l'étape
     * de configuration. On interroge donc le réglage système.
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "$packageName/${StarkAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    private fun buildSetupSteps(): List<SetupStep> = listOf(
        SetupStep(
            title = "Accès à la caméra",
            explanation = "La caméra frontale suit votre main. Les images ne quittent jamais " +
                "l'appareil : l'analyse est entièrement locale.",
            isDone = cameraGranted,
            actionLabel = "Autoriser la caméra",
            onAction = { requestCamera.launch(Manifest.permission.CAMERA) }
        ),
        SetupStep(
            title = "Affichage par-dessus les applications",
            explanation = "Nécessaire pour dessiner le curseur au-dessus des autres applications. " +
                "Sans cette autorisation, vous piloteriez sans voir où vous pointez.",
            isDone = overlayGranted,
            actionLabel = "Ouvrir le réglage",
            onAction = {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        ),
        SetupStep(
            title = "Service d'accessibilité",
            explanation = "Seul ce service permet à une application d'agir en dehors d'elle-même : " +
                "appuyer, faire défiler, revenir en arrière. Sans lui, les gestes resteront " +
                "sans effet hors de cette application.",
            isDone = accessibilityGranted,
            actionLabel = "Activer dans les réglages",
            onAction = {
                startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        ),
        SetupStep(
            title = "Notifications",
            explanation = "Android impose une notification permanente tant que la caméra est " +
                "utilisée en arrière-plan. Elle sert aussi à arrêter le contrôle gestuel.",
            isDone = notificationsGranted,
            actionLabel = "Autoriser les notifications",
            onAction = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        )
    )

    private fun toggleEngine(enabled: Boolean) {
        if (enabled) StarkVisionService.start(this) else StarkVisionService.stop(this)
    }

    private fun launchApp(target: String) {
        runCatching {
            packageManager.getLaunchIntentForPackage(target)?.let { intent ->
                // Chaque application obtient sa propre tâche et apparaît donc dans les
                // applications récentes ; RESET_TASK_IF_NEEDED ramène une tâche existante au
                // premier plan au lieu de la redémarrer.
                intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
                startActivity(intent)
            }
        }
    }

    /** Énumère les applications lançables. Opération d'E/S : à exécuter hors thread principal. */
    private fun loadLauncherApps(): List<LauncherAppItem> {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        return pm.queryIntentActivities(mainIntent, 0)
            .asSequence()
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
            .map { info ->
                LauncherAppItem(
                    label = info.loadLabel(pm).toString(),
                    packageName = info.activityInfo.packageName,
                    iconBitmap = runCatching { info.loadIcon(pm).toBitmap(96, 96) }.getOrNull()
                )
            }
            .toList()
    }
}
