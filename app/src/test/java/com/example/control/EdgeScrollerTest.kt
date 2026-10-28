package com.example.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le défilement par les bords existe pour une raison précise : c'est le seul chemin de défilement
 * qui ne demande **aucun pincement maintenu**, donc le seul qui ne dépende pas de la mesure la plus
 * fragile de la chaîne — l'écart pouce-index, que le pincement lui-même perturbe en confondant les
 * deux extrémités dans l'image.
 *
 * Son risque propre est symétrique de sa facilité : une bande de bord est traversée en permanence,
 * ne serait-ce que pour viser ce qui s'y trouve. L'essentiel de ces tests porte donc sur ce qui
 * **empêche** le déclenchement.
 */
class EdgeScrollerTest {

    private val frameMs = 33L

    private class Driver(val scroller: EdgeScroller = EdgeScroller()) {
        var t = 0L
        val steps = mutableListOf<EdgeScroller.Direction>()

        fun frames(count: Int, x: Float, y: Float, pressed: Boolean = false) {
            repeat(count) {
                scroller.update(x, y, pressed, t)?.let { steps += it }
                t += 33L
            }
        }
    }

    // --- Ce qui doit marcher -------------------------------------------------------------------

    @Test
    fun `se poser en haut fait defiler vers le haut`() {
        val d = Driver()
        d.frames(30, 0.5f, 0.02f)
        assertTrue("aucun pas de défilement", d.steps.isNotEmpty())
        assertTrue(d.steps.all { it == EdgeScroller.Direction.UP })
    }

    @Test
    fun `se poser en bas fait defiler vers le bas`() {
        val d = Driver()
        d.frames(30, 0.5f, 0.98f)
        assertTrue(d.steps.isNotEmpty())
        assertTrue(d.steps.all { it == EdgeScroller.Direction.DOWN })
    }

    /**
     * Les deux sens de l'horizontale, sur le seul bord gauche : le bord droit est au panneau de
     * commandes, et deux mécanismes sur le même bord seraient indistinguables pour la main.
     */
    @Test
    fun `le bord gauche donne les deux sens horizontaux selon la hauteur`() {
        val haut = Driver()
        haut.frames(30, 0.02f, 0.25f)
        assertEquals(listOf(EdgeScroller.Direction.LEFT), haut.steps.distinct())

        val bas = Driver()
        bas.frames(30, 0.02f, 0.75f)
        assertEquals(listOf(EdgeScroller.Direction.RIGHT), bas.steps.distinct())
    }

    /**
     * La cadence suit l'enfoncement dans la bande, et c'est ce qui rend la méthode utilisable pour
     * les deux usages opposés : chercher quelque chose ligne à ligne, ou traverser un article.
     */
    @Test
    fun `plus le curseur est enfonce dans la bande plus le defilement est rapide`() {
        val bord = Driver()
        bord.frames(60, 0.5f, 0.001f)
        val lisiere = Driver()
        lisiere.frames(60, 0.5f, 0.079f)

        assertTrue(
            "ras du bord ${bord.steps.size} pas, lisière ${lisiere.steps.size} pas",
            bord.steps.size > lisiere.steps.size * 2
        )
    }

    // --- Ce qui doit être empêché --------------------------------------------------------------

    /**
     * **Le défaut qui rendrait la méthode insupportable.** Viser un bouton situé en haut de l'écran
     * passe par la bande ; si le défilement partait aussitôt, le contenu fuirait sous le curseur
     * avant qu'on l'atteigne.
     */
    @Test
    fun `traverser une bande ne declenche rien`() {
        val d = Driver()
        // Six images dans la bande — environ 200 ms, moins que le délai d'amorçage — puis ailleurs.
        d.frames(6, 0.5f, 0.03f)
        d.frames(10, 0.5f, 0.5f)
        assertTrue("un passage a suffi à déclencher", d.steps.isEmpty())
    }

    @Test
    fun `le centre de l ecran ne defile jamais`() {
        val d = Driver()
        d.frames(60, 0.5f, 0.5f)
        assertTrue(d.steps.isEmpty())
    }

    /**
     * Un pincement en cours signifie que l'utilisateur mène un geste à la main. Les deux
     * défilements se combattraient, et celui qu'il n'a pas demandé gagnerait la moitié du temps.
     */
    @Test
    fun `un pincement en cours inhibe le defilement par les bords`() {
        val d = Driver()
        d.frames(60, 0.5f, 0.02f, pressed = true)
        assertTrue(d.steps.isEmpty())
    }

    /** Et quitter la bande arrête le défilement sans délai. */
    @Test
    fun `quitter la bande arrete le defilement`() {
        val d = Driver()
        d.frames(30, 0.5f, 0.02f)
        val avant = d.steps.size
        d.frames(30, 0.5f, 0.5f)
        assertEquals("le défilement a continué hors de la bande", avant, d.steps.size)
        assertNull(d.scroller.state)
    }

    /**
     * Les coins sont exclus des bandes verticales, sans quoi ils appartiendraient à deux bandes à
     * la fois et la direction dépendrait de l'ordre d'évaluation — donc serait imprévisible.
     */
    @Test
    fun `un coin ne produit pas une direction verticale`() {
        val d = Driver()
        d.frames(30, 0.03f, 0.02f)
        assertTrue(
            "le coin a produit une direction verticale : ${d.steps.distinct()}",
            d.steps.none { it == EdgeScroller.Direction.UP || it == EdgeScroller.Direction.DOWN }
        )
    }

    // --- L'état affiché ------------------------------------------------------------------------

    /**
     * Un défilement qui part sans que rien ne l'annonce serait vécu comme une dérive de
     * l'application. L'amorçage doit donc être visible pendant qu'il court.
     */
    @Test
    fun `l amorcage est observable avant le premier pas`() {
        val d = Driver()
        d.frames(3, 0.5f, 0.03f)
        val state = d.scroller.state
        assertNotNull("aucun état à montrer pendant l'amorçage", state)
        assertEquals(EdgeScroller.Direction.UP, state!!.direction)
        assertTrue("progression hors bornes : ${state.progress}", state.progress in 0f..1f)
        assertTrue("l'amorçage est déjà complet", state.progress < 1f)
        assertTrue(d.steps.isEmpty())
    }

    @Test
    fun `changer de bande relance l amorcage`() {
        val d = Driver()
        d.frames(30, 0.5f, 0.02f)
        d.steps.clear()
        // Passage direct d'une bande à l'autre, sans repasser par le centre.
        d.frames(3, 0.5f, 0.98f)
        assertTrue("le défilement a repris sans réamorçage", d.steps.isEmpty())
        assertEquals(EdgeScroller.Direction.DOWN, d.scroller.state?.direction)
    }

    @Test
    fun `les bandes verticales ne se recouvrent pas`() {
        assertTrue(EdgeScroller.bandsAreDisjoint(EdgeScroller.Config()))
    }
}
