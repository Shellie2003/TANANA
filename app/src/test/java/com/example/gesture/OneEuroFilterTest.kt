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
     * **La correction qui a le plus d'effet sur la précision.** La prédiction extrapole le long de
     * la vitesse, et la vitesse est une **dérivée** du signal : dériver amplifie le bruit, et le
     * résultat est réinjecté dans la position. Au repos, la « vitesse » n'est donc que du bruit
     * dérivé, et la prédiction le recopiait dans le curseur — elle **doublait** le bruit résiduel
     * en visée.
     *
     * Or prédire ne sert qu'à compenser une latence perçue, laquelle n'existe qu'en mouvement : un
     * curseur immobile n'est jamais en retard. La couper en visée ne coûte donc rien.
     */
    @Test
    fun `la prediction est coupee quand la main vise`() {
        /**
         * Bruit résiduel du curseur, main visant un point fixe.
         *
         * Le signal injecté comporte un **tremblement physiologique à 10 Hz** en plus du bruit
         * d'estimation, et c'est indispensable : un premier test n'injectait que du bruit blanc,
         * dont la dérivée filtrée reste si faible que la prédiction ne s'y déclenchait pas. Il ne
         * mesurait donc rien, et passait quelle que soit la coupure. C'est le tremblement, signal
         * cohérent, qui donne à la dérivée de quoi s'exprimer.
         */
        fun jitter(minSpeed: Float): Float {
            val filter = OneEuroFilter(
                minCutoff = 0.4f, beta = 3.5f, dCutoff = 1.0f, predictionMs = 40f,
                predictionMinSpeed = minSpeed, predictionFullSpeed = 0.20f
            )
            val random = java.util.Random(7)
            var t = 0L
            val samples = mutableListOf<Float>()
            repeat(140) { i ->
                t += FRAME_MS
                val tremor = 0.004f * kotlin.math.sin(i * 2.0 * Math.PI * 10.0 / 30.0).toFloat()
                val white = (random.nextFloat() - 0.5f) * 0.004f
                val out = filter.filter(0.5f + tremor + white, t)
                if (i > 40) samples += out
            }
            val mean = samples.average().toFloat()
            return kotlin.math.sqrt(samples.map { (it - mean) * (it - mean) }.average().toFloat())
        }

        val gated = jitter(minSpeed = 0.04f)
        // Coupure désarmée : la prédiction agit dès la moindre vitesse, bruit compris.
        val ungated = jitter(minSpeed = -1f)
        assertTrue(
            "la coupure n'apporte rien : bruit $gated contre $ungated sans elle",
            gated < ungated * 0.75f
        )
    }

    /**
     * Symétriquement, la prédiction doit continuer d'agir en mouvement établi : c'est là qu'elle
     * sert, et la couper partout rendrait le curseur traînant.
     */
    @Test
    fun `la prediction agit toujours en mouvement etabli`() {
        val lagWith = rampLag(predictionMs = 40f)
        val lagWithout = rampLag(predictionMs = 0f)
        assertTrue(
            "la coupure a aussi désarmé la prédiction en mouvement : $lagWith contre $lagWithout",
            lagWith < lagWithout * 0.8f
        )
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
