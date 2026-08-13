package com.example.control

import com.example.gesture.GestureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GesturePolicyTest {

    private fun policy() = GesturePolicy()

    /** Maintient une pose pendant [frames] frames et retourne toutes les actions produites. */
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

    @Test
    fun `l index deplace le curseur`() {
        val actions = hold(policy(), GestureType.POINT, frames = 3, x = 0.3f, y = 0.7f)
        assertEquals(SystemAction.MoveCursor(0.3f, 0.7f), actions.first())
    }

    /**
     * Un pincement bref et immobile est un appui. Il est émis au relâchement, jamais à la
     * fermeture : décider trop tôt obligerait à émettre aussi un glisser, et le système
     * exécuterait les deux.
     */
    @Test
    fun `un pincement bref et immobile produit un appui au relachement`() {
        val p = policy()
        val onClose = p.onGesture(GestureType.PINCH, 0.4f, 0.6f, isNewPose = true, timestampMs = 0)
        assertTrue("appui émis trop tôt", onClose.none { it is SystemAction.Tap })

        val onRelease = p.onGesture(GestureType.POINT, 0.4f, 0.6f, isNewPose = true, timestampMs = 100)
        assertEquals(SystemAction.Tap(0.4f, 0.6f), onRelease.filterIsInstance<SystemAction.Tap>().single())
    }

    @Test
    fun `un pincement deplace produit un glisser et non un appui`() {
        val p = policy()
        p.onGesture(GestureType.PINCH, 0.2f, 0.2f, isNewPose = true, timestampMs = 0)
        p.onGesture(GestureType.PINCH, 0.5f, 0.5f, isNewPose = false, timestampMs = 33)
        val actions = p.onGesture(GestureType.POINT, 0.8f, 0.8f, isNewPose = true, timestampMs = 66)

        assertTrue("un appui parasite a été émis", actions.none { it is SystemAction.Tap })
        val drag = actions.filterIsInstance<SystemAction.Drag>().single()
        assertEquals(0.2f, drag.fromX, 1e-5f)
        assertEquals(0.8f, drag.toX, 1e-5f)
    }

    /** Un pincement en cours doit se résoudre même si la main disparaît brutalement. */
    @Test
    fun `la perte de la main resout le pincement en cours`() {
        val p = policy()
        p.onGesture(GestureType.PINCH, 0.4f, 0.4f, isNewPose = true, timestampMs = 0)
        val actions = p.onGesture(GestureType.IDLE, 0.4f, 0.4f, isNewPose = true, timestampMs = 33)
        assertTrue(actions.any { it is SystemAction.Tap })
    }

    // --- Actions ponctuelles -----------------------------------------------------------------

    @Test
    fun `le poing ne produit qu un seul retour meme maintenu`() {
        val actions = hold(policy(), GestureType.FIST, frames = 30)
        assertEquals(1, actions.count { it == SystemAction.Back })
    }

    @Test
    fun `le pouce leve ouvre l accueil une seule fois`() {
        val actions = hold(policy(), GestureType.THUMB_UP, frames = 30)
        assertEquals(1, actions.count { it == SystemAction.Home })
    }

    @Test
    fun `la main ouverte bascule lecture-pause une seule fois`() {
        val actions = hold(policy(), GestureType.OPEN_PALM, frames = 30)
        assertEquals(1, actions.count { it == SystemAction.PlayPause })
    }

    @Test
    fun `trois doigts ouvrent les applications recentes une seule fois`() {
        val actions = hold(policy(), GestureType.THREE_FINGER, frames = 30)
        assertEquals(1, actions.count { it == SystemAction.Recents })
    }

    /**
     * Deux fronts rapprochés sur la même action — cas d'une pose mal stabilisée — ne doivent pas
     * fermer deux écrans d'affilée.
     */
    @Test
    fun `deux fronts rapproches ne declenchent qu une action`() {
        val p = policy()
        val a = p.onGesture(GestureType.FIST, 0.5f, 0.5f, isNewPose = true, timestampMs = 0)
        p.onGesture(GestureType.POINT, 0.5f, 0.5f, isNewPose = true, timestampMs = 100)
        val b = p.onGesture(GestureType.FIST, 0.5f, 0.5f, isNewPose = true, timestampMs = 200)

        assertEquals(1, a.count { it == SystemAction.Back })
        assertEquals(0, b.count { it == SystemAction.Back })
    }

    @Test
    fun `le temps de garde expire`() {
        val p = policy()
        p.onGesture(GestureType.FIST, 0.5f, 0.5f, isNewPose = true, timestampMs = 0)
        p.onGesture(GestureType.POINT, 0.5f, 0.5f, isNewPose = true, timestampMs = 100)
        val later = p.onGesture(GestureType.FIST, 0.5f, 0.5f, isNewPose = true, timestampMs = 2000)
        assertEquals(1, later.count { it == SystemAction.Back })
    }

    // --- Défilement --------------------------------------------------------------------------

    @Test
    fun `deux doigts font defiler proportionnellement au deplacement`() {
        val p = policy()
        p.onGesture(GestureType.TWO_FINGER, 0.5f, 0.5f, isNewPose = true, timestampMs = 0)
        val actions = p.onGesture(GestureType.TWO_FINGER, 0.5f, 0.7f, isNewPose = false, timestampMs = 33)

        val scroll = actions.filterIsInstance<SystemAction.Scroll>().single()
        // La main descend de 0.2 : le contenu remonte, donc un montant négatif.
        assertTrue("montant=${scroll.amount}", scroll.amount < 0f)
    }

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

    /**
     * Le déplacement effectué pendant la pose précédente ne doit pas être consommé d'un bloc au
     * moment où le défilement démarre : sans réarmement de la référence, lever deux doigts après
     * avoir bougé la main produirait un saut brutal.
     */
    @Test
    fun `changer de pose reamorce la reference de defilement`() {
        val p = policy()
        hold(p, GestureType.POINT, frames = 1, y = 0.1f)
        p.onGesture(GestureType.POINT, 0.5f, 0.9f, isNewPose = false, timestampMs = 33)

        val first = p.onGesture(GestureType.TWO_FINGER, 0.5f, 0.9f, isNewPose = true, timestampMs = 66)
        assertTrue("un saut de défilement a été émis", first.none { it is SystemAction.Scroll })
    }

    // --- Navigation entre applications -------------------------------------------------------

    @Test
    fun `le balayage droite passe a l application suivante`() {
        val actions = policy().onGesture(GestureType.SWIPE_RIGHT, 0.5f, 0.5f, isNewPose = true, timestampMs = 0)
        assertTrue(actions.contains(SystemAction.SwitchNext))
    }

    @Test
    fun `le balayage gauche revient a l application precedente`() {
        val actions = policy().onGesture(GestureType.SWIPE_LEFT, 0.5f, 0.5f, isNewPose = true, timestampMs = 0)
        assertTrue(actions.contains(SystemAction.SwitchPrevious))
    }

    /** Un balayage est ponctuel : il ne doit pas effacer la pose maintenue en dessous. */
    @Test
    fun `un balayage ne remplace pas la pose courante`() {
        val p = policy()
        p.onGesture(GestureType.THREE_FINGER, 0.5f, 0.5f, isNewPose = true, timestampMs = 0)
        p.onGesture(GestureType.SWIPE_RIGHT, 0.5f, 0.5f, isNewPose = true, timestampMs = 33)
        val after = p.onGesture(GestureType.THREE_FINGER, 0.5f, 0.5f, isNewPose = false, timestampMs = 66)
        assertTrue("les récents ont été rouverts", after.none { it == SystemAction.Recents })
    }

    @Test
    fun `la main au repos ne commande rien`() {
        assertTrue(hold(policy(), GestureType.IDLE, frames = 30).isEmpty())
    }
}
