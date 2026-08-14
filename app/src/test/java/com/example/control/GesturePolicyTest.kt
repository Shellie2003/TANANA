package com.example.control

import com.example.gesture.GestureType
import org.junit.Assert.assertEquals
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

    // --- Clic par immobilité ------------------------------------------------------------------

    @Test
    fun `l immobilite du curseur declenche un appui`() {
        val actions = hold(policy(), GestureType.POINT, frames = 40, x = 0.3f, y = 0.7f)
        val tap = actions.filterIsInstance<SystemAction.Tap>().single()
        assertEquals(0.3f, tap.x, 1e-5f)
        assertEquals(0.7f, tap.y, 1e-5f)
    }

    /** Rester immobile ne doit pas produire un clic par seconde. */
    @Test
    fun `l immobilite prolongee ne produit qu un appui`() {
        val actions = hold(policy(), GestureType.POINT, frames = 200)
        assertEquals(1, actions.count { it is SystemAction.Tap })
    }

    /** Survoler sans cliquer doit rester possible : bouger annule l'appui en préparation. */
    @Test
    fun `bouger annule l appui en preparation`() {
        val p = policy()
        var t = 0L
        var x = 0.2f
        val actions = mutableListOf<SystemAction>()
        repeat(60) { i ->
            actions += p.onGesture(GestureType.POINT, x, 0.5f, isNewPose = i == 0, timestampMs = t)
            x += 0.02f
            t += 33
        }
        assertTrue("un appui a été émis pendant un déplacement", actions.none { it is SystemAction.Tap })
    }

    @Test
    fun `la progression de l immobilite est rapportee`() {
        val p = policy()
        p.onGesture(GestureType.POINT, 0.5f, 0.5f, isNewPose = true, timestampMs = 0)
        p.onGesture(GestureType.POINT, 0.5f, 0.5f, isNewPose = false, timestampMs = 300)
        assertTrue("progression=${p.dwellProgress}", p.dwellProgress in 0.3f..0.7f)
    }

    @Test
    fun `un nouvel appui exige d avoir quitte la zone`() {
        val p = policy()
        var t = 0L
        repeat(40) { p.onGesture(GestureType.POINT, 0.5f, 0.5f, isNewPose = it == 0, timestampMs = t); t += 33 }

        // Déplacement franc, puis immobilité de nouveau.
        val second = mutableListOf<SystemAction>()
        repeat(40) { second += p.onGesture(GestureType.POINT, 0.8f, 0.5f, isNewPose = false, timestampMs = t); t += 33 }
        assertEquals(1, second.count { it is SystemAction.Tap })
    }

    // --- Pincement ----------------------------------------------------------------------------

    @Test
    fun `un pincement maintenu immobile produit un appui long`() {
        val p = policy()
        p.onGesture(GestureType.PINCH, 0.4f, 0.6f, isNewPose = true, timestampMs = 0)
        repeat(20) { p.onGesture(GestureType.PINCH, 0.4f, 0.6f, isNewPose = false, timestampMs = 33L * it) }
        val actions = p.onGesture(GestureType.POINT, 0.4f, 0.6f, isNewPose = true, timestampMs = 900)
        assertTrue(actions.any { it is SystemAction.LongPress })
    }

    @Test
    fun `un pincement deplace produit un glisser`() {
        val p = policy()
        p.onGesture(GestureType.PINCH, 0.2f, 0.2f, isNewPose = true, timestampMs = 0)
        p.onGesture(GestureType.PINCH, 0.5f, 0.5f, isNewPose = false, timestampMs = 33)
        val actions = p.onGesture(GestureType.POINT, 0.8f, 0.8f, isNewPose = true, timestampMs = 66)

        val drag = actions.filterIsInstance<SystemAction.Drag>().single()
        assertEquals(0.2f, drag.fromX, 1e-5f)
        assertEquals(0.8f, drag.toX, 1e-5f)
    }

    @Test
    fun `un pincement bref et immobile produit un appui`() {
        val p = policy()
        p.onGesture(GestureType.PINCH, 0.4f, 0.6f, isNewPose = true, timestampMs = 0)
        val actions = p.onGesture(GestureType.POINT, 0.4f, 0.6f, isNewPose = true, timestampMs = 100)
        assertTrue(actions.any { it is SystemAction.Tap })
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

    @Test
    fun `le sens du defilement suit la main`() {
        val p = policy()
        p.onGesture(GestureType.TWO_FINGER, 0.5f, 0.6f, isNewPose = true, timestampMs = 0)
        val up = p.onGesture(GestureType.TWO_FINGER, 0.5f, 0.4f, isNewPose = false, timestampMs = 33)
            .filterIsInstance<SystemAction.Scroll>().single()
        assertTrue("la main monte, le contenu doit descendre", up.amount > 0f)
    }

    @Test
    fun `le tremblement ne fait pas defiler`() {
        val p = policy()
        p.onGesture(GestureType.TWO_FINGER, 0.5f, 0.5f, isNewPose = true, timestampMs = 0)
        val actions = p.onGesture(GestureType.TWO_FINGER, 0.5f, 0.503f, isNewPose = false, timestampMs = 33)
        assertTrue(actions.none { it is SystemAction.Scroll })
    }

    @Test
    fun `changer de pose reamorce la reference de defilement`() {
        val p = policy()
        p.onGesture(GestureType.POINT, 0.5f, 0.1f, isNewPose = true, timestampMs = 0)
        p.onGesture(GestureType.POINT, 0.5f, 0.9f, isNewPose = false, timestampMs = 33)
        val first = p.onGesture(GestureType.TWO_FINGER, 0.5f, 0.9f, isNewPose = true, timestampMs = 66)
        assertTrue("un saut de défilement a été émis", first.none { it is SystemAction.Scroll })
    }

    // --- Navigation ---------------------------------------------------------------------------

    @Test
    fun `le balayage droite passe a l application suivante`() {
        val actions = policy().onGesture(GestureType.SWIPE_RIGHT, 0.5f, 0.5f, isNewPose = true, timestampMs = 0)
        assertTrue(actions.contains(SystemAction.SwitchNext))
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
