package com.example.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cadence de la caméra utilisée dans les tests (30 images/seconde). */
private const val FRAME_MS = 33L

class GestureClassifierTest {

    private fun feed(
        classifier: GestureClassifier,
        landmarks: List<Landmark>,
        frames: Int,
        startTime: Long
    ): Pair<GestureResult, Long> {
        var t = startTime
        var last = GestureResult(GestureType.IDLE)
        repeat(frames) {
            last = classifier.update(HandGeometry.analyze(landmarks), t)
            t += FRAME_MS
        }
        return last to t
    }

    @Test
    fun `pincement franc produit exactement un clic et un relachement`() {
        val classifier = GestureClassifier()
        var t = 0L
        var clicks = 0
        var releases = 0

        repeat(10) { classifier.update(SyntheticHand.metrics(SyntheticHand.openPalm()), t); t += FRAME_MS }
        repeat(10) {
            classifier.update(SyntheticHand.metrics(SyntheticHand.pinching()), t)
            if (classifier.pinchJustClosed) clicks++
            t += FRAME_MS
        }
        repeat(10) {
            classifier.update(SyntheticHand.metrics(SyntheticHand.openPalm()), t)
            if (classifier.pinchJustOpened) releases++
            t += FRAME_MS
        }

        assertEquals(1, clicks)
        assertEquals(1, releases)
    }

    /**
     * Régression centrale : un ratio de pincement qui oscille autour du seuil ne doit pas
     * produire de rafale de clics. C'est le rôle de l'hystérésis.
     */
    @Test
    fun `oscillation autour du seuil ne genere pas de rafale de clics`() {
        val classifier = GestureClassifier()
        var t = 0L
        var clicks = 0

        repeat(40) { i ->
            val ratio = if (i % 2 == 0) 0.33f else 0.36f
            val hand = SyntheticHand.build(pinchDistance = ratio * 0.9f)
            classifier.update(HandGeometry.analyze(hand), t)
            if (classifier.pinchJustClosed) clicks++
            t += FRAME_MS
        }

        assertTrue("clics parasites: $clicks", clicks <= 1)
    }

    @Test
    fun `poing reconnu`() {
        val (result, _) = feed(GestureClassifier(), SyntheticHand.fist(), frames = 8, startTime = 0)
        assertEquals(GestureType.FIST, result.gesture)
    }

    @Test
    fun `pointage reconnu`() {
        val (result, _) = feed(GestureClassifier(), SyntheticHand.pointing(), frames = 8, startTime = 0)
        assertEquals(GestureType.POINT, result.gesture)
    }

    @Test
    fun `main ouverte reconnue`() {
        val (result, _) = feed(GestureClassifier(), SyntheticHand.openPalm(), frames = 8, startTime = 0)
        assertEquals(GestureType.OPEN_PALM, result.gesture)
    }

    @Test
    fun `balayage rapide vers la droite detecte une seule fois`() {
        val classifier = GestureClassifier()
        var t = 0L
        var events = 0
        var direction: GestureType? = null

        for (i in 0..6) {
            val result = classifier.update(
                SyntheticHand.metrics(SyntheticHand.openPalm(cx = 0.32f + i * 0.06f)), t
            )
            if (result.isSwipeEvent) {
                events++
                direction = result.gesture
            }
            t += FRAME_MS
        }

        assertEquals(1, events)
        assertEquals(GestureType.SWIPE_RIGHT, direction)
    }

    @Test
    fun `mouvement lent de meme amplitude n est pas un balayage`() {
        val classifier = GestureClassifier()
        var t = 0L
        var events = 0

        for (i in 0..30) {
            if (classifier.update(
                    SyntheticHand.metrics(SyntheticHand.openPalm(cx = 0.30f + i * 0.012f)), t
                ).isSwipeEvent
            ) events++
            t += 60
        }

        assertEquals(0, events)
    }

    @Test
    fun `aller-retour rapide n est pas un balayage`() {
        val classifier = GestureClassifier()
        var t = 0L
        var events = 0

        for (x in floatArrayOf(0.35f, 0.55f, 0.35f, 0.58f, 0.34f, 0.60f)) {
            if (classifier.update(SyntheticHand.metrics(SyntheticHand.openPalm(cx = x)), t).isSwipeEvent) {
                events++
            }
            t += FRAME_MS
        }

        assertEquals(0, events)
    }

    @Test
    fun `deux balayages enchaines respectent le temps de garde`() {
        val classifier = GestureClassifier()
        var t = 0L
        var events = 0

        repeat(2) {
            for (i in 0..6) {
                if (classifier.update(
                        SyntheticHand.metrics(SyntheticHand.openPalm(cx = 0.32f + i * 0.06f)), t
                    ).isSwipeEvent
                ) events++
                t += FRAME_MS
            }
        }

        assertEquals(1, events)
    }

    @Test
    fun `perte breve de la main preserve le geste en cours`() {
        val classifier = GestureClassifier()
        val (_, t) = feed(classifier, SyntheticHand.pinching(), frames = 6, startTime = 0)
        assertEquals(GestureType.PINCH, classifier.update(null, t + 100).gesture)
    }

    @Test
    fun `perte prolongee remet la machine a zero`() {
        val classifier = GestureClassifier()
        val (_, t) = feed(classifier, SyntheticHand.pinching(), frames = 6, startTime = 0)
        assertEquals(GestureType.IDLE, classifier.update(null, t + 3000).gesture)
    }

    @Test
    fun `detection de faible qualite ne declenche aucune action`() {
        val classifier = GestureClassifier()
        // Main minuscule et hors cadre : qualité sous le seuil d'action.
        val degraded = SyntheticHand.build(cx = 1.4f, scale = 0.02f, pinchDistance = 0.1f)
        var t = 0L
        repeat(10) {
            classifier.update(HandGeometry.analyze(degraded), t)
            t += FRAME_MS
        }
        assertTrue("un clic a été émis sur une détection douteuse", !classifier.pinchJustClosed)
    }

    @Test
    fun `force de pincement croit quand les doigts se rapprochent`() {
        val classifier = GestureClassifier()
        val loose = classifier.update(SyntheticHand.metrics(SyntheticHand.openPalm()), 0)
        val tight = GestureClassifier()
            .update(SyntheticHand.metrics(SyntheticHand.pinching()), 0)
        assertTrue("${loose.pinchStrength} >= ${tight.pinchStrength}", tight.pinchStrength > loose.pinchStrength)
    }
}
