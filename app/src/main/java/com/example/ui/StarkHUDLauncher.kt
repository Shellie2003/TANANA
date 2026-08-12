package com.example.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import com.example.gesture.GestureType
import com.example.gesture.HandGestureState
import com.example.ui.theme.StarkBlue
import com.example.ui.theme.StarkCyan
import com.example.ui.theme.StarkDarkBg
import com.example.ui.theme.StarkGold
import com.example.ui.theme.StarkRed
import com.example.ui.theme.StarkSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class LauncherAppItem(
    val label: String,
    val packageName: String,
    val iconBitmap: Bitmap? = null,
    val fallbackIcon: ImageVector = Icons.Default.Android
)

/**
 * Interface Launcher Futuriste Stark HUD - Jetpack Compose
 * Inclut : Réacteur Arc Central, Anneau d'applications 3D, Curseur Holographique lissé,
 * Métriques Système en temps réel et Commandes Gestuelles.
 */
@Composable
fun StarkHUDLauncher(
    gestureState: HandGestureState,
    onToggleCameraPreview: () -> Unit,
    onSimulatePosition: (x: Float, y: Float, isPinch: Boolean, isFist: Boolean) -> Unit,
    onBindPreview: ((PreviewView) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    // Applications installées
    val installedApps = remember { mutableStateListOf<LauncherAppItem>() }
    var hoveredAppIndex by remember { mutableIntStateOf(-1) }

    // Angle de rotation de l'anneau d'applications 3D
    var orbitRotationAngle by remember { mutableFloatStateOf(0f) }

    // Position du curseur en pixels écran
    var screenWidthPx by remember { mutableFloatStateOf(1080f) }
    var screenHeightPx by remember { mutableFloatStateOf(2160f) }

    // Heure système
    var currentTimeString by remember { mutableStateOf("") }
    var currentDateString by remember { mutableStateOf("") }

    // Métriques Batterie et CPU
    var batteryLevel by remember { mutableIntStateOf(100) }
    var isBatteryCharging by remember { mutableStateOf(false) }

    // Mode simulation
    var isSimModeActive by remember { mutableStateOf(false) }
    var showCameraPIP by remember { mutableStateOf(true) }

    // Animations réacteur Arc
    val infiniteTransition = rememberInfiniteTransition(label = "ArcReactorTransition")
    val reactorRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "reactorRotation"
    )

    val corePulse by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "corePulse"
    )

    // Déclenchement haptique lors des sélections
    fun triggerVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                @Suppress("DEPRECATION")
                vibrator.vibrate(40)
            }
        } catch (_: Exception) {}
    }

    // Charger la liste des applications installées
    LaunchedEffect(Unit) {
        launch {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            val appList = mutableListOf<LauncherAppItem>()

            for (info in resolveInfos) {
                val pkgName = info.activityInfo.packageName
                if (pkgName == context.packageName) continue // ignorer le launcher lui-même dans la boucle

                val appName = info.loadLabel(pm).toString()
                val iconDrawable = try { info.loadIcon(pm) } catch (e: Exception) { null }
                val bitmap = iconDrawable?.toBitmap(96, 96)

                appList.add(LauncherAppItem(appName, pkgName, bitmap))
            }

            // Ajouter des raccourcis système de secours si la liste est restreinte
            if (appList.isEmpty()) {
                appList.addAll(
                    listOf(
                        LauncherAppItem("JARVIS Core", "com.android.settings", null, Icons.Default.Security),
                        LauncherAppItem("Arc Diagnostics", "com.android.camera2", null, Icons.Default.Camera),
                        LauncherAppItem("Stark Tech", "com.android.settings", null, Icons.Default.Memory),
                        LauncherAppItem("System Config", "com.android.settings", null, Icons.Default.Settings)
                    )
                )
            }

            installedApps.clear()
            installedApps.addAll(appList.take(12)) // Conserver max 12 apps dans l'anneau principal 3D
        }

        // Horloge et Batterie
        launch {
            val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val dateFormatter = SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault())

            while (true) {
                val now = Date()
                currentTimeString = timeFormatter.format(now)
                currentDateString = dateFormatter.format(now)

                val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                if (bm != null) {
                    batteryLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    isBatteryCharging = bm.isCharging
                }

                delay(1000)
            }
        }
    }

    // Réaction au Swipe Aérien
    LaunchedEffect(gestureState.gesture) {
        if (gestureState.gesture == GestureType.SWIPE_RIGHT) {
            orbitRotationAngle += 30f
            triggerVibration()
        } else if (gestureState.gesture == GestureType.SWIPE_LEFT) {
            orbitRotationAngle -= 30f
            triggerVibration()
        }
    }

    // Curseur calculé
    val targetCursorX = gestureState.cursorX * screenWidthPx
    val targetCursorY = gestureState.cursorY * screenHeightPx

    // Détection de survol et Clic par Pinch
    LaunchedEffect(targetCursorX, targetCursorY, gestureState.gesture) {
        if (installedApps.isEmpty()) return@LaunchedEffect

        val centerX = screenWidthPx / 2f
        val centerY = screenHeightPx / 2f
        val orbitRadiusX = screenWidthPx * 0.38f
        val orbitRadiusY = screenHeightPx * 0.22f

        var closestIndex = -1
        var minDistance = 120.dp.value * density.density

        installedApps.forEachIndexed { index, app ->
            val angle = Math.toRadians((orbitRotationAngle + index * (360f / installedApps.size)).toDouble())
            val appX = centerX + orbitRadiusX * cos(angle).toFloat()
            val appY = centerY + orbitRadiusY * sin(angle).toFloat()

            val dist = Math.hypot((targetCursorX - appX).toDouble(), (targetCursorY - appY).toDouble()).toFloat()
            if (dist < minDistance) {
                minDistance = dist
                closestIndex = index
            }
        }

        hoveredAppIndex = closestIndex

        // Si geste Pinch détecté sur une app survolée -> Lancer l'application !
        if (gestureState.gesture == GestureType.PINCH && hoveredAppIndex in installedApps.indices) {
            val selectedApp = installedApps[hoveredAppIndex]
            triggerVibration()
            try {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(selectedApp.packageName)
                if (launchIntent != null) {
                    context.startActivity(launchIntent)
                }
            } catch (_: Exception) {}
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(StarkDarkBg)
            .testTag("stark_hud_container")
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    if (isSimModeActive) {
                        change.consume()
                        val newX = (change.position.x / size.width).coerceIn(0f, 1f)
                        val newY = (change.position.y / size.height).coerceIn(0f, 1f)
                        onSimulatePosition(newX, newY, false, false)
                    } else {
                        orbitRotationAngle += dragAmount.x * 0.2f
                    }
                }
            }
    ) {
        // Enregistrer la taille de l'écran en Canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            screenWidthPx = size.width
            screenHeightPx = size.height

            // 1. Grille cybernétique et cercles de fond Stark
            val gridSpacing = 80.dp.toPx()
            val gridPath = Path()

            var x = 0f
            while (x < size.width) {
                gridPath.moveTo(x, 0f)
                gridPath.lineTo(x, size.height)
                x += gridSpacing
            }
            var y = 0f
            while (y < size.height) {
                gridPath.moveTo(0f, y)
                gridPath.lineTo(size.width, y)
                y += gridSpacing
            }

            drawPath(
                path = gridPath,
                color = StarkCyan.copy(alpha = 0.05f),
                style = Stroke(width = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
            )

            // Cercles concentriques d'arrière-plan
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(color = StarkCyan.copy(alpha = 0.08f), radius = size.width * 0.42f, center = center, style = Stroke(width = 1.5f))
            drawCircle(color = StarkCyan.copy(alpha = 0.12f), radius = size.width * 0.25f, center = center, style = Stroke(width = 1f))
        }

        // 2. Réacteur Arc Central Animé (Arc Reactor HUD)
        ArcReactorCenter(
            modifier = Modifier.align(Alignment.Center),
            rotationAngle = reactorRotation,
            pulseScale = corePulse,
            isPinching = gestureState.gesture == GestureType.PINCH,
            isFist = gestureState.gesture == GestureType.FIST
        )

        // 3. Anneau d'Applications 3D Orbit
        val centerX = screenWidthPx / 2f
        val centerY = screenHeightPx / 2f
        val orbitRadiusX = screenWidthPx * 0.38f
        val orbitRadiusY = screenHeightPx * 0.22f

        installedApps.forEachIndexed { index, app ->
            val angleDeg = orbitRotationAngle + index * (360f / installedApps.size)
            val angleRad = Math.toRadians(angleDeg.toDouble())

            val appX = centerX + orbitRadiusX * cos(angleRad).toFloat()
            val appY = centerY + orbitRadiusY * sin(angleRad).toFloat()

            val zDepthScale = 0.7f + 0.35f * sin(angleRad).toFloat()
            val appAlpha = 0.4f + 0.6f * sin(angleRad).toFloat()

            val isHovered = (index == hoveredAppIndex)

            Box(
                modifier = Modifier
                    .offset { IntOffset((appX - 40.dp.toPx()).toInt(), (appY - 40.dp.toPx()).toInt()) }
                    .graphicsLayer {
                        scaleX = if (isHovered) zDepthScale * 1.3f else zDepthScale
                        scaleY = if (isHovered) zDepthScale * 1.3f else zDepthScale
                        alpha = appAlpha
                    }
                    .clickable {
                        try {
                            val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
                            if (launchIntent != null) context.startActivity(launchIntent)
                        } catch (_: Exception) {}
                    }
            ) {
                AppOrbitalNode(
                    app = app,
                    isHovered = isHovered
                )
            }
        }

        // 4. En-tête HUD Supérieur (Système Stark / JARVIS & Stats)
        StarkHeaderHUD(
            timeString = currentTimeString,
            dateString = currentDateString,
            batteryLevel = batteryLevel,
            isCharging = isBatteryCharging,
            fps = gestureState.fps,
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // 5. Barre d'état Gestuelle Inférieure (Gestures HUD)
        StarkFooterGestureHUD(
            gestureState = gestureState,
            isSimMode = isSimModeActive,
            onToggleSimMode = {
                isSimModeActive = !isSimModeActive
            },
            onSimulateGesture = { gesture ->
                when (gesture) {
                    GestureType.PINCH -> onSimulatePosition(gestureState.cursorX, gestureState.cursorY, true, false)
                    GestureType.FIST -> onSimulatePosition(gestureState.cursorX, gestureState.cursorY, false, true)
                    GestureType.SWIPE_RIGHT -> orbitRotationAngle += 35f
                    GestureType.SWIPE_LEFT -> orbitRotationAngle -= 35f
                    else -> onSimulatePosition(gestureState.cursorX, gestureState.cursorY, false, false)
                }
            },
            onToggleCameraPIP = { showCameraPIP = !showCameraPIP },
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // 5.5. Caméra PIP de Détection et Suivi de Main (Hand Tracking Frame)
        if (showCameraPIP) {
            HandTrackingCameraPIP(
                gestureState = gestureState,
                onBindPreview = onBindPreview,
                onClosePIP = { showCameraPIP = false },
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }

        // 6. Curseur Cible Holographique
        HolographicCursorOverlay(
            cursorX = targetCursorX,
            cursorY = targetCursorY,
            gestureState = gestureState,
            isHovering = hoveredAppIndex != -1
        )
    }
}

/**
 * Réacteur Arc Central Dessiné sur Canvas Compose avec Effets Néon & Stark Core
 */
@Composable
fun ArcReactorCenter(
    rotationAngle: Float,
    pulseScale: Float,
    isPinching: Boolean,
    isFist: Boolean,
    modifier: Modifier = Modifier
) {
    val coreColor = when {
        isPinching -> StarkGold
        isFist -> StarkRed
        else -> StarkCyan
    }

    Box(
        modifier = modifier
            .size(240.dp)
            .graphicsLayer {
                scaleX = pulseScale
                scaleY = pulseScale
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxRadius = size.width / 2f

            // Anneau Extérieur
            drawCircle(
                color = coreColor.copy(alpha = 0.3f),
                radius = maxRadius * 0.9f,
                style = Stroke(width = 3.dp.toPx())
            )

            // Segments tournants
            rotate(rotationAngle, center) {
                val segmentCount = 10
                val sweepAngle = 20f
                for (i in 0 until segmentCount) {
                    val startAngle = i * (360f / segmentCount)
                    drawArc(
                        color = coreColor,
                        startAngle = startAngle,
                        sweepAngle = sweepAngle,
                        useCenter = false,
                        topLeft = Offset(center.x - maxRadius * 0.85f, center.y - maxRadius * 0.85f),
                        size = Size(maxRadius * 1.7f, maxRadius * 1.7f),
                        style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }

            // Anneau Interne
            rotate(-rotationAngle * 1.5f, center) {
                val segmentCount = 6
                for (i in 0 until segmentCount) {
                    val startAngle = i * (360f / segmentCount)
                    drawArc(
                        color = StarkBlue.copy(alpha = 0.8f),
                        startAngle = startAngle,
                        sweepAngle = 35f,
                        useCenter = false,
                        topLeft = Offset(center.x - maxRadius * 0.6f, center.y - maxRadius * 0.6f),
                        size = Size(maxRadius * 1.2f, maxRadius * 1.2f),
                        style = Stroke(width = 4.dp.toPx())
                    )
                }
            }

            // Cœur Lumineux
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(coreColor, coreColor.copy(alpha = 0.4f), Color.Transparent),
                    center = center,
                    radius = maxRadius * 0.45f
                ),
                radius = maxRadius * 0.45f
            )

            drawCircle(
                color = Color.White,
                radius = maxRadius * 0.18f
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "ARC REACTOR",
                color = StarkCyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = if (isPinching) "ENGAGED" else if (isFist) "OVERRIDE" else "100%",
                color = coreColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/**
 * Composant Noeud d'Application Orbitale
 */
@Composable
fun AppOrbitalNode(
    app: LauncherAppItem,
    isHovered: Boolean
) {
    val borderColor = if (isHovered) StarkGold else StarkCyan
    val glowBg = if (isHovered) StarkGold.copy(alpha = 0.25f) else StarkCyan.copy(alpha = 0.1f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(80.dp)
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(glowBg)
                .border(2.dp, borderColor, CircleShape)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            if (app.iconBitmap != null) {
                Image(
                    bitmap = app.iconBitmap.asImageBitmap(),
                    contentDescription = app.label,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = app.fallbackIcon,
                    contentDescription = app.label,
                    tint = borderColor,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = app.label,
            color = if (isHovered) StarkGold else Color.White,
            fontSize = 10.sp,
            fontWeight = if (isHovered) FontWeight.Bold else FontWeight.Normal,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * En-tête HUD Supérieur (Système Stark / JARVIS & Metrics)
 */
@Composable
fun StarkHeaderHUD(
    timeString: String,
    dateString: String,
    batteryLevel: Int,
    isCharging: Boolean,
    fps: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 36.dp, start = 16.dp, end = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Horloge & System Info Left
        Column {
            Text(
                text = "JARVIS OS // v4.2",
                color = StarkCyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = timeString,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = dateString.uppercase(),
                color = StarkCyan.copy(alpha = 0.7f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        // Stats Right (Battery & FPS)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = StarkSurface),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.border(1.dp, StarkCyan.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.BatteryFull,
                        contentDescription = "Battery",
                        tint = if (batteryLevel > 20) StarkCyan else StarkRed,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "$batteryLevel%" + if (isCharging) " ⚡" else "",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = StarkSurface),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.border(1.dp, StarkCyan.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = "FPS",
                        tint = StarkGold,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "$fps FPS",
                        color = StarkGold,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

/**
 * Barre de contrôle gestuel et statut HUD inférieur
 */
@Composable
fun StarkFooterGestureHUD(
    gestureState: HandGestureState,
    isSimMode: Boolean,
    onToggleSimMode: () -> Unit,
    onSimulateGesture: (GestureType) -> Unit,
    onToggleCameraPIP: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp, start = 12.dp, end = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Badge du Geste courant
        val gestureLabel = when (gestureState.gesture) {
            GestureType.PINCH -> "PINCH -> [CLIC / SELECTION]"
            GestureType.FIST -> "FIST -> [RETOUR / ACCUEIL]"
            GestureType.SWIPE_RIGHT -> "SWIPE DROITE -> [DEFILEMENT ORBITE]"
            GestureType.SWIPE_LEFT -> "SWIPE GAUCHE -> [DEFILEMENT ORBITE]"
            GestureType.OPEN_PALM -> "MAIN OUVERTE -> [CURSEUR HOLOGRAPHIQUE]"
            GestureType.IDLE -> "EN ATTENTE DE MAIN..."
        }

        val badgeColor = when (gestureState.gesture) {
            GestureType.PINCH -> StarkGold
            GestureType.FIST -> StarkRed
            GestureType.SWIPE_RIGHT, GestureType.SWIPE_LEFT -> StarkBlue
            else -> StarkCyan
        }

        Box(
            modifier = Modifier
                .background(badgeColor.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
                .border(1.dp, badgeColor, RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = gestureLabel,
                color = badgeColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Actions de Simulation et Contrôle Rapide
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "MODE TOUCH / SIM:",
                    color = StarkCyan,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(6.dp))
                Switch(
                    checked = isSimMode,
                    onCheckedChange = { onToggleSimMode() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = StarkGold,
                        checkedTrackColor = StarkGold.copy(alpha = 0.3f),
                        uncheckedThumbColor = StarkCyan,
                        uncheckedTrackColor = StarkCyan.copy(alpha = 0.2f)
                    ),
                    modifier = Modifier.scale(0.8f)
                )
            }

            if (isSimMode) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = { onSimulateGesture(GestureType.PINCH) },
                        colors = ButtonDefaults.buttonColors(containerColor = StarkGold),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("PINCH", fontSize = 10.sp, color = Color.Black)
                    }
                    Button(
                        onClick = { onSimulateGesture(GestureType.SWIPE_RIGHT) },
                        colors = ButtonDefaults.buttonColors(containerColor = StarkCyan),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("ROTATE", fontSize = 10.sp, color = Color.Black)
                    }
                }
            } else {
                IconButton(onClick = onToggleCameraPIP) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = "Camera PIP",
                        tint = StarkCyan
                    )
                }
            }
        }
    }
}

/**
 * Curseur Cible Holographique
 */
@Composable
fun HolographicCursorOverlay(
    cursorX: Float,
    cursorY: Float,
    gestureState: HandGestureState,
    isHovering: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "CursorTransition")
    val cursorRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "cursorRotation"
    )

    val cursorColor = when (gestureState.gesture) {
        GestureType.PINCH -> StarkGold
        GestureType.FIST -> StarkRed
        else -> if (isHovering) StarkGold else StarkCyan
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(cursorX, cursorY)
        val radius = if (gestureState.gesture == GestureType.PINCH) 24.dp.toPx() else 36.dp.toPx()

        // Cercle pointillé externe
        rotate(cursorRotation, center) {
            drawCircle(
                color = cursorColor,
                radius = radius,
                center = center,
                style = Stroke(
                    width = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
                )
            )
        }

        // Réticule interne
        val lineLength = 12.dp.toPx()
        drawLine(cursorColor, Offset(center.x - lineLength - radius * 0.4f, center.y), Offset(center.x - radius * 0.4f, center.y), strokeWidth = 2.dp.toPx())
        drawLine(cursorColor, Offset(center.x + radius * 0.4f, center.y), Offset(center.x + lineLength + radius * 0.4f, center.y), strokeWidth = 2.dp.toPx())
        drawLine(cursorColor, Offset(center.x, center.y - lineLength - radius * 0.4f), Offset(center.x, center.y - radius * 0.4f), strokeWidth = 2.dp.toPx())
        drawLine(cursorColor, Offset(center.x, center.y + radius * 0.4f), Offset(center.x, center.y + lineLength + radius * 0.4f), strokeWidth = 2.dp.toPx())

        // Point central
        drawCircle(
            color = cursorColor,
            radius = 4.dp.toPx(),
            center = center
        )

        // Indication des coordonnées Z
        if (gestureState.isHandDetected) {
            drawCircle(
                color = cursorColor.copy(alpha = 0.2f),
                radius = radius * (1.0f + gestureState.cursorZ * 0.5f),
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )
        }
    }
}

/**
 * Fenêtre PIP Caméra Futuriste Stark Tech
 * Affiche le flux vidéo en direct de la caméra frontale et dessine le squelette
 * complet de la main (21 points de repère, os, boîte englobante et statut de détection).
 */
@Composable
fun HandTrackingCameraPIP(
    gestureState: HandGestureState,
    onBindPreview: ((PreviewView) -> Unit)?,
    onClosePIP: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }

    val pipWidth = if (isExpanded) 220.dp else 160.dp
    val pipHeight = if (isExpanded) 280.dp else 200.dp

    Card(
        modifier = modifier
            .padding(top = 70.dp, end = 16.dp)
            .size(width = pipWidth, height = pipHeight)
            .border(
                border = BorderStroke(
                    1.5.dp,
                    if (gestureState.isHandDetected) StarkCyan else StarkGold.copy(alpha = 0.6f)
                ),
                shape = RoundedCornerShape(12.dp)
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = StarkDarkBg.copy(alpha = 0.88f))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 1. Aperçu de la Caméra en Direct via CameraX
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        onBindPreview?.invoke(this)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // 2. Calque Gradient Cyber
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                StarkDarkBg.copy(alpha = 0.25f),
                                Color.Transparent,
                                StarkDarkBg.copy(alpha = 0.65f)
                            )
                        )
                    )
            )

            // 3. Squelette de la Main & Bounding Box dessinés sur Canvas
            Canvas(modifier = Modifier.fillMaxSize()) {
                val canvasW = size.width
                val canvasH = size.height

                // Bords d'angle HUD Futuristes
                val cornerLen = 14f
                val strokeW = 2.5f
                val frameColor = if (gestureState.isHandDetected) StarkCyan else StarkGold

                drawLine(frameColor, Offset(8f, 8f), Offset(8f + cornerLen, 8f), strokeWidth = strokeW)
                drawLine(frameColor, Offset(8f, 8f), Offset(8f, 8f + cornerLen), strokeWidth = strokeW)

                drawLine(frameColor, Offset(canvasW - 8f, 8f), Offset(canvasW - 8f - cornerLen, 8f), strokeWidth = strokeW)
                drawLine(frameColor, Offset(canvasW - 8f, 8f), Offset(canvasW - 8f, 8f + cornerLen), strokeWidth = strokeW)

                drawLine(frameColor, Offset(8f, canvasH - 8f), Offset(8f + cornerLen, canvasH - 8f), strokeWidth = strokeW)
                drawLine(frameColor, Offset(8f, canvasH - 8f), Offset(8f, canvasH - 8f - cornerLen), strokeWidth = strokeW)

                drawLine(frameColor, Offset(canvasW - 8f, canvasH - 8f), Offset(canvasW - 8f - cornerLen, canvasH - 8f), strokeWidth = strokeW)
                drawLine(frameColor, Offset(canvasW - 8f, canvasH - 8f), Offset(canvasW - 8f, canvasH - 8f - cornerLen), strokeWidth = strokeW)

                // Dessin du Squelette si la main est détectée
                val landmarks = gestureState.landmarks
                if (landmarks.isNotEmpty() && gestureState.isHandDetected) {
                    val pts = landmarks.map { pt ->
                        Offset(pt.x * canvasW, pt.y * canvasH)
                    }

                    fun drawBone(i1: Int, i2: Int, color: Color = StarkCyan) {
                        if (i1 < pts.size && i2 < pts.size) {
                            drawLine(
                                color = color,
                                start = pts[i1],
                                end = pts[i2],
                                strokeWidth = 2.5f
                            )
                        }
                    }

                    if (pts.size >= 21) {
                        // Connexions de la paume / poignet
                        drawBone(0, 1)
                        drawBone(0, 5)
                        drawBone(0, 9)
                        drawBone(0, 13)
                        drawBone(0, 17)

                        // Pouce: 1 -> 2 -> 3 -> 4
                        drawBone(1, 2)
                        drawBone(2, 3)
                        drawBone(3, 4, color = StarkGold)

                        // Index: 5 -> 6 -> 7 -> 8
                        drawBone(5, 6)
                        drawBone(6, 7)
                        drawBone(7, 8, color = StarkCyan)

                        // Majeur: 9 -> 10 -> 11 -> 12
                        drawBone(9, 10)
                        drawBone(10, 11)
                        drawBone(11, 12)

                        // Annulaire: 13 -> 14 -> 15 -> 16
                        drawBone(13, 14)
                        drawBone(14, 15)
                        drawBone(15, 16)

                        // Auriculaire: 17 -> 18 -> 19 -> 20
                        drawBone(17, 18)
                        drawBone(18, 19)
                        drawBone(19, 20)

                        // Boîte englobante de la main (Bounding Box)
                        var minX = canvasW
                        var maxX = 0f
                        var minY = canvasH
                        var maxY = 0f

                        pts.forEach { pt ->
                            if (pt.x < minX) minX = pt.x
                            if (pt.x > maxX) maxX = pt.x
                            if (pt.y < minY) minY = pt.y
                            if (pt.y > maxY) maxY = pt.y
                        }

                        minX = (minX - 10f).coerceAtLeast(4f)
                        maxX = (maxX + 10f).coerceAtMost(canvasW - 4f)
                        minY = (minY - 10f).coerceAtLeast(4f)
                        maxY = (maxY + 10f).coerceAtMost(canvasH - 4f)

                        drawRect(
                            color = StarkCyan.copy(alpha = 0.5f),
                            topLeft = Offset(minX, minY),
                            size = Size(maxX - minX, maxY - minY),
                            style = Stroke(width = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
                        )
                    }

                    // Dessin des articulations (Landmarks)
                    pts.forEachIndexed { index, pt ->
                        val jointColor = when (index) {
                            4 -> StarkGold
                            8 -> if (gestureState.gesture == GestureType.PINCH) StarkGold else StarkCyan
                            0 -> StarkRed
                            else -> StarkCyan.copy(alpha = 0.85f)
                        }
                        val radius = if (index == 8 || index == 4) 5.5f else 3.5f
                        drawCircle(jointColor, radius = radius, center = pt)
                    }
                } else if (!gestureState.isHandDetected) {
                    // Ligne de balayage radar quand la main est en cours de recherche
                    val scanY = (System.currentTimeMillis() % 2000) / 2000f * canvasH
                    drawLine(
                        color = StarkGold.copy(alpha = 0.6f),
                        start = Offset(0f, scanY),
                        end = Offset(canvasW, scanY),
                        strokeWidth = 1.5f
                    )
                }
            }

            // 4. En-tête HUD avec statut
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(StarkDarkBg.copy(alpha = 0.85f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(
                                color = if (gestureState.isHandDetected) StarkCyan else StarkGold,
                                shape = CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (gestureState.isHandDetected) "HAND DETECTED" else "SEARCHING...",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (gestureState.isHandDetected) StarkCyan else StarkGold
                        )
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier.size(18.dp)
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            contentDescription = "Resize PIP",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(12.dp)
                        )
                    }
                    IconButton(
                        onClick = onClosePIP,
                        modifier = Modifier.size(18.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close PIP",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }

            // 5. Badge inférieur affichant le geste actif
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp)
                    .background(
                        color = StarkDarkBg.copy(alpha = 0.9f),
                        shape = RoundedCornerShape(4.dp)
                    )
                    .border(0.5.dp, StarkCyan.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "GESTURE: ${gestureState.gesture.name}",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 7.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (gestureState.gesture) {
                            GestureType.PINCH -> StarkGold
                            GestureType.FIST -> StarkRed
                            GestureType.OPEN_PALM -> StarkCyan
                            else -> Color.White.copy(alpha = 0.7f)
                        }
                    )
                )
            }
        }
    }
}
