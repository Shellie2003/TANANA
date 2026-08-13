package com.example.gesture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

/** État complet publié à chaque frame analysée. */
data class HandGestureState(
    val isHandDetected: Boolean = false,
    /** Curseur lissé, normalisé 0..1 dans l'espace écran. */
    val cursorX: Float = 0.5f,
    val cursorY: Float = 0.5f,
    /** Profondeur estimée : 0 = main proche, 1 = main éloignée. */
    val cursorZ: Float = 0.5f,
    /** Position brute (avant lissage), utile au diagnostic. */
    val rawCursorX: Float = 0.5f,
    val rawCursorY: Float = 0.5f,
    val gesture: GestureType = GestureType.IDLE,
    /** Force analogique du pincement, 0..1. */
    val pinchStrength: Float = 0f,
    /** Confiance globale de la détection, 0..1. */
    val confidence: Float = 0f,
    val palmRotationDeg: Float = 0f,
    val landmarks: List<Landmark> = emptyList(),
    /** Fréquence de traitement effective (images par seconde). */
    val fps: Int = 0,
    /** Latence d'inférence médiane, en millisecondes. */
    val latencyMs: Int = 0,
    val isSimulationMode: Boolean = false,
    /** Vrai si le modèle de vision n'a pas pu être chargé : le suivi de main est indisponible. */
    val isVisionUnavailable: Boolean = false,
    /** Front montant du pincement : une seule frame, à consommer comme un clic. */
    val pinchClickTick: Long = 0L
)

interface HandGestureListener {
    fun onHandGestureUpdated(state: HandGestureState)
}

/**
 * Analyseur CameraX exécutant MediaPipe Hand Landmarker et traduisant les 21 points de repère
 * en gestes stables.
 *
 * Optimisations du chemin de frame (exécuté 30 fois par seconde, donc critique) :
 *  - sortie caméra en RGBA_8888 : supprime la conversion YUV -> RGB logicielle par image ;
 *  - bitmap source réutilisé d'une frame à l'autre : une seule allocation résiduelle
 *    (l'image pivotée, que MediaPipe conserve de façon asynchrone) au lieu de trois ;
 *  - rotation, miroir et recadrage fusionnés en une seule matrice ;
 *  - horodatages strictement monotones, exigés par le mode LIVE_STREAM ;
 *  - delegate GPU avec repli automatique sur CPU.
 */
class HandGestureAnalyzer(
    private val context: Context,
    private val listener: HandGestureListener
) : ImageAnalysis.Analyzer {

    private companion object {
        const val TAG = "HandGestureAnalyzer"
        const val MODEL_ASSET = "hand_landmarker.task"

        /**
         * Prédiction de latence appliquée au curseur. Correspond à la latence typique de la
         * chaîne capteur -> inférence -> composition sur un appareil milieu de gamme.
         */
        const val CURSOR_PREDICTION_MS = 40f

        /** Nombre d'erreurs d'inférence consécutives avant de rétrograder le delegate GPU. */
        const val GPU_FAILURE_THRESHOLD = 5
    }

    private var handLandmarker: HandLandmarker? = null
    private var visionUnavailable = false
    private var activeDelegate: Delegate? = null

    /**
     * Incrémenté depuis le callback d'erreur de MediaPipe (thread d'inférence), lu depuis le
     * thread d'analyse : d'où le @Volatile.
     */
    @Volatile
    private var consecutiveInferenceErrors = 0

    private val classifier = GestureClassifier()
    private val cursorMapper = CursorMapper()
    private val cursorFilter = OneEuroFilter3D(
        minCutoff = 1.6f,
        beta = 1.2f,
        predictionMs = CURSOR_PREDICTION_MS
    )

    // Bitmap source réutilisé (jamais transmis à MediaPipe, donc sûr à muter).
    private var reusableSource: Bitmap? = null
    private val frameMatrix = Matrix()
    private var lastRotationDegrees = Int.MIN_VALUE

    private var lastTimestamp = 0L
    private var frameCount = 0
    private var currentFps = 0
    private var fpsWindowStart = SystemClock.uptimeMillis()

    private var inferenceStartedAt = 0L
    private var smoothedLatencyMs = 0f

    private var isSimulationActive = false
    private var pinchClickTick = 0L
    private var wasHandDetected = false
    private var lastIdleEmit = 0L
    private var visionUnavailableNotified = false

    init {
        setupMediaPipe()
    }

    private fun setupMediaPipe() {
        val hasModel = try {
            context.assets.list("")?.contains(MODEL_ASSET) == true
        } catch (e: Exception) {
            Log.w(TAG, "Lecture des assets impossible", e)
            false
        }

        if (!hasModel) {
            Log.e(TAG, "Modèle $MODEL_ASSET absent des assets : suivi de main indisponible.")
            visionUnavailable = true
            return
        }

        // Le delegate GPU divise environ par deux la latence d'inférence quand il est disponible ;
        // certains pilotes le refusent, d'où le repli CPU silencieux.
        handLandmarker = createLandmarker(Delegate.GPU)?.also { activeDelegate = Delegate.GPU }
            ?: createLandmarker(Delegate.CPU)?.also { activeDelegate = Delegate.CPU }
        if (handLandmarker == null) {
            Log.e(TAG, "Initialisation de HandLandmarker impossible (GPU et CPU).")
            visionUnavailable = true
        }
    }

    private fun createLandmarker(delegate: Delegate): HandLandmarker? = try {
        val baseOptions = BaseOptions.builder()
            .setDelegate(delegate)
            .setModelAssetPath(MODEL_ASSET)
            .build()

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            // Seuils relevés par rapport aux valeurs par défaut : on préfère perdre une frame
            // douteuse plutôt que piloter le curseur avec une détection incertaine.
            .setMinHandDetectionConfidence(0.6f)
            .setMinHandPresenceConfidence(0.6f)
            .setMinTrackingConfidence(0.6f)
            .setNumHands(1)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener { result, _ -> processHandResult(result) }
            .setErrorListener { error ->
                consecutiveInferenceErrors++
                Log.w(TAG, "Erreur d'inférence MediaPipe: $error")
            }
            .build()

        HandLandmarker.createFromOptions(context, options)
    } catch (e: Throwable) {
        Log.w(TAG, "Delegate $delegate indisponible: ${e.message}")
        null
    }

    fun setSimulationMode(enabled: Boolean) {
        isSimulationActive = enabled
        cursorFilter.reset()
        classifier.reset()
    }

    fun updateSimulatedPosition(
        xNormalized: Float,
        yNormalized: Float,
        isPinching: Boolean,
        isFist: Boolean
    ) {
        if (!isSimulationActive) return

        val now = nextTimestamp()
        val smoothed = cursorFilter.filter(xNormalized, yNormalized, 0.5f, now)

        val gesture = when {
            isPinching -> GestureType.PINCH
            isFist -> GestureType.FIST
            else -> GestureType.OPEN_PALM
        }
        if (isPinching) pinchClickTick = now

        listener.onHandGestureUpdated(
            HandGestureState(
                isHandDetected = true,
                cursorX = smoothed.x.coerceIn(0f, 1f),
                cursorY = smoothed.y.coerceIn(0f, 1f),
                cursorZ = 0.5f,
                rawCursorX = xNormalized,
                rawCursorY = yNormalized,
                gesture = gesture,
                pinchStrength = if (isPinching) 1f else 0f,
                confidence = 1f,
                fps = 60,
                latencyMs = 0,
                isSimulationMode = true,
                pinchClickTick = pinchClickTick
            )
        )
    }

    override fun analyze(imageProxy: ImageProxy) {
        try {
            updateFpsCounter()

            if (isSimulationActive || visionUnavailable) {
                if (visionUnavailable) emitVisionUnavailable()
                return
            }

            demoteToCpuIfGpuFailing()

            val landmarker = handLandmarker ?: return
            val bitmap = buildUprightBitmap(imageProxy) ?: return

            inferenceStartedAt = SystemClock.uptimeMillis()
            landmarker.detectAsync(BitmapImageBuilder(bitmap).build(), nextTimestamp())
        } catch (e: Throwable) {
            Log.w(TAG, "Frame ignorée: ${e.message}")
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Certains pilotes acceptent la création du delegate GPU puis échouent à chaque inférence.
     * Le cas est indétectable à l'initialisation ; on le rattrape ici, sur le thread d'analyse
     * (le seul où reconstruire le détecteur est sûr), après quelques échecs consécutifs.
     */
    private fun demoteToCpuIfGpuFailing() {
        if (activeDelegate != Delegate.GPU) return
        if (consecutiveInferenceErrors < GPU_FAILURE_THRESHOLD) return

        Log.w(TAG, "Delegate GPU défaillant après $consecutiveInferenceErrors erreurs : repli sur CPU")
        handLandmarker?.close()
        activeDelegate = Delegate.CPU
        consecutiveInferenceErrors = 0
        handLandmarker = createLandmarker(Delegate.CPU)
        if (handLandmarker == null) {
            visionUnavailable = true
            activeDelegate = null
        }
    }

    /**
     * Convertit la frame caméra en bitmap redressé et mis en miroir.
     *
     * Le miroir est appliqué ici plutôt que sur les coordonnées de sortie : MediaPipe
     * détermine aussi la latéralité (main gauche/droite) à partir de l'image, un miroir
     * appliqué après coup l'inverserait.
     */
    private fun buildUprightBitmap(imageProxy: ImageProxy): Bitmap? {
        val plane = imageProxy.planes.getOrNull(0) ?: return null
        val width = imageProxy.width
        val height = imageProxy.height
        if (width <= 0 || height <= 0) return null

        val pixelStride = plane.pixelStride
        if (pixelStride != 4) {
            // Format inattendu (la caméra doit être configurée en RGBA_8888).
            Log.w(TAG, "pixelStride=$pixelStride inattendu, frame ignorée")
            return null
        }

        // Le buffer peut être complété à droite : on alloue à la largeur réelle des lignes,
        // puis on recadre à la largeur utile.
        val paddedWidth = plane.rowStride / pixelStride
        var source = reusableSource
        if (source == null || source.width != paddedWidth || source.height != height) {
            source?.recycle()
            source = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            reusableSource = source
        }

        plane.buffer.rewind()
        source.copyPixelsFromBuffer(plane.buffer)

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        if (rotationDegrees != lastRotationDegrees) {
            lastRotationDegrees = rotationDegrees
            frameMatrix.reset()
            frameMatrix.postRotate(rotationDegrees.toFloat())
            frameMatrix.postScale(-1f, 1f, width / 2f, height / 2f)
        }

        return Bitmap.createBitmap(source, 0, 0, width, height, frameMatrix, true)
    }

    private fun updateFpsCounter() {
        val now = SystemClock.uptimeMillis()
        frameCount++
        if (now - fpsWindowStart >= 1000) {
            currentFps = frameCount
            frameCount = 0
            fpsWindowStart = now
        }
    }

    /** MediaPipe en mode LIVE_STREAM exige des horodatages strictement croissants. */
    private fun nextTimestamp(): Long {
        val now = SystemClock.uptimeMillis()
        lastTimestamp = if (now > lastTimestamp) now else lastTimestamp + 1
        return lastTimestamp
    }

    private fun processHandResult(result: HandLandmarkerResult) {
        consecutiveInferenceErrors = 0
        val now = SystemClock.uptimeMillis()
        if (inferenceStartedAt > 0L) {
            val latency = (now - inferenceStartedAt).toFloat()
            // Moyenne mobile exponentielle : évite un affichage de latence qui clignote.
            smoothedLatencyMs = if (smoothedLatencyMs == 0f) latency
            else smoothedLatencyMs * 0.8f + latency * 0.2f
        }

        val hands = result.landmarks()
        val raw = hands?.firstOrNull()

        if (raw.isNullOrEmpty()) {
            emitNoHand(now)
            return
        }

        val landmarks = ArrayList<Landmark>(raw.size)
        for (p in raw) landmarks.add(Landmark(p.x(), p.y(), p.z()))

        val metrics = HandGeometry.analyze(landmarks)
        val gestureResult = classifier.update(metrics, now)

        if (metrics == null) {
            emitNoHand(now)
            return
        }

        if (classifier.pinchJustClosed) pinchClickTick = now
        wasHandDetected = true

        val (mappedX, mappedY) = cursorMapper.map(metrics.pointerX, metrics.pointerY)
        val smoothed = cursorFilter.filter(mappedX, mappedY, metrics.depth, now)

        listener.onHandGestureUpdated(
            HandGestureState(
                isHandDetected = true,
                cursorX = smoothed.x.coerceIn(0f, 1f),
                cursorY = smoothed.y.coerceIn(0f, 1f),
                cursorZ = smoothed.z.coerceIn(0f, 1f),
                rawCursorX = metrics.pointerX,
                rawCursorY = metrics.pointerY,
                gesture = gestureResult.gesture,
                pinchStrength = gestureResult.pinchStrength,
                confidence = gestureResult.confidence,
                palmRotationDeg = metrics.palmRotationDeg,
                landmarks = landmarks,
                fps = currentFps,
                latencyMs = smoothedLatencyMs.toInt(),
                isSimulationMode = false,
                pinchClickTick = pinchClickTick
            )
        )
    }

    private fun emitNoHand(now: Long) {
        val result = classifier.update(null, now)
        if (result.gesture == GestureType.IDLE) cursorFilter.reset()

        // Sans main à suivre, l'état ne varie plus : on limite les publications à 4 Hz au lieu
        // de 30 Hz pour éviter des recompositions inutiles de toute l'interface.
        if (wasHandDetected) {
            wasHandDetected = false
        } else if (now - lastIdleEmit < 250L) {
            return
        }
        lastIdleEmit = now

        listener.onHandGestureUpdated(
            HandGestureState(
                isHandDetected = false,
                gesture = GestureType.IDLE,
                fps = currentFps,
                latencyMs = smoothedLatencyMs.toInt(),
                pinchClickTick = pinchClickTick
            )
        )
    }

    private fun emitVisionUnavailable() {
        if (visionUnavailableNotified) return
        visionUnavailableNotified = true
        listener.onHandGestureUpdated(
            HandGestureState(
                isHandDetected = false,
                gesture = GestureType.IDLE,
                fps = currentFps,
                isVisionUnavailable = true
            )
        )
    }

    fun close() {
        handLandmarker?.close()
        handLandmarker = null
        activeDelegate = null
        reusableSource?.recycle()
        reusableSource = null
    }
}
