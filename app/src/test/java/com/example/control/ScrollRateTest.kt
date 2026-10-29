package com.example.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le défilement à vitesse commandée est d'une autre **nature** que les deux précédents, et c'est
 * tout son intérêt : le décalage de la main commande une vitesse, non une distance. La propriété
 * qui en découle — et qu'aucune méthode en contrôle de position ne peut avoir — est qu'un
 * déplacement de main borné défile une distance non bornée.
 *
 * Son coût propre est d'être un mode. Une bonne part de ces tests porte donc sur les façons d'en
 * sortir, puisqu'un mode dont on ne sort pas est un piège.
 */
class ScrollRateTest {

    private class Driver(val rate: ScrollRate = ScrollRate()) {
        var t = 0L
        val steps = mutableListOf<EdgeScroller.Direction>()

        fun frames(count: Int, x: Float, y: Float, pressed: Boolean = false) {
            repeat(count) {
                rate.update(x, y, pressed, t)?.let { steps += it }
                t += 33L
            }
        }

        /**
         * Arme le mode puis laisse la main se poser à l'endroit voulu, pour que le point neutre y
         * soit pris. C'est la séquence réelle : on choisit la commande au bord droit, on ramène la
         * main devant ce qu'on lit, on s'arrête.
         */
        fun armAt(x: Float, y: Float) {
            rate.enter(0.97f, 0.5f)
            frames(15, x, y)
            steps.clear()
        }
    }

    // --- La propriété qui justifie la méthode --------------------------------------------------

    /**
     * **Le point entier.** Un décalage modeste, tenu, produit un nombre de pas qui ne dépend que du
     * temps — donc une distance défilée sans rapport avec la distance parcourue par la main.
     */
    @Test
    fun `un decalage tenu defile sans fin`() {
        val d = Driver()
        d.armAt(0.5f, 0.5f)
        d.frames(150, 0.5f, 0.68f)
        assertTrue("seulement ${d.steps.size} pas en cinq secondes", d.steps.size >= 8)
        assertTrue(d.steps.all { it == EdgeScroller.Direction.DOWN })
    }

    @Test
    fun `la vitesse croit avec le decalage`() {
        val lent = Driver()
        lent.armAt(0.5f, 0.5f)
        lent.frames(120, 0.5f, 0.59f)

        val vif = Driver()
        vif.armAt(0.5f, 0.5f)
        vif.frames(120, 0.5f, 0.85f)

        assertTrue(
            "lent ${lent.steps.size} pas, vif ${vif.steps.size} pas",
            vif.steps.size > lent.steps.size * 2
        )
    }

    @Test
    fun `les deux sens verticaux et horizontaux sont atteignables`() {
        fun sens(x: Float, y: Float): EdgeScroller.Direction? {
            val d = Driver()
            d.armAt(0.5f, 0.5f)
            d.frames(60, x, y)
            return d.steps.firstOrNull()
        }

        assertEquals(EdgeScroller.Direction.DOWN, sens(0.5f, 0.80f))
        assertEquals(EdgeScroller.Direction.UP, sens(0.5f, 0.20f))
        assertEquals(EdgeScroller.Direction.RIGHT, sens(0.80f, 0.5f))
        assertEquals(EdgeScroller.Direction.LEFT, sens(0.20f, 0.5f))
    }

    /**
     * La quasi-totalité du contenu défile verticalement, et une main qui descend ne descend jamais
     * parfaitement droit : un geste vertical un peu oblique doit rester vertical.
     */
    @Test
    fun `un geste vertical oblique reste vertical`() {
        val d = Driver()
        d.armAt(0.5f, 0.5f)
        // Descente franche avec une dérive latérale de la moitié : plausible à bras tendu.
        d.frames(60, 0.5f + 0.10f, 0.5f + 0.20f)
        assertTrue(
            "le geste a basculé à l'horizontale : ${d.steps.distinct()}",
            d.steps.all { it == EdgeScroller.Direction.DOWN }
        )
    }

    // --- La zone morte -------------------------------------------------------------------------

    /**
     * Sans zone morte, le tremblement d'une main tenue en l'air commanderait une vitesse non nulle
     * et le contenu dériverait en permanence — défaut pire que l'absence de la fonction.
     */
    @Test
    fun `la main au point neutre ne defile pas`() {
        val d = Driver()
        d.armAt(0.5f, 0.5f)
        d.frames(150, 0.5f, 0.5f)
        assertTrue(d.steps.isEmpty())
    }

    @Test
    fun `un tremblement dans la zone morte ne defile pas`() {
        val d = Driver()
        d.armAt(0.5f, 0.5f)
        repeat(150) { i ->
            val jitter = if (i % 2 == 0) 0.02f else -0.02f
            d.rate.update(0.5f + jitter, 0.5f + jitter, false, d.t)?.let { d.steps += it }
            d.t += 33L
        }
        assertTrue("le tremblement a fait défiler", d.steps.isEmpty())
    }

    @Test
    fun `la vitesse commandee est nulle dans la zone morte`() {
        val d = Driver()
        d.armAt(0.5f, 0.5f)
        d.frames(5, 0.5f, 0.52f)
        assertEquals(0f, d.rate.state?.rate ?: -1f, 1e-4f)
        assertNull(d.rate.state?.direction)
    }

    // --- Les sorties ---------------------------------------------------------------------------

    @Test
    fun `rien ne defile hors du mode`() {
        val d = Driver()
        d.frames(150, 0.5f, 0.85f)
        assertTrue(d.steps.isEmpty())
        assertTrue(!d.rate.isActive)
    }

    /**
     * La sortie de secours : un pincement. Elle ne demande pas de retrouver le panneau, et c'est le
     * geste que la main fait spontanément quand on veut reprendre la main sur le contenu.
     */
    @Test
    fun `un pincement sort du mode`() {
        val d = Driver()
        d.armAt(0.5f, 0.5f)
        d.frames(30, 0.5f, 0.80f)
        assertTrue(d.steps.isNotEmpty())

        d.frames(1, 0.5f, 0.80f, pressed = true)
        assertTrue("le pincement n'a pas fermé le mode", !d.rate.isActive)

        val avant = d.steps.size
        d.frames(60, 0.5f, 0.80f)
        assertEquals("le défilement a continué après la sortie", avant, d.steps.size)
    }

    @Test
    fun `la commande entre et sort`() {
        val r = ScrollRate()
        assertTrue(r.toggle(0.3f, 0.7f))
        assertTrue(!r.toggle(0.3f, 0.7f))
        assertNull(r.state)
    }

    // --- Le point neutre ----------------------------------------------------------------------

    /**
     * **Le défaut que ce test interdit, et qui aurait saboté la méthode dès son premier usage.**
     *
     * La commande se choisit dans le panneau, donc au bord **droit** de l'écran. Si le point neutre
     * y était fixé, ramener la main vers ce qu'on lit — c'est-à-dire vers la gauche — commanderait
     * aussitôt un défilement horizontal vers la gauche. Le mode se mettrait à défiler tout seul, sur
     * le mauvais axe, au moment même de son activation.
     */
    @Test
    fun `le point neutre n est pas celui du bord ou la commande est choisie`() {
        val d = Driver()
        // Activation au bord droit, puis la main revient au centre et s'y arrête.
        d.rate.enter(0.97f, 0.5f)
        d.frames(6, 0.70f, 0.5f)
        d.frames(30, 0.50f, 0.5f)
        assertTrue(
            "le retour vers le contenu a déclenché un défilement : ${d.steps.distinct()}",
            d.steps.none { it == EdgeScroller.Direction.LEFT }
        )
        // Et le point neutre retenu est bien là où la main s'est posée.
        assertEquals(0.50f, d.rate.state?.neutralX ?: -1f, 1e-3f)
    }

    @Test
    fun `rien ne defile tant que la main ne s est pas posee`() {
        val d = Driver()
        d.rate.enter(0.97f, 0.5f)
        // La main traverse l'écran sans jamais s'arrêter : aucun point neutre, donc aucun pas.
        repeat(40) { i ->
            d.rate.update(0.95f - i * 0.02f, 0.5f, false, d.t)?.let { d.steps += it }
            d.t += 33L
        }
        assertTrue("un défilement est parti sans point neutre", d.steps.isEmpty())
        assertEquals(false, d.rate.state?.ready)
    }

    /**
     * Le point neutre est pris là où la main se pose, et non au centre de l'écran : on active ce
     * mode en lisant, donc la main va dans une position tenable. Imposer un centre obligerait à
     * l'y amener, ce qui est l'effort que ce mode supprime.
     */
    @Test
    fun `le point neutre est la ou la main se pose`() {
        val d = Driver()
        d.armAt(0.25f, 0.75f)
        // Immobile à l'endroit où elle s'est posée : rien ne doit partir, même loin du centre.
        d.frames(90, 0.25f, 0.75f)
        assertTrue(d.steps.isEmpty())
        assertEquals(0.25f, d.rate.state?.neutralX ?: -1f, 1e-3f)
        assertEquals(0.75f, d.rate.state?.neutralY ?: -1f, 1e-3f)
    }
}
