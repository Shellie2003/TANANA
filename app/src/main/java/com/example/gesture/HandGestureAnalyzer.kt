package com.example.gesture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.power.FrameGate
import com.example.power.PowerMode
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
    /**
     * Centre de la paume, lissé, dans le même espace écran que le curseur.
     *
     * Mesure le **déplacement de la main** sans être perturbé par les doigts. Le curseur vient du
     * bout de l'index, ce qui est juste pour viser ; mais pendant un pincement le pouce vient se
     * poser sur ce bout d'index, les deux extrémités se confondent dans l'image, et l'estimation
     * devient bruitée — exactement pendant un défilement, où le gain la multipliait.
     */
    val palmX: Float = 0.5f,
    val palmY: Float = 0.5f,
    /**
     * Distance pouce-index normalisée par la taille de paume, brute.
     *
     * C'est le seul bouton de l'application. Transmise brute à dessein : c'est
     * [com.example.gesture.PinchDetector] qui la juge, contre une ligne de base apprise, et un
     * lissage préalable lui retirerait justement la vivacité sur laquelle il se prononce.
     */
    val pinchRatio: Float = Float.MAX_VALUE,
    /** Plausibilité de la détection, 0..1. */
    val confidence: Float = 0f,
    val landmarks: List<Landmark> = emptyList(),
    /** Fréquence de traitement effective (images par seconde). */
    val fps: Int = 0,
    /** Latence d'inférence médiane, en millisecondes. */
    val latencyMs: Int = 0,
    val isSimulationMode: Boolean = false,
    /** Vrai si le modèle de vision n'a pas pu être chargé : le suivi de main est indisponible. */
    val isVisionUnavailable: Boolean = false,
    /** Cause de l'indisponibilité, affichée à l'écran pour rendre la panne diagnosticable. */
    val visionError: String? = null,
    /** Régime de cadence courant du moteur. */
    val powerMode: PowerMode = PowerMode.IDLE,
    /** Proportion d'images économisées par la régulation de cadence, entre 0 et 1. */
    val savingsRatio: Float = 0f
)

interface HandGestureListener {
    fun onHandGestureUpdated(state: HandGestureState)
}

/**
 * Analyseur CameraX exécutant MediaPipe Hand Landmarker.
 *
 * Il **ne reconnaît plus aucun geste**, et c'est le changement le plus profond de la refonte : il
 * publie une position et un écart pouce-index, rien d'autre. Le classifieur entraîné de MediaPipe
 * a été retiré avec le vocabulaire de poses qu'il servait — `gesture_recognizer.task` est redevenu
 * `hand_landmarker.task`, soit 554 Ko et une inférence par image en moins.
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

        /** Images de destination réutilisées à tour de rôle. Voir [rotatedPool]. */
        const val ROTATED_POOL_SIZE = 3

        /** Préférences du moteur de vision : le chemin d'initialisation qui a marché. */
        private const val VISION_PREFS = "vision"
        private const val KEY_DELEGATE_PATH = "delegate_path"
    }

    /** Écrit depuis le fil de préparation, lu depuis le thread d'analyse : d'où le @Volatile. */
    @Volatile
    private var landmarker: HandLandmarker? = null
    private var visionUnavailable = false
    private var visionError: String? = null
    private var activeDelegate: Delegate? = null

    /**
     * Incrémenté depuis le callback d'erreur de MediaPipe (thread d'inférence), lu depuis le
     * thread d'analyse : d'où le @Volatile.
     */
    @Volatile
    private var consecutiveInferenceErrors = 0

    private val cursorMapper = CursorMapper()
    /**
     * Filtre du curseur. Les deux coefficients sont issus d'une mesure, pas d'une intuition.
     *
     * Une coupure plus basse au repos et un `beta` plus fort donnent **à la fois** moins de bruit
     * en visée et une réponse plus rapide au départ : 0,95 pixel et 66 ms contre 1,34 pixel et
     * 99 ms pour le réglage précédent. Les deux grandeurs semblaient s'opposer, et elles
     * s'opposent effectivement à `beta` constant — c'est en le relevant que le compromis se
     * dissout, puisque la coupure remonte alors d'autant plus vite que la main part.
     */
    private val cursorFilter = OneEuroFilter3D(
        minCutoff = 0.4f,
        beta = 3.5f,
        predictionMs = CURSOR_PREDICTION_MS
    )

    /**
     * Filtre du centre de la paume, **sans prédiction** et plus lissé que le curseur.
     *
     * Deux différences, chacune pour une raison. Pas de prédiction : extrapoler sert à compenser la
     * latence perçue du pointage, alors qu'ici la grandeur utile est un déplacement cumulé — une
     * extrapolation n'y ajouterait que du dépassement, amplifié par le gain. Plus lissé : la paume
     * n'a pas à être réactive au pixel, elle a à être stable, et c'est le bruit qui se trouve
     * multiplié dans un défilement.
     */
    private val palmFilter = OneEuroFilter3D(
        minCutoff = 1.0f,
        beta = 0.6f,
        predictionMs = 0f
    )

    // Bitmap source réutilisé (jamais transmis à MediaPipe, donc sûr à muter).
    private var reusableSource: Bitmap? = null

    /**
     * Petit jeu d'images de destination, utilisées à tour de rôle.
     *
     * `Bitmap.createBitmap(source, …, matrix, true)` **allouait une image entière à chaque frame** :
     * 640 × 480 × 4 octets, trente fois par seconde, soit environ 36 Mo par seconde à ramasser. La
     * pause de ramasse-miettes qui s'ensuit est exactement le genre de saccade qu'on ressent comme
     * « ça rame » — et elle tombe au pire moment, puisqu'elle est provoquée par le flux de la
     * caméra lui-même.
     *
     * Trois images suffisent, et le raisonnement vaut d'être écrit : MediaPipe conserve l'image de
     * façon asynchrone, on ne peut donc pas n'en réutiliser qu'une. Mais le mode LIVE_STREAM ne
     * traite qu'une inférence à la fois, de sorte qu'au moment de réécrire une image on en a
     * publié deux autres depuis — soit une centaine de millisecondes, très au-delà de la durée
     * d'une inférence. Et si la marge était malgré tout prise de vitesse, la conséquence serait
     * une image d'entrée abîmée, donc des points de repère douteux pour une frame : une
     * dégradation, jamais un plantage.
     */
    private val rotatedPool = arrayOfNulls<Bitmap>(ROTATED_POOL_SIZE)
    private val rotatedCanvas = Canvas()
    private var rotatedIndex = 0
    private val frameMatrix = Matrix()

    /** Filtrage bilinéaire : équivalent du `filter = true` de l'ancienne allocation par frame. */
    private val framePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var lastRotationDegrees = Int.MIN_VALUE

    private var lastTimestamp = 0L
    private var frameCount = 0
    private var currentFps = 0
    private var fpsWindowStart = SystemClock.uptimeMillis()

    private var inferenceStartedAt = 0L
    private var smoothedLatencyMs = 0f

    /**
     * Régulateur de cadence. Analyser chaque image alors qu'aucune main n'est présente est le
     * poste de consommation dominant : le régulateur ramène la cadence à un balayage lent tant
     * qu'il n'y a rien à suivre, et la rétablit intégralement dès la première détection.
     */
    private val frameGate = FrameGate()

    /** Contexte énergétique, tenu à jour par le service ; lu depuis le thread d'analyse. */
    @Volatile
    var screenOn: Boolean = true

    @Volatile
    var powerSaveMode: Boolean = false

    @Volatile
    var batteryPercent: Int = -1

    /** Régime courant et proportion d'images économisées, pour l'affichage. */
    val powerMode: PowerMode get() = frameGate.mode
    val savingsRatio: Float get() = frameGate.savingsRatio()

    private var isSimulationActive = false
    private var wasHandDetected = false
    private var lastIdleEmit = 0L
    private var visionUnavailableNotified = false

    /**
     * **Volontairement pas d'initialisation dans le constructeur.**
     *
     * Elle y était, et c'était la cause des cinq secondes qu'il fallait pour activer le service :
     * `onStartCommand` tourne sur le thread principal, y construisait cet analyseur, et le
     * constructeur y enchaînait jusqu'à trois créations de `HandLandmarker`, chacune chargeant un
     * modèle de 7,8 Mo. Rien ne s'affichait pendant ce temps, la caméra ne démarrait qu'après, et
     * un service de premier plan qui bloque son thread principal aussi longtemps frôle l'ANR.
     *
     * [prepare] est donc appelée explicitement, depuis un fil d'arrière-plan, **en parallèle** du
     * démarrage de la caméra. Les images qui arrivent avant que le modèle soit prêt sont ignorées —
     * voir le `landmarker ?: return` de l'analyse — ce qui coûte quelques images au tout début et
     * rien ensuite.
     */
    @Volatile
    private var prepared = false

    /**
     * Charge le modèle. Idempotente, et à appeler hors du thread principal.
     *
     * Synchronisée parce que deux appels concurrents créeraient deux moteurs dont un fuirait : le
     * service la déclenche depuis son exécuteur, et un redémarrage de caméra pourrait la redemander.
     */
    @Synchronized
    fun prepare() {
        if (prepared) return
        prepared = true
        setupMediaPipe()
    }

    /**
     * Trois tentatives d'initialisation, de la plus rapide à la plus robuste.
     *
     * Charger le modèle par son chemin d'asset est le plus économe — MediaPipe le projette
     * directement en mémoire — mais dépend de la façon dont l'archive a empaqueté le fichier.
     * En dernier recours, le modèle est lu dans un ByteBuffer direct : cela coûte 7,8 Mo de
     * mémoire native mais fonctionne indépendamment de l'empaquetage.
     *
     * La cause de l'échec est conservée et remontée à l'interface : sans cela, un échec de
     * chargement n'apparaissait que dans logcat, inaccessible à l'utilisateur.
     */
    private fun setupMediaPipe() {
        val all = listOf(
            Triple(Delegate.GPU, false, "GPU / asset"),
            Triple(Delegate.CPU, false, "CPU / asset"),
            Triple(Delegate.CPU, true, "CPU / mémoire")
        )

        // Le chemin qui a marché la dernière fois est essayé d'abord, et c'est la seconde moitié du
        // gain au démarrage. La cascade existe parce qu'on ne peut pas savoir *à l'avance* lequel
        // des trois fonctionne sur un appareil donné — mais on peut le savoir **après**, et il ne
        // change pas. La mesurer une fois puis s'en souvenir ramène trois chargements de modèle à
        // un seul à chaque démarrage suivant, échec du GPU compris : sur un appareil dont le
        // delegate GPU ne tient pas, c'était l'essai le plus coûteux qui était rejoué chaque fois.
        val attempts = remembered()
            ?.let { last -> all.sortedByDescending { it.third == last } }
            ?: all

        val failures = mutableListOf<String>()
        for ((delegate, useBuffer, label) in attempts) {
            val result = createLandmarker(delegate, useBuffer)
            result.onSuccess { built ->
                landmarker = built
                activeDelegate = delegate
                remember(label)
                Log.i(TAG, "HandLandmarker initialisé ($label)")
                return
            }
            result.onFailure { error ->
                Log.w(TAG, "Échec d'initialisation ($label): ${error.message}")
                failures += "$label: ${error.message ?: error::class.java.simpleName}"
            }
        }

        visionUnavailable = true
        visionError = buildString {
            append(deviceDiagnostics())
            append('\n')
            append(failures.lastOrNull() ?: "cause inconnue")
        }
        Log.e(TAG, "Suivi de main indisponible. ${deviceDiagnostics()} | ${failures.joinToString(" | ")}")
    }

    /**
     * Chemin d'initialisation retenu la dernière fois, s'il y en a un.
     *
     * Rien n'est invalidé explicitement, et ce n'est pas un oubli : une valeur devenue fausse —
     * mise à jour système qui casse le delegate GPU, par exemple — ne coûte qu'un essai raté avant
     * que la cascade reprenne son cours et réécrive la préférence. Un cache dont l'erreur se
     * corrige d'elle-même n'a pas besoin d'être invalidé.
     */
    private fun remembered(): String? = runCatching {
        context.getSharedPreferences(VISION_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DELEGATE_PATH, null)
    }.getOrNull()

    private fun remember(label: String) {
        runCatching {
            context.getSharedPreferences(VISION_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_DELEGATE_PATH, label)
                .apply()
        }
    }

    /**
     * Mesures de l'appareil jointes au message d'erreur.
     *
     * La taille de page est la donnée décisive : une bibliothèque native alignée sur 4 Ko ne se
     * charge pas sur un système en pages de 16 Ko, et rien d'autre ne permet de distinguer ce cas
     * d'un simple échec d'initialisation. L'ABI et la version d'Android complètent le tableau.
     */
    private fun deviceDiagnostics(): String {
        val pageSizeKo = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) / 1024 }.getOrNull()
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?"
        return "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · $abi · " +
            "pages ${pageSizeKo?.toString() ?: "?"} Ko · ${Build.MANUFACTURER} ${Build.MODEL}"
    }

    /**
     * Lit le modèle dans un ByteBuffer direct. MediaPipe exige un buffer direct : un
     * HeapByteBuffer serait rejeté par la couche native.
     */
    private fun readModelIntoDirectBuffer(): ByteBuffer {
        val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size).apply {
            order(ByteOrder.nativeOrder())
            put(bytes)
            rewind()
        }
    }

    /**
     * Construit le détecteur de points de repère.
     *
     * Retour à `hand_landmarker.task` seul. Le paquet `gesture_recognizer.task` y ajoutait un
     * classifieur de poses entraîné, qui n'a plus d'emploi : il répondait par une étiquette parmi
     * huit, et nous n'avons plus de vocabulaire à étiqueter. Les deux mesures qui subsistent — la
     * position et l'écart pouce-index — se lisent dans les points de repère, et nulle part
     * ailleurs.
     */
    private fun createLandmarker(delegate: Delegate, useBuffer: Boolean): Result<HandLandmarker> =
        runCatching {
            val baseOptionsBuilder = BaseOptions.builder().setDelegate(delegate)
            if (useBuffer) {
                baseOptionsBuilder.setModelAssetBuffer(readModelIntoDirectBuffer())
            } else {
                baseOptionsBuilder.setModelAssetPath(MODEL_ASSET)
            }

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptionsBuilder.build())
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
        }

    fun setSimulationMode(enabled: Boolean) {
        isSimulationActive = enabled
        cursorFilter.reset()
        palmFilter.reset()
    }

    /**
     * Pilotage simulé, sans caméra : une position et un état de pincement.
     *
     * La signature a perdu ses poses — il n'y a plus qu'un bouton à simuler, ce qui rend la
     * simulation fidèle plutôt qu'approximative. L'écart simulé est pris de part et d'autre de la
     * course d'une main ordinaire, pour que le détecteur le juge comme il jugerait une vraie main.
     */
    fun updateSimulatedPosition(
        xNormalized: Float,
        yNormalized: Float,
        isPinching: Boolean
    ) {
        if (!isSimulationActive) return

        val now = nextTimestamp()
        val smoothed = cursorFilter.filter(xNormalized, yNormalized, 0.5f, now)

        listener.onHandGestureUpdated(
            HandGestureState(
                isHandDetected = true,
                cursorX = smoothed.x.coerceIn(0f, 1f),
                cursorY = smoothed.y.coerceIn(0f, 1f),
                cursorZ = 0.5f,
                rawCursorX = xNormalized,
                rawCursorY = yNormalized,
                palmX = smoothed.x.coerceIn(0f, 1f),
                palmY = smoothed.y.coerceIn(0f, 1f),
                pinchRatio = if (isPinching) 0.10f else 0.85f,
                confidence = 1f,
                fps = 60,
                latencyMs = 0,
                isSimulationMode = true
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

            // Le filtrage intervient avant toute conversion : une image ignorée ne coûte que sa
            // fermeture, ni bitmap, ni inférence.
            if (!frameGate.shouldProcess(
                    SystemClock.uptimeMillis(), screenOn, powerSaveMode, batteryPercent
                )
            ) {
                return
            }

            demoteToCpuIfGpuFailing()

            val engine = landmarker ?: return
            val bitmap = buildUprightBitmap(imageProxy) ?: return

            inferenceStartedAt = SystemClock.uptimeMillis()
            engine.detectAsync(BitmapImageBuilder(bitmap).build(), nextTimestamp())
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
        landmarker?.close()
        activeDelegate = Delegate.CPU
        consecutiveInferenceErrors = 0
        landmarker = createLandmarker(Delegate.CPU, useBuffer = false).getOrNull()
            ?: createLandmarker(Delegate.CPU, useBuffer = true).getOrNull()
        if (landmarker == null) {
            visionUnavailable = true
            visionError = "repli CPU impossible après défaillance du GPU"
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
        // Dimensions après rotation : les quarts de tour échangent largeur et hauteur.
        val quarterTurn = rotationDegrees == 90 || rotationDegrees == 270
        val outWidth = if (quarterTurn) height else width
        val outHeight = if (quarterTurn) width else height

        if (rotationDegrees != lastRotationDegrees) {
            lastRotationDegrees = rotationDegrees
            frameMatrix.reset()
            // **L'ordre est celui d'origine, et il n'est pas interchangeable.** Tourner puis
            // réfléchir ne donne pas la même orientation que réfléchir puis tourner : les deux
            // résultats sont symétriques l'un de l'autre, et une main gauche deviendrait une main
            // droite. MediaPipe déduisant la latéralité de l'image, l'erreur serait invisible à la
            // compilation et fausserait le suivi.
            frameMatrix.postRotate(rotationDegrees.toFloat())
            frameMatrix.postScale(-1f, 1f, width / 2f, height / 2f)

            // `Bitmap.createBitmap(source, …, matrix, true)` calculait lui-même les bornes de
            // l'image transformée et l'y recadrait. En dessinant sur un canevas, ce recadrage est
            // à notre charge : sans lui, un quart de tour placerait l'image hors du cadre.
            val corners = floatArrayOf(
                0f, 0f, width.toFloat(), 0f, width.toFloat(), height.toFloat(), 0f, height.toFloat()
            )
            frameMatrix.mapPoints(corners)
            var minX = corners[0]
            var minY = corners[1]
            for (i in corners.indices step 2) {
                if (corners[i] < minX) minX = corners[i]
                if (corners[i + 1] < minY) minY = corners[i + 1]
            }
            frameMatrix.postTranslate(-minX, -minY)
        }

        var destination = rotatedPool[rotatedIndex]
        if (destination == null || destination.width != outWidth || destination.height != outHeight) {
            destination?.recycle()
            destination = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
            rotatedPool[rotatedIndex] = destination
        }
        rotatedIndex = (rotatedIndex + 1) % rotatedPool.size

        rotatedCanvas.setBitmap(destination)
        rotatedCanvas.drawBitmap(source, frameMatrix, framePaint)
        // Le canevas garde une référence sur l'image : la relâcher évite de la retenir plus
        // longtemps que nécessaire, et rend la réutilisation lisible.
        rotatedCanvas.setBitmap(null)
        return destination
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
        if (metrics == null) {
            emitNoHand(now)
            return
        }

        wasHandDetected = true
        // Réarme la pleine cadence : une main est là, la latence prime désormais sur l'énergie.
        frameGate.onHandDetected(now)

        val (mappedX, mappedY) = cursorMapper.map(metrics.pointerX, metrics.pointerY)
        val smoothed = cursorFilter.filter(mappedX, mappedY, metrics.depth, now)

        // La paume passe par la même mise à l'échelle que le curseur : les déplacements des deux
        // sont alors dans la même unité, et le gain du défilement garde la valeur pour laquelle il
        // a été réglé.
        val (palmMappedX, palmMappedY) = cursorMapper.map(metrics.palmX, metrics.palmY)
        val palmSmoothed = palmFilter.filter(palmMappedX, palmMappedY, metrics.depth, now)

        listener.onHandGestureUpdated(
            HandGestureState(
                isHandDetected = true,
                cursorX = smoothed.x.coerceIn(0f, 1f),
                cursorY = smoothed.y.coerceIn(0f, 1f),
                cursorZ = smoothed.z.coerceIn(0f, 1f),
                rawCursorX = metrics.pointerX,
                rawCursorY = metrics.pointerY,
                palmX = palmSmoothed.x.coerceIn(0f, 1f),
                palmY = palmSmoothed.y.coerceIn(0f, 1f),
                pinchRatio = metrics.pinchRatio,
                confidence = metrics.quality,
                powerMode = frameGate.mode,
                savingsRatio = frameGate.savingsRatio(),
                landmarks = landmarks,
                fps = currentFps,
                latencyMs = smoothedLatencyMs.toInt(),
                isSimulationMode = false,
            )
        )
    }

    private fun emitNoHand(now: Long) {
        cursorFilter.reset()
        palmFilter.reset()

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
                fps = currentFps,
                latencyMs = smoothedLatencyMs.toInt(),
                powerMode = frameGate.mode,
                savingsRatio = frameGate.savingsRatio()
            )
        )
    }

    private fun emitVisionUnavailable() {
        if (visionUnavailableNotified) return
        visionUnavailableNotified = true
        listener.onHandGestureUpdated(
            HandGestureState(
                isHandDetected = false,
                fps = currentFps,
                isVisionUnavailable = true,
                visionError = visionError
            )
        )
    }

    fun close() {
        frameGate.reset()
        landmarker?.close()
        landmarker = null
        activeDelegate = null
        reusableSource?.recycle()
        reusableSource = null
        for (i in rotatedPool.indices) {
            rotatedPool[i]?.recycle()
            rotatedPool[i] = null
        }
    }
}
