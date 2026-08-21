package com.example.control

import com.example.gesture.ClickDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Un réglage qui ne change rien de perceptible est pire qu'aucun réglage : l'utilisateur le
 * modifie, ne constate aucune différence, et en conclut que l'application ne l'écoute pas. Ces
 * tests vérifient que les trois positions produisent bien trois comportements distincts, sur la
 * même trajectoire de main.
 */
class ClickTuningTest {

    private val frameMs = 33L

    /** Joue une fermeture de vitesse donnée et dit si elle produit un clic. */
    private fun clicksWith(tuning: ClickTuning, closingFrames: Int): Boolean {
        val d = ClickDetector(tuning.toConfig())
        var t = 0L
        repeat(40) { d.update(0.85f, 0.5f, 0.5f, t); t += frameMs }

        var clicked = false
        // Descente régulière de 0,85 jusqu'au contact, en `closingFrames` images.
        for (i in 1..closingFrames) {
            val ratio = 0.85f - (0.75f * i / closingFrames)
            if (d.update(ratio, 0.5f, 0.5f, t) == ClickDetector.Event.CLICK) clicked = true
            t += frameMs
        }
        listOf(0.50f, 0.85f, 0.85f).forEach { ratio ->
            if (d.update(ratio, 0.5f, 0.5f, t) == ClickDetector.Event.CLICK) clicked = true
            t += frameMs
        }
        return clicked
    }

    @Test
    fun `un geste vif est accepte par les trois reglages`() {
        ClickTuning.entries.forEach { tuning ->
            assertTrue("$tuning refuse un geste vif", clicksWith(tuning, closingFrames = 6))
        }
    }

    /**
     * Les trois réglages doivent se séparer sur des vitesses réelles, pas seulement dans leurs
     * constantes. Les points ci-dessous ont été mesurés sur la trajectoire ci-dessus.
     *
     * Noter que la durée jugée n'est pas celle de toute la descente mais celle de sa part sous la
     * bande ouverte : le début du mouvement, où la main n'a encore rien engagé, n'est pas compté.
     */
    @Test
    fun `chaque cran supplementaire rejette les gestes plus lents`() {
        // Fermeture posée : « Vif » décroche, les deux autres passent encore.
        assertTrue(clicksWith(ClickTuning.RELAXED, 14))
        assertTrue(clicksWith(ClickTuning.STANDARD, 14))
        assertTrue("« Vif » doit refuser une fermeture posée", !clicksWith(ClickTuning.BRISK, 14))

        // Fermeture lente : seul « Souple » l'accepte.
        assertTrue("« Souple » doit accepter une fermeture lente", clicksWith(ClickTuning.RELAXED, 18))
        assertTrue(!clicksWith(ClickTuning.STANDARD, 18))

        // Repositionnement de la main : aucun réglage ne doit y voir un clic.
        ClickTuning.entries.forEach { tuning ->
            assertTrue("$tuning confond un repositionnement avec un clic", !clicksWith(tuning, 30))
        }
    }

    @Test
    fun `un reglage inconnu retombe sur la valeur par defaut`() {
        assertEquals(ClickTuning.DEFAULT, ClickTuning.fromId("n'existe pas"))
        assertEquals(ClickTuning.DEFAULT, ClickTuning.fromId(null))
    }

    @Test
    fun `les identifiants enregistres sont stables et distincts`() {
        val ids = ClickTuning.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        // Les identifiants sont écrits dans les préférences : les renommer invaliderait les
        // réglages déjà enregistrés sur les appareils.
        assertEquals(listOf("relaxed", "standard", "brisk"), ids)
    }
}
