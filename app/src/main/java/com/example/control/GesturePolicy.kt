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
 * Le clic est une **impulsion du pouce** : un aller-retour bref vers l'index, détecté par sa
 * vitesse de fermeture et non par une position. Voir [ClickDetector] pour le raisonnement complet.
 * Il part en moins de 100 ms, ne se déclenche jamais sur un pouce au repos, et ne déplace pas la
 * cible puisque le curseur suit l'index, qui ne bouge pas.
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
    private val clickDetector = ClickDetector()

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

    fun updateBindings(bindings: GestureBindings) {
        this.bindings = bindings
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
