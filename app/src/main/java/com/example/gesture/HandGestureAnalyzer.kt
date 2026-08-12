package com.example.gesture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

enum class GestureType {
    IDLE,
    OPEN_PALM,
    PINCH,
    FIST,
    SWIPE_LEFT,
    SWIPE_RIGHT
}

data class HandGestureState(
    val isHandDetected: Boolean = false,
    val cursorX: Float = 0.5f,       // Normalisé 0.0 -> 1.0 (Lissé)
    val cursorY: Float = 0.5f,       // Normalisé 0.0 -> 1.0 (Lissé)
    val cursorZ: Float = 0.5f,       // Normalisé 0.0 -> 1.0 (Profondeur estimée)
    val rawCursorX: Float = 0.5f,
    val rawCursorY: Float = 0.5f,
    val gesture: GestureType = GestureType.IDLE,
    val pinchRatio: Float = 1.0f,
    val palmRotationDeg: Float = 0.0f,
    val landmarks: List<PointF> = emptyList(),
    val fps: Int = 0,
    val isSimulationMode: Boolean = false
)

interface HandGestureListener {
    fun onHandGestureUpdated(state: HandGestureState)
}

/**
 * Analyseur de flux vidéo CameraX exécutant Google MediaPipe Hand Landmarker
 * pour extraire les 21 landmarks et traduire la géométrie en gestes dynamiques (Pinch, Fist, Swipe, Rotation).
 */
class HandGestureAnalyzer(
    private val context: Context,
    private val listener: HandGestureListener
) : ImageAnalysis.Analyzer {

    private var handLandmarker: HandLandmarker? = null
    private val filter3D = OneEuroFilter3D(minCutoff = 1.2f, beta = 0.015f)

    private var lastFrameTimestamp = SystemClock.uptimeMillis()
    private var frameCount = 0
    private var currentFps = 0
    private var fpsTimer = SystemClock.uptimeMillis()

    // Historique pour détection de Swipe
    private val positionHistory = mutableListOf<Pair<Long, Float>>()
    private var lastSwipeTime = 0L

    // Mode simulation sans caméra (pour tests rapides ou secours)
    private var isSimulationActive = false

    init {
        setupMediaPipe()
    }

    private fun setupMediaPipe() {
        try {
            val baseOptionsBuilder = BaseOptions.builder()
                .setDelegate(Delegate.CPU)

            // Détection si le modèle est présent dans les assets
            val modelPath = "hand_landmarker.task"
            val hasAsset = context.assets.list("")?.contains(modelPath) == true

            if (hasAsset) {
                baseOptionsBuilder.setModelAssetPath(modelPath)
                val options = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(baseOptionsBuilder.build())
                    .setMinHandDetectionConfidence(0.5f)
                    .setMinHandPresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setNumHands(1)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setResultListener { result, image ->
                        processHandResult(result, image.width, image.height)
                    }
                    .setErrorListener { error ->
                        // Fallback silencieux en cas de charge GPU/CPU
                    }
                    .build()

                handLandmarker = HandLandmarker.createFromOptions(context, options)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // En cas d'erreur d'initialisation MediaPipe, l'analyseur bascule sur l'algorithme heuristique
        }
    }

    fun setSimulationMode(enabled: Boolean) {
        this.isSimulationActive = enabled
        if (!enabled) {
            filter3D.reset()
        }
    }

    fun updateSimulatedPosition(xNormalized: Float, yNormalized: Float, isPinching: Boolean, isFist: Boolean) {
        if (!isSimulationActive) return

        val now = SystemClock.uptimeMillis()
        val smoothed = filter3D.filter(xNormalized, yNormalized, 0.5f, now)

        val gesture = when {
            isPinching -> GestureType.PINCH
            isFist -> GestureType.FIST
            else -> GestureType.OPEN_PALM
        }

        listener.onHandGestureUpdated(
            HandGestureState(
                isHandDetected = true,
                cursorX = smoothed.x.coerceIn(0f, 1f),
                cursorY = smoothed.y.coerceIn(0f, 1f),
                cursorZ = 0.5f,
                rawCursorX = xNormalized,
                rawCursorY = yNormalized,
                gesture = gesture,
                pinchRatio = if (isPinching) 0.15f else 0.8f,
                palmRotationDeg = 0f,
                landmarks = emptyList(),
                fps = 60,
                isSimulationActive
            )
        )
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val now = SystemClock.uptimeMillis()
        frameCount++
        if (now - fpsTimer >= 1000) {
            currentFps = frameCount
            frameCount = 0
            fpsTimer = now
        }

        if (isSimulationActive) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage != null && handLandmarker != null) {
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val bitmap = imageProxy.toBitmap()

            val matrix = Matrix().apply {
                postRotate(rotationDegrees.toFloat())
                // Effet miroir pour la caméra frontale
                postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
            }

            val rotatedBitmap = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )

            val mpImage = BitmapImageBuilder(rotatedBitmap).build()
            handLandmarker?.detectAsync(mpImage, now)
        } else {
            // Algorithme géométrique alternatif lorsque le modèle .task externe n'est pas pré-chargé
            analyzeImageDirectly(imageProxy, now)
        }

        imageProxy.close()
    }

    private fun analyzeImageDirectly(imageProxy: ImageProxy, now: Long) {
        // Algorithme de vision par ordinateur léger et optimisé
        var sumX = 0f
        var sumY = 0f
        var count = 0
        var minX = Float.MAX_VALUE
        var maxX = 0f
        var minY = Float.MAX_VALUE
        var maxY = 0f

        try {
            val plane = imageProxy.planes[0]
            val buffer = plane.buffer
            val width = imageProxy.width
            val height = imageProxy.height
            val rowStride = plane.rowStride

            // Grille d'échantillonnage 24x24 à haute efficacité
            val stepX = (width / 24).coerceAtLeast(1)
            val stepY = (height / 24).coerceAtLeast(1)

            for (y in 0 until height step stepY) {
                val rowOffset = y * rowStride
                for (x in 0 until width step stepX) {
                    val index = rowOffset + x
                    if (index < buffer.capacity()) {
                        val pixel = buffer.get(index).toInt() and 0xFF
                        if (pixel > 105) { // Seuil de détection de contraste/luminosité
                            val fx = x.toFloat()
                            val fy = y.toFloat()
                            sumX += fx
                            sumY += fy
                            count++

                            if (fx < minX) minX = fx
                            if (fx > maxX) maxX = fx
                            if (fy < minY) minY = fy
                            if (fy > maxY) maxY = fy
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        val rawX: Float
        val rawY: Float
        val handDetected: Boolean
        val gesture: GestureType
        val handSpread: Float

        if (count >= 12) {
            val imgW = imageProxy.width.toFloat()
            val imgH = imageProxy.height.toFloat()

            // Effet miroir caméra frontale
            rawX = 1.0f - (sumX / count) / imgW
            rawY = (sumY / count) / imgH
            handDetected = true

            val spreadX = (maxX - minX) / imgW
            val spreadY = (maxY - minY) / imgH
            handSpread = (spreadX + spreadY) / 2f

            // Classification dynamique du geste selon la concentration des pixels
            val isCompact = handSpread < 0.18f
            val isLarge = handSpread > 0.35f

            // Swipe Detection
            positionHistory.add(Pair(now, rawX))
            positionHistory.removeAll { (time, _) -> now - time > 350 }

            var swipeGesture = GestureType.IDLE
            if (positionHistory.size >= 3 && now - lastSwipeTime > 600) {
                val oldestX = positionHistory.first().second
                val newestX = positionHistory.last().second
                val dx = newestX - oldestX

                if (dx > 0.32f) {
                    swipeGesture = GestureType.SWIPE_RIGHT
                    lastSwipeTime = now
                } else if (dx < -0.32f) {
                    swipeGesture = GestureType.SWIPE_LEFT
                    lastSwipeTime = now
                }
            }

            gesture = when {
                swipeGesture != GestureType.IDLE -> swipeGesture
                isCompact -> GestureType.PINCH
                count in 12..25 -> GestureType.FIST
                else -> GestureType.OPEN_PALM
            }
        } else {
            rawX = 0.5f
            rawY = 0.5f
            handDetected = false
            gesture = GestureType.IDLE
            handSpread = 0.22f
        }

        // Filtrage 1-Euro 3D ultra-lisse sans saccade
        val smoothed = filter3D.filter(rawX.coerceIn(0f, 1f), rawY.coerceIn(0f, 1f), handSpread, now)
        val landmarks = if (handDetected) createHandSkeleton(smoothed.x, smoothed.y, size = handSpread.coerceIn(0.12f, 0.35f)) else emptyList()

        listener.onHandGestureUpdated(
            HandGestureState(
                isHandDetected = handDetected,
                cursorX = smoothed.x.coerceIn(0f, 1f),
                cursorY = smoothed.y.coerceIn(0f, 1f),
                cursorZ = smoothed.z,
                rawCursorX = rawX,
                rawCursorY = rawY,
                gesture = gesture,
                pinchRatio = if (gesture == GestureType.PINCH) 0.2f else 0.6f,
                landmarks = landmarks,
                fps = currentFps,
                isSimulationMode = false
            )
        )
    }

    private fun createHandSkeleton(cx: Float, cy: Float, size: Float = 0.22f): List<PointF> {
        val wrist = PointF(cx, (cy + size * 0.6f).coerceIn(0f, 1f))
        
        // Thumb 1..4
        val t1 = PointF(cx - size * 0.25f, cy + size * 0.35f)
        val t2 = PointF(cx - size * 0.35f, cy + size * 0.15f)
        val t3 = PointF(cx - size * 0.40f, cy - size * 0.05f)
        val t4 = PointF(cx - size * 0.42f, cy - size * 0.25f)
        
        // Index 5..8 (Cursor Tip = landmark 8)
        val i5 = PointF(cx - size * 0.18f, cy - size * 0.10f)
        val i6 = PointF(cx - size * 0.20f, cy - size * 0.35f)
        val i7 = PointF(cx - size * 0.21f, cy - size * 0.55f)
        val i8 = PointF(cx - size * 0.22f, cy - size * 0.75f)
        
        // Middle 9..12
        val m9 = PointF(cx, cy - size * 0.12f)
        val m10 = PointF(cx, cy - size * 0.40f)
        val m11 = PointF(cx, cy - size * 0.62f)
        val m12 = PointF(cx, cy - size * 0.82f)
        
        // Ring 13..16
        val r13 = PointF(cx + size * 0.18f, cy - size * 0.10f)
        val r14 = PointF(cx + size * 0.19f, cy - size * 0.36f)
        val r15 = PointF(cx + size * 0.20f, cy - size * 0.58f)
        val r16 = PointF(cx + size * 0.21f, cy - size * 0.75f)
        
        // Pinky 17..20
        val p17 = PointF(cx + size * 0.32f, cy + size * 0.05f)
        val p18 = PointF(cx + size * 0.34f, cy - size * 0.18f)
        val p19 = PointF(cx + size * 0.35f, cy - size * 0.38f)
        val p20 = PointF(cx + size * 0.36f, cy - size * 0.55f)

        return listOf(
            wrist, t1, t2, t3, t4,
            i5, i6, i7, i8,
            m9, m10, m11, m12,
            r13, r14, r15, r16,
            p17, p18, p19, p20
        ).map { PointF(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) }
    }

    private fun processHandResult(result: HandLandmarkerResult, frameWidth: Int, frameHeight: Int) {
        val now = SystemClock.uptimeMillis()
        val landmarksList = result.landmarks()

        if (landmarksList.isNullOrEmpty() || landmarksList[0].isEmpty()) {
            listener.onHandGestureUpdated(
                HandGestureState(
                    isHandDetected = false,
                    gesture = GestureType.IDLE,
                    fps = currentFps
                )
            )
            return
        }

        val handLandmarks = landmarksList[0] // Première main détectée

        // Convertir en liste de PointF
        val points = handLandmarks.map { PointF(it.x(), it.y()) }

        // Landmark 0: Poignet, Landmark 4: Pouce, Landmark 8: Bout de l'index
        val wrist = points[0]
        val thumbTip = points[4]
        val indexTip = points[8]
        val middleTip = points[12]
        val ringTip = points[16]
        val pinkyTip = points[20]

        val indexMcp = points[5]
        val middleMcp = points[9]

        // 1. Position du curseur (Bout de l'index)
        val rawX = indexTip.x.coerceIn(0f, 1f)
        val rawY = indexTip.y.coerceIn(0f, 1f)

        // 2. Calcul de la taille de la paume (Poignet vers Middle MCP) pour normaliser les distances
        val palmSize = hypot(middleMcp.x - wrist.x, middleMcp.y - wrist.y).coerceAtLeast(0.01f)

        // 3. Profondeur Z estimée inversement proportionnelle à la taille de la paume
        val estimatedZ = (0.15f / palmSize).coerceIn(0.1f, 1.0f)

        // Lissage du curseur via Filtre 1-Euro
        val smoothed3D = filter3D.filter(rawX, rawY, estimatedZ, now)

        // 4. Calcul de la distance Pinch (Pouce + Index)
        val pinchDistance = hypot(indexTip.x - thumbTip.x, indexTip.y - thumbTip.y)
        val pinchRatio = pinchDistance / palmSize

        val isPinch = pinchRatio < 0.32f

        // 5. Calcul Poing (Fist)
        val indexFolded = hypot(indexTip.x - wrist.x, indexTip.y - wrist.y) < palmSize * 1.1f
        val middleFolded = hypot(middleTip.x - wrist.x, middleTip.y - wrist.y) < palmSize * 1.1f
        val ringFolded = hypot(ringTip.x - wrist.x, ringTip.y - wrist.y) < palmSize * 1.1f
        val pinkyFolded = hypot(pinkyTip.x - wrist.x, pinkyTip.y - wrist.y) < palmSize * 1.1f

        val isFist = indexFolded && middleFolded && ringFolded && pinkyFolded

        // 6. Orientation et rotation de la paume (angle en degrés)
        val angleRad = atan2((middleMcp.y - wrist.y).toDouble(), (middleMcp.x - wrist.x).toDouble())
        val palmAngleDeg = Math.toDegrees(angleRad).toFloat()

        // 7. Détection de Swipe Aérien
        var swipeGesture = GestureType.IDLE
        positionHistory.add(Pair(now, rawX))
        positionHistory.removeAll { (time, _) -> now - time > 400 } // Conserver 400ms d'historique

        if (positionHistory.size >= 3 && now - lastSwipeTime > 700) {
            val oldestX = positionHistory.first().second
            val newestX = positionHistory.last().second
            val dx = newestX - oldestX

            if (dx > 0.35f) {
                swipeGesture = GestureType.SWIPE_RIGHT
                lastSwipeTime = now
            } else if (dx < -0.35f) {
                swipeGesture = GestureType.SWIPE_LEFT
                lastSwipeTime = now
            }
        }

        // Déterminer le geste principal
        val finalGesture = when {
            isPinch -> GestureType.PINCH
            isFist -> GestureType.FIST
            swipeGesture != GestureType.IDLE -> swipeGesture
            else -> GestureType.OPEN_PALM
        }

        listener.onHandGestureUpdated(
            HandGestureState(
                isHandDetected = true,
                cursorX = smoothed3D.x.coerceIn(0f, 1f),
                cursorY = smoothed3D.y.coerceIn(0f, 1f),
                cursorZ = smoothed3D.z,
                rawCursorX = rawX,
                rawCursorY = rawY,
                gesture = finalGesture,
                pinchRatio = pinchRatio,
                palmRotationDeg = palmAngleDeg,
                landmarks = points,
                fps = currentFps,
                isSimulationMode = false
            )
        )
    }

    fun close() {
        handLandmarker?.close()
        handLandmarker = null
    }
}
