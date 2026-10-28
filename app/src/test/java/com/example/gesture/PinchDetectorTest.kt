package com.example.gesture

import com.example.control.PinchTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le pincement est maintenant le **seul** geste de l'application : chaque faux positif agit sur le
 * téléphone entier, et chaque faux négatif rend l'appareil inutilisable. Ces tests décrivent des
 * trajectoires de pouce réalistes plutôt que des états, puisque c'est la trajectoire que le
 * détecteur juge.
 */
class PinchDetectorTest {

    private val frameMs = 33L

    /** Rejoue une trajectoire d'écart pouce-index et retourne les évènements produits. */
    private fun play(
        detector: PinchDetector,
        ratios: List<Float>,
        startMs: Long = 0L,
        stepMs: Long = frameMs,
        x: Float = 0.5f,
        y: Float = 0.5f
    ): List<PinchDetector.Event> {
        var t = startMs
        return ratios.map { ratio ->
            val event = detector.update(ratio, x, y, t)
            t += stepMs
            event
        }
    }

    /** Amène la ligne de base à la valeur de repos de l'utilisateur simulé. */
    private fun settle(detector: PinchDetector, rest: Float = 0.85f, frames: Int = 40): Long {
        play(detector, List(frames) { rest })
        return frames * frameMs
    }

    /** Impulsion franche : ouvert -> contact -> retour. */
    private fun tap(rest: Float = 0.85f) =
        listOf(rest, 0.55f, 0.18f, 0.10f, 0.45f, rest, rest, rest)

    private fun List<PinchDetector.Event>.presses() = count { it == PinchDetector.Event.PRESS }
    private fun List<PinchDetector.Event>.releases() = count { it == PinchDetector.Event.RELEASE }

    // --- Le cas nominal -----------------------------------------------------------------------

    @Test
    fun `une impulsion franche enfonce puis relache une seule fois`() {
        val d = PinchDetector()
        val t0 = settle(d)
        val events = play(d, tap(), startMs = t0)
        assertEquals(1, events.presses())
        assertEquals(1, events.releases())
    }

    @Test
    fun `le bouton s enfonce en moins de cent cinquante millisecondes`() {
        val d = PinchDetector()
        val t0 = settle(d)
        var t = t0
        var pressAt = -1L
        var contactAt = -1L
        tap().forEach { ratio ->
            if (contactAt < 0 && ratio < d.contactThreshold) contactAt = t
            if (d.update(ratio, 0.5f, 0.5f, t) == PinchDetector.Event.PRESS) pressAt = t
            t += frameMs
        }
        assertTrue("aucun enfoncement émis", pressAt >= 0)
        val latency = pressAt - contactAt
        assertTrue("latence contact -> enfoncement de $latency ms", latency <= 150)
    }

    @Test
    fun `le curseur est fige a l instant du contact`() {
        val d = PinchDetector()
        var t = settle(d)
        // Le pouce se ferme pendant que la main dérive : la cible doit rester celle du contact.
        d.update(0.85f, 0.20f, 0.20f, t); t += frameMs
        d.update(0.10f, 0.30f, 0.30f, t); t += frameMs
        d.update(0.80f, 0.90f, 0.90f, t)
        assertEquals(0.30f, d.anchorX, 1e-5f)
        assertEquals(0.30f, d.anchorY, 1e-5f)
    }

    @Test
    fun `l etat enfonce est expose pendant tout le contact`() {
        val d = PinchDetector()
        var t = settle(d)
        d.update(0.85f, 0.5f, 0.5f, t); t += frameMs
        assertTrue("le bouton ne devrait pas être enfoncé", !d.isPressed)
        d.update(0.10f, 0.5f, 0.5f, t); t += frameMs
        assertTrue("le bouton devrait être enfoncé", d.isPressed)
        repeat(20) { d.update(0.08f, 0.5f, 0.5f, t); t += frameMs }
        assertTrue("le bouton s'est relâché tout seul", d.isPressed)

        // Le relâchement demande deux images : une seule ne doit pas suffire, c'est précisément
        // l'anti-décrochage.
        d.update(0.85f, 0.5f, 0.5f, t); t += frameMs
        assertTrue("une seule image a suffi à relâcher", d.isPressed)
        d.update(0.85f, 0.5f, 0.5f, t)
        assertTrue("le bouton est resté enfoncé après ouverture", !d.isPressed)
    }

    // --- Les faux positifs, qui sont le vrai sujet --------------------------------------------

    @Test
    fun `un pouce pose au repos pres de l index n enfonce jamais`() {
        val d = PinchDetector()
        // Cet utilisateur garde en permanence le pouce contre l'index : c'est sa position neutre.
        val events = play(d, List(300) { 0.20f })
        assertEquals(0, events.presses())
    }

    /**
     * **Ce test affirmait l'inverse, et il avait tort.** Il vérifiait qu'une fermeture lente était
     * rejetée, au motif qu'une impulsion volontaire est brève et qu'un repositionnement de main est
     * lent. La prémisse est juste sur le geste et fausse sur la mesure : le critère était exprimé en
     * millisecondes mais évalué en images, et la cadence réelle de la chaîne caméra-inférence sur un
     * téléphone est de 12 à 20 images par seconde, non 30. Mesuré en harnais, une fermeture
     * volontaire mais posée de 300 ms était rejetée à toute cadence inférieure à 30 — donc, en
     * pratique, toujours.
     *
     * Le critère a été retiré, et ce test décrit maintenant la propriété voulue : une fermeture
     * **complète** aboutit, quelle que soit sa lenteur. Ce qui écarte un repositionnement de main
     * n'est pas sa durée mais sa **profondeur** — replacer sa main n'amène pas le pouce sur
     * l'index — et c'est ce que vérifient les deux tests suivants.
     */
    @Test
    fun `une fermeture lente mais complete aboutit`() {
        val d = PinchDetector()
        val t0 = settle(d)
        // Descente régulière sur une seconde, jusqu'au contact franc.
        val slow = (0..30).map { 0.85f - it * (0.75f / 30f) }
        val events = play(d, slow, startMs = t0)
        assertEquals("une fermeture complète a été rejetée pour sa lenteur", 1, events.presses())
    }

    @Test
    fun `une approche partielle ne suffit pas`() {
        val d = PinchDetector()
        val t0 = settle(d, rest = 0.85f)
        // Le pouce descend vite mais s'arrête au-dessus du seuil de contact.
        val events = play(d, listOf(0.85f, 0.45f, 0.40f, 0.42f, 0.85f), startMs = t0)
        assertEquals(0, events.presses())
    }

    @Test
    fun `le tremblement autour du repos ne produit rien`() {
        val d = PinchDetector()
        val jitter = (0..200).map { 0.80f + if (it % 2 == 0) 0.03f else -0.03f }
        assertEquals(0, play(d, jitter).presses())
    }

    /**
     * Un quasi-contact **tenu longtemps** ne compte toujours qu'un rejet.
     *
     * Le verrou de jugement est ce qui le garantit : sans lui, une main posée sous le seuil serait
     * réévaluée à chaque image et noierait le compteur, masquant les vraies causes.
     *
     * Ce test portait auparavant sur une descente lente, donc sur le critère de vitesse qui a été
     * retiré — la trajectoire était rejetée pour sa durée. Elle est désormais rejetée pour sa
     * profondeur, ce qui est le seul motif restant et celui qu'il faut vérifier.
     *
     * La borne est **deux** et non un, et c'est mesuré, pas concédé : tenir une demi-fermeture
     * deux secondes fait de cette posture le nouveau repos, la ligne de base s'y adapte — c'est
     * voulu, voir [PinchDetector.Config.baselineRestFall] — et cette adaptation clôt la tentative
     * en cours puis en ouvre une autre. Deux jugements pour deux états de la main successifs, donc,
     * et non soixante pour soixante images. C'est cette dernière différence qui compte : le défaut
     * d'origine était une réévaluation **par image**, qui noyait les vraies causes.
     */
    @Test
    fun `un quasi-contact tenu longtemps ne noie pas le compteur`() {
        val d = PinchDetector()
        val t0 = settle(d)
        // Le pouce s'arrête au-dessus du seuil et y reste deux secondes, puis se rouvre.
        play(d, listOf(0.85f, 0.58f) + List(60) { 0.55f } + List(4) { 0.85f }, startMs = t0)
        assertEquals(PinchDetector.Rejection.TOO_SHALLOW, d.lastRejection)
        assertTrue("compteur noyé : ${d.rejectedCount} rejets", d.rejectedCount <= 2)
    }

    /**
     * Un geste déjà jugé ne doit pas être recompté sous une autre cause quand la main se rouvre.
     *
     * Le défaut existait depuis le début et n'était pas visible : les trajectoires testées
     * s'arrêtaient en position fermée, sans jamais rouvrir. Un geste déjà rejeté comptait donc son
     * rejet, puis un second « quasi-contact » à la réouverture — et c'était la seconde cause qui
     * s'affichait. L'utilisateur recevait ainsi un conseil portant sur un défaut qu'il n'avait pas
     * commis, ce qui est pire que pas de conseil du tout.
     *
     * Le cas se vérifie désormais sur l'anti-rebond, le critère de rejet qui subsiste à côté de la
     * profondeur : deux impulsions collées, dont la seconde est absorbée, puis la main se rouvre.
     */
    @Test
    fun `un geste deja juge ne compte pas un second rejet en se rouvrant`() {
        val d = PinchDetector()
        val t0 = settle(d)
        // Première impulsion : aboutit. Relâchement franc sur deux images, puis re-contact
        // immédiat, absorbé par l'anti-rebond. Puis la main se rouvre franchement.
        play(
            d,
            listOf(0.85f, 0.10f, 0.80f, 0.80f, 0.10f, 0.08f) + List(10) { 0.85f },
            startMs = t0
        )
        assertEquals(PinchDetector.Rejection.COOLDOWN, d.lastRejection)
        assertEquals("la réouverture a ajouté un rejet de plus", 1, d.rejectedCount)
    }

    /**
     * **Le défaut qui arrêtait le défilement en pleine course.** Une seule image de suivi aberrante
     * — un flou de mouvement pendant un balayage — suffisait à faire croire que le pouce s'était
     * rouvert : le tracé se terminait, le défilement s'arrêtait, et il fallait repincer alors que
     * le pouce touchait toujours l'index.
     *
     * Le contact doit donc survivre à une image isolée au-dessus du seuil, et le geste rester un
     * seul enfoncement du début à la fin.
     */
    @Test
    fun `une image aberrante n interrompt pas le contact`() {
        val d = PinchDetector()
        val t0 = settle(d)
        // Contact, une image qui décroche franchement, puis le contact reprend.
        val events = play(d, listOf(0.85f, 0.10f, 0.70f, 0.10f, 0.08f, 0.08f), startMs = t0)
        assertEquals(1, events.presses())
        assertEquals("le contact a été interrompu par une image isolée", 0, events.releases())
        assertTrue("le bouton devrait toujours être enfoncé", d.isPressed)
    }

    /** L'anti-rebond reste en place pour deux enfoncements réellement trop rapprochés. */
    @Test
    fun `deux enfoncements trop rapproches sont filtres`() {
        val d = PinchDetector()
        val t0 = settle(d)
        // Contact, relâchement franc sur deux images, puis re-contact immédiat.
        val events = play(d, listOf(0.85f, 0.10f, 0.80f, 0.80f, 0.10f, 0.08f), startMs = t0)
        assertEquals(1, events.presses())
        assertEquals(PinchDetector.Rejection.COOLDOWN, d.lastRejection)
    }

    // --- Le blocage par intermittence ---------------------------------------------------------

    /**
     * **Le défaut que l'utilisateur a signalé.** Le verrou de rejet ne se levait qu'une fois la
     * main revenue au-dessus de 72 % de sa ligne de base — une valeur absolue. Une main dont la
     * position de repos tombait sous cette bande ne pouvait donc plus jamais le lever : un seul
     * rejet, et plus aucun appui ne partait. Le défilement s'arrêtait avec lui, puisqu'il a besoin
     * du même enfoncement — d'où un clic et un défilement qui « parfois ne marchent pas ».
     */
    @Test
    fun `un rejet ne bloque pas les appuis suivants`() {
        val d = PinchDetector()
        var t = settle(d, rest = 0.85f)

        // Une approche partielle : rejetée pour sa profondeur, et le verrou se pose.
        listOf(0.85f, 0.62f, 0.56f, 0.60f, 0.72f, 0.78f).forEach { r ->
            d.update(r, 0.5f, 0.5f, t); t += frameMs
        }
        assertEquals(PinchDetector.Rejection.TOO_SHALLOW, d.lastRejection)

        // La main se rouvre **partiellement**, sans jamais revenir grande ouverte, puis pince
        // franchement. C'est le cas que l'ancienne bande absolue rendait impossible.
        repeat(6) { d.update(0.55f, 0.5f, 0.5f, t); t += frameMs }
        val events = play(d, listOf(0.55f, 0.12f, 0.09f, 0.45f, 0.55f, 0.55f), startMs = t)
        assertEquals("l'appui est resté bloqué par le rejet précédent", 1, events.presses())
    }

    /**
     * Changer de posture ne doit pas rendre l'appui inopérant le temps que la référence s'adapte.
     *
     * La référence ne descendait qu'à 1,2 % par image, soit près de trois secondes pour rejoindre
     * une nouvelle position de repos. Pendant ce temps la course mesurée était celle d'une main qui
     * n'existait plus, et les appuis étaient rejetés comme trop lents.
     */
    @Test
    fun `changer de posture n empeche pas d appuyer`() {
        val d = PinchDetector()
        var t = settle(d, rest = 1.20f)

        // La main se repose plus près d'elle-même, sous l'ancienne bande ouverte (0,86).
        repeat(10) { d.update(0.75f, 0.5f, 0.5f, t); t += frameMs }

        val events = play(d, listOf(0.75f, 0.15f, 0.12f, 0.50f, 0.75f, 0.75f), startMs = t)
        assertEquals("la nouvelle posture n'a pas été apprise assez vite", 1, events.presses())
    }

    /**
     * L'adaptation rapide ne doit **pas** s'appliquer à une fermeture lente : la référence suivrait
     * le pouce qui se ferme, le seuil de contact reculerait devant lui, et l'appui ne partirait
     * jamais. C'est la contrainte qui interdisait d'accélérer la descente sans distinguer les deux
     * cas.
     */
    @Test
    fun `une fermeture lente ne tire pas la reference vers le bas`() {
        val d = PinchDetector()
        val t0 = settle(d, rest = 0.90f)
        val before = d.baseline

        // Descente régulière sur une seconde, interrompue avant le contact.
        play(d, (0..20).map { 0.90f - it * 0.025f }, startMs = t0)

        assertTrue(
            "la référence a suivi la fermeture : ${d.baseline} au lieu de $before",
            d.baseline > before - 0.12f
        )
    }

    // --- L'adaptation à l'utilisateur ---------------------------------------------------------

    @Test
    fun `la ligne de base apprend la course de l utilisateur`() {
        val ample = PinchDetector()
        settle(ample, rest = 1.30f)
        val discret = PinchDetector()
        settle(discret, rest = 0.50f)

        assertTrue("course ample non apprise : ${ample.baseline}", ample.baseline > 1.1f)
        assertTrue("course discrète non apprise : ${discret.baseline}", discret.baseline < 0.6f)
        assertTrue(
            "le seuil de contact doit suivre la course",
            ample.contactThreshold > discret.contactThreshold
        )
    }

    /**
     * Le cas que les seuils absolus ne pouvaient pas traiter : une main qui garde le pouce près
     * de l'index a une course réduite, et doit malgré tout pouvoir appuyer.
     */
    @Test
    fun `une main a course reduite peut appuyer`() {
        val d = PinchDetector()
        val t0 = settle(d, rest = 0.50f)
        val events = play(d, listOf(0.50f, 0.30f, 0.08f, 0.06f, 0.30f, 0.50f, 0.50f), startMs = t0)
        assertEquals(1, events.presses())
    }

    /** Symétriquement, une main à grande course ne doit pas appuyer sur une descente partielle. */
    @Test
    fun `une main a grande course n appuie pas a mi-chemin`() {
        val d = PinchDetector()
        val t0 = settle(d, rest = 1.40f)
        val events = play(d, listOf(1.40f, 0.90f, 0.75f, 0.80f, 1.40f), startMs = t0)
        assertEquals(0, events.presses())
    }

    @Test
    fun `un contact prolonge n erode pas la ligne de base`() {
        val d = PinchDetector()
        val t0 = settle(d, rest = 0.90f)
        val before = d.baseline
        // Contact tenu trois secondes, puis relâchement.
        play(d, listOf(0.90f, 0.10f) + List(90) { 0.08f }, startMs = t0)
        assertEquals("la référence a dérivé pendant le contact", before, d.baseline, 0.02f)
    }

    // --- Cadence ------------------------------------------------------------------------------

    @Test
    fun `des appuis rapides se transmettent tous`() {
        val d = PinchDetector()
        var t = settle(d)
        var presses = 0
        repeat(6) {
            listOf(0.85f, 0.12f, 0.09f, 0.50f, 0.85f, 0.85f, 0.85f).forEach { ratio ->
                if (d.update(ratio, 0.5f, 0.5f, t) == PinchDetector.Event.PRESS) presses++
                t += frameMs
            }
        }
        assertEquals(6, presses)
    }

    /**
     * Le détecteur ne décide **plus** du sens du geste : un contact tenu trois secondes reste un
     * seul enfoncement suivi d'un seul relâchement. C'est [com.example.control.Pointer] qui en
     * fera un appui long ou un tracé, parce que lui connaît aussi le déplacement du curseur.
     */
    @Test
    fun `un contact tenu ne produit toujours qu un enfoncement et un relachement`() {
        val d = PinchDetector()
        val t0 = settle(d)
        val events = play(
            d,
            listOf(0.85f, 0.10f) + List(90) { 0.08f } + listOf(0.50f, 0.85f, 0.85f),
            startMs = t0
        )
        assertEquals(1, events.presses())
        assertEquals(1, events.releases())
    }

    // --- Retour à l'utilisateur ---------------------------------------------------------------

    @Test
    fun `la progression de fermeture renseigne l anneau du curseur`() {
        val d = PinchDetector()
        var t = settle(d, rest = 0.90f)
        d.update(0.90f, 0.5f, 0.5f, t); t += frameMs
        val far = d.travelProgress
        d.update(0.55f, 0.5f, 0.5f, t)
        val near = d.travelProgress
        assertTrue("l'anneau doit se remplir en approchant : $far -> $near", near > far)
    }

    @Test
    fun `la cause du rejet est exposee`() {
        val d = PinchDetector()
        val t0 = settle(d)
        play(d, listOf(0.85f, 0.45f, 0.40f, 0.85f), startMs = t0)
        assertNotEquals(PinchDetector.Rejection.NONE, d.lastRejection)
        assertEquals(PinchDetector.Rejection.TOO_SHALLOW, d.lastRejection)
    }

    /**
     * Le défaut le plus fréquent, et le seul que l'utilisateur ne peut pas diagnostiquer seul :
     * il approche franchement le pouce puis repart sans toucher. De son point de vue il a pincé,
     * et rien ne s'est produit. Le détecteur doit nommer ce cas.
     */
    @Test
    fun `un quasi-contact est signale comme tel`() {
        val d = PinchDetector()
        val t0 = settle(d, rest = 0.90f)
        // S'arrête nettement au-dessus du seuil, puis repart : c'est un quasi-contact.
        play(d, listOf(0.90f, 0.62f, 0.56f, 0.70f, 0.90f, 0.90f), startMs = t0)
        assertEquals(PinchDetector.Rejection.TOO_SHALLOW, d.lastRejection)
        assertEquals(1, d.rejectedCount)
    }

    @Test
    fun `une descente timide n est pas comptee comme quasi-contact`() {
        val d = PinchDetector()
        val t0 = settle(d, rest = 0.90f)
        // Le pouce bouge à peine : ce n'est pas une tentative manquée, c'est du bruit.
        play(d, listOf(0.90f, 0.78f, 0.75f, 0.90f), startMs = t0)
        assertEquals(PinchDetector.Rejection.NONE, d.lastRejection)
        assertEquals(0, d.rejectedCount)
    }

    @Test
    fun `un appui reussi n est pas aussi compte comme quasi-contact`() {
        val d = PinchDetector()
        val t0 = settle(d)
        play(d, tap(), startMs = t0)
        assertEquals(0, d.rejectedCount)
        assertEquals(PinchDetector.Rejection.NONE, d.lastRejection)
    }

    @Test
    fun `les compteurs de session suivent les appuis reussis`() {
        val d = PinchDetector()
        val t0 = settle(d)
        play(d, tap() + tap(), startMs = t0)
        assertEquals(2, d.pressCount)
    }

    /**
     * Chaque cause de rejet doit se traduire par une correction applicable. Un état sans conseil
     * laisserait l'utilisateur devant un échec qu'il ne peut pas comprendre — et comme il n'y a
     * qu'un seul geste, il n'aurait aucun repli.
     */
    @Test
    fun `chaque cause de rejet propose une correction`() {
        PinchDetector.Rejection.entries.forEach { reason ->
            val telemetry = PinchTelemetry(lastRejection = reason)
            if (reason == PinchDetector.Rejection.NONE) {
                assertNull("un geste abouti n'a pas à être commenté", telemetry.advice)
            } else {
                assertNotNull("$reason ne propose aucune correction", telemetry.advice)
                assertTrue("$reason : conseil trop court", (telemetry.advice?.length ?: 0) > 40)
            }
        }
    }

    @Test
    fun `le taux de reussite est inconnu avant tout geste`() {
        assertNull(PinchTelemetry().successRate)
        assertEquals(0.75f, PinchTelemetry(presses = 3, rejected = 1).successRate!!, 1e-6f)
    }

    @Test
    fun `la calibration se declare apres quelques images`() {
        val d = PinchDetector()
        play(d, List(3) { 0.85f })
        assertTrue("calibration annoncée trop tôt", !d.isCalibrated)
        play(d, List(20) { 0.85f })
        assertTrue(d.isCalibrated)
    }
}
