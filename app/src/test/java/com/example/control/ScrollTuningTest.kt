package com.example.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Un réglage qui ne change rien de perceptible est pire qu'aucun réglage : l'utilisateur le
 * modifie, ne constate aucune différence, et en conclut que l'application ne l'écoute pas. Ces
 * tests vérifient que les trois positions produisent bien trois comportements distincts, sur le
 * même déplacement de main.
 */
class ScrollTuningTest {

    /** Défilement produit par un déplacement de main donné, pour un réglage donné. */
    private fun scrolledWith(tuning: ScrollTuning, frames: Int = 15, step: Float = 0.02f): Float {
        val k = StrokeKinematics(tuning.toConfig())
        val area = StrokeKinematics.Area(0f, 0f, 1f, 1f)
        k.begin(0.5f, 0.1f, area)

        var y = 0.1f
        var previous = 0.5f
        var total = 0f
        repeat(frames) {
            y = (y + step).coerceAtMost(1f)
            val a = k.move(0.5f, y)
            if (!a.recenter) total += kotlin.math.abs(a.y - previous)
            previous = a.y
        }
        return total
    }

    @Test
    fun `chaque position defile plus que la precedente`() {
        val precise = scrolledWith(ScrollTuning.PRECISE)
        val standard = scrolledWith(ScrollTuning.STANDARD)
        val swift = scrolledWith(ScrollTuning.SWIFT)

        assertTrue("précis=$precise standard=$standard", standard > precise * 1.2f)
        assertTrue("standard=$standard rapide=$swift", swift > standard * 1.2f)
    }

    /**
     * Le début du geste reste au rapport 1:1 quelle que soit la position : un petit ajustement doit
     * être exact même en « Rapide », sans quoi le réglage rendrait l'application imprécise au lieu
     * de la rendre rapide.
     */
    @Test
    fun `le debut du geste reste exact dans les trois positions`() {
        ScrollTuning.entries.forEach { tuning ->
            val k = StrokeKinematics(tuning.toConfig())
            k.begin(0.5f, 0.5f, StrokeKinematics.Area(0f, 0f, 1f, 1f))
            // Déplacement total de 6 %, sous le seuil de linéarité.
            var y = 0.5f
            var last = 0.5f
            repeat(3) {
                y += 0.02f
                last = k.move(0.5f, y).y
            }
            assertEquals("$tuning déforme un petit ajustement", 0.56f, last, 1e-3f)
        }
    }

    @Test
    fun `un reglage inconnu retombe sur la valeur par defaut`() {
        assertEquals(ScrollTuning.DEFAULT, ScrollTuning.fromId("n'existe pas"))
        assertEquals(ScrollTuning.DEFAULT, ScrollTuning.fromId(null))
    }

    @Test
    fun `les identifiants enregistres sont stables et distincts`() {
        val ids = ScrollTuning.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        // Écrits dans les préférences : les renommer invaliderait les réglages déjà enregistrés.
        assertEquals(listOf("precise", "standard", "swift"), ids)
    }

    @Test
    fun `chaque position est libellee et expliquee`() {
        ScrollTuning.entries.forEach {
            assertTrue("${it.name} sans libellé", it.label.isNotBlank())
            assertTrue("${it.name} sans explication utile", it.description.length > 20)
        }
    }
}
