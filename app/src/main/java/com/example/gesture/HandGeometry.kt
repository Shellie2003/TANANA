package com.example.gesture

import kotlin.math.max
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
}

/**
 * Mesures normalisées extraites d'une main. **Deux signaux, et rien d'autre.**
 *
 * La liste est courte parce que c'est le fond de la refonte. Elle comportait l'extension de
 * chacun des cinq doigts, le décompte des doigts tendus, la rotation de la paume — tout ce qu'il
 * faut pour reconnaître une forme de main. Plus rien de cela n'est calculé, parce que plus rien
 * de cela n'est lu : **la forme de la main n'est plus jamais interprétée.**
 *
 * Ne subsistent que les deux mesures dont la fiabilité est établie :
 *
 *  - **la position** ([pointerX], [pointerY]), qui pilote le curseur, et le centre de la paume
 *    ([palmX], [palmY]), qui mesure le déplacement de la main sans se laisser perturber par les
 *    doigts ;
 *  - **l'écart pouce-index** ([pinchRatio]), qui est le seul bouton de l'application.
 *
 * Ce ne sont pas les deux plus commodes, ce sont les deux plus robustes. L'extension d'un doigt
 * replié se déduit de points de repère que le modèle devine, puisqu'il ne les voit pas : on
 * raisonnait donc sur une inférence d'une inférence. L'écart pouce-index, lui, relie les deux
 * points les mieux vus quand la main fait face à la caméra, et c'est une distance continue, pas
 * une classification à huit issues.
 *
 * Toutes les distances sont exprimées en multiples de [palmScale], ce qui rend les seuils
 * invariants à la distance de la main par rapport à la caméra : un pincement reste un pincement
 * que la main soit à 20 cm ou à 80 cm de l'objectif.
 */
data class HandMetrics(
    /** Référence d'échelle de la main (moyenne poignet->majeur et largeur de paume). */
    val palmScale: Float,
    /** Distance pouce/index normalisée par [palmScale]. Le seul bouton de l'application. */
    val pinchRatio: Float,
    /** Position d'ancrage du curseur (bout d'index stabilisé). */
    val pointerX: Float,
    val pointerY: Float,
    /**
     * Centre de la paume : le point de la main que le pincement ne déplace **pas**.
     *
     * C'est la mesure qui manquait. Le curseur vient du bout de l'index, ce qui est juste pour
     * viser — c'est avec lui qu'on pointe. Mais pendant un pincement, le pouce vient se poser sur
     * ce même bout d'index : les deux extrémités se confondent dans l'image, et l'estimation du
     * bout d'index devient bruitée **précisément quand le pincement est tenu**, donc précisément
     * pendant un défilement. Le gain du défilement multipliait alors ce bruit avec l'intention.
     *
     * La paume, elle, ne bouge pas quand les doigts se ferment, et c'est le groupe de points le
     * mieux suivi de la main. Le défilement s'appuie donc sur elle : il n'y a plus à maintenir une
     * géométrie de doigts stable en déplaçant le bras, seulement à déplacer le bras.
     */
    val palmX: Float,
    val palmY: Float,
    /** Profondeur estimée : 0 = main proche de la caméra, 1 = main éloignée. */
    val depth: Float,
    /** Plausibilité de la détection (0..1) : écarte le bruit et les objets pris pour des mains. */
    val quality: Float
)

/**
 * Analyse des 21 points de repère. Sans allocation, appelée à chaque image.
 *
 * Le fichier a perdu les deux tiers de son contenu, et ce n'est pas un nettoyage : c'est le
 * résultat. Il contenait de quoi estimer l'extension de chaque doigt à partir d'angles
 * articulaires et de portées normalisées — une mécanique soignée, et inutile, puisque la forme de
 * la main n'est plus lue. Ce qui reste est ce qui se mesure sans rien deviner.
 */
object HandGeometry {

    private const val EPSILON = 1e-5f

    fun distance(a: Landmark, b: Landmark): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
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

    /**
     * Centre de la paume : barycentre du poignet et des quatre articulations métacarpiennes.
     *
     * Cinq points, et aucun d'eux ne bouge quand les doigts se ferment. C'est ce qui en fait la
     * mesure de référence d'un **déplacement de main**, là où le bout d'un doigt mêle le
     * déplacement de la main et le mouvement du doigt.
     */
    fun palmAnchor(lm: List<Landmark>): Pair<Float, Float> {
        var x = lm[HandLandmarks.WRIST].x
        var y = lm[HandLandmarks.WRIST].y
        for (index in intArrayOf(
            HandLandmarks.INDEX_MCP,
            HandLandmarks.MIDDLE_MCP,
            HandLandmarks.RING_MCP,
            HandLandmarks.PINKY_MCP
        )) {
            x += lm[index].x
            y += lm[index].y
        }
        return Pair(x / 5f, y / 5f)
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
        val (px, py) = pointerAnchor(lm)
        val (cx, cy) = palmAnchor(lm)

        return HandMetrics(
            palmScale = palmScale,
            pinchRatio = distance(lm[HandLandmarks.THUMB_TIP], lm[HandLandmarks.INDEX_TIP]) / palmScale,
            pointerX = px,
            pointerY = py,
            palmX = cx,
            palmY = cy,
            depth = depth(palmScale),
            quality = quality(lm, palmScale)
        )
    }

}
