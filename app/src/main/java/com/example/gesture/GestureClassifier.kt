package com.example.gesture


/**
 * Vocabulaire gestuel : **trois formes de main, et rien d'autre**.
 *
 * ## Pourquoi trois, et pourquoi celles-ci
 *
 * Le vocabulaire a compté jusqu'à huit poses, puis six, et restait instable. Réduire le nombre ne
 * suffisait pas, parce que le défaut n'était pas la quantité mais le **principe** : distinguer des
 * poses par le nombre de doigts tendus.
 *
 * Deux faits condamnent ce principe. Le mode d'échec dominant du suivi est l'occlusion des doigts
 * par la paume vue de la caméra — or les doigts repliés, qui portent toute l'information d'un
 * décompte, sont précisément ceux qu'on ne voit pas. Et la main est un continuum : passer d'une
 * pose à une autre traverse mécaniquement tous les décomptes intermédiaires, si bien que chaque
 * pose ajoutée crée autant d'occasions d'en verrouiller une autre au passage.
 *
 * Les mesures le montraient sans ambiguïté. Avec six poses, trois d'entre elles — index, poing,
 * pouce levé — se tenaient toutes à 0,200 du seuil de rejet, le pouce levé étant la rivale la plus
 * proche des deux autres. Et ouvrir la main depuis le pointage traversait une bande où la marge
 * tombait à 0,073.
 *
 * Les trois formes retenues sont **maximalement éloignées** : zéro doigt, un doigt, cinq doigts.
 * Aucune paire ne se distingue par le seul pouce, ni par une extension partielle : ce qui les
 * sépare est toujours un doigt long passant de replié à franchement tendu. Et surtout, les
 * décomptes intermédiaires — deux, trois doigts — ne sont plus des poses, donc les traverser ne
 * peut plus rien déclencher. C'est ce qui supprime le conflit à la racine plutôt que de le
 * repousser derrière un seuil.
 *
 * ## Ce qui remplace les poses supprimées
 *
 * Rien n'est perdu, parce que les commandes ne passent plus par des poses. Deux canaux les
 * portent, et tous deux sont indépendants de la forme de la main :
 *
 *  - **le contact du pouce**, orthogonal à la position de l'index : clic, et doigt posé qui suit
 *    la main pour défiler, glisser et balayer ;
 *  - **la direction**, dont la tolérance est angulaire et ne dépend ni de la taille de l'écran ni
 *    de la stabilité de la main. La main ouverte ouvre une roue de huit commandes, lisibles à
 *    l'écran : il n'y a donc plus rien à mémoriser.
 *
 * Les balayages disparaissent avec le « V » qui les portait. Ils demandaient un mouvement ample et
 * rapide, donc indiscernable d'un déplacement de main pressé, et la roue fait le même travail en
 * étant à la fois plus sûre et visible.
 */
enum class GestureType {
    /** Aucune main exploitable. */
    IDLE,

    /** Index seul tendu. Pilote le curseur, et le pouce agit. */
    POINT,

    /** Poing fermé. Retour. */
    FIST,

    /** Main grande ouverte. Ouvre la roue de commandes. */
    OPEN_PALM
}

/** Résultat d'une passe de classification. */
data class GestureResult(
    val gesture: GestureType,
    /** Pose de la frame précédente, pour distinguer maintien et changement. */
    val previousGesture: GestureType = GestureType.IDLE,
    /** Vrai sur la seule frame où la pose change : sert à déclencher les actions ponctuelles. */
    val isNewPose: Boolean = false,
    /** Confiance globale 0..1 (qualité géométrique x stabilité temporelle). */
    val confidence: Float = 0f,
)

/** Seuils du classifieur. Extraits en data class pour être ajustables et testables. */
data class GestureConfig(
    // Nombre de frames consécutives requises pour valider un changement de pose.
    val framesToLatch: Int = 3,
    // En deçà de cette qualité géométrique, aucune action n'est déclenchée.
    val minQuality: Float = 0.35f,
    // Perte de main tolérée avant remise à zéro complète.
    val handLossGraceMs: Long = 250L
)

/**
 * Machine à états de reconnaissance gestuelle.
 *
 * Quatre mécanismes assurent la stabilité, indispensable dès lors que les gestes pilotent le
 * système entier et non plus une seule application :
 *
 *  1. **Hystérésis sur l'extension des doigts** — un doigt à mi-course ne fait plus osciller la
 *     pose entre deux interprétations.
 *  2. **Verrou temporel** — une pose ne bascule qu'après N frames cohérentes. Le coût, environ
 *     100 ms, est imperceptible ; sans lui, les poses intermédiaires traversées en changeant de
 *     geste déclencheraient des actions parasites.
 *  3. **Filtrage par qualité** — les poses géométriquement invraisemblables sont ignorées.
 *
 * La pose elle-même vient du classifieur entraîné de MediaPipe quand il se prononce, et du score
 * géométrique sinon. Ces mécanismes s'appliquent à la sortie de l'un comme de l'autre : le
 * classifieur donne un avis par image, pas une décision stable, et c'est bien le verrou temporel
 * qui rend l'ensemble utilisable.
 *
 * Kotlin pur, sans dépendance Android : entièrement testable en JVM.
 */
class GestureClassifier(private val config: GestureConfig = GestureConfig()) {

    private val scorer = PoseScorer()

    private var latchedPose = GestureType.IDLE
    private var candidatePose = GestureType.IDLE
    private var candidateFrames = 0

    /** Netteté du geste courant : écart entre la meilleure et la deuxième hypothèse. */
    var poseMargin: Float = 0f
        private set

    private var lastSeenTime = Long.MIN_VALUE

    fun reset() {
        latchedPose = GestureType.IDLE
        candidatePose = GestureType.IDLE
        candidateFrames = 0
    }

    /**
     * Classe une frame. [metrics] à null signifie « aucune main détectée ».
     * [timestampMs] doit être monotone croissant.
     *
     * @param recognized pose donnée par le classifieur entraîné, ou null s'il s'est abstenu. Quand
     *   elle est fournie, elle l'emporte sur le score géométrique — mais passe par le même verrou
     *   temporel, qui reste ce qui empêche une pose traversée d'être verrouillée.
     */
    fun update(
        metrics: HandMetrics?,
        timestampMs: Long,
        recognized: GestureType? = null
    ): GestureResult {
        val previous = latchedPose

        if (metrics == null || metrics.quality < config.minQuality) {
            // Courte tolérance : un trou d'une ou deux frames ne doit pas casser un geste en cours.
            if (lastSeenTime != Long.MIN_VALUE &&
                timestampMs - lastSeenTime <= config.handLossGraceMs
            ) {
                return GestureResult(
                    gesture = latchedPose,
                    previousGesture = previous,
                    confidence = 0.3f
                )
            }
            reset()
            lastSeenTime = Long.MIN_VALUE
            return GestureResult(GestureType.IDLE, previous, isNewPose = previous != GestureType.IDLE)
        }

        lastSeenTime = timestampMs

        val rawPose = recognized ?: classifyPose(metrics)
        if (recognized != null) poseMargin = scorer.margin(metrics)

        // Verrou temporel : la pose ne change qu'après N frames cohérentes.
        if (rawPose == candidatePose) {
            candidateFrames++
        } else {
            candidatePose = rawPose
            candidateFrames = 1
        }
        val poseChanged = candidateFrames >= config.framesToLatch && latchedPose != candidatePose
        if (poseChanged) latchedPose = candidatePose

        // La confiance chute pendant une transition de pose : une action déclenchée à cet instant
        // serait probablement une pose intermédiaire mal interprétée.
        val stability = if (candidateFrames >= config.framesToLatch) 1f else 0.6f

        return GestureResult(
            gesture = latchedPose,
            previousGesture = previous,
            isNewPose = poseChanged,
            confidence = (metrics.quality * stability).coerceIn(0f, 1f)
        )
    }

    /**
     * Détermine la pose brute d'une frame.
     *
     * **Le contact pouce-index ne produit plus de pose.** Il en produisait une — PINCH — et
     * c'était un conflit direct avec le clic, qui est ce même contact : fermer le pouce pour
     * cliquer faisait basculer la pose, donc changer de rôle en pleine action. Selon que le
     * contact durait plus ou moins que le verrou temporel de trois images, le même geste partait
     * en clic ou en glisser. Le contact est désormais lu uniquement par le détecteur de clic, à
     * partir de la distance pouce-index, indépendamment de la pose.
     *
     * Les poses sont donc toutes départagées par score avec marge de rejet : une main ambiguë
     * conserve la pose courante au lieu d'en inventer une.
     */
    private fun classifyPose(m: HandMetrics): GestureType {
        poseMargin = scorer.margin(m)

        // null = observation ambiguë. On conserve la pose courante : c'est précisément ce
        // refus de trancher qui supprime les conflits entre signes voisins.
        return scorer.classify(m) ?: latchedPose
    }
}
