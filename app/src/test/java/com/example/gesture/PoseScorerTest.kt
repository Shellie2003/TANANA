package com.example.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseScorerTest {

    private val scorer = PoseScorer()

    private fun metrics(vararg extension: Float, thumb: Float = 0.8f): HandMetrics =
        SyntheticHand.metrics(
            SyntheticHand.build(
                extension = floatArrayOf(extension[0], extension[1], extension[2], extension[3]),
                thumbCurled = thumb < 0.5f
            )
        )

    @Test
    fun `chaque pose franche est reconnue`() {
        assertEquals(GestureType.POINT, scorer.classify(metrics(1f, 0f, 0f, 0f)))
        assertEquals(GestureType.TWO_FINGER, scorer.classify(metrics(1f, 1f, 0f, 0f)))
        assertEquals(GestureType.THREE_FINGER, scorer.classify(metrics(1f, 1f, 1f, 0f)))
        assertEquals(GestureType.OPEN_PALM, scorer.classify(metrics(1f, 1f, 1f, 1f)))
    }

    /**
     * Poing et pouce levé ont exactement le même décompte de doigts longs : seul le pouce les
     * sépare. C'est la paire la plus exposée à la confusion, et donc celle qui valide le mieux
     * la pondération du pouce dans le score.
     */
    @Test
    fun `le poing et le pouce leve se distinguent par le pouce`() {
        assertEquals(GestureType.FIST, scorer.classify(SyntheticHand.metrics(SyntheticHand.fist())))
        assertEquals(GestureType.THUMB_UP, scorer.classify(SyntheticHand.metrics(SyntheticHand.thumbUp())))
    }

    /**
     * Le cœur du problème des conflits : un annulaire à mi-course rend « deux doigts » et
     * « trois doigts » également plausibles. L'ancienne cascade de seuils tranchait
     * arbitrairement et déclenchait une action non voulue ; le score doit refuser de décider.
     */
    @Test
    fun `une pose ambigue est rejetee plutot que devinee`() {
        val ambiguous = metrics(1f, 1f, 0.5f, 0f)
        assertNull("une pose a été devinée sur une main ambiguë", scorer.classify(ambiguous))
    }

    @Test
    fun `la marge mesure la nettete du geste`() {
        val clear = scorer.margin(metrics(1f, 0f, 0f, 0f))
        val ambiguous = scorer.margin(metrics(1f, 1f, 0.5f, 0f))
        assertTrue("net=$clear ambigu=$ambiguous", clear > ambiguous)
    }

    @Test
    fun `une main ne ressemblant a aucun modele ne decide rien`() {
        // Tous les doigts à mi-course — extension mesurée 0,56 — c'est-à-dire l'état traversé en
        // changeant de pose. Il ne doit ressembler à rien d'assez près pour déclencher une action
        // au passage.
        assertNull(scorer.classify(metrics(0.4f, 0.4f, 0.4f, 0.4f)))
    }

    @Test
    fun `les deux poses ajoutees sont reconnues`() {
        assertEquals(GestureType.PINKY_UP, scorer.classify(SyntheticHand.metrics(SyntheticHand.pinkyUp())))
        assertEquals(GestureType.ROCK, scorer.classify(SyntheticHand.metrics(SyntheticHand.rock())))
    }

    /**
     * Test de non-régression du vocabulaire : chaque pose du catalogue doit se reconnaître
     * elle-même, avec une marge supérieure au seuil de rejet.
     *
     * C'est le garde-fou qui manquait. Toute pose ajoutée par la suite qui empiéterait sur une
     * pose existante fera échouer ce test au lieu de dégrader silencieusement la précision sur
     * l'appareil — c'est-à-dire au seul endroit où le défaut était jusqu'ici observable.
     */
    @Test
    fun `chaque pose du vocabulaire se distingue de toutes les autres`() {
        val config = PoseScorer.Config()
        val cases = mapOf(
            GestureType.POINT to SyntheticHand.pointing(),
            GestureType.TWO_FINGER to SyntheticHand.twoFingers(),
            GestureType.THREE_FINGER to SyntheticHand.threeFingers(),
            GestureType.OPEN_PALM to SyntheticHand.openPalm(),
            GestureType.PINKY_UP to SyntheticHand.pinkyUp(),
            GestureType.ROCK to SyntheticHand.rock(),
            GestureType.FIST to SyntheticHand.fist(),
            GestureType.THUMB_UP to SyntheticHand.thumbUp()
        )

        assertEquals(
            "une pose du catalogue n'est pas couverte par ce test",
            scorer.poses.toSet(), cases.keys
        )

        cases.forEach { (expected, hand) ->
            val m = SyntheticHand.metrics(hand)
            val scores = scorer.scoreAll(m)
            val margin = scores[0].score - scores[1].score
            assertEquals("$expected mal classée (${scores.take(2)})", expected, scores[0].pose)
            assertTrue(
                "$expected trop proche de ${scores[1].pose} : marge $margin",
                margin >= config.minMargin
            )
            assertEquals("$expected non retenue par classify", expected, scorer.classify(m))
        }
    }

    @Test
    fun `les scores sont ordonnes du plus probable au moins probable`() {
        val scores = scorer.scoreAll(metrics(1f, 0f, 0f, 0f))
        assertEquals(GestureType.POINT, scores.first().pose)
        assertTrue(scores.zipWithNext().all { (a, b) -> a.score >= b.score })
    }

    /**
     * Une marge plus exigeante doit rejeter davantage : le réglage donne bien un curseur entre
     * tolérance et sévérité, sur lequel s'appuie la personnalisation de la sensibilité.
     */
    @Test
    fun `une marge plus exigeante rejette davantage`() {
        val lenient = PoseScorer(PoseScorer.Config(minMargin = 0.02f, minScore = 0.5f))
        val strict = PoseScorer(PoseScorer.Config(minMargin = 0.45f, minScore = 0.5f))
        // Annulaire légèrement fléchi : marge mesurée à 0.108, entre les deux réglages.
        val borderline = metrics(1f, 1f, 0.2f, 0f)

        assertNotNull(lenient.classify(borderline))
        assertNull(strict.classify(borderline))
    }
}
