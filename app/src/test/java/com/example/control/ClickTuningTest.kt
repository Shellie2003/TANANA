package com.example.control

import com.example.gesture.ClickTrigger
import com.example.gesture.PinchDetector
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

    /**
     * Joue une fermeture s'arrêtant à une profondeur donnée et dit si elle enfonce le bouton.
     *
     * Le réglage portait auparavant sur la **vitesse** de la fermeture, et ce test mesurait des
     * durées. Le critère de vitesse a été retiré : exprimé en millisecondes mais évalué en images,
     * il dépendait de la cadence réelle de la caméra, qui tombe à 12-20 images par seconde sur un
     * téléphone, et rejetait alors les gestes volontaires mais posés. Ce qui se règle désormais est
     * la profondeur exigée, qui ne dépend d'aucune horloge.
     */
    private fun pressesWith(tuning: ClickTuning, floor: Float): Boolean {
        // Déclencheur explicite : ce test joue des trajectoires de **pincement**, et la
        // profondeur atteignable dépend du geste. Laisser la valeur par défaut — l'ouverture de la
        // main — mesurerait un réglage contre un signal qui n'est pas le sien.
        val d = PinchDetector(tuning.toConfig(ClickTrigger.PINCH))
        var t = 0L
        repeat(40) { d.update(0.85f, 0.5f, 0.5f, t); t += frameMs }

        var pressed = false
        val trace = (1..8).map { 0.85f - (0.85f - floor) * it / 8f } +
            List(6) { floor } +
            List(4) { 0.85f }
        trace.forEach { ratio ->
            if (d.update(ratio, 0.5f, 0.5f, t) == PinchDetector.Event.PRESS) pressed = true
            t += frameMs
        }
        return pressed
    }

    @Test
    fun `un contact franc est accepte par les trois reglages`() {
        ClickTuning.entries.forEach { tuning ->
            assertTrue("$tuning refuse un contact franc", pressesWith(tuning, floor = 0.10f))
        }
    }

    /**
     * Les trois réglages doivent se séparer sur des profondeurs réelles, pas seulement dans leurs
     * constantes. Les points ci-dessous sont mesurés sur une course au repos de 0,85 et le plancher
     * supposé du premier geste.
     */
    @Test
    fun `chaque cran supplementaire exige une fermeture plus profonde`() {
        // Fermeture nettement partielle : seul « Souple » l'accepte.
        assertTrue("« Souple » doit accepter une fermeture partielle", pressesWith(ClickTuning.RELAXED, 0.44f))
        assertTrue(!pressesWith(ClickTuning.STANDARD, 0.44f))
        assertTrue(!pressesWith(ClickTuning.BRISK, 0.44f))

        // Fermeture presque complète : « Vif » décroche encore, les deux autres passent.
        assertTrue(pressesWith(ClickTuning.RELAXED, 0.38f))
        assertTrue(pressesWith(ClickTuning.STANDARD, 0.38f))
        assertTrue("« Vif » doit exiger le fond de la course", !pressesWith(ClickTuning.BRISK, 0.38f))

        // Main qui se referme à peine : aucun réglage ne doit y voir un appui.
        ClickTuning.entries.forEach { tuning ->
            assertTrue(
                "$tuning confond une main à peine refermée avec un appui",
                !pressesWith(tuning, 0.60f)
            )
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
