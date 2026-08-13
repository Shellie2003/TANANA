package com.example.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/**
 * Tests quantitatifs du filtrage du curseur.
 *
 * Les seuils correspondent à la calibration retenue (minCutoff 1.6 Hz, beta 1.2, prédiction
 * 40 ms) et servent de garde-fou : toute régression de lissage ou de latence les fera échouer.
 */
class OneEuroFilterTest {

    private companion object {
        const val FRAME_MS = 33L
    }

    @Test
    fun `premiere valeur traversee sans modification`() {
        val filter = OneEuroFilter3D()
        assertEquals(0.5f, filter.filter(0.5f, 0.5f, 0.5f, 0L).x, 1e-6f)
    }

    @Test
    fun `tremblement statique attenue`() {
        val filter = OneEuroFilter3D(predictionMs = 0f, restThreshold = 0f)
        val random = Random(42)
        var t = 0L
        var maxDeviation = 0f

        repeat(200) { i ->
            t += FRAME_MS
            val noisy = 0.5f + (random.nextFloat() - 0.5f) * 0.02f  // bruit crête à crête de 2 %
            val out = filter.filter(noisy, 0.5f, 0.5f, t)
            if (i > 40) maxDeviation = maxOf(maxDeviation, abs(out.x - 0.5f))
        }

        // Le bruit d'entrée atteint ±1 % ; en sortie on exige moins de 0.7 %.
        assertTrue("déviation résiduelle $maxDeviation", maxDeviation < 0.007f)
    }

    @Test
    fun `zone morte fige completement le curseur au repos`() {
        val filter = OneEuroFilter3D(predictionMs = 0f, restThreshold = 0.004f)
        val random = Random(1)
        var t = 0L
        filter.filter(0.5f, 0.5f, 0.5f, t)

        repeat(50) {
            t += FRAME_MS
            val out = filter.filter(0.5f + (random.nextFloat() - 0.5f) * 0.001f, 0.5f, 0.5f, t)
            assertEquals(0.5f, out.x, 0.004f)
        }
    }

    @Test
    fun `la prediction reduit nettement le trainage`() {
        val lagWithout = rampLag(predictionMs = 0f)
        val lagWith = rampLag(predictionMs = 40f)

        assertTrue("sans prédiction: $lagWithout", lagWithout < 0.026f)
        assertTrue("avec prédiction: $lagWith vs $lagWithout", lagWith < lagWithout * 0.6f)
        assertTrue("traînage résiduel $lagWith", lagWith < 0.015f)
    }

    /** Erreur de suivi en régime établi sur une rampe à 0.3 unité/seconde. */
    private fun rampLag(predictionMs: Float): Float {
        val filter = OneEuroFilter3D(predictionMs = predictionMs, restThreshold = 0f)
        var t = 0L
        var position = 0.1f
        var lag = 0f
        repeat(60) { i ->
            t += FRAME_MS
            position += 0.3f * (FRAME_MS / 1000f)
            val out = filter.filter(position, 0.5f, 0.5f, t)
            if (i > 25) lag = abs(out.x - position)
        }
        return lag
    }

    /**
     * L'amortissement à la décélération évite que le curseur ne dépasse la cible quand la main
     * s'immobilise — l'instant précis où l'utilisateur vise.
     */
    @Test
    fun `depassement borne a l arret`() {
        val filter = OneEuroFilter3D(predictionMs = 40f, restThreshold = 0f)
        var t = 0L
        var position = 0.2f

        repeat(20) {
            t += FRAME_MS
            position += 2.0f * (FRAME_MS / 1000f)
            filter.filter(position, 0.5f, 0.5f, t)
        }

        var overshoot = 0f
        repeat(20) {
            t += FRAME_MS
            overshoot = maxOf(overshoot, filter.filter(position, 0.5f, 0.5f, t).x - position)
        }

        assertTrue("dépassement de $overshoot après arrêt brutal", overshoot < 0.03f)
    }

    @Test
    fun `valeurs non finies ignorees`() {
        val filter = OneEuroFilter3D()
        filter.filter(0.5f, 0.5f, 0.5f, 0L)
        val out = filter.filter(Float.NaN, Float.POSITIVE_INFINITY, 0.5f, FRAME_MS)
        assertFalse(out.x.isNaN())
        assertFalse(out.y.isNaN())
    }

    @Test
    fun `remise a zero repart de la nouvelle position sans saut`() {
        val filter = OneEuroFilter3D()
        var t = 0L
        repeat(20) { t += FRAME_MS; filter.filter(0.2f, 0.2f, 0.5f, t) }

        filter.reset()
        t += FRAME_MS
        val out = filter.filter(0.9f, 0.9f, 0.5f, t)
        assertEquals(0.9f, out.x, 1e-6f)
    }

    @Test
    fun `intervalle de temps nul ne produit pas de division par zero`() {
        val filter = OneEuroFilter3D()
        filter.filter(0.5f, 0.5f, 0.5f, 100L)
        val out = filter.filter(0.6f, 0.6f, 0.5f, 100L)
        assertFalse(out.x.isNaN())
        assertFalse(out.x.isInfinite())
    }
}
