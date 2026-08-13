package com.example.gesture

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Point de repère normalisé de la main (0.0 -> 1.0 en X/Y, Z relatif au poignet).
 *
 * Volontairement indépendant d'Android : toute la géométrie de précision est du Kotlin pur,
 * donc testable en JVM sans émulateur.
 */
data class Landmark(val x: Float, val y: Float, val z: Float = 0f)

/** Index des 21 points de repère du modèle MediaPipe Hand Landmarker. */
object HandLandmarks {
    const val WRIST = 0
    const val THUMB_CMC = 1
    const val THUMB_MCP = 2
    const val THUMB_IP = 3
    const val THUMB_TIP = 4
    const val INDEX_MCP = 5
    const val INDEX_PIP = 6
    const val INDEX_DIP = 7
    const val INDEX_TIP = 8
    const val MIDDLE_MCP = 9
    const val MIDDLE_PIP = 10
    const val MIDDLE_DIP = 11
    const val MIDDLE_TIP = 12
    const val RING_MCP = 13
    const val RING_PIP = 14
    const val RING_DIP = 15
    const val RING_TIP = 16
    const val PINKY_MCP = 17
    const val PINKY_PIP = 18
    const val PINKY_DIP = 19
    const val PINKY_TIP = 20

    const val COUNT = 21

    /** Chaînes articulaires (MCP, PIP, DIP, TIP) des quatre doigts longs. */
    val LONG_FINGERS = listOf(
        intArrayOf(INDEX_MCP, INDEX_PIP, INDEX_DIP, INDEX_TIP),
        intArrayOf(MIDDLE_MCP, MIDDLE_PIP, MIDDLE_DIP, MIDDLE_TIP),
        intArrayOf(RING_MCP, RING_PIP, RING_DIP, RING_TIP),
        intArrayOf(PINKY_MCP, PINKY_PIP, PINKY_DIP, PINKY_TIP)
    )
}

/**
 * Mesures géométriques normalisées extraites d'une main.
 *
 * Toutes les distances sont exprimées en multiples de [palmScale], ce qui rend les seuils
 * invariants à la distance de la main par rapport à la caméra : un pincement reste un
 * pincement que la main soit à 20 cm ou à 80 cm de l'objectif.
 */
data class HandMetrics(
    /** Référence d'échelle de la main (moyenne poignet->majeur et largeur de paume). */
    val palmScale: Float,
    /** Distance pouce/index normalisée par [palmScale]. */
    val pinchRatio: Float,
    /** Extension de chaque doigt long (index, majeur, annulaire, auriculaire), 0 = plié, 1 = tendu. */
    val fingerExtension: FloatArray,
    /** Extension du pouce, 0 = replié sur la paume, 1 = tendu. */
    val thumbExtension: Float,
    /** Rotation de la paume en degrés (0 = doigts vers la droite de l'image). */
    val palmRotationDeg: Float,
    /** Position d'ancrage du curseur (bout d'index stabilisé). */
    val pointerX: Float,
    val pointerY: Float,
    /** Profondeur estimée : 0 = main proche de la caméra, 1 = main éloignée. */
    val depth: Float,
    /** Qualité géométrique de la détection (0..1), sert à écarter les poses aberrantes. */
    val quality: Float
) {
    val extendedFingerCount: Int
        get() = fingerExtension.count { it > 0.6f }

    // FloatArray n'a pas d'égalité structurelle : on la fournit explicitement pour que
    // HandMetrics reste comparable (utile en test et pour la déduplication d'état Compose).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HandMetrics) return false
        return palmScale == other.palmScale &&
            pinchRatio == other.pinchRatio &&
            fingerExtension.contentEquals(other.fingerExtension) &&
            thumbExtension == other.thumbExtension &&
            palmRotationDeg == other.palmRotationDeg &&
            pointerX == other.pointerX &&
            pointerY == other.pointerY &&
            depth == other.depth &&
            quality == other.quality
    }

    override fun hashCode(): Int {
        var result = palmScale.hashCode()
        result = 31 * result + pinchRatio.hashCode()
        result = 31 * result + fingerExtension.contentHashCode()
        result = 31 * result + thumbExtension.hashCode()
        result = 31 * result + palmRotationDeg.hashCode()
        result = 31 * result + pointerX.hashCode()
        result = 31 * result + pointerY.hashCode()
        result = 31 * result + depth.hashCode()
        result = 31 * result + quality.hashCode()
        return result
    }
}

/** Analyse géométrique des 21 points de repère. Sans allocation superflue, appelée à chaque frame. */
object HandGeometry {

    private const val EPSILON = 1e-5f

    fun distance(a: Landmark, b: Landmark): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    fun distance3D(a: Landmark, b: Landmark): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Angle en degrés au sommet [vertex] entre les segments [vertex]->[a] et [vertex]->[b].
     * 180° = articulation parfaitement tendue, 0° = totalement repliée.
     */
    fun jointAngleDeg(a: Landmark, vertex: Landmark, b: Landmark): Float {
        val v1x = a.x - vertex.x
        val v1y = a.y - vertex.y
        val v2x = b.x - vertex.x
        val v2y = b.y - vertex.y

        val n1 = sqrt(v1x * v1x + v1y * v1y)
        val n2 = sqrt(v2x * v2x + v2y * v2y)
        if (n1 < EPSILON || n2 < EPSILON) return 180f

        val cos = ((v1x * v2x + v1y * v2y) / (n1 * n2)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cos).toDouble()).toFloat()
    }

    /**
     * Échelle de la main : moyenne de la longueur de paume (poignet -> MCP majeur) et de sa
     * largeur (MCP index -> MCP auriculaire). Combiner les deux rend la mesure stable même
     * quand la main pivote, alors qu'une seule des deux s'effondre en projection.
     */
    fun palmScale(lm: List<Landmark>): Float {
        val length = distance(lm[HandLandmarks.WRIST], lm[HandLandmarks.MIDDLE_MCP])
        val width = distance(lm[HandLandmarks.INDEX_MCP], lm[HandLandmarks.PINKY_MCP])
        return max((length + width) * 0.5f, 0.02f)
    }

    /**
     * Extension d'un doigt sur 0..1, combinant deux signaux indépendants :
     *  - la somme des angles articulaires PIP et DIP (robuste à la rotation de la main) ;
     *  - la distance bout-de-doigt / MCP normalisée (robuste quand le doigt pointe vers la caméra,
     *    cas où les angles 2D s'effondrent).
     * La fusion des deux évite les faux "poing" quand la main est vue de face.
     */
    fun fingerExtension(lm: List<Landmark>, chain: IntArray, palmScale: Float): Float {
        val mcp = lm[chain[0]]
        val pip = lm[chain[1]]
        val dip = lm[chain[2]]
        val tip = lm[chain[3]]

        val anglePip = jointAngleDeg(mcp, pip, dip)
        val angleDip = jointAngleDeg(pip, dip, tip)
        // 90° (replié) -> 0 ; 170° (tendu) -> 1
        val angleScore = (((anglePip + angleDip) * 0.5f) - 95f) / 70f

        val reach = distance(mcp, tip) / palmScale
        // 0.35 palme (replié) -> 0 ; 0.95 palme (tendu) -> 1
        val reachScore = (reach - 0.35f) / 0.60f

        return (0.55f * angleScore + 0.45f * reachScore).coerceIn(0f, 1f)
    }

    /**
     * Extension du pouce : mesurée par l'écart entre le bout du pouce et le MCP de l'auriculaire,
     * qui s'effondre nettement quand le pouce se rabat sur la paume (fermeture du poing).
     */
    fun thumbExtension(lm: List<Landmark>, palmScale: Float): Float {
        val spread = distance(lm[HandLandmarks.THUMB_TIP], lm[HandLandmarks.PINKY_MCP]) / palmScale
        val angle = jointAngleDeg(
            lm[HandLandmarks.THUMB_MCP],
            lm[HandLandmarks.THUMB_IP],
            lm[HandLandmarks.THUMB_TIP]
        )
        val spreadScore = (spread - 0.75f) / 0.75f
        val angleScore = (angle - 120f) / 50f
        return (0.6f * spreadScore + 0.4f * angleScore).coerceIn(0f, 1f)
    }

    /**
     * Contrôle de plausibilité : une vraie main a des proportions bornées et des points de repère
     * cohérents. Une valeur basse signale une détection douteuse (main partiellement hors champ,
     * objet confondu avec une main) et permet de ne pas déclencher d'action sur du bruit.
     */
    fun quality(lm: List<Landmark>, palmScale: Float): Float {
        if (lm.size < HandLandmarks.COUNT) return 0f

        var inFrame = 0
        for (p in lm) {
            if (p.x in -0.02f..1.02f && p.y in -0.02f..1.02f) inFrame++
        }
        val frameScore = inFrame.toFloat() / lm.size

        // La paume doit rester dans une plage d'aspect réaliste.
        val length = distance(lm[HandLandmarks.WRIST], lm[HandLandmarks.MIDDLE_MCP])
        val width = distance(lm[HandLandmarks.INDEX_MCP], lm[HandLandmarks.PINKY_MCP])
        val aspect = if (width > EPSILON) length / width else 0f
        val aspectScore = when {
            aspect < 0.35f || aspect > 3.2f -> 0.2f
            aspect < 0.6f || aspect > 2.2f -> 0.7f
            else -> 1f
        }

        // Une main trop petite dans l'image est trop bruitée pour piloter un curseur au pixel.
        val scaleScore = ((palmScale - 0.04f) / 0.10f).coerceIn(0.2f, 1f)

        return (frameScore * aspectScore * scaleScore).coerceIn(0f, 1f)
    }

    /**
     * Ancre du curseur : barycentre pondéré du bout de l'index (précis mais bruité) et de la
     * phalange DIP (stable). 75/25 conserve la finesse du pointage tout en divisant l'amplitude
     * du tremblement physiologique par près de deux.
     */
    fun pointerAnchor(lm: List<Landmark>): Pair<Float, Float> {
        val tip = lm[HandLandmarks.INDEX_TIP]
        val dip = lm[HandLandmarks.INDEX_DIP]
        return Pair(
            tip.x * 0.75f + dip.x * 0.25f,
            tip.y * 0.75f + dip.y * 0.25f
        )
    }

    /** Rotation de la paume, en degrés, dans le plan image. */
    fun palmRotationDeg(lm: List<Landmark>): Float {
        val wrist = lm[HandLandmarks.WRIST]
        val middle = lm[HandLandmarks.MIDDLE_MCP]
        return Math.toDegrees(
            atan2((middle.y - wrist.y).toDouble(), (middle.x - wrist.x).toDouble())
        ).toFloat()
    }

    /**
     * Profondeur normalisée déduite de la taille apparente de la paume.
     * 0 = main collée à l'objectif, 1 = main au fond du champ.
     */
    fun depth(palmScale: Float): Float =
        (1f - (palmScale - 0.05f) / 0.25f).coerceIn(0f, 1f)

    /** Extrait toutes les mesures d'une pose de main. Retourne null si la pose est inexploitable. */
    fun analyze(lm: List<Landmark>): HandMetrics? {
        if (lm.size < HandLandmarks.COUNT) return null
        for (p in lm) {
            if (p.x.isNaN() || p.y.isNaN()) return null
        }

        val palmScale = palmScale(lm)
        val extensions = FloatArray(HandLandmarks.LONG_FINGERS.size) { i ->
            fingerExtension(lm, HandLandmarks.LONG_FINGERS[i], palmScale)
        }
        val (px, py) = pointerAnchor(lm)

        return HandMetrics(
            palmScale = palmScale,
            pinchRatio = distance(lm[HandLandmarks.THUMB_TIP], lm[HandLandmarks.INDEX_TIP]) / palmScale,
            fingerExtension = extensions,
            thumbExtension = thumbExtension(lm, palmScale),
            palmRotationDeg = palmRotationDeg(lm),
            pointerX = px,
            pointerY = py,
            depth = depth(palmScale),
            quality = quality(lm, palmScale)
        )
    }

    /** Utilitaire : borne une valeur en neutralisant les NaN. */
    fun safeClamp(value: Float, minValue: Float, maxValue: Float, fallback: Float): Float =
        if (value.isNaN() || value.isInfinite()) fallback else min(max(value, minValue), maxValue)

    /** Différence angulaire signée la plus courte entre deux angles en degrés. */
    fun angleDeltaDeg(from: Float, to: Float): Float {
        var d = (to - from) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return if (abs(d) < EPSILON) 0f else d
    }
}
