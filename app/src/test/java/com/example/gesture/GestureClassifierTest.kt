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
    fun `main ouverte reconnue`() {
        val (result, _) = feed(GestureClassifier(), SyntheticHand.openPalm(), frames = 8)
        assertEquals(GestureType.OPEN_PALM, result.gesture)
    }

    @Test
    fun `poing reconnu`() {
        val (result, _) = feed(GestureClassifier(), SyntheticHand.fist(), frames = 8)
        assertEquals(GestureType.FIST, result.gesture)
    }

    /**
     * Propriété centrale du vocabulaire : chaque pose doit produire une classification distincte.
     * Deux poses confondues rendraient leurs deux actions inutilisables.
     */
    @Test
    fun `les poses du vocabulaire sont mutuellement discriminables`() {
        val poses = mapOf(
            GestureType.POINT to SyntheticHand.pointing(),
            GestureType.OPEN_PALM to SyntheticHand.openPalm(),
            GestureType.FIST to SyntheticHand.fist()
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

    /**
     * Le cœur de la refonte, exprimé comme une propriété vérifiable : **une forme de main qui n'est
     * pas l'une des trois poses ne doit rien verrouiller.**
     *
     * Deux et trois doigts tendus étaient des poses, et c'est ce qui rendait le vocabulaire
     * instable : chaque pose distinguée au nombre de doigts crée un piège, puisque changer de forme
     * traverse mécaniquement tous les décomptes intermédiaires. Les voir rejetées — et non
     * reclassées sur une pose voisine — est la garantie que le piège a disparu.
     */
    @Test
    fun `les decomptes intermediaires ne verrouillent aucune pose`() {
        // Deux et trois doigts longs. Quatre n'en est pas un : avec le pouce sorti, c'est la main
        // ouverte elle-même.
        for (fingers in 2..3) {
            val extension = FloatArray(4) { if (it < fingers) 1f else 0f }
            val hand = SyntheticHand.build(extension = extension)
            val (result, _) = feed(GestureClassifier(), hand, frames = 10)
            assertTrue(
                "$fingers doigts tendus ont verrouillé ${result.gesture}",
                result.gesture == GestureType.IDLE
            )
        }
    }

    /**
     * Le pouce levé a été retiré du vocabulaire pour une raison mesurée : il ne diffère du poing
     * que par le pouce, le plus mal suivi des cinq doigts, et il était la rivale la plus proche à
     * la fois du poing et de l'index pointé. Il ne doit donc plus être reconnu comme une pose — ni,
     * surtout, être pris pour un poing, ce qui déclencherait un retour en levant le pouce.
     */
    @Test
    fun `le pouce leve n est plus une pose`() {
        val (result, _) = feed(GestureClassifier(), SyntheticHand.thumbUp(), frames = 10)
        assertNotEquals(
            "le pouce levé déclencherait l'action du poing",
            GestureType.FIST,
            result.gesture
        )
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
     * Ouvrir la main depuis le pointage traverse tous les décomptes intermédiaires. Aucune pose
     * autre que les deux extrémités du mouvement ne doit apparaître en route : c'est ce qui permet
     * d'ouvrir la roue de commandes sans rien déclencher au passage.
     */
    @Test
    fun `l ouverture progressive de la main ne verrouille aucune pose intermediaire`() {
        val classifier = GestureClassifier()
        var t = 0L
        val seen = mutableSetOf<GestureType>()

        repeat(6) { classifier.update(SyntheticHand.metrics(SyntheticHand.pointing()), t); t += FRAME_MS }

        // Ouverture lente — dix étapes de trois images, assez pour que le verrou temporel puisse
        // s'armer sur une pose intermédiaire si elle existait encore.
        for (step in 1..10) {
            val opening = step / 10f
            val hand = SyntheticHand.build(
                extension = floatArrayOf(1f, opening, opening * 0.9f, opening * 0.8f)
            )
            repeat(3) {
                seen += classifier.update(HandGeometry.analyze(hand), t).gesture
                t += FRAME_MS
            }
        }

        assertTrue(
            "pose intermédiaire verrouillée: $seen",
            seen.all { it == GestureType.POINT || it == GestureType.OPEN_PALM }
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
        assertEquals("la roue serait réouverte en boucle", 1, fronts)
    }

    // --- Classifieur entraîné ----------------------------------------------------------------

    /**
     * Une pose donnée par le classifieur entraîné passe par le même verrou temporel que le score
     * géométrique. C'est indispensable : le classifieur donne un avis par image, pas une décision
     * stable, et une seule image mal lue ne doit pas déclencher d'action.
     */
    @Test
    fun `une pose du classifieur entraine passe par le verrou temporel`() {
        val classifier = GestureClassifier()
        var t = 0L
        val metrics = SyntheticHand.metrics(SyntheticHand.pointing())

        val first = classifier.update(metrics, t, recognized = GestureType.FIST)
        assertEquals("une seule image a suffi à verrouiller", GestureType.IDLE, first.gesture)

        var last = first
        repeat(4) { t += FRAME_MS; last = classifier.update(metrics, t, recognized = GestureType.FIST) }
        assertEquals(GestureType.FIST, last.gesture)
    }

    // --- Robustesse --------------------------------------------------------------------------

    @Test
    fun `perte breve de la main preserve le geste en cours`() {
        val classifier = GestureClassifier()
        val (_, t) = feed(classifier, SyntheticHand.pointing(), frames = 8)
        assertEquals(GestureType.POINT, classifier.update(null, t + 100).gesture)
    }

    @Test
    fun `perte prolongee remet la machine a zero`() {
        val classifier = GestureClassifier()
        val (_, t) = feed(classifier, SyntheticHand.pointing(), frames = 8)
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
        // On retient l'image de la bascule : c'est la seule où la pose précédente a un sens, et
        // c'est elle que la politique de commande utilise pour clore un geste en cours.
        var transition: GestureResult? = null
        repeat(8) {
            val r = classifier.update(SyntheticHand.metrics(SyntheticHand.fist()), t)
            if (r.isNewPose && transition == null) transition = r
            t += FRAME_MS
        }
        assertEquals(GestureType.FIST, transition?.gesture)
        assertEquals(GestureType.POINT, transition?.previousGesture)
    }
}
