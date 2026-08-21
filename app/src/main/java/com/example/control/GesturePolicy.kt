package com.example.control

import com.example.gesture.ClickDetector
import com.example.gesture.GestureType
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Traduit un flux de gestes en actions système, selon les associations choisies par l'utilisateur.
 *
 * ## Le clic
 *
 * Le clic est un **contact bref du pouce sur l'index**, jugé non pas à une distance absolue mais
 * à la course propre de l'utilisateur, apprise en continu. Voir [ClickDetector] pour le choix du
 * geste et le détail de la mesure. Il ne se déclenche jamais sur un pouce au repos, quelle que
 * soit la morphologie de la main, et ne déplace pas la cible puisque le curseur suit l'index,
 * qui ne bouge pas.
 *
 * Maintenir le pouce fermé prolonge le geste en glisser ou en appui long, sans rien à apprendre
 * de plus.
 *
 * Le clic par immobilité reste disponible en option ([Config.dwellEnabled]) : il ne demande aucune
 * dextérité et sert de repli d'accessibilité, au prix d'une attente incompatible avec la vitesse.
 *
 * ## Principes
 *
 *  - **Les actions ponctuelles ne se déclenchent qu'au front montant de la pose.** Maintenir un
 *    poing produit un seul retour, pas trente par seconde.
 *  - **Les actions continues sont relatives.** Défilement et glisser suivent le *déplacement* de
 *    la main, jamais sa position absolue : la main n'a pas besoin d'être à un endroit particulier.
 *
 * Kotlin pur, sans dépendance Android : entièrement testable en JVM.
 */
class GesturePolicy(
    private var bindings: GestureBindings = GestureBindings(),
    private val config: Config = Config()
) {

    data class Config(
        /** Gain du défilement : un déplacement de main de f fait défiler f x scrollGain. */
        val scrollGain: Float = 1.6f,
        /** Déplacement minimal avant d'émettre un défilement, pour ignorer le tremblement. */
        val scrollThreshold: Float = 0.012f,
        /** Intervalle minimal entre deux actions ponctuelles identiques. */
        val actionCooldownMs: Long = 700L,
        /** En deçà de ce déplacement, un pincement relâché est un appui ; au-delà, un glisser. */
        val tapMaxTravel: Float = 0.03f,
        /**
         * Clic par immobilité. Désactivé par défaut : l'attente qu'il impose est incompatible
         * avec une cadence de clic rapide. Reste proposé comme repli d'accessibilité.
         */
        val dwellEnabled: Boolean = false,
        /** Rayon dans lequel le curseur doit rester pour que l'immobilité soit reconnue. */
        val dwellRadius: Float = 0.030f,
        /** Durée d'immobilité déclenchant l'appui. */
        val dwellMs: Long = 650L,
        /** Pincement maintenu immobile au-delà de ce délai : appui long. */
        val longPressMs: Long = 600L
    )

    private var lastPose = GestureType.IDLE
    private var dragging = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragStartedAt = 0L

    private var anchorX = 0f
    private var anchorY = 0f
    private var hasAnchor = false

    private val lastActionAt = HashMap<String, Long>()
    private var clickDetector = ClickDetector()

    // État du clic par immobilité.
    private var dwellX = 0f
    private var dwellY = 0f
    private var dwellSince = Long.MIN_VALUE
    private var dwellConsumed = false

    /** Progression de l'immobilité, 0..1. Alimente l'anneau affiché autour du curseur. */
    var dwellProgress: Float = 0f
        private set

    /** Progression du maintien du pouce, 0..1 : annonce la bascule du clic vers le glisser. */
    val holdProgress: Float get() = clickDetector.holdProgress

    /** Progression de la fermeture en cours, 0..1 : alimente l'anneau du curseur. */
    val clickProgress: Float get() = clickDetector.travelProgress

    /**
     * Instantané de l'état du clic, destiné à l'affichage.
     *
     * Rendre ces mesures visibles n'est pas un luxe de diagnostic : le clic gestuel est le seul
     * geste dont l'échec est **silencieux et indiscernable d'une erreur de visée**. Sans retour,
     * l'utilisateur ne peut ni corriger son geste ni faire confiance au système. Avec, il voit sa
     * propre course, son taux de réussite, et la cause exacte de ce qui n'a pas marché.
     */
    data class ClickTelemetry(
        val isCalibrated: Boolean = false,
        /** Course pouce-index apprise pour cette main, en multiples de paume. */
        val baseline: Float = 0f,
        /** Seuil de contact courant, dérivé de la course. */
        val contactThreshold: Float = 0f,
        /** Progression de la fermeture en cours, 0..1. */
        val progress: Float = 0f,
        val clicks: Int = 0,
        val rejected: Int = 0,
        val lastRejection: ClickDetector.Rejection = ClickDetector.Rejection.NONE,
        /** Durée de la dernière fermeture mesurée, en millisecondes. */
        val lastCloseMs: Long = 0L
    ) {
        /** Part des gestes aboutis, ou null tant qu'aucun geste n'a été tenté. */
        val successRate: Float?
            get() = (clicks + rejected).takeIf { it > 0 }?.let { clicks.toFloat() / it }

        /**
         * Correction à proposer à l'utilisateur, ou null si le dernier geste a abouti.
         *
         * Traduit la cause technique du rejet en une action qu'il peut appliquer : afficher
         * « TOO_SLOW » n'aiderait personne, ce qu'il faut dire c'est quoi faire autrement.
         *
         * Ce texte vit ici, et non dans l'écran qui l'affiche, pour qu'il soit couvert par les
         * tests : c'est la seule phrase de l'application dont l'exactitude conditionne la capacité
         * de l'utilisateur à se servir du geste le plus fréquent.
         */
        val advice: String?
            get() = when (lastRejection) {
                ClickDetector.Rejection.TOO_SHALLOW ->
                    "Votre pouce s'approche sans toucher l'index. Laissez-les se rejoindre " +
                        "franchement : le contact est ce qui déclenche l'appui, et il se sent au doigt."

                ClickDetector.Rejection.TOO_SLOW ->
                    "Le mouvement est trop lent pour être distingué d'un repositionnement de la " +
                        "main. Frappez d'un coup sec plutôt que de refermer progressivement."

                ClickDetector.Rejection.COOLDOWN ->
                    "Deux appuis très rapprochés ont été détectés ; le second a été ignoré."

                ClickDetector.Rejection.NONE -> null
            }
    }

    fun clickTelemetry(): ClickTelemetry = ClickTelemetry(
        isCalibrated = clickDetector.isCalibrated,
        baseline = clickDetector.baseline.takeIf { !it.isNaN() } ?: 0f,
        contactThreshold = clickDetector.contactThreshold,
        progress = clickDetector.travelProgress,
        clicks = clickDetector.clickCount,
        rejected = clickDetector.rejectedCount,
        lastRejection = clickDetector.lastRejection,
        lastCloseMs = clickDetector.lastCloseDurationMs
    )

    fun updateBindings(bindings: GestureBindings) {
        this.bindings = bindings
    }

    /**
     * Applique un nouveau réglage de fermeté du clic.
     *
     * Le détecteur est reconstruit, et perd donc la course apprise. C'est voulu : l'utilisateur
     * vient de changer de réglage, il va enchaîner quelques gestes d'essai, et ce sont eux qui
     * doivent servir de référence. Conserver l'ancienne calibration mêlerait deux régimes.
     */
    fun updateClickTuning(tuning: ClickTuning) {
        clickDetector = ClickDetector(tuning.toConfig())
    }

    fun reset() {
        lastPose = GestureType.IDLE
        dragging = false
        hasAnchor = false
        lastActionAt.clear()
        clickDetector.reset()
        resetDwell()
    }

    private fun resetDwell() {
        dwellSince = Long.MIN_VALUE
        dwellConsumed = false
        dwellProgress = 0f
    }

    /**
     * Décide des actions à exécuter pour une frame.
     *
     * @param gesture pose courante, déjà stabilisée par le classifieur
     * @param cursorX,cursorY position du curseur en coordonnées écran normalisées
     * @param isNewPose vrai sur la seule frame où la pose change
     * @param timestampMs horloge monotone
     */
    fun onGesture(
        gesture: GestureType,
        cursorX: Float,
        cursorY: Float,
        isNewPose: Boolean,
        timestampMs: Long,
        pinchRatio: Float = Float.MAX_VALUE
    ): List<SystemAction> {
        val actions = mutableListOf<SystemAction>()

        // Le pincement se résout à son relâchement, comme un clic de souris : immobile et bref
        // c'est un appui, immobile et long un appui long, déplacé un glisser.
        if (dragging && gesture != GestureType.PINCH) {
            dragging = false
            val travel = hypot(cursorX - dragStartX, cursorY - dragStartY)
            val heldMs = timestampMs - dragStartedAt
            actions += when {
                travel >= config.tapMaxTravel ->
                    SystemAction.Drag(dragStartX, dragStartY, cursorX, cursorY)
                heldMs >= config.longPressMs -> SystemAction.LongPress(dragStartX, dragStartY)
                else -> SystemAction.Tap(dragStartX, dragStartY)
            }
        }

        // La référence d'un geste continu est réarmée à chaque changement de pose : sans cela, le
        // déplacement effectué dans la pose précédente serait consommé d'un bloc.
        //
        // Un balayage est explicitement exclu de ce réarmement. Il ne remplace pas la pose tenue,
        // il la traverse : réarmer sur son passage ferait perdre au défilement en cours sa
        // référence, et l'image suivante repartirait d'un saut.
        if (gesture != lastPose && !GestureBindings.isSwipe(gesture)) {
            hasAnchor = false
            resetDwell()
        }

        // Un balayage est un évènement sans durée : il ne peut pas porter un rôle continu, qui
        // n'aurait aucune image suivante sur laquelle s'exercer. Une configuration qui en
        // demanderait un est neutralisée ici plutôt que de produire un curseur d'une seule frame.
        val binding = bindings[gesture].let {
            if (GestureBindings.isSwipe(gesture) && it.isContinuous) BindableAction.NONE else it
        }

        when (binding) {
            BindableAction.CURSOR -> {
                actions += SystemAction.MoveCursor(cursorX, cursorY)
                actions += click(pinchRatio, cursorX, cursorY, timestampMs)
                dwell(cursorX, cursorY, timestampMs)?.let { actions += it }
            }

            BindableAction.DRAG -> {
                if (isNewPose) {
                    dragStartX = cursorX
                    dragStartY = cursorY
                    dragStartedAt = timestampMs
                    dragging = true
                    actions += SystemAction.DragStart(cursorX, cursorY)
                } else if (dragging) {
                    actions += SystemAction.DragMove(cursorX, cursorY)
                }
            }

            BindableAction.SCROLL -> {
                relativeMove(cursorY, isVertical = true)?.let {
                    actions += SystemAction.Scroll(it)
                }
            }

            BindableAction.SCROLL_HORIZONTAL -> {
                relativeMove(cursorX, isVertical = false)?.let {
                    actions += SystemAction.ScrollHorizontal(it)
                }
            }

            BindableAction.NONE -> Unit

            // Toutes les autres associations sont des actions ponctuelles.
            else -> if (isNewPose) {
                emitOnce(actions, binding.id, binding.action, timestampMs)
            }
        }

        // Un balayage traverse la pose tenue sans la remplacer : la mémoriser comme pose courante
        // ferait réarmer les gestes continus à chaque balayage, et le défilement repartirait de
        // zéro juste après. La pose sous-jacente est donc conservée.
        if (!GestureBindings.isSwipe(gesture)) lastPose = gesture

        return actions
    }

    /**
     * Détection du clic seule, sans traduction en action système.
     *
     * Sert au clavier gestuel, qui court-circuite la politique de commande — un appui ne doit pas
     * partir dans l'application sous-jacente pendant qu'on écrit — mais doit valider au même geste
     * que partout ailleurs. Passer par le même détecteur, et non par une seconde instance, est ce
     * qui permet à la course apprise de survivre à l'ouverture du clavier.
     */
    fun clickOnly(pinchRatio: Float, cursorX: Float, cursorY: Float, nowMs: Long): ClickDetector.Event =
        clickDetector.update(pinchRatio, cursorX, cursorY, nowMs)

    /**
     * Clic par impulsion du pouce, et prolongation en glisser.
     *
     * Le maintien se résout à son terme comme un clic de souris : un déplacement négligeable
     * pendant le maintien donne un appui long, un déplacement franc un glisser.
     */
    private fun click(pinchRatio: Float, x: Float, y: Float, now: Long): List<SystemAction> =
        when (clickDetector.update(pinchRatio, x, y, now)) {
            ClickDetector.Event.CLICK ->
                listOf(SystemAction.Tap(clickDetector.anchorX, clickDetector.anchorY))

            ClickDetector.Event.HOLD_START -> {
                dragStartX = clickDetector.anchorX
                dragStartY = clickDetector.anchorY
                dragStartedAt = now
                listOf(SystemAction.DragStart(dragStartX, dragStartY))
            }

            ClickDetector.Event.HOLD_END -> {
                val travel = hypot(x - dragStartX, y - dragStartY)
                listOf(
                    if (travel >= config.tapMaxTravel) {
                        SystemAction.Drag(dragStartX, dragStartY, x, y)
                    } else {
                        SystemAction.LongPress(dragStartX, dragStartY)
                    }
                )
            }

            ClickDetector.Event.NONE -> emptyList()
        }

    /**
     * Clic par immobilité.
     *
     * Le curseur doit rester dans un rayon donné pendant toute la durée requise. Sortir du rayon
     * remet le compte à zéro — c'est ce qui permet d'annuler un clic en cours simplement en
     * bougeant, et donc de survoler sans cliquer.
     *
     * Après déclenchement, un nouvel appui exige de quitter la zone : sans cela, rester immobile
     * produirait un clic par seconde.
     */
    private fun dwell(x: Float, y: Float, now: Long): SystemAction? {
        if (!config.dwellEnabled) return null

        val moved = hypot(x - dwellX, y - dwellY) > config.dwellRadius
        if (moved || dwellSince == Long.MIN_VALUE) {
            dwellX = x
            dwellY = y
            dwellSince = now
            dwellConsumed = false
            dwellProgress = 0f
            return null
        }

        if (dwellConsumed) {
            dwellProgress = 1f
            return null
        }

        val elapsed = now - dwellSince
        dwellProgress = (elapsed.toFloat() / config.dwellMs).coerceIn(0f, 1f)
        if (elapsed < config.dwellMs) return null

        dwellConsumed = true
        dwellProgress = 1f
        return SystemAction.Tap(dwellX, dwellY)
    }

    /** Déplacement relatif depuis la dernière référence, ou null si sous le seuil de bruit. */
    private fun relativeMove(value: Float, isVertical: Boolean): Float? {
        val anchor = if (isVertical) anchorY else anchorX
        if (!hasAnchor) {
            anchorX = value
            anchorY = value
            hasAnchor = true
            return null
        }

        val delta = value - anchor
        if (abs(delta) < config.scrollThreshold) return null

        if (isVertical) anchorY = value else anchorX = value
        // Le contenu suit la main : monter la main fait remonter le contenu.
        return -delta * config.scrollGain
    }

    /**
     * Garde-fou temporel commun aux actions ponctuelles. Un geste mal stabilisé peut produire deux
     * fronts rapprochés ; sans ce filtre, un « retour » involontaire fermerait deux écrans.
     */
    private fun emitOnce(
        into: MutableList<SystemAction>,
        key: String,
        action: SystemAction,
        now: Long
    ) {
        val last = lastActionAt[key]
        if (last != null && now - last < config.actionCooldownMs) return
        lastActionAt[key] = now
        into += action
    }
}
