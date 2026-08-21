package com.example.gesture

import kotlin.math.abs

/** Score d'appartenance d'une pose observée à un modèle de référence. */
data class PoseScore(val pose: GestureType, val score: Float)

/**
 * Classification des poses par score, avec marge de rejet.
 *
 * L'approche précédente enchaînait des seuils : « index tendu ET majeur replié ET… ». Elle a un
 * défaut structurel — dès qu'un doigt se trouve à mi-course, plusieurs poses deviennent également
 * plausibles et la première règle rencontrée l'emporte, arbitrairement. C'est exactement ce qui
 * produit des conflits entre signes voisins, et donc des actions non voulues.
 *
 * Ici, chaque pose reçoit un score de ressemblance à un modèle d'extension des doigts, et la
 * décision n'est prise que si la meilleure hypothèse **devance la deuxième d'une marge suffisante**.
 * Une main ambiguë ne produit plus une pose au hasard : elle ne produit rien, et l'état précédent
 * est conservé. Refuser de décider est ici la bonne décision.
 *
 * Kotlin pur, sans dépendance Android : entièrement testable en JVM.
 */
class PoseScorer(private val config: Config = Config()) {

    data class Config(
        /**
         * Marge minimale entre la meilleure et la deuxième hypothèse. En dessous, la pose est
         * jugée ambiguë et rejetée. C'est le principal levier contre les conflits entre signes.
         */
        val minMargin: Float = 0.12f,
        /** Score minimal absolu : une main qui ne ressemble à aucun modèle ne décide rien. */
        val minScore: Float = 0.62f,
        /** Poids du pouce dans le score. Moins fiable que les doigts longs, donc minoré. */
        val thumbWeight: Float = 0.6f
    )

    /**
     * Modèle d'une pose : extension attendue de chaque doigt long, puis du pouce.
     * `null` signifie « indifférent » : le doigt n'entre pas dans la discrimination.
     */
    private data class Template(
        val pose: GestureType,
        val index: Float,
        val middle: Float,
        val ring: Float,
        val pinky: Float,
        val thumb: Float?
    )

    /**
     * Les modèles sont choisis pour qu'aucune paire ne diffère par moins d'un doigt long entier —
     * à la seule exception de poing / pouce levé, que le pouce sépare explicitement.
     *
     * C'est la raison pour laquelle certaines poses pourtant faciles à faire ont été **écartées** :
     *
     *  - *quatre doigts* (pouce replié) ne se distingue de la main ouverte que par le pouce, le
     *    plus mal suivi des cinq doigts, et par une distinction — pouce rentré ou sorti — que
     *    personne ne fait de façon reproductible ;
     *  - le *pistolet* (index tendu + pouce sorti) obligerait à imposer un pouce replié au
     *    pointage, alors que c'est précisément le doigt qui bouge pour cliquer.
     *
     * Ajouter ces deux poses aurait porté le vocabulaire à onze signes en rendant instables les
     * deux plus utilisés. Neuf signes fiables valent mieux que onze dont deux se disputent.
     */
    private val templates = listOf(
        Template(GestureType.POINT, 1f, 0f, 0f, 0f, null),
        Template(GestureType.TWO_FINGER, 1f, 1f, 0f, 0f, null),
        Template(GestureType.THREE_FINGER, 1f, 1f, 1f, 0f, null),
        Template(GestureType.OPEN_PALM, 1f, 1f, 1f, 1f, 1f),
        Template(GestureType.PINKY_UP, 0f, 0f, 0f, 1f, null),
        Template(GestureType.ROCK, 1f, 0f, 0f, 1f, null),
        // Poing et pouce levé ne diffèrent que par le pouce : c'est donc lui qui doit trancher,
        // et le modèle l'exige explicitement dans les deux cas.
        Template(GestureType.FIST, 0f, 0f, 0f, 0f, 0f),
        Template(GestureType.THUMB_UP, 0f, 0f, 0f, 0f, 1f)
    )

    /** Modèles exposés pour les tests de discriminabilité et l'aide à l'écran. */
    val poses: List<GestureType> get() = templates.map { it.pose }

    /** Extension attendue des quatre doigts longs pour une pose donnée, ou null si inconnue. */
    fun templateOf(pose: GestureType): FloatArray? =
        templates.firstOrNull { it.pose == pose }
            ?.let { floatArrayOf(it.index, it.middle, it.ring, it.pinky) }

    /** Scores de toutes les poses, du plus probable au moins probable. */
    fun scoreAll(metrics: HandMetrics): List<PoseScore> =
        templates
            .map { PoseScore(it.pose, score(it, metrics)) }
            .sortedByDescending { it.score }

    /**
     * Pose retenue, ou null si l'observation est trop ambiguë pour décider.
     *
     * Retourner null est un résultat utile : l'appelant conserve alors la pose précédente au lieu
     * de basculer sur une interprétation douteuse.
     */
    fun classify(metrics: HandMetrics): GestureType? {
        val scores = scoreAll(metrics)
        val best = scores.firstOrNull() ?: return null
        if (best.score < config.minScore) return null

        val second = scores.getOrNull(1)
        if (second != null && best.score - second.score < config.minMargin) return null
        return best.pose
    }

    /** Écart entre la meilleure et la deuxième hypothèse : mesure directe de la netteté du geste. */
    fun margin(metrics: HandMetrics): Float {
        val scores = scoreAll(metrics)
        val best = scores.getOrNull(0)?.score ?: return 0f
        val second = scores.getOrNull(1)?.score ?: return best
        return best - second
    }

    private fun score(template: Template, m: HandMetrics): Float {
        var error = 0f
        var weight = 0f

        error += abs(m.fingerExtension[0] - template.index); weight += 1f
        error += abs(m.fingerExtension[1] - template.middle); weight += 1f
        error += abs(m.fingerExtension[2] - template.ring); weight += 1f
        error += abs(m.fingerExtension[3] - template.pinky); weight += 1f

        template.thumb?.let {
            error += abs(m.thumbExtension - it) * config.thumbWeight
            weight += config.thumbWeight
        }

        return (1f - error / weight).coerceIn(0f, 1f)
    }
}
