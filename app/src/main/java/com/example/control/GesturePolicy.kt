package com.example.control

import com.example.gesture.GestureType
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Traduit un flux de gestes en actions système, selon les associations choisies par l'utilisateur.
 *
 * ## Le clic
 *
 * Le clic principal n'est plus un geste mais une **immobilité** : arrêter le curseur sur une cible
 * pendant un court instant déclenche l'appui. C'est la méthode retenue depuis des décennies par
 * les interfaces de commande oculaire et céphalique, pour une raison simple — viser et cliquer
 * deviennent le même geste. Le pincement demandait au contraire de coordonner deux doigts *sans
 * bouger la main*, ce qui déplace la cible au moment précis où l'on valide.
 *
 * Un anneau de progression accompagne l'attente : l'utilisateur voit le clic se préparer et peut
 * l'annuler en bougeant. Cette prévisibilité est ce qui fait la confiance dans le pointage.
 *
 * Le pincement reste disponible : maintenu immobile il produit un appui long, déplacé il produit
 * un glisser.
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
        /** Clic par immobilité : activé par défaut. */
        val dwellEnabled: Boolean = true,
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

    // État du clic par immobilité.
    private var dwellX = 0f
    private var dwellY = 0f
    private var dwellSince = Long.MIN_VALUE
    private var dwellConsumed = false

    /** Progression de l'immobilité, 0..1. Alimente l'anneau affiché autour du curseur. */
    var dwellProgress: Float = 0f
        private set

    fun updateBindings(bindings: GestureBindings) {
        this.bindings = bindings
    }

    fun reset() {
        lastPose = GestureType.IDLE
        dragging = false
        hasAnchor = false
        lastActionAt.clear()
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
        timestampMs: Long
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
        if (gesture != lastPose) {
            hasAnchor = false
            resetDwell()
        }

        val binding = bindings[gesture]

        when (binding) {
            BindableAction.CURSOR -> {
                actions += SystemAction.MoveCursor(cursorX, cursorY)
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

        // Les balayages restent câblés à la navigation entre applications : ce sont des évènements
        // de mouvement, pas des poses, et ils ne sont donc pas personnalisables.
        when (gesture) {
            GestureType.SWIPE_RIGHT -> emitOnce(actions, "switch", SystemAction.SwitchNext, timestampMs)
            GestureType.SWIPE_LEFT -> emitOnce(actions, "switch", SystemAction.SwitchPrevious, timestampMs)
            else -> lastPose = gesture
        }

        return actions
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
