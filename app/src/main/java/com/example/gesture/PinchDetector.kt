package com.example.gesture

import kotlin.math.abs

/**
 * Le pincement : **le seul geste de l'application**.
 *
 * ## Pourquoi un seul geste, et pourquoi celui-ci
 *
 * Trois vocabulaires de poses ont été essayés — huit signes, puis six, puis trois, chaque fois
 * mieux mesurés et mieux départagés — et les trois se sont confondus à l'usage. Le défaut n'était
 * donc pas le nombre de poses ni la qualité du classement : c'était de **reconnaître une forme de
 * main**. Cette tâche est une classification sur un continuum, et elle hérite de trois défauts
 * qu'aucun réglage ne corrige. Les doigts repliés, qui portent toute l'information d'une forme,
 * sont ceux que la caméra ne voit pas. Passer d'une forme à une autre traverse toutes les formes
 * intermédiaires. Et tenir une forme fatigue la main, donc la dégrade à mesure qu'on s'en sert.
 *
 * Les trois plateformes qui ont le plus investi sur le sujet ont convergé sur la même réponse, et
 * avec des capteurs bien meilleurs qu'une caméra frontale de téléphone : Vision Pro, HoloLens et
 * Quest n'ont **pas** de vocabulaire de poses. Elles ont le pincement, et elles en dérivent tout
 * le reste — l'appui, l'appui long, le glisser, le défilement. Si un vocabulaire de poses était
 * tenable, elles l'auraient.
 *
 * Ce que le pincement a de particulier se résume à cinq propriétés, qu'aucune pose ne réunit :
 *
 *  1. **Il se mesure sans rien deviner.** C'est la distance entre deux points — bout du pouce,
 *     bout de l'index — qui sont les mieux vus quand la main fait face à la caméra. Une extension
 *     de doigt replié, à l'inverse, se déduit de points que le modèle invente.
 *  2. **C'est une grandeur continue, pas une classe.** Un seuil avec hystérésis sur une distance
 *     est stable ; un classement à huit issues sur une main ambiguë est un tirage au sort.
 *  3. **Il se sent au doigt.** Le pouce touche l'index : le geste se confirme sans regarder
 *     l'écran, et il s'arrête sur une butée anatomique plutôt que sur un seuil arbitraire.
 *  4. **Il n'existe pas au repos.** Une main détendue n'a pas le pouce collé à l'index.
 *  5. **Il ne fatigue pas.** L'opposition pouce-index est le mouvement le plus économique de la
 *     main, et surtout : **il ne demande de tenir aucune forme.** Le reste de la main fait ce
 *     qu'il veut, détendu, bras baissé.
 *
 * Son défaut connu disparaît du même coup. La littérature note qu'un pincement est indistinguable
 * des autres gestes qui rapprochent le pouce de l'index — un signe « OK » déclenche un pincement.
 * C'est vrai, et c'est sans conséquence **ici** : ce défaut n'est un défaut que s'il existe autre
 * chose à confondre avec. Avec un seul geste, il n'y a plus rien à confondre.
 *
 * ## Ce que ce détecteur fait, et ce qu'il ne fait plus
 *
 * Il ne produit que deux événements : le bouton s'enfonce ([Event.PRESS]), le bouton se relâche
 * ([Event.RELEASE]). **Il ne décide plus du sens du geste** — appui, appui long, tracé. Cette
 * part est passée à [com.example.control.Pointer], et la séparation n'est pas cosmétique : la
 * version précédente basculait elle-même en « maintien » au bout de 350 ms, si bien qu'un
 * défilement ne pouvait pas commencer avant ce délai. Le détecteur mesure le doigt ; l'arbitrage
 * du sens revient à ce qui connaît aussi le déplacement du curseur.
 *
 * ## Tout est relatif à la course de l'utilisateur
 *
 * La version initiale comparait l'écart pouce-index à des **seuils absolus**. C'était son défaut
 * de fond : l'écart au repos dépend de la morphologie et de l'habitude. Un même seuil est trop
 * lâche pour l'un — appuis fantômes — et hors d'atteinte pour l'autre — appuis qui ne partent pas.
 *
 *  - **Ligne de base adaptative** ([baseline]) — un suiveur de crête asymétrique apprend l'écart
 *    au repos. Il monte vite, ne descend que lentement, et **se fige pendant le contact** : un
 *    appui maintenu ne peut donc pas éroder sa propre référence.
 *  - **Seuil de contact proportionnel** — une fraction de cette course, et non une constante.
 *  - **Fenêtre de fermeture** mesurée depuis la bande ouverte : c'est ce qui sépare une impulsion
 *    volontaire d'un repositionnement de la main.
 *  - **Relâchement anticipé** — le bouton remonte dès que le pouce est reparti d'une fraction du
 *    chemin, sans attendre l'ouverture complète. Quelques dizaines de millisecondes gagnées à
 *    chaque appui, sans rien concéder.
 *
 * Chaque rejet est enregistré avec sa cause ([lastRejection]) : l'utilisateur peut voir *pourquoi*
 * un appui n'est pas parti, au lieu de constater qu'il ne se passe rien.
 *
 * Kotlin pur, sans dépendance Android : entièrement testable en JVM.
 */
class PinchDetector(private val config: Config = Config()) {

    data class Config(
        /**
         * Fraction de la ligne de base sous laquelle le contact est déclaré. Bornée : une course
         * de référence aberrante ne doit pas rendre l'appui impossible ni trivial.
         */
        val contactFraction: Float = 0.34f,
        val contactMin: Float = 0.12f,
        val contactMax: Float = 0.34f,

        /**
         * Progression en deçà de laquelle la main est considérée franchement ouverte : c'est de là
         * qu'est chronométrée la fermeture.
         *
         * Exprimée en fraction de la **course** de l'utilisateur, et non plus de sa ligne de base.
         * Les deux formulations sont voisines, mais la première est celle dans laquelle tout le
         * reste du détecteur raisonne, ce qui rend le seuil comparable aux autres.
         *
         * La valeur est celle à laquelle correspondait l'ancienne bande absolue (72 % de la ligne
         * de base) sur une main ordinaire, et c'est voulu : la changer aurait décalé la calibration
         * de [maxCloseMs] et des trois positions de fermeté, donc rendu les appuis plus difficiles
         * — exactement l'inverse du but. La correction porte sur le **verrou** et sur la vitesse
         * d'adaptation, pas sur l'exigence demandée au geste.
         */
        val clockArmProgress: Float = 0.42f,

        /**
         * Progression en deçà de laquelle la tentative en cours est close et le verrou de rejet
         * levé.
         *
         * **C'est la correction du défaut qui rendait l'appui inopérant par intermittence.** Le
         * verrou ne se levait qu'une fois la main revenue au-dessus de 72 % de sa ligne de base —
         * une valeur absolue. Une main dont la position de repos tombait sous cette bande ne
         * pouvait donc plus jamais lever le verrou : un seul rejet, et plus aucun appui ne partait
         * tant que la main n'était pas grande ouverte. Le défilement s'arrêtait avec lui, puisqu'il
         * a besoin du même enfoncement.
         *
         * Au-dessus de [nearMissProgress] à dessein : la paire forme une hystérésis, de sorte
         * qu'une main qui oscille autour du seuil ne produise pas une rafale de rejets.
         */
        val rearmProgress: Float = 0.45f,

        /**
         * Progression de fermeture au-delà de laquelle une tentative avortée est comptée comme un
         * quasi-contact. C'est le seul défaut que l'utilisateur ne peut pas diagnostiquer seul :
         * de son point de vue, il a pincé.
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
         * bouton remonte au milieu de la remontée du pouce, soit typiquement 30 ms plus tôt
         * qu'en attendant l'ouverture complète.
         */
        val releaseFraction: Float = 0.50f,

        /**
         * Intervalle minimal entre deux enfoncements. Le garde-fou est posé sur le **bouton**, et
         * non plus sur l'appui : c'est un anti-rebond du suivi, il n'a donc rien à savoir du sens
         * que prendra le geste.
         */
        val cooldownMs: Long = 120L,

        /** Vitesses de suivi de la ligne de base : montée franche, descente très lente. */
        val baselineRise: Float = 0.10f,
        val baselineFall: Float = 0.012f,

        /**
         * Vitesse de descente quand la main est **au repos** à une nouvelle position.
         *
         * La descente lente est indispensable pendant une fermeture : une référence qui suivrait le
         * pouce qui se ferme verrait le seuil de contact reculer devant lui, et l'appui ne partirait
         * jamais. Mais elle rendait l'adaptation à un **changement de posture** beaucoup trop
         * longue — près de trois secondes, pendant lesquelles la course mesurée était celle d'une
         * main qui n'existait plus, et les appuis étaient rejetés.
         *
         * Les deux besoins sont conciliables parce qu'ils se distinguent : une fermeture descend de
         * façon soutenue, une main au repos ne descend pas. Voir [isAtRest].
         */
        val baselineRestFall: Float = 0.12f,
        val baselineMin: Float = 0.35f,
        val baselineMax: Float = 2.0f,

        /** Nombre d'images d'observation avant que la ligne de base soit jugée fiable. */
        val calibrationFrames: Int = 12,

        /**
         * Déplacement net, sur la fenêtre d'observation, en deçà duquel la main est jugée au repos.
         *
         * Un **déplacement net sur plusieurs images**, et non un écart par image : c'est ce qui
         * distingue le bruit d'une fermeture lente. Le bruit s'annule sur la fenêtre, une fermeture
         * s'y accumule. Un critère par image ne peut pas les séparer, puisqu'une fermeture lente
         * avance, image par image, du même ordre que le bruit.
         */
        val restNetMovement: Float = 0.05f,

        /** Longueur de la fenêtre d'observation du repos, en images. */
        val restWindowFrames: Int = 5
    )

    /** Ce que la frame courante produit. Deux événements, comme un bouton. */
    enum class Event { NONE, PRESS, RELEASE }

    /** Cause du dernier geste rejeté. Affichée à l'utilisateur pour rendre le réglage lisible. */
    enum class Rejection {
        /** Aucun rejet depuis la dernière remise à zéro. */
        NONE,

        /**
         * Le pouce s'est nettement approché puis est reparti sans toucher l'index. C'est le
         * défaut le plus fréquent chez qui débute.
         */
        TOO_SHALLOW,

        /** La fermeture a pris trop de temps : repositionnement de la main, pas une impulsion. */
        TOO_SLOW,

        /** Deux enfoncements trop rapprochés : le second est un rebond du suivi. */
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

    // --- État du bouton -----------------------------------------------------------------------

    /** Vrai tant que le pouce touche l'index : le bouton est enfoncé. */
    var isPressed = false
        private set

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
    private var contactBaseline = 0f
    private var contactMin = 0f
    private var lastPressAt = Long.MIN_VALUE

    /**
     * Instant où la main était pour la dernière fois franchement ouverte : origine du chronomètre
     * de fermeture.
     */
    private var lastOpenAt = Long.MIN_VALUE

    /** Fenêtre glissante des derniers écarts observés, pour juger du repos. */
    private val recentRatios = FloatArray(16) { Float.NaN }
    private var recentIndex = 0

    /** Diagnostic du dernier geste mesuré. */
    var lastAmplitude: Float = 0f
        private set
    var lastCloseDurationMs: Long = 0L
        private set
    var lastRejection: Rejection = Rejection.NONE
        private set

    /** Compteurs de session, pour l'écran de confiance. */
    var pressCount: Int = 0
        private set
    var rejectedCount: Int = 0
        private set

    /**
     * Position du curseur figée à l'instant du contact.
     *
     * C'est ce qui fait qu'un appui porte sur la cible visée au début du geste, et non sur celle
     * atteinte après la dérive inévitable de la fin du mouvement.
     */
    var anchorX: Float = 0f
        private set
    var anchorY: Float = 0f
        private set

    /**
     * Progression de la fermeture en cours, 0..1. Alimente l'anneau du curseur : l'utilisateur
     * voit l'appui approcher avant qu'il ne parte, ce qui rend le geste apprenable.
     */
    var travelProgress: Float = 0f
        private set

    fun reset() {
        isPressed = false
        endAttempt()
        travelProgress = 0f
        lastOpenAt = Long.MIN_VALUE
        baseline = Float.NaN
        observedFrames = 0
        lastRejection = Rejection.NONE
        recentRatios.fill(Float.NaN)
        recentIndex = 0
    }

    /** Remet à zéro les compteurs de session sans perdre la calibration acquise. */
    fun resetStatistics() {
        pressCount = 0
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

        // Contact établi : la ligne de base est gelée. Sans ce gel, un appui prolongé ferait
        // converger la référence vers la valeur fermée, et le geste suivant serait mesuré contre
        // une course fictive.
        if (isPressed) {
            travelProgress = 1f
            return onSustainedContact(pinchRatio, nowMs)
        }

        val atRest = isAtRest(pinchRatio)
        record(pinchRatio)
        trackBaseline(pinchRatio, atRest)

        val contact = contactThreshold
        travelProgress = closingProgress(pinchRatio, contact)
        peakProgress = maxOf(peakProgress, travelProgress)

        // Main franchement ouverte : c'est ici que démarre le chronomètre de la fermeture.
        if (travelProgress <= config.clockArmProgress) lastOpenAt = nowMs

        // Fin de la tentative en cours, sur un seuil **relatif à la course** de l'utilisateur.
        if (travelProgress < config.rearmProgress) {
            // `!closureJudged` compte. Sans lui, une fermeture déjà jugée — trop lente, ou absorbée
            // par l'anti-rebond — était **recomptée** en quasi-contact au moment où la main se
            // rouvrait : un seul geste produisait deux rejets, et la cause affichée était la
            // seconde, donc la mauvaise. Le conseil donné à l'utilisateur portait alors sur un
            // défaut qu'il n'avait pas commis.
            if (attemptPending && !closureJudged && peakProgress >= config.nearMissProgress) {
                reject(Rejection.TOO_SHALLOW, baseline - (baseline - contact) * peakProgress, 0L)
            }
            endAttempt()
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
     * s'ouvre ; la descente doit être lente pour qu'une série d'appuis rapprochés ne tire pas la
     * référence vers le bas et ne finisse pas par s'auto-invalider.
     */
    private fun trackBaseline(ratio: Float, atRest: Boolean) {
        val rate = when {
            ratio > baseline -> config.baselineRise
            // Main posée à une nouvelle position : la référence doit l'y rejoindre vite, sans quoi
            // la course mesurée reste celle d'une posture abandonnée.
            atRest -> config.baselineRestFall
            else -> config.baselineFall
        }
        baseline = (baseline + (ratio - baseline) * rate)
            .coerceIn(config.baselineMin, config.baselineMax)
    }

    /**
     * La main est-elle posée, plutôt qu'en train de se fermer ?
     *
     * Jugé sur le **déplacement net** depuis quelques images, et non sur l'écart d'une image à la
     * suivante. C'est la seule façon de séparer le bruit d'une fermeture lente : le bruit s'annule
     * sur la fenêtre, une fermeture s'y accumule, alors qu'image par image les deux ont la même
     * amplitude.
     */
    private fun isAtRest(ratio: Float): Boolean {
        val window = config.restWindowFrames.coerceIn(1, recentRatios.size)
        val oldest = recentRatios[(recentIndex - window + recentRatios.size) % recentRatios.size]
        if (oldest.isNaN()) return false
        return abs(ratio - oldest) < config.restNetMovement
    }

    private fun record(ratio: Float) {
        recentRatios[recentIndex] = ratio
        recentIndex = (recentIndex + 1) % recentRatios.size
    }

    private fun closingProgress(ratio: Float, contact: Float): Float {
        val span = baseline - contact
        if (span <= 1e-4f) return 0f
        return ((baseline - ratio) / span).coerceIn(0f, 1f)
    }

    /**
     * Franchissement du seuil de contact. C'est ici, et seulement ici, que l'intention est jugée :
     * il ne reste que deux critères, la vitesse de la descente et l'anti-rebond.
     */
    private fun onContact(ratio: Float, cursorX: Float, cursorY: Float, nowMs: Long): Event {
        val amplitude = baseline - ratio
        val duration = if (lastOpenAt == Long.MIN_VALUE) Long.MAX_VALUE else nowMs - lastOpenAt
        if (duration > config.maxCloseMs) {
            reject(Rejection.TOO_SLOW, amplitude, duration)
            return Event.NONE
        }
        if (lastPressAt != Long.MIN_VALUE && nowMs - lastPressAt < config.cooldownMs) {
            reject(Rejection.COOLDOWN, amplitude, duration)
            return Event.NONE
        }

        isPressed = true
        attemptPending = false
        peakProgress = 0f
        contactBaseline = baseline
        contactMin = ratio
        lastAmplitude = amplitude
        lastCloseDurationMs = duration
        lastRejection = Rejection.NONE
        lastPressAt = nowMs
        anchorX = cursorX
        anchorY = cursorY
        pressCount++
        return Event.PRESS
    }

    /** Contact établi : il ne reste qu'à guetter le relâchement. */
    private fun onSustainedContact(ratio: Float, nowMs: Long): Event {
        if (ratio < contactMin) contactMin = ratio

        // Relâchement anticipé : une fraction du chemin de retour suffit. Attendre l'ouverture
        // complète n'apporte aucune certitude supplémentaire et ne fait que retarder le geste.
        val releaseAt = contactMin + (contactBaseline - contactMin) * config.releaseFraction
        if (ratio <= releaseAt) return Event.NONE

        isPressed = false
        travelProgress = 0f
        endAttempt()
        // La référence repart de la valeur observée : la main est en train de se rouvrir.
        baseline = maxOf(baseline, ratio).coerceIn(config.baselineMin, config.baselineMax)
        // Le chronomètre de la prochaine fermeture démarre ici : c'est le point haut réel du
        // geste suivant, même si la main n'est pas remontée jusqu'à la bande ouverte.
        lastOpenAt = nowMs
        return Event.RELEASE
    }

    private fun reject(reason: Rejection, amplitude: Float, duration: Long) {
        closureJudged = true
        lastRejection = reason
        lastAmplitude = amplitude
        lastCloseDurationMs = duration
        rejectedCount++
    }
}
