package com.example.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le coup sec partage sa pose avec le curseur : il n'en est séparé que par la façon de bouger.
 * C'est donc la seule chose que ces tests éprouvent — un déplacement de visée ne doit jamais
 * faire défiler, et un coup de poignet doit toujours le faire.
 */
class FlickDetectorTest {

    private val frameMs = 33L

    /** Rejoue une trajectoire de curseur et retourne le dernier coup sec reconnu. */
    private fun play(
        detector: FlickDetector,
        points: List<Pair<Float, Float>>,
        startMs: Long = 0L,
        stepMs: Long = frameMs
    ): FlickDetector.Flick? {
        var t = startMs
        var last: FlickDetector.Flick? = null
        points.forEach { (x, y) ->
            detector.update(x, y, t)?.let { last = it }
            t += stepMs
        }
        return last
    }

    private fun count(
        detector: FlickDetector,
        points: List<Pair<Float, Float>>,
        stepMs: Long = frameMs
    ): Int {
        var t = 0L
        var n = 0
        points.forEach { (x, y) ->
            if (detector.update(x, y, t) != null) n++
            t += stepMs
        }
        return n
    }

    /** Trois images immobiles : c'est l'arrêt qui clôt le geste et le rend reconnaissable. */
    private fun settle(x: Float, y: Float) = List(3) { x to y }

    /** Coup sec : quatre images d'élan — 132 ms pour un quart d'écran — puis la main se pose. */
    private fun flickUp() =
        (0..4).map { 0.5f to 0.65f - it * 0.07f } + settle(0.5f, 0.37f)

    private fun flickDown() =
        (0..4).map { 0.5f to 0.35f + it * 0.07f } + settle(0.5f, 0.63f)

    private fun flickRight() =
        (0..4).map { 0.35f + it * 0.07f to 0.5f } + settle(0.63f, 0.5f)

    /** Retour de la main à sa position de départ, assez lentement pour ne rien déclencher. */
    private fun slowReturn(fromY: Float, toY: Float) =
        (1..14).map { 0.5f to fromY + (toY - fromY) * it / 14f } + settle(0.5f, toY)

    // --- Ce qui doit faire défiler --------------------------------------------------------------

    @Test
    fun `un coup sec vers le haut est reconnu`() {
        val f = play(FlickDetector(), flickUp())
        assertNotNull(f)
        assertTrue("axe incorrect", !f!!.horizontal)
        assertTrue("le sens doit être négatif quand la main monte : ${f.delta}", f.delta < 0f)
    }

    @Test
    fun `un coup sec vers le bas est reconnu`() {
        val f = play(FlickDetector(), flickDown())
        assertNotNull(f)
        assertTrue(!f!!.horizontal)
        assertTrue(f.delta > 0f)
    }

    @Test
    fun `un coup sec vers la droite est reconnu sur l axe horizontal`() {
        val f = play(FlickDetector(), flickRight())
        assertNotNull(f)
        assertTrue("axe incorrect", f!!.horizontal)
        assertTrue(f.delta > 0f)
    }

    // --- Ce qui ne doit surtout pas faire défiler ------------------------------------------------

    /**
     * Le cas décisif. La pose du coup sec est celle du curseur : si viser faisait défiler, la
     * pose deviendrait inutilisable pour ce à quoi elle sert le plus.
     */
    @Test
    fun `un deplacement de visee ne fait pas defiler`() {
        // Même amplitude que le coup sec, mais étalée sur une seconde : on regarde où l'on va.
        val slow = (0..30).map { 0.5f to 0.65f - it * 0.011f }
        assertEquals(0, count(FlickDetector(), slow))
    }

    @Test
    fun `une traversee d ecran rapide mais continue ne fait pas defiler`() {
        // Bord à bord en 660 ms : c'est rapide pour un curseur, mais bien plus long qu'un
        // coup de poignet — c'est la durée qui tranche, pas la vitesse seule.
        val sweep = (0..20).map { 0.5f to 0.05f + it * 0.045f }
        assertEquals(0, count(FlickDetector(), sweep))
    }

    @Test
    fun `le tremblement ne fait pas defiler`() {
        val jitter = (0..60).map { 0.5f to 0.5f + if (it % 2 == 0) 0.006f else -0.006f }
        assertEquals(0, count(FlickDetector(), jitter))
    }

    @Test
    fun `un mouvement oblique ne produit aucun defilement`() {
        val diagonal = (0..4).map { 0.35f + it * 0.07f to 0.35f + it * 0.065f } + settle(0.63f, 0.61f)
        assertNull(play(FlickDetector(), diagonal))
    }

    @Test
    fun `un aller-retour ne fait pas defiler`() {
        val backAndForth = listOf(
            0.5f to 0.30f, 0.5f to 0.55f, 0.5f to 0.30f, 0.5f to 0.58f, 0.5f to 0.30f
        )
        assertEquals(0, count(FlickDetector(), backAndForth))
    }

    @Test
    fun `un deplacement trop court ne fait pas defiler`() {
        assertNull(play(FlickDetector(), (0..4).map { 0.5f to 0.50f - it * 0.02f } + settle(0.5f, 0.42f)))
    }

    // --- Cadence --------------------------------------------------------------------------------

    /**
     * Un geste ample ne doit compter qu'une fois. Sans période réfractaire, la fin du mouvement
     * serait relue comme un second coup sec et la page sauterait deux fois.
     */
    @Test
    fun `un seul coup sec ne fait defiler qu une fois`() {
        val long = (0..8).map { 0.5f to 0.85f - it * 0.06f } + settle(0.5f, 0.37f)
        assertEquals(1, count(FlickDetector(), long))
    }

    @Test
    fun `des coups secs successifs sont tous transmis`() {
        val d = FlickDetector()
        var t = 0L
        var n = 0
        repeat(4) {
            (flickUp() + slowReturn(0.37f, 0.65f)).forEach { (x, y) ->
                if (d.update(x, y, t) != null) n++
                t += frameMs
            }
        }
        assertEquals(4, n)
    }

    @Test
    fun `la remise a zero oublie la trajectoire en cours`() {
        val d = FlickDetector()
        var t = 0L
        flickUp().dropLast(4).forEach { (x, y) -> d.update(x, y, t); t += frameMs }
        d.reset()
        flickUp().takeLast(4).forEach { (x, y) -> assertNull(d.update(x, y, t)); t += frameMs }
    }

    /**
     * Le retour de la main après un coup sec ne doit pas produire un défilement en sens inverse :
     * on ramène le bras lentement, et cette lenteur est exactement ce qui l'en distingue.
     */
    @Test
    fun `le retour de la main ne fait pas defiler en sens inverse`() {
        val d = FlickDetector()
        var t = 0L
        var up = 0
        var down = 0
        (flickUp() + slowReturn(0.37f, 0.65f)).forEach { (x, y) ->
            d.update(x, y, t)?.let { if (it.delta < 0f) up++ else down++ }
            t += frameMs
        }
        assertEquals(1, up)
        assertEquals(0, down)
    }
}
