package com.example.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CursorMapperTest {

    private val linear = CursorMapper(activeMarginX = 0.15f, activeMarginY = 0.15f, precisionGain = 0f)

    @Test
    fun `le centre reste au centre`() {
        assertEquals(0.5f, linear.map(0.5f, 0.5f).first, 1e-5f)
        assertEquals(0.5f, linear.map(0.5f, 0.5f).second, 1e-5f)
    }

    @Test
    fun `les bords de la zone active atteignent les bords de l ecran`() {
        assertEquals(0f, linear.map(0.15f, 0.5f).first, 1e-5f)
        assertEquals(1f, linear.map(0.85f, 0.5f).first, 1e-5f)
    }

    @Test
    fun `au-dela de la zone active la sortie est bornee`() {
        assertEquals(0f, linear.map(0.01f, 0.5f).first, 1e-5f)
        assertEquals(1f, linear.map(0.99f, 0.5f).first, 1e-5f)
    }

    /** Le recadrage sur la zone atteignable amplifie le déplacement utile d'un facteur 1/0.7. */
    @Test
    fun `amplification conforme a la zone active`() {
        val delta = linear.map(0.6f, 0.5f).first - 0.5f
        assertEquals(0.1f / 0.7f, delta, 1e-4f)
    }

    @Test
    fun `le gain de precision preserve centre et extremites`() {
        val gained = CursorMapper(activeMarginX = 0f, activeMarginY = 0f, precisionGain = 0.3f)
        assertEquals(0.5f, gained.map(0.5f, 0.5f).first, 1e-5f)
        assertEquals(0f, gained.map(0f, 0.5f).first, 1e-5f)
        assertEquals(1f, gained.map(1f, 0.5f).first, 1e-5f)
    }

    @Test
    fun `le gain de precision reste monotone`() {
        val gained = CursorMapper(precisionGain = 0.3f)
        val values = (0..40).map { gained.map(it / 40f, 0.5f).first }
        assertTrue(values.zipWithNext().all { (a, b) -> b >= a })
    }

    /** Autour du centre, le gain compresse le déplacement : la visée fine devient plus stable. */
    @Test
    fun `le gain attenue les micro-deplacements centraux`() {
        val gained = CursorMapper(activeMarginX = 0f, activeMarginY = 0f, precisionGain = 0.4f)
        val centralDelta = gained.map(0.55f, 0.5f).first - gained.map(0.5f, 0.5f).first
        assertTrue("delta central $centralDelta", centralDelta < 0.05f)
    }

    @Test
    fun `entree NaN retombe au centre`() {
        assertEquals(0.5f, linear.map(Float.NaN, Float.NaN).first, 1e-5f)
        assertEquals(0.5f, linear.map(Float.NaN, Float.NaN).second, 1e-5f)
    }

    @Test
    fun `conversion en pixels bornee a l ecran`() {
        val (x, y) = linear.toScreenPixels(1.5f, -0.5f, 1080f, 2400f)
        assertEquals(1080f, x, 1e-3f)
        assertEquals(0f, y, 1e-3f)
    }
}
