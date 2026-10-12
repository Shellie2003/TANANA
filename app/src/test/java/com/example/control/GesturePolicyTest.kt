package com.example.control

import com.example.gesture.GestureType
import com.example.input.CommandWheel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GesturePolicyTest {

    private fun policy(bindings: GestureBindings = GestureBindings()) = GesturePolicy(bindings)

    private fun hold(
        policy: GesturePolicy,
        gesture: GestureType,
        frames: Int,
        startTime: Long = 0,
        x: Float = 0.5f,
        y: Float = 0.5f
    ): List<SystemAction> {
        val all = mutableListOf<SystemAction>()
        var t = startTime
        repeat(frames) { i ->
            all += policy.onGesture(gesture, x, y, isNewPose = i == 0, timestampMs = t)
            t += 33
        }
        return all
    }

    // --- Clic par impulsion du pouce ------------------------------------------------------

    /** Ouverture du pouce, valeur de repos : ne doit jamais cliquer. */
    private val open = 0.85f
    private val closed = 0.15f

    @Test
    fun `une impulsion du pouce produit un appui immediat`() {
        val p = policy()
        var t = 0L
        val actions = mutableListOf<SystemAction>()
        listOf(open, closed, open).forEachIndexed { i, ratio ->
            actions += p.onGesture(GestureType.POINT, 0.3f, 0.7f, i == 0, t, ratio)
            t += 33
        }
        val tap = actions.filterIsInstance<SystemAction.Tap>().single()
        assertEquals(0.3f, tap.x, 1e-5f)
        assertTrue("latence ${t}ms", t <= 100)
    }

    /**
     * Le pouce au repos près de l'index est la position naturelle du pointage. Elle ne doit
     * produire aucun clic, même maintenue longuement.
     */
    @Test
    fun `un pouce au repos pres de l index ne clique pas`() {
        val p = policy()
        var t = 0L
        val actions = mutableListOf<SystemAction>()
        repeat(120) { i ->
            actions += p.onGesture(GestureType.POINT, 0.5f, 0.5f, i == 0, t, closed)
            t += 33
        }
        assertTrue(actions.none { it is SystemAction.Tap })
    }

    /** Vitesse de clic : plusieurs impulsions rapprochées doivent toutes passer. */
    @Test
    fun `des clics rapproches sont tous transmis`() {
        val p = policy()
        var t = 0L
        var taps = 0
        repeat(5) {
            listOf(open, closed, open, open, open, open, open, open).forEach { ratio ->
                taps += p.onGesture(GestureType.POINT, 0.5f, 0.5f, false, t, ratio)
                    .count { a -> a is SystemAction.Tap }
                t += 33
            }
        }
        assertEquals(5, taps)
    }

    @Test
    fun `l immobilite ne clique plus par defaut`() {
        val p = policy()
        var t = 0L
        val actions = mutableListOf<SystemAction>()
        repeat(120) { i ->
            actions += p.onGesture(GestureType.POINT, 0.5f, 0.5f, i == 0, t, open)
            t += 33
        }
        assertTrue("un clic par attente a été émis", actions.none { it is SystemAction.Tap })
    }

    @Test
    fun `un pouce maintenu ferme puis deplace produit un glisser`() {
        val p = policy()
        var t = 0L
        val actions = mutableListOf<SystemAction>()
        actions += p.onGesture(GestureType.POINT, 0.2f, 0.2f, true, t, open); t += 33
        actions += p.onGesture(GestureType.POINT, 0.2f, 0.2f, false, t, closed); t += 33
        repeat(20) { actions += p.onGesture(GestureType.POINT, 0.2f, 0.2f, false, t, closed); t += 33 }
        repeat(5) { actions += p.onGesture(GestureType.POINT, 0.8f, 0.8f, false, t, closed); t += 33 }
        actions += p.onGesture(GestureType.POINT, 0.8f, 0.8f, false, t, open)

        assertTrue(actions.any { it is SystemAction.DragStart })
        val drag = actions.filterIsInstance<SystemAction.Drag>().single()
        assertEquals(0.2f, drag.fromX, 1e-5f)
        assertEquals(0.8f, drag.toX, 1e-5f)
    }

    // --- Actions ponctuelles ------------------------------------------------------------------

    /**
     * Une action ponctuelle exige désormais que la pose soit **tenue**. Elle partait au front
     * montant, ce qui en faisait l'otage de la moindre image mal classée : une seule suffisait à
     * fermer un écran. Le maintien est ce qui rend l'erreur rattrapable — l'anneau se remplit, et
     * changer de pose annule.
     */
    @Test
    fun `une action ponctuelle attend que la pose soit tenue`() {
        val p = policy()
        val immediate = p.onGesture(GestureType.FIST, 0.5f, 0.5f, isNewPose = true, timestampMs = 0)
        assertTrue("le retour est parti au front montant", immediate.none { it == SystemAction.Back })

        val actions = mutableListOf<SystemAction>()
        var t = 33L
        repeat(20) { actions += p.onGesture(GestureType.FIST, 0.5f, 0.5f, false, t); t += 33 }
        assertEquals(1, actions.count { it == SystemAction.Back })
    }

    /** Quitter la pose avant la fin du maintien doit l'annuler, sans rien exécuter. */
    @Test
    fun `quitter la pose avant la fin du maintien annule l action`() {
        val p = policy()
        val actions = mutableListOf<SystemAction>()
        var t = 0L
        // Cinq images, soit 165 ms : moins que le maintien exigé.
        repeat(5) { i -> actions += p.onGesture(GestureType.FIST, 0.5f, 0.5f, i == 0, t); t += 33 }
        repeat(20) { actions += p.onGesture(GestureType.POINT, 0.5f, 0.5f, false, t); t += 33 }
        assertTrue("une action annulée a été exécutée", actions.none { it == SystemAction.Back })
    }

    /** La progression du maintien doit être lisible : c'est elle qui rend le délai acceptable. */
    @Test
    fun `la progression du maintien est exposee`() {
        val p = policy()
        var t = 0L
        p.onGesture(GestureType.FIST, 0.5f, 0.5f, isNewPose = true, timestampMs = t)
        assertEquals(0f, p.poseProgress, 1e-5f)
        repeat(5) { t += 33; p.onGesture(GestureType.FIST, 0.5f, 0.5f, false, t) }
        assertTrue("progression figée à ${p.poseProgress}", p.poseProgress > 0.2f && p.poseProgress < 1f)
    }

    @Test
    fun `le poing ne produit qu un seul retour meme maintenu`() {
        val actions = hold(policy(), GestureType.FIST, frames = 60)
        assertEquals(1, actions.count { it == SystemAction.Back })
    }

    @Test
    fun `deux maintiens rapproches ne declenchent qu une action`() {
        val p = policy()
        val a = hold(p, GestureType.FIST, frames = 15, startTime = 0)
        // Retour immédiat au poing : le garde-fou de répétition doit absorber le second maintien.
        val b = hold(p, GestureType.POINT, frames = 2, startTime = 500) +
            hold(p, GestureType.FIST, frames = 15, startTime = 570)
        assertEquals(1, a.count { it == SystemAction.Back })
        assertEquals(0, b.count { it == SystemAction.Back })
    }

    // --- Roue de commandes --------------------------------------------------------------------

    /**
     * La roue est tenue ouverte par la pose, et ne s'ouvre pas sur une action : la pose de la main
     * ouverte ne doit donc exécuter aucune commande par elle-même, même maintenue longuement.
     */
    @Test
    fun `ouvrir la roue n execute aucune commande`() {
        val p = policy()
        val actions = hold(policy(), GestureType.OPEN_PALM, frames = 60)
        assertTrue("la roue a exécuté une commande en s'ouvrant", actions.isEmpty())
        assertEquals(BindableAction.COMMAND_WHEEL, GestureBindings()[GestureType.OPEN_PALM])
        assertEquals(null, p.wheelState())
    }

    @Test
    fun `la roue s ouvre avec la pose et se referme en la quittant`() {
        val p = policy()
        p.onGesture(GestureType.OPEN_PALM, 0.5f, 0.5f, isNewPose = true, timestampMs = 0)
        assertTrue("la roue ne s'est pas ouverte", p.wheelOpen)
        p.onGesture(GestureType.POINT, 0.5f, 0.5f, isNewPose = true, timestampMs = 33)
        assertTrue("la roue est restée ouverte", !p.wheelOpen)
        assertEquals(null, p.wheelState())
    }

    /** Viser une direction puis cliquer exécute la commande de ce secteur. */
    @Test
    fun `viser une direction puis cliquer execute sa commande`() {
        val p = policy()
        var t = 0L
        val actions = mutableListOf<SystemAction>()
        // Main ouverte au centre : rien n'est visé, et le détecteur de clic apprend la course.
        repeat(20) { i ->
            actions += p.onGesture(GestureType.OPEN_PALM, 0.5f, 0.5f, i == 0, t, open); t += 33
        }
        // Vers le haut : secteur 0, « Accueil » par défaut.
        repeat(6) { actions += p.onGesture(GestureType.OPEN_PALM, 0.5f, 0.1f, false, t, open); t += 33 }
        actions += p.onGesture(GestureType.OPEN_PALM, 0.5f, 0.1f, false, t, closed); t += 33
        actions += p.onGesture(GestureType.OPEN_PALM, 0.5f, 0.1f, false, t, open)

        assertTrue("la commande du secteur visé n'est pas partie : $actions", actions.contains(SystemAction.Home))
    }

    /** Refermer la main sans cliquer n'exécute rien : c'est la façon d'annuler. */
    @Test
    fun `quitter la roue sans cliquer n execute rien`() {
        val p = policy()
        var t = 0L
        val actions = mutableListOf<SystemAction>()
        repeat(20) { i ->
            actions += p.onGesture(GestureType.OPEN_PALM, 0.5f, 0.1f, i == 0, t, open); t += 33
        }
        repeat(5) { actions += p.onGesture(GestureType.POINT, 0.5f, 0.1f, false, t, open); t += 33 }
        assertTrue("une commande est partie sans validation", actions.none { it == SystemAction.Home })
    }

    @Test
    fun `une direction de la roue est personnalisable`() {
        val custom = GestureBindings().withSector(0, BindableAction.SCREENSHOT)
        val p = policy(custom)
        var t = 0L
        val actions = mutableListOf<SystemAction>()
        repeat(20) { i ->
            actions += p.onGesture(GestureType.OPEN_PALM, 0.5f, 0.5f, i == 0, t, open); t += 33
        }
        repeat(6) { actions += p.onGesture(GestureType.OPEN_PALM, 0.5f, 0.1f, false, t, open); t += 33 }
        actions += p.onGesture(GestureType.OPEN_PALM, 0.5f, 0.1f, false, t, closed); t += 33
        actions += p.onGesture(GestureType.OPEN_PALM, 0.5f, 0.1f, false, t, open)

        assertTrue(actions.contains(SystemAction.Screenshot))
        assertTrue(actions.none { it == SystemAction.Home })
    }

    // --- Personnalisation ---------------------------------------------------------------------

    @Test
    fun `une association personnalisee remplace l action par defaut`() {
        val custom = GestureBindings().with(GestureType.FIST, BindableAction.QUICK_SETTINGS)
        val actions = hold(policy(custom), GestureType.FIST, frames = 20)
        assertTrue(actions.contains(SystemAction.QuickSettings))
        assertTrue(actions.none { it == SystemAction.Back })
    }

    @Test
    fun `une pose peut etre desactivee`() {
        val custom = GestureBindings().with(GestureType.FIST, BindableAction.NONE)
        assertTrue(hold(policy(custom), GestureType.FIST, frames = 60).isEmpty())
    }

    @Test
    fun `le role de curseur peut etre deplace sur une autre pose`() {
        val custom = GestureBindings()
            .with(GestureType.POINT, BindableAction.NONE)
            .with(GestureType.OPEN_PALM, BindableAction.CURSOR)
        val actions = hold(policy(custom), GestureType.OPEN_PALM, frames = 3, x = 0.4f, y = 0.4f)
        assertTrue(actions.any { it is SystemAction.MoveCursor })
    }

    // --- Le doigt posé ---------------------------------------------------------------------------

    /**
     * Le contact maintenu pose le doigt et le fait suivre la main. C'est ce flux continu qui
     * remplace le défilement gestuel : rien n'est reconnu, c'est le système qui interprète — et
     * il sait déjà faire défiler, lancer, glisser-déposer et balayer.
     */
    @Test
    fun `le contact maintenu produit un flux continu de deplacement`() {
        val p = policy()
        var t = 0L
        val actions = mutableListOf<SystemAction>()
        actions += p.onGesture(GestureType.POINT, 0.5f, 0.70f, true, t, open); t += 33
        actions += p.onGesture(GestureType.POINT, 0.5f, 0.70f, false, t, closed); t += 33
        repeat(15) { actions += p.onGesture(GestureType.POINT, 0.5f, 0.70f, false, t, closed); t += 33 }
        // Le doigt est posé : la main remonte, le contenu doit suivre pas à pas.
        (1..8).forEach { i ->
            actions += p.onGesture(GestureType.POINT, 0.5f, 0.70f - i * 0.05f, false, t, closed)
            t += 33
        }
        actions += p.onGesture(GestureType.POINT, 0.5f, 0.30f, false, t, open)

        assertTrue("le doigt n'a pas été posé", actions.any { it is SystemAction.DragStart })
        val moves = actions.filterIsInstance<SystemAction.DragMove>()
        assertTrue("flux trop pauvre : ${moves.size} déplacements", moves.size >= 8)
        assertTrue("le doigt n'a pas été levé", actions.any { it is SystemAction.Drag })
    }

    /** Tant que le doigt n'est pas posé, aucun déplacement ne doit partir. */
    @Test
    fun `deplacer le curseur sans contact ne produit aucun flux`() {
        val actions = mutableListOf<SystemAction>()
        val p = policy()
        var t = 0L
        (0..30).forEach { i ->
            actions += p.onGesture(GestureType.POINT, 0.5f, 0.65f - i * 0.011f, i == 0, t, open)
            t += 33
        }
        assertTrue(actions.none { it is SystemAction.DragMove || it is SystemAction.DragStart })
        assertTrue(actions.any { it is SystemAction.MoveCursor })
    }

    /** Un contact bref reste un clic : il ne doit poser aucun doigt. */
    @Test
    fun `un clic bref ne pose pas le doigt`() {
        val p = policy()
        var t = 0L
        val actions = mutableListOf<SystemAction>()
        listOf(open, closed, open, open).forEach { ratio ->
            actions += p.onGesture(GestureType.POINT, 0.5f, 0.5f, false, t, ratio)
            t += 33
        }
        assertTrue(actions.any { it is SystemAction.Tap })
        assertTrue(actions.none { it is SystemAction.DragStart })
    }

    // --- Vocabulaire ---------------------------------------------------------------------------

    /**
     * Le vocabulaire ne compte plus que trois poses. Ce n'est pas un détail de présentation : c'est
     * ce qui garantit qu'aucune paire ne se distingue par un seul doigt, et donc qu'aucune pose ne
     * peut être verrouillée en traversant une autre.
     */
    @Test
    fun `le vocabulaire configurable se limite a trois poses`() {
        assertEquals(
            listOf(GestureType.POINT, GestureType.FIST, GestureType.OPEN_PALM),
            GestureBindings.CONFIGURABLE_POSES
        )
    }

    @Test
    fun `chaque pose a une action par defaut`() {
        val bindings = GestureBindings()
        GestureBindings.CONFIGURABLE_POSES.forEach { pose ->
            assertNotEquals("$pose n'a pas d'action par défaut", BindableAction.NONE, bindings[pose])
        }
    }

    /** Les huit directions doivent toutes être occupées : un secteur vide serait un trou visible. */
    @Test
    fun `les huit directions de la roue ont une commande par defaut`() {
        val bindings = GestureBindings()
        assertEquals(CommandWheel.SECTORS, bindings.wheelLabels().size)
        repeat(CommandWheel.SECTORS) { i ->
            assertNotEquals("le secteur $i est vide", BindableAction.NONE, bindings.sector(i))
        }
    }

    /**
     * Un secteur se vise puis se valide : il n'a pas de durée, donc aucun rôle continu ne doit lui
     * être proposé — ni, s'il est écrit en dur dans une préférence, accepté.
     */
    @Test
    fun `aucun role continu n est assignable a un secteur`() {
        assertTrue(BindableAction.assignableToSector().none { it.isContinuous })
        val forced = GestureBindings.deserialize("w0=cursor")
        assertNotEquals(BindableAction.CURSOR, forced.sector(0))
    }

    @Test
    fun `la main au repos ne commande rien`() {
        assertTrue(hold(policy(), GestureType.IDLE, frames = 60).isEmpty())
    }

    // --- Sérialisation des associations -------------------------------------------------------

    @Test
    fun `les associations survivent a un aller-retour de serialisation`() {
        val custom = GestureBindings()
            .with(GestureType.FIST, BindableAction.QUICK_SETTINGS)
            .withSector(3, BindableAction.SCREENSHOT)
        val restored = GestureBindings.deserialize(custom.serialize())
        assertEquals(BindableAction.QUICK_SETTINGS, restored[GestureType.FIST])
        assertEquals(BindableAction.SCREENSHOT, restored.sector(3))
        assertEquals(BindableAction.CURSOR, restored[GestureType.POINT])
        assertEquals(GestureBindings.WHEEL_DEFAULTS[0], restored.sector(0))
    }

    @Test
    fun `une configuration corrompue retombe sur les valeurs par defaut`() {
        val restored = GestureBindings.deserialize("FIST=inconnu;POSE_QUI_N_EXISTE_PAS=home;w99=home;;=")
        assertEquals(BindableAction.BACK, restored[GestureType.FIST])
        assertEquals(BindableAction.CURSOR, restored[GestureType.POINT])
        assertEquals(GestureBindings.WHEEL_DEFAULTS[0], restored.sector(0))
    }

    /**
     * Les appareils déjà équipés portent des préférences qui nomment des poses supprimées. Elles
     * doivent être ignorées sans bruit : une configuration héritée ne doit jamais empêcher
     * l'application de démarrer, ni priver l'utilisateur de ses valeurs par défaut.
     */
    @Test
    fun `une configuration heritee nommant des poses supprimees est ignoree`() {
        val legacy = "POINT=cursor;TWO_FINGER=none;THUMB_UP=home;SWIPE_LEFT=switch_prev;FIST=screenshot"
        val restored = GestureBindings.deserialize(legacy)
        assertEquals(BindableAction.SCREENSHOT, restored[GestureType.FIST])
        assertEquals(BindableAction.CURSOR, restored[GestureType.POINT])
        assertEquals(BindableAction.COMMAND_WHEEL, restored[GestureType.OPEN_PALM])
    }
}
