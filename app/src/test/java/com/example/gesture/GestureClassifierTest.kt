package com.example.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    /**
     * Le contact pouce-index ne doit **pas** produire de pose. Il en produisait une, et c'était un
     * conflit direct avec le clic : refermer le pouce pour cliquer changeait de rôle en pleine
     * action, si bien que le même geste partait tantôt en clic, tantôt en glisser, selon qu'il
     * durait plus ou moins que le verrou temporel. La main qui pointe reste donc la main qui
     * pointe, pouce ouvert ou fermé.
     */
    @Test
    fun `le contact pouce-index ne change pas la pose`() {
        val classifier = GestureClassifier()
        val (ouvert, t) = feed(classifier, SyntheticHand.pointing(), frames = 8)
        assertEquals(GestureType.POINT, ouvert.gesture)

        var time = t
        var dernier = ouvert
        repeat(8) {
            dernier = classifier.update(
                SyntheticHand.metrics(
                    SyntheticHand.build(extension = floatArrayOf(1f, 0f, 0f, 0f), pinchDistance = 0.08f)
                ),
                time
            )
            time += FRAME_MS
        }
        assertEquals(GestureType.POINT, dernier.gesture)
    }

    /**
     * Propriété centrale du vocabulaire : chaque pose doit produire une classification distincte.
     * Deux poses confondues rendraient leurs deux actions inutilisables.
     */
    @Test
    fun `les poses du vocabulaire sont mutuellement discriminables`() {
        val poses = mapOf(
            GestureType.POINT to SyntheticHand.pointing(),
            GestureType.TWO_FINGER to SyntheticHand.twoFingers(),
            GestureType.OPEN_PALM to SyntheticHand.openPalm(),
            GestureType.FIST to SyntheticHand.fist(),
            GestureType.THUMB_UP to SyntheticHand.thumbUp()
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
     * Passer de l'index seul à la main ouverte traverse mécaniquement la pose en « V ». Elle ne
     * doit pas être verrouillée au passage, sans quoi ouvrir la main armerait la détection de
     * balayage et le mouvement d'ouverture lui-même pourrait être lu comme un balayage.
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
            "pose intermédiaire verrouillée: $seen",
            GestureType.TWO_FINGER !in seen
        )
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
    fun `balayage reconnu en pose V`() {
        val classifier = GestureClassifier()
        var t = 0L
        var events = 0
        var direction: GestureType? = null

        // Verrouiller la pose avant de balayer.
        repeat(5) { classifier.update(SyntheticHand.metrics(SyntheticHand.twoFingers(0.32f)), t); t += FRAME_MS }
        for (i in 0..6) {
            val r = classifier.update(
                SyntheticHand.metrics(SyntheticHand.twoFingers(0.32f + i * 0.06f)), t
            )
            if (r.isSwipeEvent) { events++; direction = r.gesture }
            t += FRAME_MS
        }

        assertEquals(1, events)
        assertEquals(GestureType.SWIPE_RIGHT, direction)
    }

    /** Un balayage en pointage déplacerait le curseur : il ne doit surtout pas changer d'application. */
    @Test
    fun `balayage ignore hors de la pose V`() {
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
                    SyntheticHand.metrics(SyntheticHand.twoFingers(0.30f + i * 0.012f)), t
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
        repeat(5) { classifier.update(SyntheticHand.metrics(SyntheticHand.twoFingers(0.45f)), t); t += FRAME_MS }
        for (x in floatArrayOf(0.35f, 0.55f, 0.35f, 0.58f, 0.34f, 0.60f)) {
            if (classifier.update(SyntheticHand.metrics(SyntheticHand.twoFingers(x)), t).isSwipeEvent) events++
            t += FRAME_MS
        }
        assertEquals(0, events)
    }

    /** Main en « V » placée librement dans l'image, pour éprouver les deux axes. */
    private fun twoFingersAt(cx: Float, cy: Float) = SyntheticHand.metrics(
        SyntheticHand.build(cx = cx, cy = cy, extension = floatArrayOf(1f, 1f, 0f, 0f))
    )

    private fun swipeDirection(steps: List<Pair<Float, Float>>): GestureType? {
        val classifier = GestureClassifier()
        var t = 0L
        var direction: GestureType? = null
        repeat(5) { classifier.update(twoFingersAt(steps.first().first, steps.first().second), t); t += FRAME_MS }
        steps.forEach { (x, y) ->
            val r = classifier.update(twoFingersAt(x, y), t)
            if (r.isSwipeEvent) direction = r.gesture
            t += FRAME_MS
        }
        return direction
    }

    /**
     * Le volet des notifications — et donc les données mobiles — n'était atteignable par aucun
     * geste tant que seuls les balayages horizontaux existaient.
     */
    @Test
    fun `un balayage vers le bas est reconnu`() {
        assertEquals(
            GestureType.SWIPE_DOWN,
            swipeDirection((0..6).map { 0.5f to 0.30f + it * 0.06f })
        )
    }

    @Test
    fun `un balayage vers le haut est reconnu`() {
        assertEquals(
            GestureType.SWIPE_UP,
            swipeDirection((0..6).map { 0.5f to 0.70f - it * 0.06f })
        )
    }

    /**
     * Un mouvement oblique ne doit produire aucune direction : entre « notifications » et
     * « application suivante », deviner serait pire que ne rien faire.
     */
    @Test
    fun `un balayage oblique ne produit aucune direction`() {
        assertNull(swipeDirection((0..6).map { 0.30f + it * 0.06f to 0.30f + it * 0.055f }))
    }

    // --- Robustesse --------------------------------------------------------------------------

    @Test
    fun `perte breve de la main preserve le geste en cours`() {
        val classifier = GestureClassifier()
        val (_, t) = feed(classifier, SyntheticHand.twoFingers(), frames = 8)
        assertEquals(GestureType.TWO_FINGER, classifier.update(null, t + 100).gesture)
    }

    @Test
    fun `perte prolongee remet la machine a zero`() {
        val classifier = GestureClassifier()
        val (_, t) = feed(classifier, SyntheticHand.twoFingers(), frames = 8)
        assertEquals(GestureType.IDLE, classifier.update(null, t + 3000).gesture)
    }

    @Test
    fun `detection de faible qualite ne verrouille aucune pose`() {
        val classifier = GestureClassifier()
        val degraded = SyntheticHand.build(cx = 1.4f, scale = 0.02f, pinchDistance = 0.1f)
        var t = 0L
        var last = classifier.update(HandGeometry.analyze(degraded), t)
        repeat(10) { t += FRAME_MS; last = classifier.update(HandGeometry.analyze(degraded), t) }
        assertEquals("une pose a été verrouillée sur une détection douteuse", GestureType.IDLE, last.gesture)
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
