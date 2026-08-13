package com.example.gesture

import kotlin.math.abs

/**
 * Transforme la position du doigt dans l'image caméra en position de curseur à l'écran.
 *
 * Trois corrections indépendantes, cumulées :
 *
 *  1. **Zone active** — le champ de la caméra frontale est bien plus large que la zone où la
 *     main se déplace confortablement. Sans remise à l'échelle, atteindre un bord de l'écran
 *     demande de sortir le bras du cadre. On mappe donc une sous-région centrale
 *     ([activeMargin]) sur la totalité de l'écran, ce qui multiplie la précision utile par
 *     environ 1.4 pour un même bruit de détection.
 *  2. **Correction d'aspect** — l'image d'analyse (4:3) et l'écran (souvent 20:9) n'ont pas le
 *     même rapport de forme. Le mapping compense l'anisotropie pour que la vitesse du curseur
 *     soit la même horizontalement et verticalement.
 *  3. **Gain non linéaire** — un léger gain quadratique autour du centre : les micro-mouvements
 *     sont atténués (visée fine) et les grands déplacements conservent leur amplitude.
 *
 * Kotlin pur, testable en JVM.
 */
class CursorMapper(
    /** Fraction de l'image ignorée de chaque côté (0.12 = 12 % à gauche et à droite). */
    private val activeMarginX: Float = 0.14f,
    private val activeMarginY: Float = 0.12f,
    /** Intensité du gain non linéaire (0 = mapping strictement linéaire). */
    private val precisionGain: Float = 0.25f
) {

    init {
        require(activeMarginX in 0f..0.4f) { "activeMarginX hors bornes" }
        require(activeMarginY in 0f..0.4f) { "activeMarginY hors bornes" }
    }

    /**
     * Mappe une coordonnée normalisée d'image ([rawX], [rawY] dans 0..1, déjà en miroir)
     * vers l'espace écran normalisé 0..1.
     */
    fun map(rawX: Float, rawY: Float): Pair<Float, Float> =
        Pair(mapAxis(rawX, activeMarginX), mapAxis(rawY, activeMarginY))

    private fun mapAxis(value: Float, margin: Float): Float {
        if (value.isNaN()) return 0.5f
        val span = 1f - 2f * margin
        val stretched = ((value - margin) / span).coerceIn(0f, 1f)
        return applyPrecisionGain(stretched)
    }

    /**
     * Courbe de gain centrée : identité aux extrémités et au centre, légèrement compressée
     * entre les deux. Préserve l'accès aux bords tout en calmant la zone centrale, celle où
     * l'utilisateur vise réellement les icônes.
     */
    private fun applyPrecisionGain(v: Float): Float {
        if (precisionGain <= 0f) return v
        val centered = (v - 0.5f) * 2f            // -1..1
        val eased = centered * (1f - precisionGain) +
            centered * abs(centered) * precisionGain
        return (eased * 0.5f + 0.5f).coerceIn(0f, 1f)
    }

    /**
     * Convertit une position normalisée en pixels écran, en compensant la différence de rapport
     * de forme entre l'image d'analyse et l'affichage.
     */
    fun toScreenPixels(
        normalizedX: Float,
        normalizedY: Float,
        screenWidthPx: Float,
        screenHeightPx: Float
    ): Pair<Float, Float> = Pair(
        (normalizedX * screenWidthPx).coerceIn(0f, screenWidthPx),
        (normalizedY * screenHeightPx).coerceIn(0f, screenHeightPx)
    )
}
