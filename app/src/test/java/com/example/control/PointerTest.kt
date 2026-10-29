package com.example.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le modèle d'interaction complet tient dans ces tests, parce qu'il ne reste qu'un geste.
 *
 * Ce qu'ils décrivent n'est pas un vocabulaire mais un **arbitrage** : le même pincement devient
 * appui, appui long ou tracé selon ce que fait la main pendant qu'il est tenu. Les cas limites
 * sont donc les frontières de cet arbitrage, et c'est là que se jouait la confusion des versions
 * précédentes.
 */
class PointerTest {

    private val frameMs = 33L

    /** Écart pouce-index au repos, et en contact. */
    private val open = 0.85f
    private val closed = 0.10f

    private class Driver(val pointer: Pointer) {
        var t = 0L
        val events = mutableListOf<PointerEvent>()

        fun frame(
            x: Float = 0.5f,
            y: Float = 0.5f,
            pinch: Float = 0.85f,
            present: Boolean = true,
            palmX: Float = x,
            palmY: Float = y
        ) {
            events += pointer.update(
                handPresent = present,
                quality = if (present) 1f else 0f,
                x = x,
                y = y,
                pinchRatio = pinch,
                nowMs = t,
                palmX = palmX,
                palmY = palmY
            )
            t += 33L
        }

        fun frames(count: Int, x: Float = 0.5f, y: Float = 0.5f, pinch: Float = 0.85f) {
            repeat(count) { frame(x, y, pinch) }
        }

        /**
         * Relâche le pincement. Deux images, parce que le détecteur exige désormais deux images
         * consécutives au-dessus du seuil : une seule est traitée comme une erreur de suivi, ce qui
         * est ce qui empêche un flou de mouvement d'interrompre un défilement en cours.
         */
        fun release(x: Float = 0.5f, y: Float = 0.5f) {
            frames(2, x, y, 0.85f)
        }
    }

    private inline fun <reified T : PointerEvent> Driver.only(): List<T> =
        events.filterIsInstance<T>()

    /** Pointeur dont la course au repos est déjà apprise : c'est l'état normal à l'usage. */
    private fun driver(x: Float = 0.5f, y: Float = 0.5f): Driver {
        val d = Driver(Pointer())
        d.frames(40, x, y, open)
        d.events.clear()
        return d
    }

    // --- Les trois issues ---------------------------------------------------------------------

    @Test
    fun `pincer et relacher sans bouger produit un appui`() {
        val d = driver(0.3f, 0.7f)
        d.frame(0.3f, 0.7f, closed)
        d.frame(0.3f, 0.7f, closed)
        d.release(0.3f, 0.7f)

        val taps = d.only<PointerEvent.Tap>()
        assertEquals(1, taps.size)
        assertEquals(0.3f, taps[0].x, 1e-4f)
        assertEquals(0.7f, taps[0].y, 1e-4f)
        assertTrue("un tracé est parti d'un simple appui", d.only<PointerEvent.StrokeBegin>().isEmpty())
    }

    @Test
    fun `pincer et rester immobile produit un appui long`() {
        val d = driver()
        d.frame(0.5f, 0.5f, closed)
        d.frames(25, 0.5f, 0.5f, closed)
        d.release()

        assertEquals(1, d.only<PointerEvent.LongPress>().size)
        assertTrue(
            "un appui long ne doit pas produire aussi un appui bref",
            d.only<PointerEvent.Tap>().isEmpty()
        )
    }

    @Test
    fun `pincer et deplacer la main produit un trace continu`() {
        val d = driver(0.2f, 0.5f)
        d.frame(0.2f, 0.5f, closed)
        // La main traverse l'écran, bouton tenu.
        for (i in 1..10) d.frame(0.2f + i * 0.06f, 0.5f, closed)
        d.release(0.8f, 0.5f)

        assertEquals(1, d.only<PointerEvent.StrokeBegin>().size)
        assertEquals(1, d.only<PointerEvent.StrokeEnd>().size)
        assertTrue("flux trop pauvre", d.only<PointerEvent.StrokeMove>().size >= 9)
        assertTrue("un tracé ne doit pas produire d'appui", d.only<PointerEvent.Tap>().isEmpty())
    }

    /** Le tracé part du point d'enfoncement, pas de la position atteinte au moment du verdict. */
    @Test
    fun `le trace demarre au point d enfoncement`() {
        val d = driver(0.2f, 0.3f)
        d.frame(0.2f, 0.3f, closed)
        d.frame(0.5f, 0.3f, closed)

        val begin = d.only<PointerEvent.StrokeBegin>().single()
        assertEquals(0.2f, begin.x, 1e-4f)
        assertEquals(0.3f, begin.y, 1e-4f)
    }

    // --- Les frontières de l'arbitrage --------------------------------------------------------

    /**
     * Le déplacement l'emporte sur la durée. Sans cette priorité, un défilement entamé lentement
     * basculerait en appui long au bout d'une demi-seconde, et l'application du dessous ouvrirait
     * un menu contextuel en plein milieu du geste.
     */
    @Test
    fun `le deplacement l emporte sur l appui long`() {
        val d = driver(0.5f, 0.5f)
        d.frame(0.5f, 0.5f, closed)
        // Immobile assez longtemps pour frôler le seuil de l'appui long, puis la main part.
        d.frames(15, 0.5f, 0.5f, closed)
        for (i in 1..6) d.frame(0.5f + i * 0.05f, 0.5f, closed)

        assertEquals(1, d.only<PointerEvent.StrokeBegin>().size)
        assertTrue(d.only<PointerEvent.LongPress>().isEmpty())
    }

    /**
     * Le tremblement de la main tendue ne doit pas transformer un appui en micro-glisser : c'est
     * exactement le défaut qui rendait les appuis inopérants sur les cibles petites.
     */
    @Test
    fun `le tremblement sous le seuil reste un appui`() {
        val d = driver(0.5f, 0.5f)
        d.frame(0.5f, 0.5f, closed)
        // Oscillation de 1 % d'écran, soit nettement sous le seuil de déplacement.
        for (i in 1..5) d.frame(0.5f + if (i % 2 == 0) 0.008f else -0.008f, 0.5f, closed)
        d.release()

        assertEquals(1, d.only<PointerEvent.Tap>().size)
        assertTrue(d.only<PointerEvent.StrokeBegin>().isEmpty())
    }

    @Test
    fun `deplacer la main sans pincer ne produit rien`() {
        val d = driver()
        for (i in 0..30) d.frame(0.1f + i * 0.025f, 0.5f, open)
        assertTrue("un geste est parti sans pincement : ${d.events}", d.events.isEmpty())
    }

    @Test
    fun `un appui long suivi du relachement ne produit pas d appui supplementaire`() {
        val d = driver()
        d.frame(0.5f, 0.5f, closed)
        d.frames(30, 0.5f, 0.5f, closed)
        d.release()
        d.frames(5, 0.5f, 0.5f, open)

        assertEquals(1, d.only<PointerEvent.LongPress>().size)
        assertEquals(0, d.only<PointerEvent.Tap>().size)
    }

    // --- Perte de main ------------------------------------------------------------------------

    /**
     * Un tracé laissé ouvert est un doigt resté posé sur l'écran du point de vue du système :
     * l'application du dessous resterait bloquée. Il doit donc être clos, pas abandonné.
     */
    @Test
    fun `perdre la main pendant un trace le termine`() {
        val d = driver(0.3f, 0.5f)
        d.frame(0.3f, 0.5f, closed)
        for (i in 1..5) d.frame(0.3f + i * 0.04f, 0.5f, closed)
        d.events.clear()
        repeat(20) { d.frame(present = false) }

        assertEquals(1, d.only<PointerEvent.StrokeEnd>().size)
    }

    /** À l'inverse, on ne devine pas ce que voulait un appui qu'on n'a pas vu finir. */
    @Test
    fun `perdre la main avant le verdict annule le geste`() {
        val d = driver()
        d.frame(0.5f, 0.5f, closed)
        d.events.clear()
        repeat(20) { d.frame(present = false) }

        assertTrue("un appui a été deviné : ${d.events}", d.events.isEmpty())
    }

    @Test
    fun `une perte breve n interrompt pas le trace`() {
        val d = driver(0.3f, 0.5f)
        d.frame(0.3f, 0.5f, closed)
        for (i in 1..5) d.frame(0.3f + i * 0.04f, 0.5f, closed)
        // Deux images perdues, soit 66 ms : sous la tolérance.
        d.frame(present = false)
        d.frame(present = false)
        d.events.clear()
        d.frame(0.6f, 0.5f, closed)

        assertEquals(1, d.only<PointerEvent.StrokeMove>().size)
        assertTrue("le tracé a été clos pour un trou de deux images", d.only<PointerEvent.StrokeEnd>().isEmpty())
    }

    @Test
    fun `une detection douteuse est traitee comme une absence de main`() {
        val d = driver()
        d.events.clear()
        repeat(20) {
            d.events += d.pointer.update(
                handPresent = true, quality = 0.1f,
                x = 0.5f, y = 0.5f, pinchRatio = closed, nowMs = d.t
            )
            d.t += frameMs
        }
        assertTrue("un geste est parti sur du bruit : ${d.events}", d.events.isEmpty())
    }

    // --- État exposé à l'affichage ------------------------------------------------------------

    @Test
    fun `la progression de l appui long est annoncee avant qu il ne parte`() {
        val d = driver()
        d.frame(0.5f, 0.5f, closed)
        d.frames(8, 0.5f, 0.5f, closed)

        assertTrue("progression nulle", d.pointer.longPressProgress > 0f)
        assertTrue("progression déjà pleine", d.pointer.longPressProgress < 1f)
        assertEquals(Pointer.Phase.PENDING, d.pointer.phase)
    }

    @Test
    fun `la phase suit le geste`() {
        val d = driver()
        assertEquals(Pointer.Phase.HOVER, d.pointer.phase)
        d.frame(0.5f, 0.5f, closed)
        assertEquals(Pointer.Phase.PENDING, d.pointer.phase)
        d.frame(0.6f, 0.5f, closed)
        assertEquals(Pointer.Phase.STROKE, d.pointer.phase)
        d.release(0.6f, 0.5f)
        assertEquals(Pointer.Phase.HOVER, d.pointer.phase)
    }

    @Test
    fun `l etat enfonce est expose pour le panneau`() {
        val d = driver()
        assertTrue(!d.pointer.isPressed)
        d.frame(0.5f, 0.5f, closed)
        assertTrue(d.pointer.isPressed)
    }

    // --- Le défilement est piloté par la paume ------------------------------------------------

    /**
     * **La correction du défilement « difficile avec le pincement ».** Le curseur vient du bout de
     * l'index, et c'est justement l'endroit où le pouce vient se poser en pinçant : les deux
     * extrémités se confondent dans l'image, l'estimation devient bruitée, et le gain du
     * défilement multipliait ce bruit. Un tracé doit donc transporter la position de la paume, que
     * la fermeture des doigts ne bouge pas.
     */
    @Test
    fun `le trace transporte la position de la paume`() {
        val d = driver(0.5f, 0.5f)
        d.frame(0.5f, 0.5f, closed, palmX = 0.4f, palmY = 0.6f)
        for (i in 1..4) {
            d.frame(0.5f, 0.5f, closed, palmX = 0.4f + i * 0.03f, palmY = 0.6f)
        }

        val begin = d.only<PointerEvent.StrokeBegin>().single()
        assertEquals("le tracé doit mémoriser la paume de l'enfoncement", 0.4f, begin.driveX, 1e-4f)
        assertEquals(0.6f, begin.driveY, 1e-4f)

        val moves = d.only<PointerEvent.StrokeMove>()
        assertTrue("aucun déplacement transmis", moves.isNotEmpty())
        assertTrue("la paume n'avance pas : ${moves.last().driveX}", moves.last().driveX > 0.5f)
    }

    /**
     * Le déplacement qui décide entre appui et tracé se mesure sur la **paume**. Un curseur qui
     * tremble parce que le pouce s'est posé sur l'index ne doit pas transformer un appui en
     * glisser : c'est la main qui décide, et la paume en est la mesure fidèle.
     */
    @Test
    fun `un curseur agite sur une paume immobile reste un appui`() {
        val d = driver(0.5f, 0.5f)
        d.frame(0.5f, 0.5f, closed, palmX = 0.5f, palmY = 0.5f)
        // Le curseur saute de 6 % — bien au-delà du seuil — mais la paume ne bouge pas.
        for (i in 1..5) {
            val jitter = if (i % 2 == 0) 0.06f else -0.06f
            d.frame(0.5f + jitter, 0.5f, closed, palmX = 0.5f, palmY = 0.5f)
        }
        d.release(0.5f, 0.5f)

        assertTrue("le tremblement du curseur a lancé un tracé", d.only<PointerEvent.StrokeBegin>().isEmpty())
        assertEquals(1, d.only<PointerEvent.Tap>().size)
    }

    /** Symétriquement, une paume qui se déplace lance bien un tracé, curseur immobile ou non. */
    @Test
    fun `une paume qui se deplace lance un trace`() {
        val d = driver(0.5f, 0.5f)
        d.frame(0.5f, 0.5f, closed, palmX = 0.5f, palmY = 0.5f)
        for (i in 1..4) {
            d.frame(0.5f, 0.5f, closed, palmX = 0.5f, palmY = 0.5f + i * 0.02f)
        }
        assertEquals(1, d.only<PointerEvent.StrokeBegin>().size)
    }

    // --- Cadence ------------------------------------------------------------------------------

    @Test
    fun `des appuis rapproches sont tous transmis`() {
        val d = driver()
        repeat(5) {
            d.frame(0.5f, 0.5f, closed)
            d.frame(0.5f, 0.5f, closed)
            d.release()
            d.frames(3, 0.5f, 0.5f, open)
        }
        assertEquals(5, d.only<PointerEvent.Tap>().size)
    }

    // --- Le maintien, second chemin vers l'appui ----------------------------------------------

    /**
     * La garantie que cherche ce chemin : **un appui sans aucune articulation des doigts.**
     *
     * Il existe parce que le pincement a échoué sur l'appareil pour une raison structurelle — le
     * pouce posé sur l'index se confond avec lui dans l'image — et que tout appui reposant sur les
     * doigts hérite de cette fragilité. Une position, elle, est la grandeur la mieux mesurée de
     * toute la chaîne.
     */
    @Test
    fun `viser et ne plus bouger produit un appui sans pincer`() {
        val d = driver(0.5f, 0.5f)
        // Déplacement franc : c'est lui qui arme le maintien.
        d.frames(3, 0.8f, 0.3f, open)
        // Puis l'immobilité, main parfaitement ouverte.
        d.frames(32, 0.8f, 0.3f, open)

        val taps = d.only<PointerEvent.Tap>()
        assertEquals("le maintien n'a produit aucun appui", 1, taps.size)
        assertEquals(0.8f, taps[0].x, 1e-3f)
        assertEquals(0.3f, taps[0].y, 1e-3f)
    }

    /**
     * Le défaut qu'il faut absolument écarter : une main simplement posée produirait un appui par
     * seconde, indéfiniment. Le réarmement par le déplacement est ce qui l'interdit.
     */
    @Test
    fun `une main posee ne produit pas une rafale d appuis`() {
        val d = driver(0.5f, 0.5f)
        d.frames(3, 0.8f, 0.3f, open)
        // Trois secondes d'immobilité : de quoi produire trois appuis si rien ne l'empêchait.
        d.frames(95, 0.8f, 0.3f, open)
        assertEquals(1, d.only<PointerEvent.Tap>().size)
    }

    @Test
    fun `le maintien ne declenche pas tant que la main n a pas bouge`() {
        val d = driver(0.5f, 0.5f)
        // Main immobile depuis le début, jamais déplacée : rien ne doit partir.
        d.frames(60, 0.5f, 0.5f, open)
        assertTrue(
            "un appui est parti sans que la main ait jamais bougé",
            d.only<PointerEvent.Tap>().isEmpty()
        )
    }

    @Test
    fun `bouger annule le maintien avant son terme`() {
        val d = driver(0.5f, 0.5f)
        d.frames(3, 0.8f, 0.3f, open)
        // Attente volontairement **dérivée du réglage**, et non écrite en dur : une valeur fixe de
        // vingt images décrivait une attente incomplète tant que le maintien durait 900 ms, et une
        // attente déjà aboutie après son passage à 520. Le test affirmait alors le contraire de son
        // intention sans que rien ne le signale — sauf qu'ici, il a échoué, ce qui est le but.
        val partialFrames = (Pointer.Config().dwellMs * 2 / 3 / frameMs).toInt()
        d.frames(partialFrames, 0.8f, 0.3f, open)
        d.frames(6, 0.2f, 0.7f, open)
        assertTrue("le maintien a abouti malgré le mouvement", d.only<PointerEvent.Tap>().isEmpty())
    }

    /** Viser puis pincer ne doit pas produire deux appuis pour un seul geste. */
    @Test
    fun `un pincement n ajoute pas un appui par maintien`() {
        val d = driver(0.5f, 0.5f)
        d.frames(3, 0.8f, 0.3f, open)
        d.frames(10, 0.8f, 0.3f, open)
        d.frame(0.8f, 0.3f, closed)
        d.frame(0.8f, 0.3f, closed)
        d.release(0.8f, 0.3f)
        // Puis la main reste posée là où elle vient de cliquer.
        d.frames(40, 0.8f, 0.3f, open)
        assertEquals(1, d.only<PointerEvent.Tap>().size)
    }

    /**
     * **Le défaut né de la rencontre de deux ajouts corrects.** Le défilement par les bords demande
     * de poser le curseur contre un bord et de ne plus bouger ; le maintien se déclenche
     * précisément quand on ne bouge plus. Poser le curseur pour défiler appuyait donc aussi sur ce
     * qui se trouvait sous lui — au bord de l'écran, soit souvent une barre d'onglets ou d'état.
     */
    @Test
    fun `le maintien ne declenche pas la ou le defilement prend la main`() {
        val d = driver(0.5f, 0.5f)
        repeat(3) {
            d.pointer.update(
                handPresent = true, quality = 1f, x = 0.5f, y = 0.02f,
                pinchRatio = open, nowMs = d.t, dwellInhibited = true
            ).let { d.events += it }
            d.t += frameMs
        }
        repeat(40) {
            d.events += d.pointer.update(
                handPresent = true, quality = 1f, x = 0.5f, y = 0.02f,
                pinchRatio = open, nowMs = d.t, dwellInhibited = true
            )
            d.t += frameMs
        }
        assertTrue("un appui est parti dans la bande de défilement", d.only<PointerEvent.Tap>().isEmpty())
    }

    /**
     * Et sortir de la zone inhibée ne doit pas libérer l'appui resté en attente : il partirait à
     * l'endroit où le curseur arrive, que personne n'a visé.
     */
    @Test
    fun `sortir de la zone inhibee ne libere pas un appui en attente`() {
        val d = driver(0.5f, 0.5f)
        // Longue immobilité en zone inhibée, puis sortie et immobilité brève ailleurs.
        repeat(40) {
            d.events += d.pointer.update(
                handPresent = true, quality = 1f, x = 0.5f, y = 0.02f,
                pinchRatio = open, nowMs = d.t, dwellInhibited = true
            )
            d.t += frameMs
        }
        repeat(4) {
            d.events += d.pointer.update(
                handPresent = true, quality = 1f, x = 0.5f, y = 0.40f,
                pinchRatio = open, nowMs = d.t, dwellInhibited = false
            )
            d.t += frameMs
        }
        assertTrue(
            "l'appui en attente a été libéré en sortant de la bande",
            d.only<PointerEvent.Tap>().isEmpty()
        )
    }

    /**
     * Le réglage de durée doit se **voir** : c'est le reproche qui l'a fait changer — « la méthode
     * me plaît mais c'est trop lent ». Un maintien qui ignorerait sa configuration rendrait le
     * retour d'usage inopérant.
     */
    @Test
    fun `la duree du maintien suit son reglage`() {
        fun framesUntilTap(dwellMs: Long): Int {
            val d = Driver(Pointer(Pointer.Config(dwellMs = dwellMs)))
            d.frames(40, 0.5f, 0.5f, open)
            d.events.clear()
            d.frames(3, 0.8f, 0.3f, open)
            var frames = 0
            while (frames < 200 && d.only<PointerEvent.Tap>().isEmpty()) {
                d.frame(0.8f, 0.3f, open)
                frames++
            }
            return frames
        }

        val court = framesUntilTap(300L)
        val long = framesUntilTap(900L)
        assertTrue("court=$court long=$long", long > court * 2)
    }

    /**
     * Une dérive tardive ne doit pas annuler une attente presque terminée.
     *
     * C'est la moitié invisible de la lenteur : tenir une main en l'air dérive d'autant plus qu'on
     * la tient longtemps, si bien qu'un rayon constant exigeait le plus de stabilité au moment où la
     * main en a le moins. La durée nominale n'était alors jamais la durée vécue.
     */
    @Test
    fun `une derive tardive n annule pas une attente presque terminee`() {
        val d = driver(0.5f, 0.5f)
        d.frames(3, 0.8f, 0.3f, open)
        // Attente presque complète, puis une dérive au-delà du rayon nominal mais sous le rayon
        // élargi : l'appui doit tout de même aboutir.
        val almost = (Pointer.Config().dwellMs * 9 / 10 / frameMs).toInt()
        d.frames(almost, 0.8f, 0.3f, open)
        val nominal = Pointer.Config().dwellRadius
        d.frames(6, 0.8f + nominal * 1.4f, 0.3f, open)
        assertEquals(
            "la dérive tardive a annulé l'attente",
            1,
            d.only<PointerEvent.Tap>().size
        )
    }

    @Test
    fun `le maintien peut etre desactive`() {
        val d = Driver(Pointer(Pointer.Config(dwellEnabled = false)))
        d.frames(40, 0.5f, 0.5f, open)
        d.events.clear()
        d.frames(3, 0.8f, 0.3f, open)
        d.frames(60, 0.8f, 0.3f, open)
        assertTrue(d.only<PointerEvent.Tap>().isEmpty())
    }
}
