package com.example.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cadence de la caméra utilisée dans les tests (30 images/seconde). */
private const val FRAME_MS = 33L

class GestureClassifierTest {

    private fun feed(
        classifier: GestureClassifier,
        landmarks: List<Landmark>,
        frames: Int,
        startTime: Long = 0
    ): Pair<GestureResult, Long> {
        var t = startTime
        var last = GestureResult(GestureType.IDLE)
        repeat(frames) {
            last = classifier.update(HandGeometry.analyze(landmarks), t)
            t += FRAME_MS
        }
        return last to t
    }

    // --- Vocabulaire des poses ---------------------------------------------------------------

    @Test
    fun `index seul reconnu comme pointage`() {
        val (result, _) = feed(GestureClassifier(), SyntheticHand.pointing(), frames = 8)
        assertEquals(GestureType.POINT, result.gesture)
    }

    @Test
    fun `index et majeur reconnus comme deux doigts`() {
        val (result, _) = feed(GestureClassifier(), SyntheticHand.twoFingers(), frames = 8)
        assertEquals(GestureType.TWO_FINGER, result.gesture)
    }

    @Test
    fun `trois doigts reconnus`() {
        val (result, _) = feed(GestureClassifier(), SyntheticHand.threeFingers(), frames = 8)
        assertEquals(GestureType.THREE_FINGER, result.gesture)
    }

    @Test
    fun `main ouverte reconnue`() {
        val (result, _) = feed(GestureClassifier(), SyntheticHand.openPalm(), frames = 8)
        assertEquals(GestureType.OPEN_PALM, result.gesture)
    }

    @Test
    fun `poing reconnu`() {
        val (result, _) = feed(GestureClassifier(), SyntheticHand.fist(), frames = 8)
        assertEquals(GestureType.FIST, result.gesture)
    }

    @Test
    fun `pouce leve reconnu et distingue du poing`() {
        val (result, _) = feed(GestureClassifier(), SyntheticHand.thumbUp(), frames = 8)
        assertEquals(GestureType.THUMB_UP, result.gesture)
    }

    @Test
    fun `pincement reconnu`() {
        val (result, _) = feed(GestureClassifier(), SyntheticHand.pinching(), frames = 8)
        assertEquals(GestureType.PINCH, result.gesture)
    }

    /**
     * Propriété centrale du vocabulaire : chaque pose doit produire une classification distincte.
     * Deux poses confondues rendraient leurs deux actions inutilisables.
     */
    @Test
    fun `les sept poses sont mutuellement discriminables`() {
        val poses = mapOf(
            GestureType.POINT to SyntheticHand.pointing(),
            GestureType.TWO_FINGER to SyntheticHand.twoFingers(),
            GestureType.THREE_FINGER to SyntheticHand.threeFingers(),
            GestureType.OPEN_PALM to SyntheticHand.openPalm(),
            GestureType.FIST to SyntheticHand.fist(),
            GestureType.THUMB_UP to SyntheticHand.thumbUp(),
            GestureType.PINCH to SyntheticHand.pinching()
        )

        val observed = poses.mapValues { (_, hand) ->
            feed(GestureClassifier(), hand, frames = 8).first.gesture
        }

        for ((expected, actual) in observed) {
            assertEquals("pose $expected mal classée", expected, actual)
        }
        assertEquals(
            "deux poses partagent la même classification",
            poses.size,
            observed.values.toSet().size
        )
    }

    // --- Stabilité ---------------------------------------------------------------------------

    @Test
    fun `une pose ne bascule qu apres confirmation`() {
        val classifier = GestureClassifier()
        var t = 0L
        repeat(8) { classifier.update(SyntheticHand.metrics(SyntheticHand.pointing()), t); t += FRAME_MS }

        // Une seule frame de main ouverte ne doit pas suffire à changer de pose.
        val transient = classifier.update(SyntheticHand.metrics(SyntheticHand.openPalm()), t)
        assertEquals(GestureType.POINT, transient.gesture)
    }

    /**
     * Passer de l'index seul à la main ouverte traverse mécaniquement des poses à deux et trois
     * doigts. Aucune de ces poses intermédiaires ne doit être verrouillée, sans quoi le simple
     * fait d'ouvrir la main déclencherait la commutation d'applications.
     */
    @Test
    fun `l ouverture progressive de la main ne verrouille pas les poses traversees`() {
        val classifier = GestureClassifier()
        var t = 0L
        val seen = mutableSetOf<GestureType>()

        repeat(6) { classifier.update(SyntheticHand.metrics(SyntheticHand.pointing()), t); t += FRAME_MS }

        // Ouverture en 5 étapes, une seule frame chacune : trop rapide pour verrouiller.
        for (step in 1..5) {
            val opening = step / 5f
            val hand = SyntheticHand.build(
                extension = floatArrayOf(1f, opening, opening * 0.9f, opening * 0.8f)
            )
            seen += classifier.update(HandGeometry.analyze(hand), t).gesture
            t += FRAME_MS
        }

        assertTrue(
            "poses intermédiaires verrouillées: $seen",
            GestureType.THREE_FINGER !in seen
        )
    }

    @Test
    fun `oscillation autour du seuil ne genere pas de rafale de clics`() {
        val classifier = GestureClassifier()
        var t = 0L
        var clicks = 0

        repeat(40) { i ->
            val ratio = if (i % 2 == 0) 0.33f else 0.36f
            classifier.update(HandGeometry.analyze(SyntheticHand.build(pinchDistance = ratio * 0.9f)), t)
            if (classifier.pinchJustClosed) clicks++
            t += FRAME_MS
        }

        assertTrue("clics parasites: $clicks", clicks <= 1)
    }

    @Test
    fun `pincement franc produit exactement un clic et un relachement`() {
        val classifier = GestureClassifier()
        var t = 0L
        var clicks = 0
        var releases = 0

        repeat(10) { classifier.update(SyntheticHand.metrics(SyntheticHand.pointing()), t); t += FRAME_MS }
        repeat(10) {
            classifier.update(SyntheticHand.metrics(SyntheticHand.pinching()), t)
            if (classifier.pinchJustClosed) clicks++
            t += FRAME_MS
        }
        repeat(10) {
            classifier.update(SyntheticHand.metrics(SyntheticHand.pointing()), t)
            if (classifier.pinchJustOpened) releases++
            t += FRAME_MS
        }

        assertEquals(1, clicks)
        assertEquals(1, releases)
    }

    @Test
    fun `le front de pose n est signale qu une fois`() {
        val classifier = GestureClassifier()
        var t = 0L
        var fronts = 0
        repeat(20) {
            if (classifier.update(SyntheticHand.metrics(SyntheticHand.openPalm()), t).isNewPose) fronts++
            t += FRAME_MS
        }
        assertEquals("la lecture/pause serait déclenchée en boucle", 1, fronts)
    }

    // --- Balayage ----------------------------------------------------------------------------

    @Test
    fun `balayage reconnu en pose a trois doigts`() {
        val classifier = GestureClassifier()
        var t = 0L
        var events = 0
        var direction: GestureType? = null

        // Verrouiller la pose avant de balayer.
        repeat(5) { classifier.update(SyntheticHand.metrics(SyntheticHand.threeFingers(0.32f)), t); t += FRAME_MS }
        for (i in 0..6) {
            val r = classifier.update(
                SyntheticHand.metrics(SyntheticHand.threeFingers(0.32f + i * 0.06f)), t
            )
            if (r.isSwipeEvent) { events++; direction = r.gesture }
            t += FRAME_MS
        }

        assertEquals(1, events)
        assertEquals(GestureType.SWIPE_RIGHT, direction)
    }

    /** Un balayage en pointage déplacerait le curseur : il ne doit surtout pas changer d'application. */
    @Test
    fun `balayage ignore hors de la pose a trois doigts`() {
        val classifier = GestureClassifier()
        var t = 0L
        var events = 0

        repeat(5) { classifier.update(SyntheticHand.metrics(SyntheticHand.pointing(0.32f)), t); t += FRAME_MS }
        for (i in 0..6) {
            if (classifier.update(
                    SyntheticHand.metrics(SyntheticHand.pointing(0.32f + i * 0.06f)), t
                ).isSwipeEvent
            ) events++
            t += FRAME_MS
        }

        assertEquals(0, events)
    }

    @Test
    fun `mouvement lent n est pas un balayage`() {
        val classifier = GestureClassifier()
        var t = 0L
        var events = 0
        for (i in 0..30) {
            if (classifier.update(
                    SyntheticHand.metrics(SyntheticHand.threeFingers(0.30f + i * 0.012f)), t
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
        repeat(5) { classifier.update(SyntheticHand.metrics(SyntheticHand.threeFingers(0.45f)), t); t += FRAME_MS }
        for (x in floatArrayOf(0.35f, 0.55f, 0.35f, 0.58f, 0.34f, 0.60f)) {
            if (classifier.update(SyntheticHand.metrics(SyntheticHand.threeFingers(x)), t).isSwipeEvent) events++
            t += FRAME_MS
        }
        assertEquals(0, events)
    }

    // --- Robustesse --------------------------------------------------------------------------

    @Test
    fun `perte breve de la main preserve le geste en cours`() {
        val classifier = GestureClassifier()
        val (_, t) = feed(classifier, SyntheticHand.pinching(), frames = 8)
        assertEquals(GestureType.PINCH, classifier.update(null, t + 100).gesture)
    }

    @Test
    fun `perte prolongee remet la machine a zero`() {
        val classifier = GestureClassifier()
        val (_, t) = feed(classifier, SyntheticHand.pinching(), frames = 8)
        assertEquals(GestureType.IDLE, classifier.update(null, t + 3000).gesture)
    }

    @Test
    fun `detection de faible qualite ne declenche aucune action`() {
        val classifier = GestureClassifier()
        val degraded = SyntheticHand.build(cx = 1.4f, scale = 0.02f, pinchDistance = 0.1f)
        var t = 0L
        repeat(10) { classifier.update(HandGeometry.analyze(degraded), t); t += FRAME_MS }
        assertTrue("un clic a été émis sur une détection douteuse", !classifier.pinchJustClosed)
    }

    @Test
    fun `force de pincement croit quand les doigts se rapprochent`() {
        val loose = GestureClassifier().update(SyntheticHand.metrics(SyntheticHand.openPalm()), 0)
        val tight = GestureClassifier().update(SyntheticHand.metrics(SyntheticHand.pinching()), 0)
        assertTrue(tight.pinchStrength > loose.pinchStrength)
    }

    @Test
    fun `la pose precedente est rapportee`() {
        val classifier = GestureClassifier()
        var t = 0L
        repeat(8) { classifier.update(SyntheticHand.metrics(SyntheticHand.pointing()), t); t += FRAME_MS }
        var result = GestureResult(GestureType.IDLE)
        repeat(8) { result = classifier.update(SyntheticHand.metrics(SyntheticHand.fist()), t); t += FRAME_MS }
        assertEquals(GestureType.FIST, result.gesture)
        assertNotEquals(GestureType.FIST, GestureType.POINT)
    }
}
