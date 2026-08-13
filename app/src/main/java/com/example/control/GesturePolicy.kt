package com.example.control

import com.example.gesture.GestureType
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Traduit un flux de gestes en actions système.
 *
 * Correspondance des poses, choisie pour que la main au repos ne commande rien et que chaque
 * action fréquente soit la plus confortable :
 *
 * | Pose | Action |
 * |---|---|
 * | Index seul | déplace le curseur |
 * | Pouce + index (pincement) | clic, et glisser tant qu'il est maintenu |
 * | Index + majeur | défilement vertical suivant le mouvement de la main |
 * | Trois doigts | ouvre les applications récentes ; un balayage y navigue |
 * | Main ouverte | lecture / pause du média |
 * | Poing | retour |
 * | Pouce levé | accueil |
 *
 * Deux principes gouvernent l'implémentation :
 *
 *  - **Les actions ponctuelles ne se déclenchent qu'au front montant de la pose.** Maintenir un
 *    poing doit produire un seul retour, pas trente par seconde.
 *  - **Les actions continues sont relatives.** Le défilement et le glisser suivent le *déplacement*
 *    de la main depuis la frame précédente, jamais sa position absolue : la main n'a donc pas
 *    besoin d'être à un endroit particulier pour commencer.
 *
 * Kotlin pur, sans dépendance Android : entièrement testable en JVM.
 */
class GesturePolicy(private val config: Config = Config()) {

    data class Config(
        /**
         * Gain du défilement : un déplacement de main d'une fraction f de l'écran fait défiler
         * f * scrollGain. Au-dessus de 1, la main fatigue moins ; trop haut, le défilement devient
         * incontrôlable.
         */
        val scrollGain: Float = 1.6f,
        /** Déplacement minimal avant d'émettre un défilement, pour ignorer le tremblement. */
        val scrollThreshold: Float = 0.012f,
        /** Intervalle minimal entre deux actions ponctuelles identiques. */
        val actionCooldownMs: Long = 700L,
        /**
         * En deçà de ce déplacement, un pincement relâché est un appui ; au-delà, un glisser.
         * 3 % de la diagonale de l'écran : assez pour absorber le tremblement d'une main tendue,
         * assez peu pour qu'un glisser volontaire soit reconnu.
         */
        val tapMaxTravel: Float = 0.03f
    )

    private var lastPose = GestureType.IDLE
    private var dragging = false
    private var dragStartX = 0f
    private var dragStartY = 0f

    // Référence du geste continu en cours (défilement ou glisser).
    private var anchorY = 0f
    private var hasAnchor = false

    private val lastActionAt = HashMap<String, Long>()

    fun reset() {
        lastPose = GestureType.IDLE
        dragging = false
        hasAnchor = false
        lastActionAt.clear()
    }

    /**
     * Décide des actions à exécuter pour une frame.
     *
     * @param gesture pose courante, déjà stabilisée par le classifieur
     * @param cursorX,cursorY position du curseur en coordonnées écran normalisées
     * @param isNewPose vrai sur la seule frame où la pose change
     * @param timestampMs horloge monotone
     * @return les actions à exécuter, dans l'ordre
     */
    fun onGesture(
        gesture: GestureType,
        cursorX: Float,
        cursorY: Float,
        isNewPose: Boolean,
        timestampMs: Long
    ): List<SystemAction> {
        val actions = mutableListOf<SystemAction>()

        // Le pincement se résout à son relâchement, comme un clic de souris : un déplacement
        // négligeable est un appui, un déplacement franc est un glisser. Décider à la fermeture
        // du pincement obligerait à émettre les deux, et le système exécuterait les deux.
        if (dragging && gesture != GestureType.PINCH) {
            dragging = false
            val travel = hypot(cursorX - dragStartX, cursorY - dragStartY)
            actions += if (travel < config.tapMaxTravel) {
                SystemAction.Tap(dragStartX, dragStartY)
            } else {
                SystemAction.Drag(dragStartX, dragStartY, cursorX, cursorY)
            }
        }

        // La référence d'un geste continu est réarmée à chaque changement de pose, sinon le
        // déplacement effectué dans la pose précédente serait consommé d'un coup.
        if (gesture != lastPose) {
            hasAnchor = false
        }

        when (gesture) {
            GestureType.POINT -> {
                actions += SystemAction.MoveCursor(cursorX, cursorY)
            }

            GestureType.PINCH -> {
                if (isNewPose) {
                    dragStartX = cursorX
                    dragStartY = cursorY
                    dragging = true
                    actions += SystemAction.DragStart(cursorX, cursorY)
                } else if (dragging) {
                    actions += SystemAction.DragMove(cursorX, cursorY)
                }
            }

            GestureType.TWO_FINGER -> {
                if (!hasAnchor) {
                    anchorY = cursorY
                    hasAnchor = true
                } else {
                    val delta = cursorY - anchorY
                    if (abs(delta) >= config.scrollThreshold) {
                        // Le contenu suit la main : monter la main fait remonter le contenu.
                        actions += SystemAction.Scroll(-delta * config.scrollGain)
                        anchorY = cursorY
                    }
                }
            }

            GestureType.THREE_FINGER -> {
                if (isNewPose) emitOnce(actions, "recents", SystemAction.Recents, timestampMs)
            }

            GestureType.OPEN_PALM -> {
                if (isNewPose) emitOnce(actions, "playPause", SystemAction.PlayPause, timestampMs)
            }

            GestureType.FIST -> {
                if (isNewPose) emitOnce(actions, "back", SystemAction.Back, timestampMs)
            }

            GestureType.THUMB_UP -> {
                if (isNewPose) emitOnce(actions, "home", SystemAction.Home, timestampMs)
            }

            GestureType.SWIPE_RIGHT -> {
                emitOnce(actions, "switch", SystemAction.SwitchNext, timestampMs)
            }

            GestureType.SWIPE_LEFT -> {
                emitOnce(actions, "switch", SystemAction.SwitchPrevious, timestampMs)
            }

            GestureType.IDLE -> Unit
        }

        // Les balayages sont des évènements ponctuels : ils ne remplacent pas la pose maintenue.
        if (gesture != GestureType.SWIPE_LEFT && gesture != GestureType.SWIPE_RIGHT) {
            lastPose = gesture
        }
        return actions
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
