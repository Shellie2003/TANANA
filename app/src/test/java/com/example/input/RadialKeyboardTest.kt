package com.example.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class RadialKeyboardTest {

    /** Coordonnées du centre angulaire d'un secteur, à mi-distance du bord. */
    private fun pointAt(sector: Int): Pair<Float, Float> {
        val angle = (sector + 0.5) * (2 * PI / RadialKeyboard.SECTORS)
        return (0.5f + (sin(angle) * 0.30).toFloat()) to (0.5f - (cos(angle) * 0.30).toFloat())
    }

    /** Pointe le secteur [sector], attend qu'il se stabilise, puis valide d'un clic. */
    private fun select(kb: RadialKeyboard, sector: Int, startAt: Long): Pair<RadialKeyboard.Emission, Long> {
        val (x, y) = pointAt(sector)
        var t = startAt
        kb.update(x, y, t)
        t += 120
        return kb.update(x, y, t, commit = true) to (t + 20)
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

    @Test
    fun `changer de secteur remet la selection a zero`() {
        val kb = RadialKeyboard()
        kb.update(0.5f, 0.2f, 0)                   // secteur du haut
        kb.update(0.5f, 0.2f, 300, clickProgress = 0.5f)
        val emission = kb.update(0.8f, 0.5f, 320)  // bascule vers la droite
        assertNull(emission.text)
        assertEquals(0f, kb.state().progress, 1e-6f)
    }

    /**
     * L'anneau du secteur pointé suit la progression du clic : c'est la même grandeur que celle
     * qui déclenche la validation, et donc le seul retour qui annonce vraiment ce qui va se passer.
     */
    @Test
    fun `l anneau du secteur suit la progression du clic`() {
        val kb = RadialKeyboard()
        kb.update(0.5f, 0.2f, 0)
        kb.update(0.5f, 0.2f, 100, clickProgress = 0.6f)
        assertEquals(0.6f, kb.state().progress, 1e-6f)
    }

    /**
     * Le point central du changement : la validation ne doit plus jamais partir d'elle-même. Sans
     * clic, l'utilisateur peut hésiter aussi longtemps qu'il veut sur un secteur.
     */
    @Test
    fun `sans clic, l attente ne valide jamais`() {
        val kb = RadialKeyboard()
        val (x, y) = pointAt(0)
        var t = 0L
        repeat(200) {
            assertNull("une validation est partie sans clic", kb.update(x, y, t).text)
            t += 33
        }
        assertEquals(RadialKeyboard.Level.GROUP, kb.state().level)
    }

    /**
     * Un clic tombant pendant que le curseur traverse un secteur ne doit rien y valider : le
     * secteur visé est celui sur lequel la main s'est posée, pas celui qu'elle survole en chemin.
     */
    @Test
    fun `un clic pendant la traversee d un secteur ne valide rien`() {
        val kb = RadialKeyboard()
        val (x, y) = pointAt(0)
        kb.update(x, y, 0)
        // Clic 20 ms après l'entrée dans le secteur : trop tôt pour que la visée soit établie.
        assertEquals(RadialKeyboard.Level.GROUP, kb.state().level)
        kb.update(x, y, 20, commit = true)
        assertEquals(RadialKeyboard.Level.GROUP, kb.state().level)
    }

    /** La validation par immobilité reste possible pour qui ne peut pas cliquer. */
    @Test
    fun `la validation par immobilite reste disponible en option`() {
        val kb = RadialKeyboard(RadialKeyboard.Config(dwellMs = 500L))
        val (x, y) = pointAt(0)
        kb.update(x, y, 0)
        kb.update(x, y, 250)
        assertTrue("progression=${kb.state().progress}", kb.state().progress in 0.4f..0.6f)
        kb.update(x, y, 520)
        assertEquals(RadialKeyboard.Level.CHARACTER, kb.state().level)
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
