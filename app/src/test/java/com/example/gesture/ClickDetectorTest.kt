package com.example.gesture

import org.junit.Assert.assertEquals
import com.example.control.GesturePolicy
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le clic est le geste le plus fréquent et le moins pardonnable : un faux positif agit sur le
 * téléphone entier. Ces tests décrivent des trajectoires de pouce réalistes plutôt que des états,
 * puisque c'est précisément la trajectoire que le détecteur juge.
 */
class ClickDetectorTest {

    private val frameMs = 33L

    /** Rejoue une trajectoire d'écart pouce-index et retourne les évènements produits. */
    private fun play(
        detector: ClickDetector,
        ratios: List<Float>,
        startMs: Long = 0L,
        stepMs: Long = frameMs,
        x: Float = 0.5f,
        y: Float = 0.5f
    ): List<ClickDetector.Event> {
        var t = startMs
        return ratios.map { ratio ->
            val event = detector.update(ratio, x, y, t)
            t += stepMs
            event
        }
    }

    /** Amène la ligne de base à la valeur de repos de l'utilisateur simulé. */
    private fun settle(detector: ClickDetector, rest: Float = 0.85f, frames: Int = 40): Long {
        play(detector, List(frames) { rest })
        return frames * frameMs
    }

    /** Impulsion franche : ouvert -> contact -> retour. */
    private fun tap(rest: Float = 0.85f) =
        listOf(rest, 0.55f, 0.18f, 0.10f, 0.45f, rest, rest)

    // --- Le cas nominal -----------------------------------------------------------------------

    @Test
    fun `une impulsion franche produit un clic unique`() {
        val d = ClickDetector()
        val t0 = settle(d)
        val events = play(d, tap(), startMs = t0)
        assertEquals(1, events.count { it == ClickDetector.Event.CLICK })
    }

    @Test
    fun `le clic part en moins de cent cinquante millisecondes`() {
        val d = ClickDetector()
        val t0 = settle(d)
        val ratios = tap()
        var t = t0
        var clickAt = -1L
        var contactAt = -1L
        ratios.forEach { ratio ->
            if (contactAt < 0 && ratio < d.contactThreshold) contactAt = t
            if (d.update(ratio, 0.5f, 0.5f, t) == ClickDetector.Event.CLICK) clickAt = t
            t += frameMs
        }
        assertTrue("aucun clic émis", clickAt >= 0)
        val latency = clickAt - contactAt
        assertTrue("latence contact -> clic de $latency ms", latency <= 150)
    }

    @Test
    fun `le curseur est fige a l instant du contact`() {
        val d = ClickDetector()
        var t = settle(d)
        // Le pouce se ferme pendant que la main dérive : la cible doit rester celle du contact.
        d.update(0.85f, 0.20f, 0.20f, t); t += frameMs
        d.update(0.10f, 0.30f, 0.30f, t); t += frameMs
        d.update(0.80f, 0.90f, 0.90f, t)
        assertEquals(0.30f, d.anchorX, 1e-5f)
        assertEquals(0.30f, d.anchorY, 1e-5f)
    }

    // --- Les faux positifs, qui sont le vrai sujet --------------------------------------------

    @Test
    fun `un pouce pose au repos pres de l index ne clique jamais`() {
        val d = ClickDetector()
        // Cet utilisateur garde en permanence le pouce contre l'index : c'est sa position neutre.
        val events = play(d, List(300) { 0.20f })
        assertEquals(0, events.count { it == ClickDetector.Event.CLICK })
    }

    @Test
    fun `une fermeture lente est un repositionnement, pas un clic`() {
        val d = ClickDetector()
        val t0 = settle(d)
        // Descente régulière sur une seconde : la main se replace, elle ne frappe pas.
        val slow = (0..30).map { 0.85f - it * (0.75f / 30f) }
        val events = play(d, slow, startMs = t0)
        assertEquals(0, events.count { it == ClickDetector.Event.CLICK })
        assertEquals(ClickDetector.Rejection.TOO_SLOW, d.lastRejection)
    }

    @Test
    fun `une approche partielle ne suffit pas`() {
        val d = ClickDetector()
        val t0 = settle(d, rest = 0.85f)
        // Le pouce descend vite mais s'arrête au-dessus du seuil de contact.
        val events = play(d, listOf(0.85f, 0.45f, 0.40f, 0.42f, 0.85f), startMs = t0)
        assertEquals(0, events.count { it == ClickDetector.Event.CLICK })
    }

    @Test
    fun `le tremblement autour du repos ne produit rien`() {
        val d = ClickDetector()
        val jitter = (0..200).map { 0.80f + if (it % 2 == 0) 0.03f else -0.03f }
        val events = play(d, jitter)
        assertEquals(0, events.count { it == ClickDetector.Event.CLICK })
    }

    @Test
    fun `un rejet repete ne compte qu une fois`() {
        val d = ClickDetector()
        val t0 = settle(d)
        // Le pouce descend lentement puis reste posé : un seul geste, donc un seul rejet.
        play(d, (0..20).map { 0.85f - it * 0.035f } + List(60) { 0.15f }, startMs = t0)
        assertEquals(1, d.rejectedCount)
    }

    // --- L'adaptation à l'utilisateur ---------------------------------------------------------

    @Test
    fun `la ligne de base apprend la course de l utilisateur`() {
        val ample = ClickDetector()
        settle(ample, rest = 1.30f)
        val discret = ClickDetector()
        settle(discret, rest = 0.50f)

        assertTrue("course ample non apprise : ${ample.baseline}", ample.baseline > 1.1f)
        assertTrue("course discrète non apprise : ${discret.baseline}", discret.baseline < 0.6f)
        assertTrue(
            "le seuil de contact doit suivre la course",
            ample.contactThreshold > discret.contactThreshold
        )
    }

    /**
     * Le cas que les seuils absolus ne pouvaient pas traiter : une main qui garde le pouce près
     * de l'index a une course réduite, et doit malgré tout pouvoir cliquer.
     */
    @Test
    fun `une main a course reduite peut cliquer`() {
        val d = ClickDetector()
        val t0 = settle(d, rest = 0.50f)
        val events = play(d, listOf(0.50f, 0.30f, 0.08f, 0.06f, 0.30f, 0.50f), startMs = t0)
        assertEquals(1, events.count { it == ClickDetector.Event.CLICK })
    }

    /** Symétriquement, une main à grande course ne doit pas cliquer sur une descente partielle. */
    @Test
    fun `une main a grande course ne clique pas a mi-chemin`() {
        val d = ClickDetector()
        val t0 = settle(d, rest = 1.40f)
        val events = play(d, listOf(1.40f, 0.90f, 0.75f, 0.80f, 1.40f), startMs = t0)
        assertEquals(0, events.count { it == ClickDetector.Event.CLICK })
    }

    @Test
    fun `un maintien prolonge n erode pas la ligne de base`() {
        val d = ClickDetector()
        val t0 = settle(d, rest = 0.90f)
        val before = d.baseline
        // Contact tenu trois secondes, puis relâchement.
        play(d, listOf(0.90f, 0.10f) + List(90) { 0.08f }, startMs = t0)
        assertEquals("la référence a dérivé pendant le maintien", before, d.baseline, 0.02f)
    }

    // --- Cadence ------------------------------------------------------------------------------

    @Test
    fun `des clics rapides se transmettent tous`() {
        val d = ClickDetector()
        var t = settle(d)
        var clicks = 0
        repeat(6) {
            listOf(0.85f, 0.12f, 0.09f, 0.50f, 0.85f, 0.85f).forEach { ratio ->
                if (d.update(ratio, 0.5f, 0.5f, t) == ClickDetector.Event.CLICK) clicks++
                t += frameMs
            }
        }
        assertEquals(6, clicks)
    }

    // --- Maintien -----------------------------------------------------------------------------

    @Test
    fun `un contact tenu bascule en maintien puis se termine`() {
        val d = ClickDetector()
        val t0 = settle(d)
        val events = play(
            d,
            listOf(0.85f, 0.10f) + List(20) { 0.08f } + listOf(0.50f, 0.85f),
            startMs = t0
        )
        assertEquals(1, events.count { it == ClickDetector.Event.HOLD_START })
        assertEquals(1, events.count { it == ClickDetector.Event.HOLD_END })
        assertEquals("un maintien ne doit pas produire de clic", 0, events.count { it == ClickDetector.Event.CLICK })
    }

    @Test
    fun `la progression du maintien est annoncee avant la bascule`() {
        val d = ClickDetector()
        var t = settle(d)
        d.update(0.85f, 0.5f, 0.5f, t); t += frameMs
        d.update(0.10f, 0.5f, 0.5f, t); t += frameMs
        d.update(0.09f, 0.5f, 0.5f, t)
        assertTrue("progression nulle", d.holdProgress > 0f)
        assertTrue("progression déjà pleine", d.holdProgress < 1f)
    }

    // --- Retour à l'utilisateur ---------------------------------------------------------------

    @Test
    fun `la progression de fermeture renseigne l anneau du curseur`() {
        val d = ClickDetector()
        var t = settle(d, rest = 0.90f)
        d.update(0.90f, 0.5f, 0.5f, t); t += frameMs
        val far = d.travelProgress
        d.update(0.55f, 0.5f, 0.5f, t)
        val near = d.travelProgress
        assertTrue("l'anneau doit se remplir en approchant : $far -> $near", near > far)
    }

    @Test
    fun `la cause du rejet est exposee`() {
        val d = ClickDetector()
        val t0 = settle(d)
        play(d, listOf(0.85f, 0.45f, 0.40f, 0.85f), startMs = t0)
        assertNotEquals(ClickDetector.Rejection.NONE, d.lastRejection)
        assertEquals(ClickDetector.Rejection.TOO_SHALLOW, d.lastRejection)
    }

    /**
     * Le défaut le plus fréquent, et le seul que l'utilisateur ne peut pas diagnostiquer seul :
     * il approche franchement le pouce puis repart sans toucher. De son point de vue il a cliqué,
     * et rien ne s'est produit. Le détecteur doit nommer ce cas.
     */
    @Test
    fun `un quasi-contact est signale comme tel`() {
        val d = ClickDetector()
        val t0 = settle(d, rest = 0.90f)
        play(d, listOf(0.90f, 0.45f, 0.40f, 0.60f, 0.90f), startMs = t0)
        assertEquals(ClickDetector.Rejection.TOO_SHALLOW, d.lastRejection)
        assertEquals(1, d.rejectedCount)
    }

    @Test
    fun `une descente timide n est pas comptee comme quasi-contact`() {
        val d = ClickDetector()
        val t0 = settle(d, rest = 0.90f)
        // Le pouce bouge à peine : ce n'est pas une tentative de clic manquée, c'est du bruit.
        play(d, listOf(0.90f, 0.78f, 0.75f, 0.90f), startMs = t0)
        assertEquals(ClickDetector.Rejection.NONE, d.lastRejection)
        assertEquals(0, d.rejectedCount)
    }

    @Test
    fun `un clic reussi n est pas aussi compte comme quasi-contact`() {
        val d = ClickDetector()
        val t0 = settle(d)
        play(d, tap(), startMs = t0)
        assertEquals(0, d.rejectedCount)
        assertEquals(ClickDetector.Rejection.NONE, d.lastRejection)
    }

    @Test
    fun `les compteurs de session suivent les clics reussis`() {
        val d = ClickDetector()
        val t0 = settle(d)
        play(d, tap() + tap(), startMs = t0)
        assertEquals(2, d.clickCount)
    }

    /**
     * Chaque cause de rejet doit se traduire par une correction applicable. Un état sans conseil
     * laisserait l'utilisateur devant un échec qu'il ne peut pas comprendre.
     */
    @Test
    fun `chaque cause de rejet propose une correction`() {
        ClickDetector.Rejection.entries.forEach { reason ->
            val telemetry = GesturePolicy.ClickTelemetry(lastRejection = reason)
            if (reason == ClickDetector.Rejection.NONE) {
                assertNull("un geste abouti n'a pas à être commenté", telemetry.advice)
            } else {
                assertNotNull("$reason ne propose aucune correction", telemetry.advice)
                assertTrue("$reason : conseil trop court", (telemetry.advice?.length ?: 0) > 40)
            }
        }
    }

    @Test
    fun `le taux de reussite est inconnu avant tout geste`() {
        assertNull(GesturePolicy.ClickTelemetry().successRate)
        assertEquals(
            0.75f,
            GesturePolicy.ClickTelemetry(clicks = 3, rejected = 1).successRate!!,
            1e-6f
        )
    }

    @Test
    fun `la calibration se declare apres quelques images`() {
        val d = ClickDetector()
        play(d, List(3) { 0.85f })
        assertTrue("calibration annoncée trop tôt", !d.isCalibrated)
        play(d, List(20) { 0.85f })
        assertTrue(d.isCalibrated)
    }
}
