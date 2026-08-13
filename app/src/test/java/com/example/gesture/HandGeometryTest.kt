package com.example.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandGeometryTest {

    @Test
    fun `main ouverte - quatre doigts detectes comme tendus`() {
        val m = SyntheticHand.metrics(SyntheticHand.openPalm())
        assertEquals(4, m.extendedFingerCount)
        assertTrue("pouce attendu tendu, obtenu ${m.thumbExtension}", m.thumbExtension > 0.5f)
    }

    @Test
    fun `poing - aucun doigt tendu et pouce rabattu`() {
        val m = SyntheticHand.metrics(SyntheticHand.fist())
        assertEquals(0, m.extendedFingerCount)
        assertTrue("pouce attendu replié, obtenu ${m.thumbExtension}", m.thumbExtension < 0.5f)
    }

    @Test
    fun `pointage - index tendu et autres doigts replies`() {
        val m = SyntheticHand.metrics(SyntheticHand.pointing())
        assertTrue(m.fingerExtension[0] > 0.7f)
        assertTrue(m.fingerExtension[1] < 0.4f)
        assertTrue(m.fingerExtension[2] < 0.4f)
        assertTrue(m.fingerExtension[3] < 0.4f)
    }

    /**
     * Propriété essentielle : les seuils gestuels doivent rester valables quelle que soit la
     * distance de la main à la caméra. Une main deux fois plus petite dans l'image doit produire
     * exactement les mêmes ratios normalisés.
     */
    @Test
    fun `ratios invariants a l echelle de la main`() {
        val far = SyntheticHand.metrics(SyntheticHand.build(scale = 0.09f, pinchDistance = 0.25f))
        val near = SyntheticHand.metrics(SyntheticHand.build(scale = 0.22f, pinchDistance = 0.25f))

        assertEquals(near.pinchRatio, far.pinchRatio, 0.05f)
        assertEquals(near.fingerExtension[0], far.fingerExtension[0], 0.05f)
    }

    @Test
    fun `profondeur croit quand la main s eloigne`() {
        val far = SyntheticHand.metrics(SyntheticHand.build(scale = 0.09f))
        val near = SyntheticHand.metrics(SyntheticHand.build(scale = 0.22f))
        assertTrue("far=${far.depth} near=${near.depth}", far.depth > near.depth)
    }

    @Test
    fun `pose incomplete rejetee`() {
        assertNull(HandGeometry.analyze(SyntheticHand.openPalm().take(10)))
    }

    @Test
    fun `coordonnee NaN rejetee`() {
        val corrupted = SyntheticHand.openPalm().mapIndexed { i, p ->
            if (i == 3) Landmark(Float.NaN, p.y) else p
        }
        assertNull(HandGeometry.analyze(corrupted))
    }

    @Test
    fun `qualite chute quand la main sort du cadre`() {
        val inside = SyntheticHand.metrics(SyntheticHand.openPalm(cx = 0.5f))
        val outside = SyntheticHand.metrics(SyntheticHand.openPalm(cx = 1.35f))
        assertTrue(inside.quality > 0.6f)
        assertTrue("outside=${outside.quality}", outside.quality < inside.quality)
    }

    @Test
    fun `angle articulaire - segment tendu proche de 180 degres`() {
        val a = Landmark(0f, 0f)
        val vertex = Landmark(0f, 1f)
        val b = Landmark(0f, 2f)
        assertEquals(180f, HandGeometry.jointAngleDeg(a, vertex, b), 0.01f)
    }

    @Test
    fun `angle articulaire - angle droit`() {
        val a = Landmark(0f, 0f)
        val vertex = Landmark(0f, 1f)
        val b = Landmark(1f, 1f)
        assertEquals(90f, HandGeometry.jointAngleDeg(a, vertex, b), 0.01f)
    }
}
