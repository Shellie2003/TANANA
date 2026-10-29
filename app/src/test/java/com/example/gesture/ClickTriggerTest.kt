package com.example.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le détecteur juge un scalaire qui **descend** vers un seuil appris. L'étendue de la main, elle,
 * **monte** quand on s'approche du déclenchement. Tout l'enjeu de ce fichier est que l'inversion
 * soit correcte, parce que si elle ne l'est pas le clic se déclenche exactement à l'envers — main
 * détendue — et rien dans le détecteur ne permettrait de s'en apercevoir.
 */
class ClickTriggerTest {

    private fun hand(extension: Float, pinch: Float = 0.9f) = SyntheticHand.metrics(
        SyntheticHand.build(
            extension = floatArrayOf(extension, extension, extension, extension),
            pinchDistance = pinch
        )
    )

    @Test
    fun `ouvrir la main fait descendre le signal`() {
        val detendue = ClickTrigger.OPEN_HAND.signalFrom(hand(0.35f))
        val ouverte = ClickTrigger.OPEN_HAND.signalFrom(hand(1f))
        assertTrue(
            "détendue=$detendue ouverte=$ouverte : le signal monte au lieu de descendre",
            ouverte < detendue
        )
    }

    @Test
    fun `pincer fait descendre le signal`() {
        val ouvert = ClickTrigger.PINCH.signalFrom(hand(1f, pinch = 0.9f))
        val ferme = ClickTrigger.PINCH.signalFrom(hand(1f, pinch = 0.08f))
        assertTrue("ouvert=$ouvert fermé=$ferme", ferme < ouvert)
    }

    /**
     * Le signal retourné doit tomber dans les bornes de ligne de base du détecteur, faute de quoi
     * la référence serait écrêtée et la course mesurée fausse.
     */
    @Test
    fun `le signal de la main ouverte reste dans les bornes du detecteur`() {
        val config = ClickTrigger.OPEN_HAND.detectorConfig()
        for (ext in listOf(0f, 0.35f, 0.7f, 1f)) {
            val signal = ClickTrigger.OPEN_HAND.signalFrom(hand(ext))
            assertTrue(
                "extension $ext -> signal $signal hors de [${config.baselineMin}, ${config.baselineMax}]",
                signal in config.baselineMin..config.baselineMax
            )
        }
    }

    /**
     * Un détecteur alimenté par l'ouverture doit produire un appui, et il doit le produire **en
     * ouvrant** la main. C'est le bout-en-bout, et c'est ce qui vérifie que l'inversion sert à
     * quelque chose plutôt que d'être seulement cohérente.
     */
    @Test
    fun `ouvrir la main enfonce le bouton`() {
        val d = PinchDetector(ClickTrigger.OPEN_HAND.detectorConfig())
        var t = 0L
        val frame = 33L

        // Main détendue : la ligne de base s'apprend.
        repeat(40) {
            d.update(ClickTrigger.OPEN_HAND.signalFrom(hand(0.35f)), 0.5f, 0.5f, t)
            t += frame
        }

        var presses = 0
        val trace = listOf(0.35f, 0.5f, 0.7f, 0.9f, 1f, 1f, 1f, 0.6f, 0.35f, 0.35f, 0.35f)
        trace.forEach { ext ->
            if (d.update(ClickTrigger.OPEN_HAND.signalFrom(hand(ext)), 0.5f, 0.5f, t) ==
                PinchDetector.Event.PRESS
            ) {
                presses++
            }
            t += frame
        }
        assertEquals("l'ouverture n'a pas enfoncé le bouton", 1, presses)
    }

    /** Et une main qui reste détendue ne doit rien déclencher, même longuement observée. */
    @Test
    fun `une main detendue ne declenche jamais`() {
        val d = PinchDetector(ClickTrigger.OPEN_HAND.detectorConfig())
        var t = 0L
        var presses = 0
        repeat(300) { i ->
            // Repos avec une variation lente d'extension, comme une main qui se fatigue.
            val ext = 0.35f + 0.08f * kotlin.math.sin(i / 40.0).toFloat()
            if (d.update(ClickTrigger.OPEN_HAND.signalFrom(hand(ext)), 0.5f, 0.5f, t) ==
                PinchDetector.Event.PRESS
            ) {
                presses++
            }
            t += 33L
        }
        assertEquals("appuis fantômes sur une main au repos", 0, presses)
    }

    @Test
    fun `les identifiants enregistres sont stables`() {
        assertEquals(ClickTrigger.OPEN_HAND, ClickTrigger.fromId("open_hand"))
        assertEquals(ClickTrigger.PINCH, ClickTrigger.fromId("pinch"))
        assertEquals(ClickTrigger.DEFAULT, ClickTrigger.fromId("n'existe pas"))
        assertEquals(ClickTrigger.DEFAULT, ClickTrigger.fromId(null))
    }
}
