package com.example.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le fichier a maigri avec la refonte, et c'est le point : il ne reste à vérifier que deux
 * mesures, la position et l'écart pouce-index. Les tests d'extension de doigts ont disparu parce
 * que la mesure a disparu — la forme de la main n'est plus lue.
 */
class HandGeometryTest {

    // --- L'écart pouce-index, le seul bouton -------------------------------------------------

    /**
     * Propriété essentielle : le seuil du pincement doit rester valable quelle que soit la
     * distance de la main à la caméra. Une main deux fois plus petite dans l'image doit produire
     * exactement le même ratio normalisé — sans quoi le geste cesserait de marcher dès qu'on
     * éloigne le bras, ce qui est précisément ce qu'un seuil absolu produisait.
     */
    @Test
    fun `le pincement est invariant a l echelle de la main`() {
        val far = SyntheticHand.metrics(SyntheticHand.build(scale = 0.09f, pinchDistance = 0.25f))
        val near = SyntheticHand.metrics(SyntheticHand.build(scale = 0.22f, pinchDistance = 0.25f))
        assertEquals(near.pinchRatio, far.pinchRatio, 0.05f)
    }

    @Test
    fun `le pincement distingue la main fermee de la main ouverte`() {
        val ouvert = SyntheticHand.metrics(SyntheticHand.build(pinchDistance = 0.9f))
        val ferme = SyntheticHand.metrics(SyntheticHand.build(pinchDistance = 0.08f))
        assertTrue("ouvert=${ouvert.pinchRatio} fermé=${ferme.pinchRatio}", ouvert.pinchRatio > ferme.pinchRatio * 3f)
    }

    /**
     * Le pincement ne doit pas dépendre de la forme du reste de la main : c'est ce qui permet de
     * garder la main détendue, dans la position qu'on veut, au lieu de tenir une pose.
     */
    @Test
    fun `le pincement ne depend pas de la forme du reste de la main`() {
        val mesures = listOf(
            floatArrayOf(1f, 1f, 1f, 1f),
            floatArrayOf(1f, 0f, 0f, 0f),
            floatArrayOf(1f, 1f, 0f, 0f),
            floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)
        ).map {
            SyntheticHand.metrics(SyntheticHand.build(extension = it, pinchDistance = 0.1f)).pinchRatio
        }

        val ecart = mesures.max() - mesures.min()
        assertTrue("le pincement varie de $ecart selon la forme de la main : $mesures", ecart < 0.12f)
    }

    // --- Position et plausibilité ------------------------------------------------------------

    @Test
    fun `profondeur croit quand la main s eloigne`() {
        val far = SyntheticHand.metrics(SyntheticHand.build(scale = 0.09f))
        val near = SyntheticHand.metrics(SyntheticHand.build(scale = 0.22f))
        assertTrue("far=${far.depth} near=${near.depth}", far.depth > near.depth)
    }

    @Test
    fun `l ancre du curseur suit la main`() {
        val gauche = SyntheticHand.metrics(SyntheticHand.build(cx = 0.25f))
        val droite = SyntheticHand.metrics(SyntheticHand.build(cx = 0.75f))
        assertTrue(droite.pointerX > gauche.pointerX)
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
}
