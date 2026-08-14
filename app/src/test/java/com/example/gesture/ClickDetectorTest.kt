package com.example.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickDetectorTest {

    private companion object {
        const val FRAME = 33L
        const val OPEN = 0.85f
        const val CLOSED = 0.15f
    }

    /** Rejoue une suite de ratios et retourne les évènements produits. */
    private fun play(
        detector: ClickDetector,
        ratios: List<Float>,
        startAt: Long = 0,
        step: Long = FRAME
    ): List<ClickDetector.Event> {
        var t = startAt
        return ratios.map { ratio ->
            val event = detector.update(ratio, 0.5f, 0.5f, t)
            t += step
            event
        }
    }

    @Test
    fun `une impulsion breve du pouce produit un clic`() {
        val events = play(
            ClickDetector(),
            listOf(OPEN, OPEN, CLOSED, CLOSED, OPEN, OPEN)
        )
        assertEquals(1, events.count { it == ClickDetector.Event.CLICK })
    }

    /**
     * Le défaut historique : un pouce simplement posé près de l'index — position naturelle de la
     * main qui pointe — déclenchait un clic à chaque frame. Sans vitesse de fermeture, il ne doit
     * plus rien produire.
     */
    @Test
    fun `un pouce deja proche au repos ne produit aucun clic`() {
        val events = play(ClickDetector(), List(60) { CLOSED })
        assertTrue("clics fantômes: ${events.count { it == ClickDetector.Event.CLICK }}",
            events.none { it == ClickDetector.Event.CLICK })
    }

    /** Une fermeture lente est un repositionnement de la main, pas une intention de cliquer. */
    @Test
    fun `une fermeture lente ne produit pas de clic`() {
        val slow = (0..30).map { OPEN - (OPEN - CLOSED) * it / 30f }
        val events = play(ClickDetector(), slow + List(5) { OPEN })
        assertTrue(events.none { it == ClickDetector.Event.CLICK })
    }

    @Test
    fun `le clic est immediat au relachement`() {
        val detector = ClickDetector()
        var t = 0L
        detector.update(OPEN, 0.5f, 0.5f, t); t += FRAME
        detector.update(CLOSED, 0.5f, 0.5f, t); t += FRAME
        val event = detector.update(OPEN, 0.5f, 0.5f, t)

        assertEquals(ClickDetector.Event.CLICK, event)
        // Deux frames à 33 ms : le clic part en moins de 100 ms.
        assertTrue("latence=${t}ms", t <= 100)
    }

    @Test
    fun `plusieurs impulsions successives produisent plusieurs clics`() {
        val detector = ClickDetector()
        var clicks = 0
        var t = 0L
        repeat(4) {
            listOf(OPEN, CLOSED, OPEN, OPEN, OPEN, OPEN, OPEN, OPEN).forEach { ratio ->
                if (detector.update(ratio, 0.5f, 0.5f, t) == ClickDetector.Event.CLICK) clicks++
                t += FRAME
            }
        }
        assertEquals(4, clicks)
    }

    /** Le pouce maintenu fermé devient un glisser : c'est ce qui distingue clic et maintien. */
    @Test
    fun `un pouce maintenu ferme bascule en maintien`() {
        val events = play(
            ClickDetector(),
            listOf(OPEN, CLOSED) + List(30) { CLOSED } + listOf(OPEN)
        )
        assertEquals(1, events.count { it == ClickDetector.Event.HOLD_START })
        assertEquals(1, events.count { it == ClickDetector.Event.HOLD_END })
        assertTrue("un clic parasite accompagne le maintien",
            events.none { it == ClickDetector.Event.CLICK })
    }

    /**
     * Propriété qui justifie le choix du pouce plutôt que de l'index : la cible visée reste celle
     * du début du geste, même si la main dérive pendant l'impulsion.
     */
    @Test
    fun `la cible est figee au debut de l impulsion`() {
        val detector = ClickDetector()
        var t = 0L
        detector.update(OPEN, 0.30f, 0.40f, t); t += FRAME
        detector.update(CLOSED, 0.31f, 0.41f, t); t += FRAME
        detector.update(OPEN, 0.55f, 0.70f, t)

        assertEquals(0.31f, detector.anchorX, 1e-5f)
        assertEquals(0.41f, detector.anchorY, 1e-5f)
        assertNotEquals(0.55f, detector.anchorX)
    }

    @Test
    fun `la progression du maintien est rapportee`() {
        val detector = ClickDetector()
        var t = 0L
        detector.update(OPEN, 0.5f, 0.5f, t); t += FRAME
        detector.update(CLOSED, 0.5f, 0.5f, t); t += 200
        detector.update(CLOSED, 0.5f, 0.5f, t)
        assertTrue("progression=${detector.holdProgress}", detector.holdProgress in 0.3f..0.7f)
    }

    @Test
    fun `la remise a zero annule le geste en cours`() {
        val detector = ClickDetector()
        var t = 0L
        detector.update(OPEN, 0.5f, 0.5f, t); t += FRAME
        detector.update(CLOSED, 0.5f, 0.5f, t); t += FRAME
        detector.reset()
        assertEquals(ClickDetector.Event.NONE, detector.update(OPEN, 0.5f, 0.5f, t + FRAME))
    }
}
