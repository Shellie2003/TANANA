package com.example.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
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
import com.example.gesture.HandLandmarks
import com.example.service.StarkAccessibilityService
import com.example.ui.theme.StarkBlue
import com.example.ui.theme.StarkCyan
import com.example.ui.theme.StarkDarkBg
import com.example.ui.theme.StarkGold
import com.example.ui.theme.StarkRed
import com.example.ui.theme.StarkSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class LauncherAppItem(
    val label: String,
    val packageName: String,
    val iconBitmap: Bitmap? = null,
    val fallbackIcon: ImageVector = Icons.Default.Android
)

/** Nombre maximal d'applications sur l'anneau principal. */
private const val MAX_ORBIT_APPS = 12

/** Rayon de capture du curseur autour d'une icône, en pixels indépendants de la densité. */
private const val HOVER_RADIUS_DP = 78f

/**
 * Interface du launcher Stark HUD.
 *
 * L'état gestuel est reçu sous forme de [State] et non de valeur : à 30 images par seconde,
 * passer la valeur ferait recomposer l'intégralité de l'arbre à chaque frame. Ici, chaque
 * consommateur lit l'état à la phase la plus tardive possible — dessin pour le curseur, mise
 * en page pour l'anneau, `derivedStateOf` pour le survol — si bien qu'un déplacement de curseur
 * ne déclenche plus aucune recomposition.
 */
@Composable
fun StarkHUDLauncher(
    gestureState: State<HandGestureState>,
    onSimulatePosition: (x: Float, y: Float, isPinch: Boolean, isFist: Boolean) -> Unit,
    onSimulationModeChanged: (Boolean) -> Unit,
    onBindPreview: ((PreviewView) -> Unit)? = null,
    onReleasePreview: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val installedApps = remember { mutableStateListOf<LauncherAppItem>() }
    var orbitRotationAngle by remember { mutableFloatStateOf(0f) }
    var canvasWidthPx by remember { mutableFloatStateOf(1080f) }
    var canvasHeightPx by remember { mutableFloatStateOf(2160f) }

    var currentTimeString by remember { mutableStateOf("") }
    var currentDateString by remember { mutableStateOf("") }
    var batteryLevel by remember { mutableIntStateOf(100) }
    var isBatteryCharging by remember { mutableStateOf(false) }

    var isSimModeActive by remember { mutableStateOf(false) }
    var showCameraPIP by remember { mutableStateOf(true) }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val hoverRadiusPx = remember(density) { with(density) { HOVER_RADIUS_DP.dp.toPx() } }

    val infiniteTransition = rememberInfiniteTransition(label = "ArcReactorTransition")
    val reactorRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(12000, easing = LinearEasing), RepeatMode.Restart),
        label = "reactorRotation"
    )
    val corePulse by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Reverse),
        label = "corePulse"
    )

    val vibrate = rememberHapticFeedback()

    /** Position d'une icône sur l'ellipse orbitale, pour un index donné. */
    fun orbitPosition(index: Int, count: Int, angleDeg: Float): Pair<Float, Float> {
        val angle = Math.toRadians((angleDeg + index * (360f / count)).toDouble())
        return Pair(
            canvasWidthPx / 2f + canvasWidthPx * 0.38f * cos(angle).toFloat(),
            canvasHeightPx / 2f + canvasHeightPx * 0.22f * sin(angle).toFloat()
        )
    }

    // Le survol ne change que lorsque le curseur franchit la frontière d'une icône : derivedStateOf
    // absorbe les milliers de micro-déplacements intermédiaires.
    val hoveredAppIndex by remember {
        derivedStateOf {
            val state = gestureState.value
            val count = installedApps.size
            if (count == 0 || !(state.isHandDetected || state.isSimulationMode)) return@derivedStateOf -1

            val cursorX = state.cursorX * canvasWidthPx
            val cursorY = state.cursorY * canvasHeightPx
            var best = -1
            var bestDistance = hoverRadiusPx
            for (index in 0 until count) {
                val (x, y) = orbitPosition(index, count, orbitRotationAngle)
                val distance = hypot(cursorX - x, cursorY - y)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = index
                }
            }
            best
        }
    }

    // --- Chargement des applications (hors thread principal) ---
    LaunchedEffect(Unit) {
        val apps = withContext(Dispatchers.IO) { loadLauncherApps(context) }
        installedApps.clear()
        installedApps.addAll(apps)
    }

    // --- Horloge et batterie ---
    LaunchedEffect(Unit) {
        val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val dateFormatter = SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault())
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

        while (true) {
            val now = Date()
            currentTimeString = timeFormatter.format(now)
            currentDateString = dateFormatter.format(now)
            batteryManager?.let {
                batteryLevel = it.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                isBatteryCharging = it.isCharging
            }
            delay(1000)
        }
    }

    // --- Balayage aérien : rotation de l'anneau ---
    // snapshotFlow observe l'état sans provoquer de recomposition de ce composable.
    LaunchedEffect(Unit) {
        snapshotFlow { gestureState.value.gesture }
            .distinctUntilChanged()
            .collect { gesture ->
                when (gesture) {
                    GestureType.SWIPE_RIGHT -> {
                        orbitRotationAngle += 30f
                        vibrate()
                    }
                    GestureType.SWIPE_LEFT -> {
                        orbitRotationAngle -= 30f
                        vibrate()
                    }
                    else -> Unit
                }
            }
    }

    // --- Pincement : lancement de l'application survolée ---
    // Déclenché par le front montant publié par l'analyseur (`pinchClickTick`), et non par l'état
    // « en train de pincer » : auparavant, maintenir le pincement relançait l'application à chaque
    // déplacement du curseur.
    LaunchedEffect(Unit) {
        snapshotFlow { gestureState.value.pinchClickTick }
            .distinctUntilChanged()
            .drop(1)
            .collect {
                val index = hoveredAppIndex
                if (index in installedApps.indices) {
                    vibrate()
                    launchApp(context, installedApps[index].packageName)
                }
            }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(StarkDarkBg)
            .testTag("stark_hud_container")
            .onSizeChanged {
                canvasWidthPx = it.width.toFloat()
                canvasHeightPx = it.height.toFloat()
            }
            .pointerInput(isSimModeActive) {
                detectDragGestures { change, dragAmount ->
                    if (isSimModeActive) {
                        change.consume()
                        onSimulatePosition(
                            (change.position.x / size.width).coerceIn(0f, 1f),
                            (change.position.y / size.height).coerceIn(0f, 1f),
                            false,
                            false
                        )
                    } else {
                        orbitRotationAngle += dragAmount.x * 0.2f
                    }
                }
            }
    ) {
        CyberBackdrop()

        ArcReactorCenter(
            modifier = Modifier.align(Alignment.Center),
            rotationAngle = reactorRotation,
            pulseScale = corePulse,
            gestureState = gestureState
        )

        installedApps.forEachIndexed { index, app ->
            val count = installedApps.size
            Box(
                modifier = Modifier
                    // Lu en phase de mise en page : faire tourner l'anneau ne recompose rien.
                    .offset {
                        val (x, y) = orbitPosition(index, count, orbitRotationAngle)
                        IntOffset((x - 40.dp.toPx()).toInt(), (y - 40.dp.toPx()).toInt())
                    }
                    .graphicsLayer {
                        val angle = Math.toRadians(
                            (orbitRotationAngle + index * (360f / count)).toDouble()
                        )
                        val depth = 0.7f + 0.35f * sin(angle).toFloat()
                        val hovered = index == hoveredAppIndex
                        scaleX = if (hovered) depth * 1.3f else depth
                        scaleY = if (hovered) depth * 1.3f else depth
                        alpha = (0.4f + 0.6f * sin(angle).toFloat()).coerceIn(0.15f, 1f)
                    }
                    .clickable { launchApp(context, app.packageName) }
            ) {
                AppOrbitalNode(app = app, isHovered = index == hoveredAppIndex)
            }
        }

        StarkHeaderHUD(
            timeString = currentTimeString,
            dateString = currentDateString,
            batteryLevel = batteryLevel,
            isCharging = isBatteryCharging,
            gestureState = gestureState,
            modifier = Modifier.align(Alignment.TopCenter)
        )

        StarkFooterGestureHUD(
            gestureState = gestureState,
            isSimMode = isSimModeActive,
            onToggleSimMode = {
                isSimModeActive = !isSimModeActive
                onSimulationModeChanged(isSimModeActive)
            },
            onSimulateGesture = { gesture ->
                val state = gestureState.value
                when (gesture) {
                    GestureType.PINCH -> onSimulatePosition(state.cursorX, state.cursorY, true, false)
                    GestureType.FIST -> onSimulatePosition(state.cursorX, state.cursorY, false, true)
                    GestureType.SWIPE_RIGHT -> orbitRotationAngle += 35f
                    GestureType.SWIPE_LEFT -> orbitRotationAngle -= 35f
                    else -> onSimulatePosition(state.cursorX, state.cursorY, false, false)
                }
            },
            onToggleCameraPIP = { showCameraPIP = !showCameraPIP },
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        if (showCameraPIP) {
            HandTrackingCameraPIP(
                gestureState = gestureState,
                onBindPreview = onBindPreview,
                onReleasePreview = onReleasePreview,
                onClosePIP = { showCameraPIP = false },
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }

        HolographicCursorOverlay(
            gestureState = gestureState,
            isHovering = hoveredAppIndex != -1
        )
    }
}

/**
 * Grille cybernétique et cercles de fond.
 *
 * `drawWithCache` construit le tracé une seule fois par taille : la version précédente
 * reconstruisait plusieurs dizaines de segments de `Path` à chaque image.
 */
@Composable
private fun CyberBackdrop(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val gridSpacing = 80.dp.toPx()
                val gridPath = Path().apply {
                    var x = 0f
                    while (x < size.width) {
                        moveTo(x, 0f); lineTo(x, size.height); x += gridSpacing
                    }
                    var y = 0f
                    while (y < size.height) {
                        moveTo(0f, y); lineTo(size.width, y); y += gridSpacing
                    }
                }
                val gridStroke = Stroke(
                    width = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                )
                val center = Offset(size.width / 2f, size.height / 2f)

                onDrawBehind {
                    drawPath(gridPath, StarkCyan.copy(alpha = 0.05f), style = gridStroke)
                    drawCircle(StarkCyan.copy(alpha = 0.08f), size.width * 0.42f, center, style = Stroke(1.5f))
                    drawCircle(StarkCyan.copy(alpha = 0.12f), size.width * 0.25f, center, style = Stroke(1f))
                }
            }
    )
}

/** Réacteur Arc central. */
@Composable
fun ArcReactorCenter(
    rotationAngle: Float,
    pulseScale: Float,
    gestureState: State<HandGestureState>,
    modifier: Modifier = Modifier
) {
    val gesture by remember { derivedStateOf { gestureState.value.gesture } }
    val coreColor = when (gesture) {
        GestureType.PINCH -> StarkGold
        GestureType.FIST -> StarkRed
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

            drawCircle(
                color = coreColor.copy(alpha = 0.3f),
                radius = maxRadius * 0.9f,
                style = Stroke(width = 3.dp.toPx())
            )

            rotate(rotationAngle, center) {
                val segmentCount = 10
                for (i in 0 until segmentCount) {
                    drawArc(
                        color = coreColor,
                        startAngle = i * (360f / segmentCount),
                        sweepAngle = 20f,
                        useCenter = false,
                        topLeft = Offset(center.x - maxRadius * 0.85f, center.y - maxRadius * 0.85f),
                        size = Size(maxRadius * 1.7f, maxRadius * 1.7f),
                        style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }

            rotate(-rotationAngle * 1.5f, center) {
                val segmentCount = 6
                for (i in 0 until segmentCount) {
                    drawArc(
                        color = StarkBlue.copy(alpha = 0.8f),
                        startAngle = i * (360f / segmentCount),
                        sweepAngle = 35f,
                        useCenter = false,
                        topLeft = Offset(center.x - maxRadius * 0.6f, center.y - maxRadius * 0.6f),
                        size = Size(maxRadius * 1.2f, maxRadius * 1.2f),
                        style = Stroke(width = 4.dp.toPx())
                    )
                }
            }

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(coreColor, coreColor.copy(alpha = 0.4f), Color.Transparent),
                    center = center,
                    radius = maxRadius * 0.45f
                ),
                radius = maxRadius * 0.45f
            )
            drawCircle(color = Color.White, radius = maxRadius * 0.18f)
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "ARC REACTOR",
                color = StarkCyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = when (gesture) {
                    GestureType.PINCH -> "ENGAGED"
                    GestureType.FIST -> "OVERRIDE"
                    else -> "100%"
                },
                color = coreColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun AppOrbitalNode(app: LauncherAppItem, isHovered: Boolean) {
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
            val icon = app.iconBitmap
            if (icon != null) {
                Image(
                    bitmap = icon.asImageBitmap(),
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

/** En-tête HUD : horloge, batterie et télémétrie du moteur de suivi. */
@Composable
fun StarkHeaderHUD(
    timeString: String,
    dateString: String,
    batteryLevel: Int,
    isCharging: Boolean,
    gestureState: State<HandGestureState>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 36.dp, start = 16.dp, end = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
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

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            HudChip(
                icon = Icons.Default.BatteryFull,
                tint = if (batteryLevel > 20) StarkCyan else StarkRed,
                text = "$batteryLevel%" + if (isCharging) " ⚡" else "",
                textColor = Color.White
            )
            TrackingTelemetryChip(gestureState)
        }
    }
}

/**
 * Télémétrie du moteur de suivi : cadence, latence d'inférence et confiance.
 * Isolée dans son propre composable et alimentée par `derivedStateOf`, elle se rafraîchit
 * sans entraîner le reste de l'en-tête.
 */
@Composable
private fun TrackingTelemetryChip(gestureState: State<HandGestureState>) {
    val telemetry by remember {
        derivedStateOf {
            val s = gestureState.value
            Triple(s.fps, s.latencyMs, (s.confidence * 100).toInt())
        }
    }
    val (fps, latency, confidence) = telemetry

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
                imageVector = Icons.Default.Speed,
                contentDescription = "Télémétrie du suivi",
                tint = StarkGold,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Column {
                Text(
                    text = "$fps FPS · ${latency}ms",
                    color = StarkGold,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "PRÉCISION $confidence%",
                    color = if (confidence >= 70) StarkCyan else StarkCyan.copy(alpha = 0.5f),
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun HudChip(icon: ImageVector, tint: Color, text: String, textColor: Color) {
    Card(
        colors = CardDefaults.cardColors(containerColor = StarkSurface),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.border(1.dp, StarkCyan.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = text,
                color = textColor,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/** Barre inférieure : geste courant et contrôles rapides. */
@Composable
fun StarkFooterGestureHUD(
    gestureState: State<HandGestureState>,
    isSimMode: Boolean,
    onToggleSimMode: () -> Unit,
    onSimulateGesture: (GestureType) -> Unit,
    onToggleCameraPIP: () -> Unit,
    modifier: Modifier = Modifier
) {
    val status by remember {
        derivedStateOf {
            val s = gestureState.value
            if (s.isVisionUnavailable) null else s.gesture
        }
    }
    val visionError by remember { derivedStateOf { gestureState.value.visionError } }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp, start = 12.dp, end = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val label = when (status) {
            null -> "MODULE DE VISION INDISPONIBLE"
            GestureType.PINCH -> "PINCE -> [CLIC / SÉLECTION]"
            GestureType.FIST -> "POING -> [RETOUR SYSTÈME]"
            GestureType.POINT -> "INDEX -> [VISÉE DE PRÉCISION]"
            GestureType.SWIPE_RIGHT -> "BALAYAGE DROITE -> [ROTATION ORBITE]"
            GestureType.SWIPE_LEFT -> "BALAYAGE GAUCHE -> [ROTATION ORBITE]"
            GestureType.OPEN_PALM -> "MAIN OUVERTE -> [CURSEUR HOLOGRAPHIQUE]"
            GestureType.IDLE -> "EN ATTENTE DE MAIN..."
        }
        val badgeColor = when (status) {
            null -> StarkRed
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
                text = label,
                color = badgeColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        // La cause exacte de la panne est affichée sous le badge : un échec de chargement du
        // modèle n'apparaissait auparavant que dans logcat, hors de portée de l'utilisateur.
        visionError?.let { detail ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = detail,
                color = StarkRed.copy(alpha = 0.85f),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "MODE TACTILE / SIM:",
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
                    ) { Text("PINCE", fontSize = 10.sp, color = Color.Black) }
                    Button(
                        onClick = { onSimulateGesture(GestureType.SWIPE_RIGHT) },
                        colors = ButtonDefaults.buttonColors(containerColor = StarkCyan),
                        modifier = Modifier.height(32.dp)
                    ) { Text("ROTATION", fontSize = 10.sp, color = Color.Black) }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SystemActionsRow()
                    IconButton(onClick = onToggleCameraPIP) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = "Aperçu caméra",
                            tint = StarkCyan
                        )
                    }
                }
            }
        }
    }
}

/**
 * Curseur holographique.
 *
 * Toute la lecture de l'état se fait dans la lambda de dessin : le curseur suit la main à la
 * cadence de la caméra sans jamais déclencher de recomposition ni de nouvelle mise en page.
 */
@Composable
fun HolographicCursorOverlay(
    gestureState: State<HandGestureState>,
    isHovering: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "CursorTransition")
    val cursorRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart),
        label = "cursorRotation"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val state = gestureState.value
        if (!state.isHandDetected && !state.isSimulationMode) return@Canvas

        val center = Offset(state.cursorX * size.width, state.cursorY * size.height)
        val cursorColor = when (state.gesture) {
            GestureType.PINCH -> StarkGold
            GestureType.FIST -> StarkRed
            else -> if (isHovering) StarkGold else StarkCyan
        }
        val radius = if (state.gesture == GestureType.PINCH) 24.dp.toPx() else 36.dp.toPx()

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

        val lineLength = 12.dp.toPx()
        val stroke = 2.dp.toPx()
        val gap = radius * 0.4f
        drawLine(cursorColor, Offset(center.x - lineLength - gap, center.y), Offset(center.x - gap, center.y), stroke)
        drawLine(cursorColor, Offset(center.x + gap, center.y), Offset(center.x + lineLength + gap, center.y), stroke)
        drawLine(cursorColor, Offset(center.x, center.y - lineLength - gap), Offset(center.x, center.y - gap), stroke)
        drawLine(cursorColor, Offset(center.x, center.y + gap), Offset(center.x, center.y + lineLength + gap), stroke)

        drawCircle(color = cursorColor, radius = 4.dp.toPx(), center = center)

        // Anneau de force du pincement : retour visuel analogique avant même le déclenchement,
        // l'utilisateur voit le clic approcher au lieu de le subir.
        if (state.pinchStrength > 0.05f) {
            drawCircle(
                color = StarkGold.copy(alpha = 0.25f + 0.55f * state.pinchStrength),
                radius = radius * (1.35f - 0.35f * state.pinchStrength),
                center = center,
                style = Stroke(width = 3.dp.toPx())
            )
        }

        // Halo de profondeur.
        drawCircle(
            color = cursorColor.copy(alpha = 0.2f),
            radius = radius * (1f + state.cursorZ * 0.5f),
            center = center,
            style = Stroke(width = 1.dp.toPx())
        )
    }
}

/**
 * Fenêtre d'aperçu caméra avec superposition du squelette de la main.
 */
@Composable
fun HandTrackingCameraPIP(
    gestureState: State<HandGestureState>,
    onBindPreview: ((PreviewView) -> Unit)?,
    onReleasePreview: () -> Unit,
    onClosePIP: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }

    val isDetected by remember { derivedStateOf { gestureState.value.isHandDetected } }
    val gesture by remember { derivedStateOf { gestureState.value.gesture } }

    // La surface d'aperçu est relâchée quand la fenêtre disparaît, pour que la caméra puisse
    // être relié sans cas d'usage d'aperçu orphelin.
    DisposableEffect(Unit) { onDispose { onReleasePreview() } }

    Card(
        modifier = modifier
            .padding(top = 70.dp, end = 16.dp)
            .size(
                width = if (isExpanded) 220.dp else 160.dp,
                height = if (isExpanded) 280.dp else 200.dp
            )
            .border(
                border = BorderStroke(1.5.dp, if (isDetected) StarkCyan else StarkGold.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(12.dp)
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = StarkDarkBg.copy(alpha = 0.88f))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        onBindPreview?.invoke(this)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

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

            HandSkeletonOverlay(gestureState)

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
                            .background(if (isDetected) StarkCyan else StarkGold, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isDetected) "MAIN DÉTECTÉE" else "RECHERCHE...",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDetected) StarkCyan else StarkGold
                        )
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { isExpanded = !isExpanded }, modifier = Modifier.size(18.dp)) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            contentDescription = "Redimensionner",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(12.dp)
                        )
                    }
                    IconButton(onClick = onClosePIP, modifier = Modifier.size(18.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Fermer l'aperçu",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp)
                    .background(StarkDarkBg.copy(alpha = 0.9f), RoundedCornerShape(4.dp))
                    .border(0.5.dp, StarkCyan.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "GESTE: ${gesture.name}",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 7.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (gesture) {
                            GestureType.PINCH -> StarkGold
                            GestureType.FIST -> StarkRed
                            GestureType.OPEN_PALM, GestureType.POINT -> StarkCyan
                            else -> Color.White.copy(alpha = 0.7f)
                        }
                    )
                )
            }
        }
    }
}

/** Connexions osseuses du squelette de la main, précalculées une fois pour toutes. */
private val HAND_BONES: List<Pair<Int, Int>> = listOf(
    0 to 1, 0 to 5, 0 to 9, 0 to 13, 0 to 17,
    1 to 2, 2 to 3, 3 to 4,
    5 to 6, 6 to 7, 7 to 8,
    9 to 10, 10 to 11, 11 to 12,
    13 to 14, 14 to 15, 15 to 16,
    17 to 18, 18 to 19, 19 to 20,
    5 to 9, 9 to 13, 13 to 17
)

/** Squelette 21 points superposé à l'aperçu caméra. Lecture d'état en phase de dessin. */
@Composable
private fun HandSkeletonOverlay(gestureState: State<HandGestureState>) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val state = gestureState.value
        val frameColor = if (state.isHandDetected) StarkCyan else StarkGold

        // Coins de visée du cadre.
        val cornerLength = 14f
        val cornerStroke = 2.5f
        val w = size.width
        val h = size.height
        for ((cx, cy, sx, sy) in listOf(
            Quad(8f, 8f, 1f, 1f),
            Quad(w - 8f, 8f, -1f, 1f),
            Quad(8f, h - 8f, 1f, -1f),
            Quad(w - 8f, h - 8f, -1f, -1f)
        )) {
            drawLine(frameColor, Offset(cx, cy), Offset(cx + sx * cornerLength, cy), strokeWidth = cornerStroke)
            drawLine(frameColor, Offset(cx, cy), Offset(cx, cy + sy * cornerLength), strokeWidth = cornerStroke)
        }

        val landmarks = state.landmarks
        if (landmarks.size < HandLandmarks.COUNT || !state.isHandDetected) {
            if (!state.isHandDetected) {
                // Balayage radar pendant la recherche.
                val scanY = (System.currentTimeMillis() % 2000) / 2000f * h
                drawLine(StarkGold.copy(alpha = 0.6f), Offset(0f, scanY), Offset(w, scanY), strokeWidth = 1.5f)
            }
            return@Canvas
        }

        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE

        for (bone in HAND_BONES) {
            val a = landmarks[bone.first]
            val b = landmarks[bone.second]
            drawLine(
                color = if (bone.second == 4 || bone.second == 8) StarkGold else StarkCyan,
                start = Offset(a.x * w, a.y * h),
                end = Offset(b.x * w, b.y * h),
                strokeWidth = 2.5f
            )
        }

        landmarks.forEachIndexed { index, point ->
            val x = point.x * w
            val y = point.y * h
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y

            val jointColor = when (index) {
                HandLandmarks.WRIST -> StarkRed
                HandLandmarks.THUMB_TIP -> StarkGold
                HandLandmarks.INDEX_TIP ->
                    if (state.gesture == GestureType.PINCH) StarkGold else StarkCyan
                else -> StarkCyan.copy(alpha = 0.85f)
            }
            val radius = if (index == HandLandmarks.INDEX_TIP || index == HandLandmarks.THUMB_TIP) 5.5f else 3.5f
            drawCircle(jointColor, radius = radius, center = Offset(x, y))
        }

        drawRect(
            color = StarkCyan.copy(alpha = 0.5f),
            topLeft = Offset(
                (minX - 10f).coerceAtLeast(4f),
                (minY - 10f).coerceAtLeast(4f)
            ),
            size = Size(
                (maxX - minX + 20f).coerceAtMost(w - 8f),
                (maxY - minY + 20f).coerceAtMost(h - 8f)
            ),
            style = Stroke(width = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
        )
    }
}

private data class Quad(val x: Float, val y: Float, val sx: Float, val sy: Float)

/** Retour haptique court, sans exception si l'appareil n'a pas de vibreur. */
@Composable
private fun rememberHapticFeedback(): () -> Unit {
    val context = LocalContext.current
    val vibrator = remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
    return remember(vibrator) {
        {
            try {
                vibrator?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
            } catch (_: Exception) {
            }
        }
    }
}

/** Énumère les applications lançables. Opération d'E/S : à exécuter hors thread principal. */
private fun loadLauncherApps(context: Context): List<LauncherAppItem> {
    val packageManager = context.packageManager
    val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }

    val apps = packageManager.queryIntentActivities(mainIntent, 0)
        .asSequence()
        .filter { it.activityInfo.packageName != context.packageName }
        .distinctBy { it.activityInfo.packageName }
        .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        .take(MAX_ORBIT_APPS)
        .map { info ->
            LauncherAppItem(
                label = info.loadLabel(packageManager).toString(),
                packageName = info.activityInfo.packageName,
                iconBitmap = runCatching { info.loadIcon(packageManager).toBitmap(96, 96) }.getOrNull()
            )
        }
        .toList()

    if (apps.isNotEmpty()) return apps

    return listOf(
        LauncherAppItem("JARVIS Core", "com.android.settings", null, Icons.Default.Security),
        LauncherAppItem("Arc Diagnostics", "com.android.camera2", null, Icons.Default.Camera),
        LauncherAppItem("Stark Tech", "com.android.settings", null, Icons.Default.Memory),
        LauncherAppItem("System Config", "com.android.settings", null, Icons.Default.Settings)
    )
}

private fun launchApp(context: Context, packageName: String) {
    try {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
        // Comportement attendu d'un launcher : chaque application vit dans sa propre tâche, ce
        // qui la fait apparaître dans l'écran des applications récentes. RESET_TASK_IF_NEEDED
        // ramène une tâche déjà ouverte au premier plan au lieu de la redémarrer.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}

/**
 * Barre d'actions système. Le service d'accessibilité exposait déjà les actions Récents,
 * Accueil et Notifications, mais rien ne les appelait : elles sont désormais accessibles.
 */
@Composable
private fun SystemActionsRow(modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        SystemActionButton(
            icon = Icons.Default.Apps,
            label = "Applications récentes",
            action = StarkAccessibilityService.ACTION_GLOBAL_RECENTS
        )
        SystemActionButton(
            icon = Icons.Default.Notifications,
            label = "Notifications",
            action = StarkAccessibilityService.ACTION_GLOBAL_NOTIFICATIONS
        )
    }
}

@Composable
private fun SystemActionButton(icon: ImageVector, label: String, action: String) {
    val context = LocalContext.current
    IconButton(
        onClick = {
            // Sans service d'accessibilité activé, l'action est impossible : on ouvre le réglage
            // correspondant plutôt que de laisser le bouton sans effet.
            if (!StarkAccessibilityService.performGlobalAction(action)) {
                runCatching {
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        },
        modifier = Modifier.size(34.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = StarkCyan,
            modifier = Modifier.size(20.dp)
        )
    }
}
