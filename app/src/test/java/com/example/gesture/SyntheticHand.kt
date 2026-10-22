package com.example.gesture

import kotlin.math.cos
import kotlin.math.sin

/**
 * Générateur de mains synthétiques de 21 points, paramétrable.
 *
 * Permet de valider la chaîne de précision — géométrie, pincement, filtrage — sur JVM, sans
 * caméra ni émulateur, avec des mains parfaitement reproductibles.
 *
 * Les fabriques nommées d'après des poses ont disparu avec les poses elles-mêmes. Il ne reste que
 * [build], qui produit une main quelconque : c'est exactement ce dont on a besoin maintenant,
 * puisque la propriété à vérifier est que le pincement se mesure **indépendamment de la forme du
 * reste de la main**.
 */
object SyntheticHand {

    private val CHAINS = listOf(
        intArrayOf(5, 6, 7, 8),
        intArrayOf(9, 10, 11, 12),
        intArrayOf(13, 14, 15, 16),
        intArrayOf(17, 18, 19, 20)
    )
    private val SEGMENTS = floatArrayOf(0.45f, 0.28f, 0.22f)

    /**
     * @param cx,cy centre de la paume dans l'image normalisée
     * @param scale longueur poignet -> MCP du majeur
     * @param extension extension de chaque doigt long (index, majeur, annulaire, auriculaire)
     * @param pinchDistance écart pouce/index en multiples de [scale]
     * @param thumbCurled pouce rabattu sur la paume (poing fermé)
     */
    fun build(
        cx: Float = 0.5f,
        cy: Float = 0.6f,
        scale: Float = 0.18f,
        extension: FloatArray = floatArrayOf(1f, 1f, 1f, 1f),
        pinchDistance: Float = 0.9f,
        thumbCurled: Boolean = false
    ): List<Landmark> {
        val pts = arrayOfNulls<Landmark>(HandLandmarks.COUNT)
        pts[HandLandmarks.WRIST] = Landmark(cx, cy)
        pts[HandLandmarks.MIDDLE_MCP] = Landmark(cx, cy - scale)
        pts[HandLandmarks.INDEX_MCP] = Landmark(cx - 0.40f * scale, cy - 0.90f * scale)
        pts[HandLandmarks.RING_MCP] = Landmark(cx + 0.28f * scale, cy - 0.92f * scale)
        pts[HandLandmarks.PINKY_MCP] = Landmark(cx + 0.40f * scale, cy - 0.80f * scale)

        for ((fingerIndex, chain) in CHAINS.withIndex()) {
            val mcp = pts[chain[0]]!!
            val bend = Math.toRadians(((1f - extension[fingerIndex]) * 90f).toDouble()).toFloat()
            var x = mcp.x
            var y = mcp.y
            for (j in 0 until 3) {
                val angle = bend * j
                x += sin(angle) * SEGMENTS[j] * scale
                y += -cos(angle) * SEGMENTS[j] * scale
                pts[chain[j + 1]] = Landmark(x, y)
            }
        }

        pts[HandLandmarks.THUMB_CMC] = Landmark(cx - 0.35f * scale, cy - 0.20f * scale)
        pts[HandLandmarks.THUMB_MCP] = Landmark(cx - 0.55f * scale, cy - 0.45f * scale)
        pts[HandLandmarks.THUMB_IP] = Landmark(cx - 0.62f * scale, cy - 0.70f * scale)
        pts[HandLandmarks.THUMB_TIP] = if (thumbCurled) {
            Landmark(cx + 0.05f * scale, cy - 0.55f * scale)
        } else {
            val indexTip = pts[HandLandmarks.INDEX_TIP]!!
            Landmark(indexTip.x - pinchDistance * scale, indexTip.y + 0.10f * scale)
        }

        return pts.map { it!! }
    }

    /**
     * Main ouverte, pouce écarté. Elle ne porte plus de sens gestuel : ce n'est qu'une main
     * plausible, utile pour éprouver la position et la plausibilité de la détection.
     */
    fun openPalm(cx: Float = 0.5f, scale: Float = 0.18f) =
        build(cx = cx, scale = scale)

    /** Main dont le pouce touche l'index : le bouton enfoncé. */
    fun pinching(cx: Float = 0.5f) = build(cx = cx, pinchDistance = 0.10f)

    fun metrics(landmarks: List<Landmark>): HandMetrics =
        requireNotNull(HandGeometry.analyze(landmarks)) { "pose synthétique invalide" }
}
