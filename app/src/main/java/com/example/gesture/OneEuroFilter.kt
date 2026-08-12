package com.example.gesture

import kotlin.math.PI
import kotlin.math.abs

/**
 * Filtre 1-Euro (One-Euro Filter)
 * Algorithme mathématique de lissage adaptatif à faible latence et réduction des tremblements.
 * Utilisé pour stabiliser le curseur holographique en temps réel.
 */
class LowPassFilter(private var alpha: Float = 1.0f) {
    private var y: Float = 0.0f
    private var s: Float = 0.0f
    private var initialized = false

    fun filter(value: Float, alpha: Float): Float {
        this.alpha = alpha
        return if (initialized) {
            val result = alpha * value + (1.0f - alpha) * s
            s = result
            result
        } else {
            initialized = true
            s = value
            value
        }
    }

    fun lastValue(): Float = s

    fun reset() {
        initialized = false
        s = 0.0f
    }
}

class OneEuroFilter(
    private val minCutoff: Float = 1.0f,
    private val beta: Float = 0.007f,
    private val dCutoff: Float = 1.0f
) {
    private var xFilter = LowPassFilter()
    private var dxFilter = LowPassFilter()
    private var lastTime: Long = -1L

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1.0f / (2.0f * PI.toFloat() * cutoff)
        return 1.0f / (1.0f + tau / dt)
    }

    fun filter(value: Float, timestampMs: Long): Float {
        if (lastTime < 0L) {
            lastTime = timestampMs
            return xFilter.filter(value, 1.0f)
        }

        val dt = ((timestampMs - lastTime).toFloat() / 1000.0f).coerceAtLeast(0.001f)
        lastTime = timestampMs

        // Estimation de la vitesse
        val dValue = (value - xFilter.lastValue()) / dt
        val edValue = dxFilter.filter(dValue, alpha(dCutoff, dt))

        // Cutoff dynamique basé sur la vitesse (réduit le délai lors des mouvements rapides)
        val cutoff = minCutoff + beta * abs(edValue)

        return xFilter.filter(value, alpha(cutoff, dt))
    }

    fun reset() {
        xFilter.reset()
        dxFilter.reset()
        lastTime = -1L
    }
}

/**
 * Filtre 1-Euro Tri-Dimensionnel (X, Y, Z)
 */
class OneEuroFilter3D(
    minCutoff: Float = 1.2f,
    beta: Float = 0.008f,
    dCutoff: Float = 1.0f
) {
    private val xFilter = OneEuroFilter(minCutoff, beta, dCutoff)
    private val yFilter = OneEuroFilter(minCutoff, beta, dCutoff)
    private val zFilter = OneEuroFilter(minCutoff, beta, dCutoff)

    data class Point3D(val x: Float, val y: Float, val z: Float)

    fun filter(x: Float, y: Float, z: Float, timestampMs: Long): Point3D {
        val filteredX = xFilter.filter(x, timestampMs)
        val filteredY = yFilter.filter(y, timestampMs)
        val filteredZ = zFilter.filter(z, timestampMs)
        return Point3D(filteredX, filteredY, filteredZ)
    }

    fun reset() {
        xFilter.reset()
        yFilter.reset()
        zFilter.reset()
    }
}
