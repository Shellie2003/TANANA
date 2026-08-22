package com.example.gesture

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Détection du **coup sec directionnel** de l'index, qui fait défiler.
 *
 * ## Le problème que ce détecteur résout
 *
 * Le défilement demandait deux doigts. C'était un geste de plus à former, et surtout un geste
 * différent de celui du pointage : il fallait changer de main pour parcourir une page, puis en
 * rechanger pour désigner quelque chose. Faire défiler avec le seul index supprime cette bascule.
 *
 * Mais l'index tenait déjà le curseur, et une pose ne peut pas porter deux rôles continus : si le
 * déplacement de l'index faisait défiler, il ne pourrait plus viser. **La distinction ne peut donc
 * pas être une pose, elle doit être une manière de bouger** — même raisonnement que pour le clic,
 * et pour la même raison : deux intentions partagent une configuration de main, seule leur
 * dynamique les sépare.
 *
 * ## Ce qui distingue réellement les deux, et ce qui ne suffit pas
 *
 * Le réflexe est de mesurer une distance et une vitesse. C'est insuffisant, et de façon
 * intéressante : une traversée d'écran au curseur, même menée posément, contient toujours un
 * sous-segment qui satisfait n'importe quel couple « assez loin, assez vite ». Un critère
 * instantané détecte donc un coup sec au milieu d'un simple déplacement de visée.
 *
 * Ce qui sépare vraiment les deux gestes est ailleurs : **un coup sec s'arrête**. C'est une
 * impulsion bornée — la main part, va, et se pose. Une visée, elle, se poursuit jusqu'à la cible.
 * Le geste n'est donc jamais reconnu pendant le mouvement, mais à sa décélération, et seulement
 * si tout le trajet parcouru depuis le départ tient dans la durée d'un coup de poignet.
 *
 * Cela coûte une image ou deux de latence — le temps de constater l'arrêt. C'est le prix de la
 * cohabitation avec le curseur, et il est payé sur un geste dont le résultat, contrairement au
 * clic, n'a pas besoin d'être instantané.
 *
 * Kotlin pur, sans dépendance Android : entièrement testable en JVM.
 */
class FlickDetector(private val config: Config = Config()) {

    data class Config(
        /** Vitesse, en fractions d'écran par seconde, à partir de laquelle un geste est engagé. */
        val startSpeed: Float = 0.9f,

        /** Vitesse en deçà de laquelle la main est considérée arrêtée. */
        val stopSpeed: Float = 0.35f,

        /** Distance minimale parcourue, en fraction d'écran. En deçà, c'est du repositionnement. */
        val minDistance: Float = 0.18f,

        /**
         * Durée maximale du geste complet. Au-delà, ce n'est plus un coup de poignet mais un
         * déplacement conduit : le geste est abandonné, et il faut que la main s'arrête avant
         * qu'un nouveau puisse être engagé.
         */
        val maxDurationMs: Long = 320L,

        /** Rapport maximal entre l'axe secondaire et l'axe principal. */
        val maxCrossRatio: Float = 0.5f,

        /** Part des pas devant aller dans le sens du geste. */
        val minCoherence: Float = 0.8f,

        /** Délai avant qu'un nouveau coup sec puisse être reconnu. */
        val cooldownMs: Long = 260L
    )

    /** Un coup sec reconnu. */
    data class Flick(
        val horizontal: Boolean,
        /**
         * Déplacement signé sur l'axe retenu, en fraction d'écran. Positif = vers la droite ou
         * vers le bas, dans le repère de l'écran.
         */
        val delta: Float
    )

    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var lastAt = Long.MIN_VALUE

    private var active = false
    private var startX = 0f
    private var startY = 0f
    private var startAt = 0L
    private val pathX = ArrayList<Float>()
    private val pathY = ArrayList<Float>()

    /**
     * Vrai après un geste abandonné pour cause de durée : empêche un long déplacement d'être
     * redécoupé en plusieurs coups secs. Levé dès que la main s'arrête.
     */
    private var blocked = false

    private var lastFlickAt = Long.MIN_VALUE

    fun reset() {
        active = false
        blocked = false
        pathX.clear()
        pathY.clear()
        lastX = Float.NaN
        lastY = Float.NaN
        lastAt = Long.MIN_VALUE
    }

    /**
     * Alimente le détecteur d'une position de curseur, et retourne un coup sec s'il vient de se
     * terminer.
     */
    fun update(x: Float, y: Float, nowMs: Long): Flick? {
        val previousX = lastX
        val previousY = lastY
        val previousAt = lastAt
        lastX = x
        lastY = y
        lastAt = nowMs

        if (previousAt == Long.MIN_VALUE) return null
        val dtMs = nowMs - previousAt
        if (dtMs <= 0) return null

        val speed = hypot(x - previousX, y - previousY) / (dtMs / 1000f)
        val stopped = speed <= config.stopSpeed

        if (stopped) blocked = false

        if (!active) {
            if (!blocked && speed >= config.startSpeed) {
                // Le geste part du point *précédent* : c'est de là que la main s'est élancée.
                active = true
                startX = previousX
                startY = previousY
                startAt = previousAt
                pathX.clear(); pathY.clear()
                pathX.add(previousX); pathY.add(previousY)
                pathX.add(x); pathY.add(y)
            }
            return null
        }

        pathX.add(x)
        pathY.add(y)

        if (nowMs - startAt > config.maxDurationMs) {
            // Trop long pour un coup de poignet : c'est un déplacement conduit. On abandonne, et
            // on refuse d'en réengager un tant que la main n'est pas revenue au repos — sans quoi
            // une longue traversée serait découpée en une série de coups secs.
            active = false
            blocked = true
            return null
        }

        if (!stopped) return null

        active = false
        if (lastFlickAt != Long.MIN_VALUE && nowMs - lastFlickAt < config.cooldownMs) return null

        val dx = x - startX
        val dy = y - startY
        val horizontal = abs(dx) >= abs(dy)
        val main = if (horizontal) dx else dy
        val cross = if (horizontal) dy else dx

        if (abs(main) < config.minDistance) return null
        if (abs(cross) > abs(main) * config.maxCrossRatio) return null

        val samples = if (horizontal) pathX else pathY
        val sign = if (main > 0) 1f else -1f
        var coherent = 0
        for (i in 1 until samples.size) {
            if ((samples[i] - samples[i - 1]) * sign >= -0.002f) coherent++
        }
        val steps = samples.size - 1
        if (steps <= 0 || coherent.toFloat() / steps < config.minCoherence) return null

        lastFlickAt = nowMs
        return Flick(horizontal = horizontal, delta = main)
    }
}
