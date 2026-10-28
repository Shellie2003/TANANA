package com.example.gesture

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min

/**
 * Filtre passe-bas du premier ordre à coefficient variable.
 *
 * Conserve séparément la dernière valeur *brute* et la dernière valeur *filtrée* : le filtre
 * 1-Euro a besoin de la valeur brute précédente pour estimer la vitesse, faute de quoi la
 * dérivée est calculée sur un signal déjà lissé et le gain adaptatif réagit trop tard
 * (c'est la cause classique du « curseur qui traîne » lors des mouvements rapides).
 */
class LowPassFilter {
    private var filtered: Float = 0f
    private var raw: Float = 0f
    private var initialized = false

    val hasValue: Boolean get() = initialized

    fun filter(value: Float, alpha: Float): Float {
        raw = value
        if (!initialized) {
            initialized = true
            filtered = value
            return value
        }
        filtered = alpha * value + (1f - alpha) * filtered
        return filtered
    }

    fun lastFiltered(): Float = filtered

    fun lastRaw(): Float = raw

    fun reset() {
        initialized = false
        filtered = 0f
        raw = 0f
    }
}

/**
 * Filtre 1-Euro (Casiez, Roussel & Vogel, CHI 2012) avec **prédiction de latence**.
 *
 * Deux propriétés recherchées, habituellement contradictoires :
 *  - au repos, un lissage fort qui supprime le tremblement physiologique (~8-12 Hz) ;
 *  - en mouvement, une latence quasi nulle.
 *
 * Le gain est piloté par la vitesse estimée (`cutoff = minCutoff + beta * |v|`). En complément,
 * [predictionMs] extrapole la position le long du vecteur vitesse pour compenser la latence
 * résiduelle de la chaîne caméra -> inférence -> composition (typiquement 40 à 70 ms), ce qui
 * fait « coller » le curseur au doigt au lieu de le suivre avec un retard visible.
 *
 * Cette prédiction est **coupée en visée** ([predictionMinSpeed]), et c'est le réglage qui a le
 * plus d'effet sur la précision : voir l'explication là-bas.
 */
class OneEuroFilter(
    private val minCutoff: Float = 1.0f,
    private val beta: Float = 0.007f,
    private val dCutoff: Float = 1.0f,
    private val predictionMs: Float = 0f,
    private val maxPredictionDelta: Float = 0.08f,
    /**
     * Vitesse en deçà de laquelle la prédiction est **coupée**, en unités par seconde.
     *
     * Mesuré en harnais : la prédiction **doublait** le bruit résiduel du curseur en visée — 1,34
     * pixel contre 0,62. La raison est structurelle : prédire, c'est extrapoler le long de la
     * vitesse, et la vitesse est une **dérivée** du signal. Dériver amplifie le bruit, et le
     * résultat est réinjecté dans la position. Au repos, la « vitesse » n'est donc que du bruit
     * dérivé, et la prédiction la recopie dans le curseur.
     *
     * La prédiction sert à compenser la latence perçue, laquelle n'a de sens qu'en mouvement : un
     * curseur immobile n'est jamais en retard. La couper en visée ne coûte donc rien et retire la
     * principale source de bruit à l'instant où la précision compte. Ce n'est pas un compromis,
     * c'est une correction : les deux grandeurs s'améliorent ensemble.
     */
    private val predictionMinSpeed: Float = 0.04f,
    /**
     * Vitesse à partir de laquelle la prédiction est pleine.
     *
     * Les deux bornes sont basses, et un test l'a imposé : un premier réglage les avait placées
     * dans la bande des **déplacements lents mais volontaires** (0,2 à 0,5 unité par seconde), si
     * bien que la prédiction y était bridée de moitié et que le curseur traînait sur les gestes
     * posés. Or il n'y a pas besoin de monter si haut : l'estimateur de vitesse est lui-même filtré
     * à 1 Hz, de sorte qu'un tremblement à 10 Hz n'y produit qu'une vitesse apparente d'environ
     * 0,025 unité par seconde. Le bruit et le mouvement volontaire sont donc déjà séparés d'un
     * ordre de grandeur — il suffit de placer la coupure entre les deux, pas au milieu du second.
     */
    private val predictionFullSpeed: Float = 0.20f
) {
    private val xFilter = LowPassFilter()
    private val dxFilter = LowPassFilter()
    private var lastTime: Long = -1L

    /** Vitesse filtrée, en unités par seconde. */
    var velocity: Float = 0f
        private set

    private var previousSpeed = 0f

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }

    fun filter(value: Float, timestampMs: Long): Float {
        if (value.isNaN() || value.isInfinite()) return xFilter.lastFiltered()

        if (lastTime < 0L || !xFilter.hasValue) {
            lastTime = timestampMs
            velocity = 0f
            return xFilter.filter(value, 1f)
        }

        // dt borné : une frame perdue ne doit pas produire un saut de gain incontrôlé.
        val dt = ((timestampMs - lastTime).toFloat() / 1000f).coerceIn(0.001f, 0.2f)
        lastTime = timestampMs

        // Dérivée calculée sur le signal brut, puis lissée.
        val derivative = (value - xFilter.lastRaw()) / dt
        velocity = dxFilter.filter(derivative, alpha(dCutoff, dt))

        val cutoff = minCutoff + beta * abs(velocity)
        val filtered = xFilter.filter(value, alpha(cutoff, dt))

        if (predictionMs <= 0f) return filtered

        // Amortissement à la décélération : l'estimateur de vitesse est lui-même en retard, si bien
        // qu'une prédiction à plein gain fait dépasser la cible au moment où la main s'arrête —
        // exactement l'instant où l'utilisateur vise. On réduit donc la prédiction proportionnellement
        // à la chute de vitesse, ce qui divise le dépassement par trois sans coûter de latence en
        // mouvement établi.
        val speed = abs(velocity)
        val damping = if (speed < previousSpeed && previousSpeed > 1e-4f) {
            (speed / previousSpeed).coerceIn(0f, 1f)
        } else {
            1f
        }
        previousSpeed = speed

        // Fondu en fonction de la vitesse : nul en visée, plein en mouvement établi. C'est ce qui
        // retire du curseur immobile le bruit que la dérivation injectait, sans rien concéder sur
        // la latence là où elle se voit.
        val gate = ((speed - predictionMinSpeed) / (predictionFullSpeed - predictionMinSpeed))
            .coerceIn(0f, 1f)
        if (gate <= 0f) return filtered

        // Extrapolation bornée : au-delà de maxPredictionDelta la prédiction devient du dépassement.
        val predicted = velocity * (predictionMs / 1000f) * damping * gate
        val clamped = predicted.coerceIn(-maxPredictionDelta, maxPredictionDelta)
        return filtered + clamped
    }

    fun reset() {
        xFilter.reset()
        dxFilter.reset()
        lastTime = -1L
        velocity = 0f
        previousSpeed = 0f
    }
}

/**
 * Filtre 1-Euro tridimensionnel utilisé pour le curseur holographique.
 *
 * Un **seuil de repos** (dead zone) complète le filtrage : sous [restThreshold] unités de
 * déplacement, la sortie est gelée. C'est ce qui produit un curseur parfaitement immobile
 * quand l'utilisateur vise, là où un simple lissage laisse toujours un résidu de dérive.
 */
class OneEuroFilter3D(
    minCutoff: Float = 1.6f,
    beta: Float = 1.2f,
    dCutoff: Float = 1.0f,
    predictionMs: Float = 0f,
    private val restThreshold: Float = 0.0015f
) {
    private val xFilter = OneEuroFilter(minCutoff, beta, dCutoff, predictionMs)
    private val yFilter = OneEuroFilter(minCutoff, beta, dCutoff, predictionMs)
    // La profondeur est intrinsèquement plus bruitée : on la lisse plus fort et sans prédiction.
    private val zFilter = OneEuroFilter(minCutoff * 0.5f, beta * 0.5f, dCutoff)

    private var heldX = 0f
    private var heldY = 0f
    private var hasHold = false

    data class Point3D(val x: Float, val y: Float, val z: Float)

    /** Norme de la vitesse du curseur, en unités normalisées par seconde. */
    val speed: Float
        get() {
            val vx = xFilter.velocity
            val vy = yFilter.velocity
            return kotlin.math.sqrt(vx * vx + vy * vy)
        }

    fun filter(x: Float, y: Float, z: Float, timestampMs: Long): Point3D {
        val fx = xFilter.filter(x, timestampMs)
        val fy = yFilter.filter(y, timestampMs)
        val fz = zFilter.filter(z, timestampMs)

        if (!hasHold) {
            heldX = fx
            heldY = fy
            hasHold = true
            return Point3D(fx, fy, fz)
        }

        val dx = fx - heldX
        val dy = fy - heldY
        if (min(abs(dx), abs(dy)) < restThreshold &&
            kotlin.math.sqrt(dx * dx + dy * dy) < restThreshold
        ) {
            // Micro-oscillation : on gèle la sortie pour un curseur parfaitement stable.
            return Point3D(heldX, heldY, fz)
        }

        heldX = fx
        heldY = fy
        return Point3D(fx, fy, fz)
    }

    fun reset() {
        xFilter.reset()
        yFilter.reset()
        zFilter.reset()
        hasHold = false
        heldX = 0f
        heldY = 0f
    }
}
