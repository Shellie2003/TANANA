package com.example.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandPanelTest {

    private val items = 8
    private val labels = (0 until items).map { "c$it" }
    private val frameMs = 33L

    /** Maintient le curseur à une position pendant une durée donnée. */
    private fun CommandPanel.hold(
        x: Float,
        y: Float,
        durationMs: Long,
        pressed: Boolean = false,
        startMs: Long = 0L
    ): Long {
        var t = startMs
        val end = startMs + durationMs
        while (t <= end) {
            update(x, y, pressed, items, t)
            t += frameMs
        }
        return t
    }

    // --- Ouverture ----------------------------------------------------------------------------

    @Test
    fun `rester au bord droit ouvre le panneau`() {
        val panel = CommandPanel()
        panel.hold(0.98f, 0.5f, durationMs = 300)
        assertTrue(panel.isOpen)
    }

    /**
     * Garde-fou principal contre l'ouverture accidentelle : un simple passage de la main vers la
     * droite traverse la bande sans y rester.
     */
    @Test
    fun `traverser la bande sans s y arreter n ouvre rien`() {
        val panel = CommandPanel()
        var t = 0L
        // La main balaie l'écran de gauche à droite puis revient, une image par position.
        for (x in listOf(0.3f, 0.6f, 0.9f, 0.97f, 0.99f, 0.9f, 0.5f)) {
            panel.update(x, 0.5f, false, items, t)
            t += frameMs
        }
        assertTrue("le panneau s'est ouvert au passage", !panel.isOpen)
    }

    @Test
    fun `le centre de l ecran n ouvre jamais le panneau`() {
        val panel = CommandPanel()
        panel.hold(0.5f, 0.5f, durationMs = 3000)
        assertTrue(!panel.isOpen)
    }

    /**
     * Un tracé qui longe le bord droit — défiler le long de la marge, par exemple — ne doit pas
     * ouvrir le panneau, sans quoi le geste deviendrait impossible à terminer.
     */
    @Test
    fun `le panneau ne s ouvre pas pendant un pincement`() {
        val panel = CommandPanel()
        panel.hold(0.98f, 0.5f, durationMs = 2000, pressed = true)
        assertTrue(!panel.isOpen)
    }

    /** L'approche se voit avant l'ouverture : on ne découvre pas le panneau par surprise. */
    @Test
    fun `le panneau se montre pendant l approche`() {
        val panel = CommandPanel()
        panel.update(0.5f, 0.5f, false, items, 0)
        val loin = panel.state(labels).openProgress
        panel.update(0.88f, 0.5f, false, items, frameMs)
        val proche = panel.state(labels).openProgress

        assertEquals("rien ne doit se montrer au centre", 0f, loin, 1e-5f)
        assertTrue("l'aperçu doit croître en approchant : $proche", proche > 0f)
        assertTrue("l'aperçu ne doit pas valoir une ouverture", proche < 1f)
    }

    // --- Fermeture ----------------------------------------------------------------------------

    @Test
    fun `s eloigner du bord referme le panneau`() {
        val panel = CommandPanel()
        val t = panel.hold(0.98f, 0.5f, durationMs = 300)
        assertTrue(panel.isOpen)

        panel.update(0.3f, 0.5f, false, items, t)
        assertTrue(panel.isOpen.not())
    }

    /**
     * Hystérésis : la bande d'ouverture est étroite, la zone de maintien est large. Sans cet
     * écart, une main qui tremble au bord ferait papilloter le panneau.
     */
    @Test
    fun `le panneau ne papillonne pas quand la main tremble au bord`() {
        val panel = CommandPanel()
        var t = panel.hold(0.98f, 0.5f, durationMs = 300)
        // Le curseur s'écarte de plusieurs pour cent, mais reste dans la colonne.
        for (x in listOf(0.93f, 0.88f, 0.95f, 0.80f, 0.99f, 0.75f)) {
            panel.update(x, 0.5f, false, items, t)
            t += frameMs
            assertTrue("fermé à x=$x", panel.isOpen)
        }
    }

    // --- Sélection ----------------------------------------------------------------------------

    @Test
    fun `chaque ligne est atteignable et distincte`() {
        val panel = CommandPanel()
        val selected = (0 until items).map { row ->
            panel.reset()
            val (top, bottom) = panel.rowBounds(row, items)
            panel.hold(0.98f, (top + bottom) / 2f, durationMs = 300)
            panel.commit()
        }
        assertEquals((0 until items).toList(), selected)
    }

    /**
     * L'affichage et la sélection doivent s'accorder exactement : une ligne dessinée ailleurs
     * qu'à l'endroit où elle se sélectionne serait un piège, et le genre de défaut qu'on ne
     * remarque qu'à l'usage.
     */
    @Test
    fun `les bornes dessinees correspondent a la ligne selectionnee`() {
        val panel = CommandPanel()
        for (row in 0 until items) {
            val (top, bottom) = panel.rowBounds(row, items)
            panel.reset()
            panel.hold(0.98f, top + (bottom - top) * 0.1f, durationMs = 300)
            assertEquals("haut de la ligne $row", row, panel.highlightedIndex)

            panel.reset()
            panel.hold(0.98f, bottom - (bottom - top) * 0.1f, durationMs = 300)
            assertEquals("bas de la ligne $row", row, panel.highlightedIndex)
        }
    }

    @Test
    fun `la selection suit la hauteur de la main`() {
        val panel = CommandPanel()
        var t = panel.hold(0.98f, 0.1f, durationMs = 300)
        val haut = panel.highlightedIndex
        panel.update(0.98f, 0.9f, false, items, t)
        val bas = panel.highlightedIndex
        assertTrue("haut=$haut bas=$bas", bas > haut)
    }

    // --- Validation ---------------------------------------------------------------------------

    @Test
    fun `valider referme le panneau`() {
        val panel = CommandPanel()
        panel.hold(0.98f, 0.5f, durationMs = 300)
        assertTrue(panel.commit() != null)
        assertTrue("le panneau est resté ouvert après validation", !panel.isOpen)
    }

    /** Rien ne s'exécute tant que le panneau n'est pas ouvert. */
    @Test
    fun `valider sans panneau ouvert ne produit rien`() {
        val panel = CommandPanel()
        assertNull(panel.commit())
        panel.update(0.5f, 0.5f, false, items, 0)
        assertNull(panel.commit())
    }

    /** S'éloigner sans valider est la façon d'annuler, et elle ne doit rien exécuter. */
    @Test
    fun `s eloigner sans valider n execute rien`() {
        val panel = CommandPanel()
        val t = panel.hold(0.98f, 0.5f, durationMs = 300)
        panel.update(0.2f, 0.5f, false, items, t)
        assertNull(panel.commit())
    }

    // --- Cas dégradés -------------------------------------------------------------------------

    /**
     * Une liste vide ne doit pas ouvrir un panneau sans ligne, qui serait un écran noir dont on ne
     * sait pas sortir.
     */
    @Test
    fun `un panneau sans commande ne s ouvre pas`() {
        val panel = CommandPanel()
        var t = 0L
        repeat(30) { panel.update(0.98f, 0.5f, false, 0, t); t += frameMs }
        assertTrue(!panel.isOpen)
        assertNull(panel.commit())
    }

    @Test
    fun `le nombre de lignes est libre`() {
        for (count in listOf(1, 3, 10, 16)) {
            val panel = CommandPanel()
            var t = 0L
            repeat(30) { panel.update(0.98f, 0.5f, false, count, t); t += frameMs }
            assertTrue("n=$count", panel.isOpen)
            val selected = panel.commit()
            assertTrue("n=$count a donné $selected", selected != null && selected in 0 until count)
        }
    }

    @Test
    fun `la remise a zero referme tout`() {
        val panel = CommandPanel()
        panel.hold(0.98f, 0.5f, durationMs = 300)
        panel.reset()
        assertTrue(!panel.isOpen)
        assertEquals(-1, panel.highlightedIndex)
        assertEquals(0f, panel.state(labels).openProgress, 1e-5f)
    }
}
