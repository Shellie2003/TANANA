package com.example.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameGateTest {

    private companion object {
        const val FRAME_MS = 33L
    }

    /** Simule [durationMs] de flux caméra et retourne le nombre d'images analysées. */
    private fun run(
        gate: FrameGate,
        durationMs: Long,
        startAt: Long = 0,
        screenOn: Boolean = true,
        powerSave: Boolean = false,
        battery: Int = -1,
        handPresent: Boolean = false
    ): Int {
        var processed = 0
        var t = startAt
        while (t < startAt + durationMs) {
            if (handPresent) gate.onHandDetected(t)
            if (gate.shouldProcess(t, screenOn, powerSave, battery)) processed++
            t += FRAME_MS
        }
        return processed
    }

    @Test
    fun `une main suivie est analysee a pleine cadence`() {
        val gate = FrameGate()
        val processed = run(gate, durationMs = 1000, handPresent = true)
        assertEquals(PowerMode.ACTIVE, gate.mode)
        // 1000 ms à 33 ms par image : toutes les images doivent passer.
        assertEquals(31, processed)
    }

    @Test
    fun `l ecran eteint suspend toute analyse`() {
        val gate = FrameGate()
        assertEquals(0, run(gate, durationMs = 5000, screenOn = false, handPresent = true))
        assertEquals(PowerMode.SUSPENDED, gate.mode)
    }

    @Test
    fun `l absence prolongee de main fait chuter la cadence`() {
        val gate = FrameGate()
        run(gate, durationMs = 500, handPresent = true)
        val active = gate.processedFrames

        // Bien au-delà du seuil de balayage lent.
        val idle = run(gate, durationMs = 10_000, startAt = 20_000)
        assertEquals(PowerMode.IDLE, gate.mode)

        // 10 s de balayage à 400 ms produisent environ 25 analyses, contre 300 à pleine cadence.
        assertTrue("images analysées au repos: $idle", idle in 20..30)
        assertTrue(active > 0)
    }

    @Test
    fun `le regime intermediaire suit la perte recente d une main`() {
        val gate = FrameGate()
        run(gate, durationMs = 500, handPresent = true)
        run(gate, durationMs = 1000, startAt = 3000)
        assertEquals(PowerMode.SEARCHING, gate.mode)
    }

    /**
     * Propriété décisive pour l'utilisabilité : le retour en pleine cadence doit être immédiat.
     * Une reprise progressive ferait manquer le début du geste.
     */
    @Test
    fun `la detection d une main relance immediatement la pleine cadence`() {
        val gate = FrameGate()
        run(gate, durationMs = 30_000)
        assertEquals(PowerMode.IDLE, gate.mode)

        gate.onHandDetected(30_000)
        assertTrue(gate.shouldProcess(30_000, screenOn = true))
        assertEquals(PowerMode.ACTIVE, gate.mode)
    }

    @Test
    fun `le mode economie d energie allonge le balayage au repos`() {
        val normal = FrameGate()
        val saving = FrameGate()

        val processedNormal = run(normal, durationMs = 20_000)
        val processedSaving = run(saving, durationMs = 20_000, powerSave = true)

        assertTrue(
            "normal=$processedNormal économie=$processedSaving",
            processedSaving < processedNormal
        )
    }

    @Test
    fun `une batterie faible allonge le balayage au repos`() {
        val normal = FrameGate()
        val low = FrameGate()

        val processedNormal = run(normal, durationMs = 20_000, battery = 80)
        val processedLow = run(low, durationMs = 20_000, battery = 8)

        assertTrue("normal=$processedNormal faible=$processedLow", processedLow < processedNormal)
    }

    /**
     * L'économie ne doit jamais dégrader le pilotage en cours : une main suivie reste analysée à
     * pleine cadence même en économie d'énergie et batterie basse.
     */
    @Test
    fun `le pilotage en cours n est jamais degrade`() {
        val gate = FrameGate()
        val processed = run(
            gate,
            durationMs = 1000,
            powerSave = true,
            battery = 3,
            handPresent = true
        )
        assertEquals(PowerMode.ACTIVE, gate.mode)
        assertEquals(31, processed)
    }

    @Test
    fun `le gain mesure correspond aux images ignorees`() {
        val gate = FrameGate()
        run(gate, durationMs = 20_000)
        assertTrue("gain=${gate.savingsRatio()}", gate.savingsRatio() > 0.9f)
    }

    @Test
    fun `le compteur est vierge avant toute image`() {
        assertEquals(0f, FrameGate().savingsRatio(), 1e-6f)
    }

    @Test
    fun `la remise a zero repart d un etat neutre`() {
        val gate = FrameGate()
        run(gate, durationMs = 5000, handPresent = true)
        gate.reset()
        assertEquals(0L, gate.processedFrames)
        assertEquals(0L, gate.skippedFrames)
        assertEquals(PowerMode.IDLE, gate.mode)
    }

    @Test
    fun `la premiere image est toujours analysee`() {
        assertTrue(FrameGate().shouldProcess(0, screenOn = true))
    }

    @Test
    fun `l ecran eteint prime sur la presence d une main`() {
        val gate = FrameGate()
        gate.onHandDetected(0)
        assertFalse(gate.shouldProcess(0, screenOn = false))
    }
}
