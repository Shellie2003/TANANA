package com.example.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Le défilement est le geste que l'utilisateur a signalé comme impraticable, et ces tests
 * décrivent exactement les trois propriétés qui lui manquaient : de la course, de l'élan, et un
 * effort proportionné.
 */
class StrokeKinematicsTest {

    /** Zone défilable couvrant tout l'écran, les deux axes. */
    private val fullScreen = StrokeKinematics.Area(0f, 0f, 1f, 1f)

    /** Rejoue un déplacement de curseur en ligne droite et retourne les avances produites. */
    private fun drive(
        k: StrokeKinematics,
        fromX: Float,
        fromY: Float,
        stepX: Float,
        stepY: Float,
        frames: Int
    ): List<StrokeKinematics.Advance> {
        val out = mutableListOf<StrokeKinematics.Advance>()
        var x = fromX
        var y = fromY
        repeat(frames) {
            x += stepX
            y += stepY
            out += k.move(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        }
        return out
    }

    // --- Mode direct : rien ne change ---------------------------------------------------------

    /**
     * Hors zone défilable, le comportement doit être **exactement** celui d'avant : amplifier un
     * glisser d'icône ou le curseur d'un réglage le rendrait faux.
     */
    @Test
    fun `hors zone defilable le rapport reste strictement un pour un`() {
        val k = StrokeKinematics()
        k.begin(0.2f, 0.3f)
        assertEquals(StrokeKinematics.Mode.DIRECT, k.mode)

        val advances = drive(k, 0.2f, 0.3f, 0.05f, 0.02f, frames = 10)
        advances.forEachIndexed { i, a ->
            assertEquals("x à l'image $i", 0.2f + 0.05f * (i + 1), a.x, 1e-4f)
            assertEquals("y à l'image $i", 0.3f + 0.02f * (i + 1), a.y, 1e-4f)
            assertTrue("un repositionnement en mode direct", !a.recenter)
        }
    }

    @Test
    fun `le mode direct ne garantit aucun degagement`() {
        val k = StrokeKinematics()
        k.begin(0.5f, 0.5f)
        assertNull(k.endAdjustment())
    }

    // --- Mode défilement : la course ----------------------------------------------------------

    /**
     * Le doigt part du centre de la zone, et non du point pincé. C'est ce qui donne la même course
     * dans les quatre directions : avant, pincer en bas de l'écran ne laissait presque rien pour
     * défiler vers le bas.
     */
    @Test
    fun `le doigt part du centre de la zone defilable`() {
        val k = StrokeKinematics()
        val start = k.begin(0.2f, 0.95f, fullScreen)
        assertEquals(StrokeKinematics.Mode.SCROLL, k.mode)
        assertEquals(0.5f, start.x, 1e-4f)
        assertEquals(0.5f, start.y, 1e-4f)
    }

    @Test
    fun `la course est la meme dans les quatre directions`() {
        val amplitudes = listOf(
            "bas" to Pair(0f, 0.02f),
            "haut" to Pair(0f, -0.02f),
            "droite" to Pair(0.02f, 0f),
            "gauche" to Pair(-0.02f, 0f)
        ).map { (nom, pas) ->
            val k = StrokeKinematics()
            k.begin(0.5f, 0.5f, fullScreen)
            val advances = drive(k, 0.5f, 0.5f, pas.first, pas.second, frames = 8)
            nom to advances.count { it.recenter }
        }

        // Aucun sens ne doit se heurter à un bord plus vite qu'un autre.
        val counts = amplitudes.map { it.second }.toSet()
        assertEquals("course asymétrique selon le sens : $amplitudes", 1, counts.size)
    }

    // --- Mode défilement : l'effort -----------------------------------------------------------

    /** Un petit ajustement doit rester exact : le gain ne démarre qu'au-delà. */
    @Test
    fun `le premier centimetre reste au rapport un pour un`() {
        val k = StrokeKinematics()
        k.begin(0.5f, 0.5f, fullScreen)
        // Déplacement total de 6 %, sous le seuil de linéarité (9 %).
        val advances = drive(k, 0.5f, 0.5f, 0f, 0.02f, frames = 3)
        assertEquals(0.56f, advances.last().y, 1e-3f)
    }

    /** Au-delà, le même déplacement de main doit produire nettement plus de défilement. */
    @Test
    fun `un deplacement ample est amplifie`() {
        val k = StrokeKinematics()
        k.begin(0.5f, 0.1f, fullScreen)
        val cursorTravel = 0.3f
        val advances = drive(k, 0.1f, 0.1f, 0f, 0.02f, frames = 15)

        val scrolled = scrolledDistance(advances, fromY = 0.5f)
        assertTrue(
            "défilement de $scrolled pour un déplacement de main de $cursorTravel",
            scrolled > cursorTravel * 1.8f
        )
    }

    /** Distance réellement défilée : la somme des pas, les repositionnements exclus. */
    private fun scrolledDistance(advances: List<StrokeKinematics.Advance>, fromY: Float): Float {
        var previous = fromY
        var total = 0f
        for (a in advances) {
            if (!a.recenter) total += abs(a.y - previous)
            previous = a.y
        }
        return total
    }

    // --- Mode défilement : l'élan -------------------------------------------------------------

    /**
     * **La propriété décisive.** Le doigt ne doit jamais se figer : c'est son immobilité, une fois
     * buté sur le bord, qui faisait mesurer à Android une vitesse nulle au relâchement — donc
     * aucune inertie. Tant que la main pousse, chaque image doit produire du mouvement.
     */
    @Test
    fun `le doigt ne se fige jamais tant que la main pousse`() {
        val k = StrokeKinematics()
        k.begin(0.5f, 0.05f, fullScreen)

        var previousX = 0.5f
        var previousY = 0.5f
        var frozen = 0
        // La main traverse l'écran de haut en bas, bien au-delà de ce qu'une seule course permet.
        var y = 0.05f
        repeat(45) {
            y = (y + 0.02f).coerceAtMost(1f)
            val a = k.move(0.5f, y)
            if (!k.isSignificant(a, previousX, previousY)) frozen++
            previousX = a.x
            previousY = a.y
        }

        // Les seules images sans mouvement sont celles où le curseur lui-même est arrivé en butée.
        assertTrue("$frozen images sans mouvement du doigt", frozen <= 2)
    }

    @Test
    fun `le doigt reste toujours dans la zone`() {
        val k = StrokeKinematics()
        val area = StrokeKinematics.Area(0.1f, 0.2f, 0.9f, 0.8f)
        k.begin(0.5f, 0.05f, area)

        var y = 0.05f
        repeat(60) {
            y = (y + 0.02f).coerceAtMost(1f)
            val a = k.move(0.5f, y)
            assertTrue("doigt sorti en y=${a.y}", a.y >= area.top && a.y <= area.bottom)
            assertTrue("doigt sorti en x=${a.x}", a.x >= area.left && a.x <= area.right)
        }
    }

    /** Repositionnement : on repart du bord opposé, pour continuer dans le même sens. */
    @Test
    fun `le repositionnement repart du bord oppose`() {
        val k = StrokeKinematics()
        // Le tracé commence là où le balayage commence : un saut de curseur entre la pose et le
        // premier déplacement serait une discontinuité que le geste réel ne produit pas.
        k.begin(0.5f, 0.05f, fullScreen)

        var y = 0.05f
        var recenter: StrokeKinematics.Advance? = null
        var previousY = 0.5f
        repeat(40) {
            y = (y + 0.02f).coerceAtMost(1f)
            val a = k.move(0.5f, y)
            if (a.recenter && recenter == null) recenter = a
            if (recenter == null) previousY = a.y
        }

        val r = requireNotNull(recenter) { "aucun repositionnement sur une traversée complète" }
        assertTrue("repositionné en bas ($previousY -> ${r.y}) alors qu'on descendait", r.y < previousY)
        assertTrue("repositionné hors de la bande", r.y >= 0.05f)
    }

    // --- Garantie anti-appui ------------------------------------------------------------------

    /**
     * Le défaut le plus dangereux que ce mécanisme pourrait introduire : un tracé reposé puis
     * relâché aussitôt est un contact posé et relevé presque au même endroit — **un appui**. Sur
     * une liste, cela ouvrirait l'élément sous le doigt au lieu de défiler.
     */
    @Test
    fun `un trace repositionne puis relache aussitot est degage avant la levee`() {
        val k = StrokeKinematics()
        k.begin(0.5f, 0.05f, fullScreen)

        // On s'arrête **à l'image même** du repositionnement : c'est le seul instant où le tracé
        // courant n'a encore rien parcouru, donc le seul où il pourrait passer pour un appui.
        var y = 0.05f
        var recenter: StrokeKinematics.Advance? = null
        repeat(40) {
            if (recenter != null) return@repeat
            y = (y + 0.02f).coerceAtMost(1f)
            val a = k.move(0.5f, y)
            if (a.recenter) recenter = a
        }
        val r = requireNotNull(recenter) { "aucun repositionnement" }

        val adjustment = requireNotNull(k.endAdjustment()) {
            "aucun dégagement après un repositionnement : le tracé passerait pour un appui"
        }
        val travel = hypot(adjustment.x - r.x, adjustment.y - r.y)
        assertTrue("dégagement insuffisant : $travel", travel >= 0.02f)
    }

    @Test
    fun `un trace deja ample n a pas besoin de degagement`() {
        val k = StrokeKinematics()
        k.begin(0.5f, 0.2f, fullScreen)
        drive(k, 0.5f, 0.2f, 0f, 0.02f, frames = 6)
        assertNull("dégagement inutile ajouté à un tracé déjà ample", k.endAdjustment())
    }

    // --- Axes ---------------------------------------------------------------------------------

    /**
     * Une liste qui ne défile que verticalement ne doit pas recevoir de composante horizontale :
     * une main qui dérive en diagonale lutterait contre elle.
     */
    @Test
    fun `un axe non defilable est neutralise`() {
        val k = StrokeKinematics()
        val vertical = StrokeKinematics.Area(0f, 0f, 1f, 1f, horizontal = false, vertical = true)
        k.begin(0.5f, 0.5f, vertical)

        val advances = drive(k, 0.5f, 0.5f, 0.02f, 0.02f, frames = 6)
        advances.forEach { assertEquals("dérive horizontale transmise", 0.5f, it.x, 1e-4f) }
        assertTrue("l'axe vertical doit, lui, avancer", advances.last().y > 0.55f)
    }

    @Test
    fun `un carrousel horizontal ignore la derive verticale`() {
        val k = StrokeKinematics()
        val horizontal = StrokeKinematics.Area(0f, 0.3f, 1f, 0.5f, horizontal = true, vertical = false)
        k.begin(0.5f, 0.4f, horizontal)

        val advances = drive(k, 0.5f, 0.4f, 0.02f, 0.02f, frames = 6)
        val centerY = (0.3f + 0.5f) / 2f
        advances.forEach { assertEquals("dérive verticale transmise", centerY, it.y, 1e-4f) }
        assertTrue(advances.last().x > 0.55f)
    }

    // --- Robustesse ---------------------------------------------------------------------------

    @Test
    fun `une zone degeneree ne produit ni NaN ni sortie`() {
        val k = StrokeKinematics()
        k.begin(0.5f, 0.5f, StrokeKinematics.Area(0.5f, 0.5f, 0.5f, 0.5f))
        repeat(10) {
            val a = k.move(0.5f, 0.5f + it * 0.02f)
            assertTrue("NaN produit", !a.x.isNaN() && !a.y.isNaN())
            assertEquals(0.5f, a.x, 1e-3f)
            assertEquals(0.5f, a.y, 1e-3f)
        }
    }

    @Test
    fun `la remise a zero ramene au mode direct`() {
        val k = StrokeKinematics()
        k.begin(0.5f, 0.5f, fullScreen)
        assertEquals(StrokeKinematics.Mode.SCROLL, k.mode)
        k.reset()
        assertEquals(StrokeKinematics.Mode.DIRECT, k.mode)
    }
}
