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
        /**
         * Poids du pouce dans le score.
         *
         * Il était minoré — le pouce est le doigt le moins bien suivi — mais c'était un mauvais
         * calcul : le pouce est aussi le **seul** discriminant entre le poing et le pouce levé,
         * et le minorer ramenait la marge de cette paire à 0,130, à peine au-dessus du seuil de
         * rejet, sur une main pourtant parfaite. Sur une main réelle, cette paire oscillait donc
         * en permanence. Relevé à 1, il porte cette marge à 0,200.
         */
        val thumbWeight: Float = 1.0f,

        /**
         * Part du **doigt le plus mal placé** dans le score, le reste venant de l'erreur moyenne.
         *
         * Ce terme a été ajouté après une mesure qui contredisait l'intuition. Réduire le
         * vocabulaire à trois modèles **affaiblissait** le rejet au lieu de le renforcer : la marge
         * ne départage que les modèles entre eux, et moins il y a de rivaux, plus la meilleure
         * hypothèse gagne confortablement, même quand elle ne ressemble à rien. Deux doigts tendus
         * étaient ainsi classés « index pointé » avec une marge de 0,150 et un score de 0,750, parce
         * que l'erreur moyennée sur cinq doigts diluait un doigt totalement faux en un écart de 0,2.
         *
         * Compter le pire doigt empêche cette dilution : une main dont un seul doigt contredit
         * franchement le modèle n'est plus « assez proche », quelle que soit la justesse des autres.
         * C'est ce qui rend vraie la propriété dont dépend toute la refonte — les décomptes
         * intermédiaires ne correspondent à aucune pose, donc les traverser ne déclenche rien.
         */
        val worstFingerWeight: Float = 0.5f
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
     * Trois modèles, choisis pour être **maximalement éloignés** : zéro doigt, un doigt, cinq
     * doigts. La propriété qui compte n'est pas leur nombre mais la nature de ce qui les sépare —
     * chaque paire diffère par au moins un **doigt long** passant de replié à franchement tendu,
     * jamais par le seul pouce ni par une extension partielle.
     *
     * Les poses écartées l'ont été pour des raisons mesurées, pas par goût de la sobriété :
     *
     *  - le *pouce levé* ne diffère du poing que par le pouce, le plus mal suivi des cinq doigts,
     *    et il était la rivale la plus proche à la fois du poing **et** de l'index pointé — trois
     *    poses se disputaient donc le même voisinage, toutes à 0,200 du seuil de rejet ;
     *  - le *pouce baissé* ajoutait une troisième lecture de cette même forme de main ;
     *  - le *« V »* ne diffère de l'index pointé que par le majeur, replié donc occulté dans le
     *    pointage ;
     *  - *trois doigts* demandait de replier l'annulaire indépendamment, ce que le couplage des
     *    tendons fléchisseurs interdit à la plupart des mains ;
     *  - *quatre doigts*, l'*auriculaire seul* et les *cornes* reposaient sur un contrôle isolé
     *    des doigts les plus petits et les plus mal suivis.
     *
     * Conséquence la plus importante : les décomptes intermédiaires — deux, trois, quatre doigts —
     * ne correspondent plus à aucun modèle. Les traverser en changeant de forme ne peut donc plus
     * verrouiller quoi que ce soit, là où chaque pose supplémentaire créait autant de pièges.
     */
    private val templates = listOf(
        Template(GestureType.POINT, 1f, 0f, 0f, 0f, null),
        Template(GestureType.OPEN_PALM, 1f, 1f, 1f, 1f, 1f),
        Template(GestureType.FIST, 0f, 0f, 0f, 0f, 0f)
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

    /**
     * Score d'une pose : combinaison de l'erreur moyenne et de celle du doigt le plus mal placé.
     *
     * Les deux termes ne mesurent pas la même chose, et c'est pourquoi il en faut deux. L'erreur
     * moyenne dit si la main ressemble globalement au modèle ; l'erreur maximale dit si **un** doigt
     * le contredit. Avec la moyenne seule, un doigt totalement faux ne coûtait qu'un cinquième du
     * score, de sorte qu'une main à deux doigts passait pour un index pointé.
     */
    private fun score(template: Template, m: HandMetrics): Float {
        var error = 0f
        var weight = 0f
        var worst = 0f

        fun add(observed: Float, expected: Float, fingerWeight: Float) {
            val e = abs(observed - expected)
            error += e * fingerWeight
            weight += fingerWeight
            if (e > worst) worst = e
        }

        add(m.fingerExtension[0], template.index, 1f)
        add(m.fingerExtension[1], template.middle, 1f)
        add(m.fingerExtension[2], template.ring, 1f)
        add(m.fingerExtension[3], template.pinky, 1f)
        template.thumb?.let { add(m.thumbExtension, it, config.thumbWeight) }

        val mean = error / weight
        val combined = (1f - config.worstFingerWeight) * mean + config.worstFingerWeight * worst
        return (1f - combined).coerceIn(0f, 1f)
    }
}
