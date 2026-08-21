package com.example.gesture

/**
 * Détection du clic par **contact pouce-index**, sur ligne de base adaptative.
 *
 * ## Pourquoi ce signe, et pas un autre
 *
 * Le choix du geste de validation a été repris à zéro. Un bon clic doit satisfaire cinq
 * conditions simultanément, et une seule famille de gestes les satisfait toutes :
 *
 *  1. **Ne pas déplacer la cible.** Le curseur suit le bout de l'index ; le geste de validation
 *     ne doit donc mobiliser que d'autres doigts. Cela élimine la flexion de l'index, le
 *     « clic gâchette » et la poussée de la main vers la caméra, qui déplacent tous le point visé
 *     au moment précis où il faut le figer.
 *  2. **Ne pas exister au repos.** La position naturelle de la main qui pointe ne doit produire
 *     aucun clic. Cela élimine le pouce posé sur le majeur replié — c'est exactement là qu'il
 *     tombe quand on pointe — et tout critère fondé sur une simple proximité.
 *  3. **Se confirmer sans regarder l'écran.** Le pouce et l'index se touchent : le doigt *sent*
 *     le clic partir. C'est la seule modalité qui donne un retour proprioceptif, et c'est la
 *     raison pour laquelle tous les systèmes de réalité mixte ont convergé vers elle. Un geste
 *     en l'air, lui, n'a pas de fin perceptible : l'utilisateur ne sait jamais s'il a cliqué.
 *  4. **Se terminer d'elle-même.** Le contact physique borne la course : le geste s'arrête sur
 *     une butée anatomique, pas sur un seuil arbitraire. La répétabilité vient de là.
 *  5. **Ne pas fatiguer.** L'opposition pouce-index est le mouvement le plus économique de la
 *     main ; il est soutenable des centaines de fois par heure.
 *
 * Le signe retenu est donc bien le contact pouce-index. **Ce qui change radicalement, c'est la
 * façon de le mesurer.**
 *
 * ## Ce qui n'allait pas, et ce qui le remplace
 *
 * La version précédente comparait la distance pouce-index à des **seuils absolus**. C'était le
 * défaut de fond : la distance au repos dépend de la morphologie, de l'habitude et de
 * l'orientation de la main. Un même seuil est trop lâche pour l'un — clics fantômes — et hors
 * d'atteinte pour l'autre — clics qui ne partent pas. Aucun réglage global ne peut convenir aux
 * deux.
 *
 * Ici, tout est **relatif à la course propre de l'utilisateur**, estimée en continu :
 *
 *  - **Ligne de base adaptative** ([baseline]) — un suiveur de crête asymétrique apprend l'écart
 *    pouce-index au repos. Il monte vite, ne descend que lentement, et **se fige pendant le
 *    contact** : un clic maintenu ne peut donc pas éroder sa propre référence.
 *  - **Seuil de contact proportionnel** — une fraction de cette course, et non une constante.
 *  - **Amplitude relative** — le geste doit parcourir une fraction significative de la course
 *    *habituelle de cette main*, ce qui rejette le tremblement quelle que soit sa taille.
 *  - **Fenêtre de fermeture mesurée depuis la bande ouverte** — également relative, alors que la
 *    version précédente mesurait depuis un maximum glissant dont la validité expirait.
 *  - **Relâchement anticipé** — le clic part dès que le pouce est reparti d'une fraction du
 *    chemin, sans attendre le retour à l'ouverture complète. C'est plusieurs dizaines de
 *    millisecondes gagnées à chaque clic, sans rien concéder à la fiabilité.
 *
 * Chaque rejet est enregistré avec sa cause ([lastRejection]) : l'utilisateur peut voir *pourquoi*
 * un clic n'est pas parti, au lieu de constater qu'il ne se passe rien.
 *
 * Kotlin pur, sans dépendance Android : entièrement testable en JVM.
 */
class ClickDetector(private val config: Config = Config()) {

    data class Config(
        /**
         * Fraction de la ligne de base sous laquelle le contact est déclaré. Bornée : une course
         * de référence aberrante ne doit pas rendre le clic impossible ni trivial.
         */
        val contactFraction: Float = 0.34f,
        val contactMin: Float = 0.12f,
        val contactMax: Float = 0.34f,

        /**
         * Bande haute : au-dessus de cette fraction de la ligne de base, la main est considérée
         * franchement ouverte. C'est de là qu'est chronométrée la fermeture.
         */
        val openBandFraction: Float = 0.72f,

        /**
         * Progression de fermeture au-delà de laquelle une tentative avortée est comptée comme un
         * quasi-contact.
         *
         * Il n'y a délibérément **pas** de critère d'amplitude minimale. La version précédente en
         * imposait un, en plus du seuil de contact ; il était mathématiquement inatteignable. Le
         * seuil de contact étant lui-même une fraction de la course de référence, franchir ce seuil
         * garantit déjà d'avoir parcouru les deux tiers de cette course — toujours davantage que
         * n'importe quelle amplitude minimale exprimée dans la même unité. Le critère ne pouvait
         * donc jamais rejeter quoi que ce soit, et laissait croire à une protection qui n'existait
         * pas. Ce qui manque réellement à l'utilisateur, c'est de savoir qu'il **approche sans
         * toucher** : c'est ce que mesure ce seuil-ci.
         */
        val nearMissProgress: Float = 0.55f,

        /**
         * Durée maximale de la fermeture, mesurée depuis la sortie de la bande ouverte. Une
         * impulsion volontaire dure environ 100 ms ; un repositionnement de la main, plusieurs
         * centaines. C'est le critère qui sépare l'intention du mouvement parasite.
         */
        val maxCloseMs: Long = 240L,

        /**
         * Fraction du chemin de retour au-delà de laquelle le relâchement est acquis. À 0,5 le
         * clic part au milieu de la remontée, soit typiquement 30 ms plus tôt qu'en attendant
         * l'ouverture complète.
         */
        val releaseFraction: Float = 0.50f,

        /** Au-delà de cette durée de contact, le geste devient un maintien (glisser, appui long). */
        val holdMs: Long = 350L,

        /** Intervalle minimal entre deux clics, pour absorber les rebonds du suivi. */
        val cooldownMs: Long = 120L,

        /** Vitesses de suivi de la ligne de base : montée franche, descente très lente. */
        val baselineRise: Float = 0.10f,
        val baselineFall: Float = 0.012f,
        val baselineMin: Float = 0.35f,
        val baselineMax: Float = 2.0f,

        /** Nombre d'images d'observation avant que la ligne de base soit jugée fiable. */
        val calibrationFrames: Int = 12
    )

    /** Ce que la frame courante produit. */
    enum class Event { NONE, CLICK, HOLD_START, HOLD_END }

    /** Cause du dernier geste rejeté. Affichée à l'utilisateur pour rendre le réglage lisible. */
    enum class Rejection {
        /** Aucun rejet depuis la dernière remise à zéro. */
        NONE,

        /**
         * Le pouce s'est nettement approché puis est reparti sans toucher l'index. C'est le
         * défaut le plus fréquent chez qui débute, et le seul que l'utilisateur ne peut pas
         * diagnostiquer seul : de son point de vue, il a cliqué.
         */
        TOO_SHALLOW,

        /** La fermeture a pris trop de temps : repositionnement de la main, pas une impulsion. */
        TOO_SLOW,

        /** Deux clics trop rapprochés : le second est un rebond du suivi. */
        COOLDOWN
    }

    // --- État de la ligne de base -----------------------------------------------------------

    /** Écart pouce-index au repos, appris en continu. */
    var baseline: Float = Float.NaN
        private set

    private var observedFrames = 0

    /** Vrai quand la ligne de base a été observée assez longtemps pour être fiable. */
    val isCalibrated: Boolean get() = observedFrames >= config.calibrationFrames

    /** Seuil de contact courant, dérivé de la ligne de base. */
    val contactThreshold: Float
        get() = if (baseline.isNaN()) config.contactMax
        else (baseline * config.contactFraction).coerceIn(config.contactMin, config.contactMax)

    // --- État du geste en cours ---------------------------------------------------------------

    private var inContact = false

    /**
     * Vrai quand la fermeture en cours a déjà été jugée. Sans ce verrou, une main simplement
     * posée sous le seuil de contact serait réévaluée — et rejetée — à chaque image, ce qui
     * noierait le compteur de rejets et masquerait les vraies causes.
     */
    private var closureJudged = false

    /** Une descente est en cours et n'a pas encore abouti. */
    private var attemptPending = false

    /** Progression maximale atteinte par la descente en cours, pour détecter les quasi-contacts. */
    private var peakProgress = 0f
    private var contactAt = 0L
    private var contactBaseline = 0f
    private var contactMin = 0f
    private var holding = false
    private var lastClickAt = Long.MIN_VALUE
    private var lastOpenAt = Long.MIN_VALUE

    /** Diagnostic du dernier geste mesuré. */
    var lastAmplitude: Float = 0f
        private set
    var lastCloseDurationMs: Long = 0L
        private set
    var lastRejection: Rejection = Rejection.NONE
        private set

    /** Compteurs de session, pour l'écran de confiance. */
    var clickCount: Int = 0
        private set
    var rejectedCount: Int = 0
        private set

    /** Position du curseur figée à l'instant du contact. */
    var anchorX: Float = 0f
        private set
    var anchorY: Float = 0f
        private set

    /**
     * Progression de la fermeture en cours, 0..1. Alimente l'anneau du curseur : l'utilisateur
     * voit le clic approcher avant qu'il ne parte, ce qui rend le geste apprenable.
     */
    var travelProgress: Float = 0f
        private set

    /** Progression du maintien, 0..1 : annonce la bascule du clic vers le glisser. */
    var holdProgress: Float = 0f
        private set

    /** Vrai tant qu'un maintien est en cours. */
    val isHolding: Boolean get() = holding

    fun reset() {
        inContact = false
        endAttempt()
        holding = false
        holdProgress = 0f
        travelProgress = 0f
        lastOpenAt = Long.MIN_VALUE
        baseline = Float.NaN
        observedFrames = 0
        lastRejection = Rejection.NONE
    }

    /** Remet à zéro les compteurs de session sans perdre la calibration acquise. */
    fun resetStatistics() {
        clickCount = 0
        rejectedCount = 0
        lastRejection = Rejection.NONE
    }

    /**
     * @param pinchRatio distance pouce-index normalisée par la taille de paume
     * @param cursorX,cursorY position courante du curseur
     * @param nowMs horloge monotone
     */
    fun update(pinchRatio: Float, cursorX: Float, cursorY: Float, nowMs: Long): Event {
        if (pinchRatio.isNaN() || pinchRatio.isInfinite()) return Event.NONE

        if (baseline.isNaN()) baseline = pinchRatio.coerceIn(config.baselineMin, config.baselineMax)
        if (observedFrames < config.calibrationFrames) observedFrames++

        // Contact établi : la ligne de base est gelée. Sans ce gel, un maintien prolongé ferait
        // converger la référence vers la valeur fermée, et le geste suivant serait mesuré contre
        // une course fictive.
        if (inContact) {
            travelProgress = 1f
            return onSustainedContact(pinchRatio, cursorX, cursorY, nowMs)
        }

        trackBaseline(pinchRatio)

        val contact = contactThreshold
        val openBand = baseline * config.openBandFraction
        travelProgress = closingProgress(pinchRatio, contact)
        peakProgress = maxOf(peakProgress, travelProgress)

        // Bande haute : la main est franchement ouverte. C'est ici que se clôt la tentative
        // précédente, et que démarre le chronomètre de la suivante.
        if (pinchRatio >= openBand) {
            if (attemptPending && peakProgress >= config.nearMissProgress) {
                reject(Rejection.TOO_SHALLOW, baseline - (baseline - contact) * peakProgress, 0L)
            }
            endAttempt()
            lastOpenAt = nowMs
            return Event.NONE
        }

        // Bande de fermeture : une tentative est en cours.
        attemptPending = true
        if (pinchRatio >= contact || closureJudged) return Event.NONE
        return onContact(pinchRatio, cursorX, cursorY, nowMs)
    }

    private fun endAttempt() {
        attemptPending = false
        closureJudged = false
        peakProgress = 0f
    }

    /**
     * Suiveur de crête asymétrique. La montée doit être franche pour rattraper une main qui
     * s'ouvre ; la descente doit être lente pour qu'une série de clics rapprochés ne tire pas la
     * référence vers le bas et ne finisse pas par s'auto-invalider.
     */
    private fun trackBaseline(ratio: Float) {
        val rate = if (ratio > baseline) config.baselineRise else config.baselineFall
        baseline = (baseline + (ratio - baseline) * rate)
            .coerceIn(config.baselineMin, config.baselineMax)
    }

    private fun closingProgress(ratio: Float, contact: Float): Float {
        val span = baseline - contact
        if (span <= 1e-4f) return 0f
        return ((baseline - ratio) / span).coerceIn(0f, 1f)
    }

    /**
     * Franchissement du seuil de contact. C'est ici, et seulement ici, que l'intention est jugée :
     * il ne reste qu'un critère, la vitesse de la descente.
     */
    private fun onContact(ratio: Float, cursorX: Float, cursorY: Float, nowMs: Long): Event {
        val amplitude = baseline - ratio
        val duration = if (lastOpenAt == Long.MIN_VALUE) Long.MAX_VALUE else nowMs - lastOpenAt
        if (duration > config.maxCloseMs) {
            reject(Rejection.TOO_SLOW, amplitude, duration)
            return Event.NONE
        }

        inContact = true
        attemptPending = false
        peakProgress = 0f
        contactAt = nowMs
        contactBaseline = baseline
        contactMin = ratio
        holding = false
        holdProgress = 0f
        lastAmplitude = amplitude
        lastCloseDurationMs = duration
        lastRejection = Rejection.NONE
        // Le curseur est figé ici : la validation portera sur la cible visée au début du geste,
        // pas sur celle atteinte après la dérive inévitable de la fin du mouvement.
        anchorX = cursorX
        anchorY = cursorY
        return Event.NONE
    }

    /** Contact établi : reste à décider entre clic, maintien et fin de maintien. */
    private fun onSustainedContact(ratio: Float, cursorX: Float, cursorY: Float, nowMs: Long): Event {
        if (ratio < contactMin) contactMin = ratio

        val heldMs = nowMs - contactAt
        holdProgress = (heldMs.toFloat() / config.holdMs).coerceIn(0f, 1f)

        // Relâchement anticipé : une fraction du chemin de retour suffit. Attendre l'ouverture
        // complète n'apporte aucune certitude supplémentaire et ne fait que retarder le clic.
        val releaseAt = contactMin + (contactBaseline - contactMin) * config.releaseFraction
        if (ratio > releaseAt) {
            inContact = false
            holdProgress = 0f
            travelProgress = 0f
            endAttempt()
            // La référence repart de la valeur observée : la main est en train de se rouvrir.
            baseline = maxOf(baseline, ratio).coerceIn(config.baselineMin, config.baselineMax)
            // Le chronomètre de la prochaine fermeture démarre ici : c'est le point haut réel du
            // geste suivant, même si la main n'est pas remontée jusqu'à la bande ouverte.
            lastOpenAt = nowMs

            if (holding) {
                holding = false
                return Event.HOLD_END
            }
            if (lastClickAt != Long.MIN_VALUE && nowMs - lastClickAt < config.cooldownMs) {
                reject(Rejection.COOLDOWN, lastAmplitude, heldMs)
                return Event.NONE
            }
            lastClickAt = nowMs
            clickCount++
            return Event.CLICK
        }

        if (!holding && heldMs > config.holdMs) {
            holding = true
            return Event.HOLD_START
        }

        return Event.NONE
    }

    private fun reject(reason: Rejection, amplitude: Float, duration: Long) {
        closureJudged = true
        lastRejection = reason
        lastAmplitude = amplitude
        lastCloseDurationMs = duration
        rejectedCount++
    }
}
