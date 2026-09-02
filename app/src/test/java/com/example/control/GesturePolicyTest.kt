package com.example.control

import com.example.gesture.GestureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

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

    // --- Glisser -------------------------------------------------------------------------------

    /**
     * Le glisser n'a plus de pose dédiée : c'est le clic maintenu. Une pose à laquelle on assigne
     * explicitement « Glisser » doit malgré tout continuer de fonctionner, et se résoudre au
     * relâchement de cette pose — quelle qu'elle soit.
     */
    @Test
    fun `une pose a laquelle on assigne le glisser se resout a son relachement`() {
        val custom = GestureBindings().with(GestureType.OPEN_PALM, BindableAction.DRAG)
        val p = policy(custom)
        p.onGesture(GestureType.OPEN_PALM, 0.2f, 0.2f, isNewPose = true, timestampMs = 0)
        p.onGesture(GestureType.OPEN_PALM, 0.5f, 0.5f, isNewPose = false, timestampMs = 33)
        val actions = p.onGesture(GestureType.POINT, 0.8f, 0.8f, isNewPose = true, timestampMs = 66)

        val drag = actions.filterIsInstance<SystemAction.Drag>().single()
        assertEquals(0.2f, drag.fromX, 1e-5f)
        assertEquals(0.8f, drag.toX, 1e-5f)
    }

    // --- Actions ponctuelles ------------------------------------------------------------------

    @Test
    fun `le poing ne produit qu un seul retour meme maintenu`() {
        val actions = hold(policy(), GestureType.FIST, frames = 30)
        assertEquals(1, actions.count { it == SystemAction.Back })
    }

    @Test
    fun `le pouce leve ouvre l accueil une seule fois`() {
        assertEquals(1, hold(policy(), GestureType.THUMB_UP, frames = 30).count { it == SystemAction.Home })
    }

    @Test
    fun `la main ouverte bascule lecture-pause une seule fois`() {
        assertEquals(1, hold(policy(), GestureType.OPEN_PALM, frames = 30).count { it == SystemAction.PlayPause })
    }

    @Test
    fun `deux fronts rapproches ne declenchent qu une action`() {
        val p = policy()
        val a = p.onGesture(GestureType.FIST, 0.5f, 0.5f, isNewPose = true, timestampMs = 0)
        p.onGesture(GestureType.POINT, 0.5f, 0.5f, isNewPose = true, timestampMs = 100)
        val b = p.onGesture(GestureType.FIST, 0.5f, 0.5f, isNewPose = true, timestampMs = 200)
        assertEquals(1, a.count { it == SystemAction.Back })
        assertEquals(0, b.count { it == SystemAction.Back })
    }

    // --- Personnalisation ---------------------------------------------------------------------

    @Test
    fun `une association personnalisee remplace l action par defaut`() {
        val custom = GestureBindings().with(GestureType.FIST, BindableAction.QUICK_SETTINGS)
        val actions = hold(policy(custom), GestureType.FIST, frames = 10)
        assertTrue(actions.contains(SystemAction.QuickSettings))
        assertTrue(actions.none { it == SystemAction.Back })
    }

    @Test
    fun `une pose peut etre desactivee`() {
        val custom = GestureBindings().with(GestureType.OPEN_PALM, BindableAction.NONE)
        assertTrue(hold(policy(custom), GestureType.OPEN_PALM, frames = 30).isEmpty())
    }

    @Test
    fun `le role de curseur peut etre deplace sur une autre pose`() {
        val custom = GestureBindings()
            .with(GestureType.POINT, BindableAction.NONE)
            .with(GestureType.TWO_FINGER, BindableAction.CURSOR)
        val actions = hold(policy(custom), GestureType.TWO_FINGER, frames = 3, x = 0.4f, y = 0.4f)
        assertTrue(actions.any { it is SystemAction.MoveCursor })
    }

    // --- Défilement ---------------------------------------------------------------------------

    /** Le défilement continu reste assignable, même s'il n'est plus câblé par défaut. */
    private fun scrollPolicy() =
        policy(GestureBindings().with(GestureType.TWO_FINGER, BindableAction.SCROLL))

    @Test
    fun `le sens du defilement suit la main`() {
        val p = scrollPolicy()
        p.onGesture(GestureType.TWO_FINGER, 0.5f, 0.6f, isNewPose = true, timestampMs = 0)
        val up = p.onGesture(GestureType.TWO_FINGER, 0.5f, 0.4f, isNewPose = false, timestampMs = 33)
            .filterIsInstance<SystemAction.Scroll>().single()
        assertTrue("la main monte, le contenu doit descendre", up.amount > 0f)
    }

    @Test
    fun `le tremblement ne fait pas defiler`() {
        val p = scrollPolicy()
        p.onGesture(GestureType.TWO_FINGER, 0.5f, 0.5f, isNewPose = true, timestampMs = 0)
        val actions = p.onGesture(GestureType.TWO_FINGER, 0.5f, 0.503f, isNewPose = false, timestampMs = 33)
        assertTrue(actions.none { it is SystemAction.Scroll })
    }

    @Test
    fun `changer de pose reamorce la reference de defilement`() {
        val p = scrollPolicy()
        p.onGesture(GestureType.POINT, 0.5f, 0.1f, isNewPose = true, timestampMs = 0)
        p.onGesture(GestureType.POINT, 0.5f, 0.9f, isNewPose = false, timestampMs = 33)
        val first = p.onGesture(GestureType.TWO_FINGER, 0.5f, 0.9f, isNewPose = true, timestampMs = 66)
        assertTrue("un saut de défilement a été émis", first.none { it is SystemAction.Scroll })
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

    // --- Navigation ---------------------------------------------------------------------------

    @Test
    fun `le balayage droite passe a l application suivante`() {
        val actions = policy().onGesture(GestureType.SWIPE_RIGHT, 0.5f, 0.5f, isNewPose = true, timestampMs = 0)
        assertTrue(actions.contains(SystemAction.SwitchNext))
    }

    @Test
    fun `le balayage bas deroule les notifications`() {
        val actions = policy().onGesture(GestureType.SWIPE_DOWN, 0.5f, 0.5f, isNewPose = true, timestampMs = 0)
        assertTrue(actions.contains(SystemAction.Notifications))
    }

    @Test
    fun `un balayage est personnalisable comme une pose`() {
        val custom = GestureBindings().with(GestureType.SWIPE_UP, BindableAction.SCREENSHOT)
        val actions = policy(custom)
            .onGesture(GestureType.SWIPE_UP, 0.5f, 0.5f, isNewPose = true, timestampMs = 0)
        assertTrue(actions.contains(SystemAction.Screenshot))
    }

    /**
     * Un balayage n'a pas d'image suivante : lui confier un rôle continu produirait un curseur
     * d'une seule frame, c'est-à-dire un saut. La configuration est neutralisée plutôt qu'exécutée.
     */
    @Test
    fun `un role continu affecte a un balayage est neutralise`() {
        val custom = GestureBindings().with(GestureType.SWIPE_UP, BindableAction.CURSOR)
        val actions = policy(custom)
            .onGesture(GestureType.SWIPE_UP, 0.5f, 0.5f, isNewPose = true, timestampMs = 0)
        assertTrue(actions.none { it is SystemAction.MoveCursor })
    }

    @Test
    fun `l interface ne propose pas de role continu pour un balayage`() {
        val proposed = BindableAction.assignableTo(GestureType.SWIPE_LEFT)
        assertTrue(proposed.none { it.isContinuous })
        assertTrue(BindableAction.assignableTo(GestureType.POINT).any { it.isContinuous })
    }

    /**
     * Un balayage traverse la pose tenue. S'il la remplaçait, le défilement en cours perdrait sa
     * référence et repartirait d'un saut à l'image suivante.
     */
    @Test
    fun `un balayage ne reamorce pas le geste continu sous-jacent`() {
        val p = scrollPolicy()
        p.onGesture(GestureType.TWO_FINGER, 0.5f, 0.50f, isNewPose = true, timestampMs = 0)
        p.onGesture(GestureType.TWO_FINGER, 0.5f, 0.45f, isNewPose = false, timestampMs = 33)
        p.onGesture(GestureType.SWIPE_RIGHT, 0.5f, 0.45f, isNewPose = true, timestampMs = 66)
        val after = p.onGesture(GestureType.TWO_FINGER, 0.5f, 0.42f, isNewPose = false, timestampMs = 99)
        val scroll = after.filterIsInstance<SystemAction.Scroll>().singleOrNull()
        assertTrue("le défilement a été perdu ou a sauté", scroll != null && abs(scroll.amount) < 0.2f)
    }

    // --- Poses ajoutées -----------------------------------------------------------------------

    /**
     * Le « V » est la pose des balayages : lui associer une action ponctuelle la déclencherait à
     * chaque fois qu'on entre dans la pose pour balayer.
     */
    @Test
    fun `la pose de navigation ne declenche rien par elle-meme`() {
        assertEquals(BindableAction.NONE, GestureBindings()[GestureType.TWO_FINGER])
        assertTrue(hold(policy(), GestureType.TWO_FINGER, frames = 30).isEmpty())
    }

    @Test
    fun `chaque pose tenue autre que la navigation a une action par defaut`() {
        val bindings = GestureBindings()
        GestureBindings.CONFIGURABLE_POSES
            .filterNot { GestureBindings.isSwipe(it) || it == GestureType.TWO_FINGER }
            .forEach { pose ->
                assertNotEquals("$pose n'a pas d'action par défaut", BindableAction.NONE, bindings[pose])
            }
    }

    @Test
    fun `la main au repos ne commande rien`() {
        assertTrue(hold(policy(), GestureType.IDLE, frames = 30).isEmpty())
    }

    // --- Sérialisation des associations -------------------------------------------------------

    @Test
    fun `les associations survivent a un aller-retour de serialisation`() {
        val custom = GestureBindings()
            .with(GestureType.FIST, BindableAction.QUICK_SETTINGS)
            .with(GestureType.OPEN_PALM, BindableAction.SCREENSHOT)
        val restored = GestureBindings.deserialize(custom.serialize())
        assertEquals(BindableAction.QUICK_SETTINGS, restored[GestureType.FIST])
        assertEquals(BindableAction.SCREENSHOT, restored[GestureType.OPEN_PALM])
        assertEquals(BindableAction.CURSOR, restored[GestureType.POINT])
    }

    @Test
    fun `une configuration corrompue retombe sur les valeurs par defaut`() {
        val restored = GestureBindings.deserialize("FIST=inconnu;POSE_QUI_N_EXISTE_PAS=home;;=")
        assertEquals(BindableAction.BACK, restored[GestureType.FIST])
        assertEquals(BindableAction.CURSOR, restored[GestureType.POINT])
    }
}
