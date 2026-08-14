package com.example.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class RadialKeyboardTest {

    private val dwell = 500L

    /** Place le curseur dans le secteur [sector] et y reste assez longtemps pour valider. */
    private fun select(kb: RadialKeyboard, sector: Int, startAt: Long): Pair<RadialKeyboard.Emission, Long> {
        // Centre angulaire du secteur, à mi-distance du bord : la tolérance y est maximale.
        val angle = (sector + 0.5) * (2 * PI / RadialKeyboard.SECTORS)
        val x = 0.5f + (sin(angle) * 0.30).toFloat()
        val y = 0.5f - (cos(angle) * 0.30).toFloat()

        var t = startAt
        kb.update(x, y, t)
        t += dwell + 20
        return kb.update(x, y, t) to (t + 20)
    }

    @Test
    fun `deux selections produisent un caractere`() {
        val kb = RadialKeyboard()
        val (first, t) = select(kb, 0, 0)
        assertNull("le premier niveau ne doit rien écrire", first.text)

        val (second, _) = select(kb, 0, t)
        assertEquals("A", second.text)
    }

    @Test
    fun `chaque couple de directions donne un caractere distinct`() {
        val produced = mutableSetOf<String>()
        for (group in 0 until RadialKeyboard.SECTORS) {
            for (char in 0 until RadialKeyboard.SECTORS) {
                val kb = RadialKeyboard()
                val (_, t) = select(kb, group, 0)
                val (emission, _) = select(kb, char, t)
                emission.text?.let { produced += it }
            }
        }
        // 26 lettres + accents + chiffres + ponctuation + espace, sans les effacements.
        assertTrue("symboles distincts: ${produced.size}", produced.size >= 40)
        assertTrue(produced.containsAll(listOf("A", "Z", "1", "0", " ", "?")))
    }

    @Test
    fun `l effacement est signale separement`() {
        val kb = RadialKeyboard()
        val (_, t) = select(kb, 7, 0)
        val (emission, _) = select(kb, 3, t)
        assertTrue(emission.backspace)
        assertNull(emission.text)
    }

    /** Le centre est la zone de repos : on doit pouvoir y ramener la main sans rien déclencher. */
    @Test
    fun `le centre ne declenche rien`() {
        val kb = RadialKeyboard()
        var t = 0L
        repeat(60) {
            val emission = kb.update(0.5f, 0.5f, t)
            assertNull(emission.text)
            t += 33
        }
        assertEquals(-1, kb.state().hoveredSector)
    }

    /** Sortir d'un secteur avant la fin de l'attente annule la sélection. */
    @Test
    fun `changer de secteur annule la selection en cours`() {
        val kb = RadialKeyboard()
        kb.update(0.5f, 0.2f, 0)          // secteur du haut
        kb.update(0.5f, 0.2f, 300)         // attente incomplète
        val emission = kb.update(0.8f, 0.5f, 320)  // bascule vers la droite
        assertNull(emission.text)
        assertEquals(0f, kb.state().progress, 1e-6f)
    }

    @Test
    fun `la progression est rapportee pendant l attente`() {
        val kb = RadialKeyboard()
        kb.update(0.5f, 0.2f, 0)
        kb.update(0.5f, 0.2f, 250)
        val progress = kb.state().progress
        assertTrue("progression=$progress", progress in 0.4f..0.6f)
    }

    @Test
    fun `le second niveau affiche les symboles du groupe choisi`() {
        val kb = RadialKeyboard()
        select(kb, 0, 0)
        assertEquals(RadialKeyboard.Level.CHARACTER, kb.state().level)
        assertEquals("A", kb.state().labels.first())
    }

    @Test
    fun `la remise a zero revient au premier niveau`() {
        val kb = RadialKeyboard()
        select(kb, 2, 0)
        kb.reset()
        assertEquals(RadialKeyboard.Level.GROUP, kb.state().level)
        assertEquals(RadialKeyboard.GROUP_LABELS, kb.state().labels)
    }

    /**
     * Propriété qui justifie le choix d'un menu radial : la tolérance est angulaire. Un point à
     * peine sorti de la zone morte et un point au bord de l'écran, dans la même direction,
     * doivent désigner le même secteur.
     */
    @Test
    fun `la selection depend de la direction et non de la distance`() {
        val kb = RadialKeyboard()
        kb.update(0.5f, 0.35f, 0)
        val near = kb.state().hoveredSector
        kb.reset()
        kb.update(0.5f, 0.02f, 0)
        val far = kb.state().hoveredSector
        assertEquals(near, far)
    }
}
