package com.example.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandWheelTest {

    private val labels = (0 until CommandWheel.SECTORS).map { "s$it" }

    /** Point situé à une distance franche du centre, dans la direction d'un secteur donné. */
    private fun towards(sector: Int, distance: Float = 0.4f): Pair<Float, Float> {
        val angle = (sector + 0.5) * (2 * Math.PI / CommandWheel.SECTORS)
        return (0.5f + (Math.sin(angle) * distance).toFloat()) to
            (0.5f - (Math.cos(angle) * distance).toFloat())
    }

    /** Vise un secteur assez longtemps pour que la visée soit tenue, puis valide. */
    private fun select(wheel: CommandWheel, sector: Int, startMs: Long = 0): Int? {
        val (x, y) = towards(sector)
        var t = startMs
        repeat(6) { wheel.update(x, y, t); t += 33 }
        return wheel.update(x, y, t, commit = true)
    }

    // --- Direction ---------------------------------------------------------------------------

    /**
     * Chaque direction doit désigner son propre secteur. C'est la seule propriété dont dépend toute
     * la roue : si deux directions se confondaient, deux commandes deviendraient inatteignables.
     */
    @Test
    fun `chaque direction designe son propre secteur`() {
        val selected = (0 until CommandWheel.SECTORS).map { select(CommandWheel(), it) }
        assertEquals((0 until CommandWheel.SECTORS).toList(), selected)
    }

    /**
     * La tolérance est **angulaire**, donc indépendante de la distance au centre. C'est ce qui rend
     * la sélection insensible à la précision du pointage, là où une grille de boutons exige de
     * viser une surface.
     */
    @Test
    fun `la selection ne depend pas de la distance au centre`() {
        for (distance in listOf(0.2f, 0.35f, 0.5f)) {
            val wheel = CommandWheel()
            val (x, y) = towards(2, distance)
            var t = 0L
            repeat(6) { wheel.update(x, y, t); t += 33 }
            assertEquals("distance $distance", 2, wheel.update(x, y, t, commit = true))
        }
    }

    /** Au centre, rien n'est visé : c'est la zone de repos, et la façon de ne rien choisir. */
    @Test
    fun `le centre ne designe aucun secteur`() {
        val wheel = CommandWheel()
        assertNull(wheel.update(0.5f, 0.52f, 0, commit = true))
        assertEquals(-1, wheel.state(labels).hoveredSector)
    }

    // --- Validation --------------------------------------------------------------------------

    /** Sans clic, viser ne valide rien : la roue se parcourt sans risque. */
    @Test
    fun `survoler sans cliquer n execute rien`() {
        val wheel = CommandWheel()
        val (x, y) = towards(4)
        var t = 0L
        repeat(30) {
            assertNull(wheel.update(x, y, t))
            t += 33
        }
    }

    /**
     * Un clic qui tombe pendant que la main traverse un secteur ne doit rien valider. Sans ce
     * délai, aller vers une direction en cliquant au passage exécuterait une commande voisine —
     * c'est-à-dire la mauvaise.
     */
    @Test
    fun `un clic au passage ne valide pas le secteur traverse`() {
        val wheel = CommandWheel()
        val (x, y) = towards(5)
        // Première image dans ce secteur, et clic immédiat.
        assertNull(wheel.update(x, y, 0, commit = true))
    }

    /** Un second clic sans quitter le secteur ne doit pas réexécuter la même commande. */
    @Test
    fun `un clic maintenu ne reexecute pas la commande`() {
        val wheel = CommandWheel()
        val sector = select(wheel, 1)
        assertEquals(1, sector)

        val (x, y) = towards(1)
        assertNull("la commande est repartie aussitôt", wheel.update(x, y, 240, commit = true))
    }

    /** Changer de direction réarme la visée : la commande suit la main, pas l'historique. */
    @Test
    fun `changer de direction reamorce la visee`() {
        val wheel = CommandWheel()
        assertEquals(0, select(wheel, 0))
        assertEquals(4, select(wheel, 4, startMs = 1000))
    }

    // --- Affichage ---------------------------------------------------------------------------

    @Test
    fun `l etat affiche le secteur vise et la progression`() {
        val wheel = CommandWheel()
        val (x, y) = towards(3)
        var t = 0L
        repeat(4) { wheel.update(x, y, t, clickProgress = 0.5f); t += 33 }

        val state = wheel.state(labels)
        assertEquals(3, state.hoveredSector)
        assertEquals(labels, state.labels)
        assertEquals(0.5f, state.progress, 1e-5f)
    }

    @Test
    fun `la remise a zero oublie la visee`() {
        val wheel = CommandWheel()
        val (x, y) = towards(6)
        wheel.update(x, y, 0)
        wheel.reset()
        assertEquals(-1, wheel.state(labels).hoveredSector)
        assertTrue(wheel.state(labels).progress == 0f)
    }
}
