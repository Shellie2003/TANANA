package com.example.control

import com.example.gesture.PinchDetector
import kotlin.math.hypot

/**
 * Ce que le pincement produit, une fois son sens arbitré.
 *
 * Volontairement exprimé dans le vocabulaire d'un **pointeur**, et non dans celui du système :
 * c'est ce qui permet d'envoyer le même appui tantôt à l'application du dessous, tantôt au
 * panneau de commandes, tantôt au clavier gestuel, sans que [Pointer] ait à connaître aucun des
 * trois.
 */
sealed interface PointerEvent {
    /** Appui bref, à l'endroit visé au début du geste. */
    data class Tap(val x: Float, val y: Float) : PointerEvent

    /** Appui prolongé immobile : menus contextuels, sélection de texte. */
    data class LongPress(val x: Float, val y: Float) : PointerEvent

    /** Le doigt se pose : début d'un tracé continu. */
    data class StrokeBegin(
        val x: Float,
        val y: Float,
        /** Centre de la paume : origine de la mesure du déplacement. Voir [StrokeMove]. */
        val driveX: Float,
        val driveY: Float
    ) : PointerEvent

    /**
     * Le doigt posé suit la main.
     *
     * Porte **deux** positions, et la distinction corrige le défilement. [x] et [y] sont le
     * curseur, issu du bout de l'index : c'est avec lui qu'on vise, donc il pilote la manipulation
     * directe au rapport 1:1. [driveX] et [driveY] sont le centre de la paume, qui mesure le
     * déplacement de la main sans être perturbé par les doigts — c'est lui qui pilote le
     * défilement.
     *
     * Le bout de l'index servait aux deux usages, et c'était le défaut : pendant un pincement, le
     * pouce se pose précisément dessus, les deux extrémités se confondent dans l'image, et
     * l'estimation devient bruitée exactement pendant un défilement.
     */
    data class StrokeMove(
        val x: Float,
        val y: Float,
        val driveX: Float,
        val driveY: Float
    ) : PointerEvent

    /**
     * Le doigt se lève. Porte le **trajet complet**, et non seulement le point d'arrivée : quand
     * la diffusion continue n'est pas disponible — Android 7, ou refus du système — le trajet est
     * rejoué d'un bloc, et il faut alors savoir d'où il partait.
     */
    data class StrokeEnd(
        val fromX: Float,
        val fromY: Float,
        val toX: Float,
        val toY: Float
    ) : PointerEvent
}

/**
 * Le modèle d'interaction complet : **une position, un bouton, trois issues.**
 *
 * ## Ce qui remplace le vocabulaire de poses
 *
 * Il n'y a plus de geste à reconnaître, donc plus rien à confondre. La main ne fournit que deux
 * signaux — où elle est, et si le pouce touche l'index — et tout le reste se décide sur **ce
 * qu'on fait pendant que le bouton est enfoncé** :
 *
 * | Ce qu'on fait | Ce que ça donne |
 * |---|---|
 * | pincer et relâcher sans bouger | appui |
 * | pincer et rester immobile | appui long |
 * | pincer et déplacer la main | tracé continu — défilement, balayage, glisser-déposer |
 * | viser et ne plus bouger | appui, sans pincer du tout — voir [Config.dwellEnabled] |
 *
 * La dernière ligne est un **second chemin vers le même appui**, et non un geste de plus : il n'y a
 * rien à apprendre ni à distinguer, puisque ne rien faire est ce qui le déclenche. Il existe parce
 * que tout appui obtenu par l'articulation des doigts hérite de la fiabilité avec laquelle la
 * caméra voit ces doigts, et que cette fiabilité a fait défaut — longuement.
 *
 * C'est exactement une souris à un bouton, et c'est voulu : personne n'a à apprendre une souris.
 * C'est aussi, à peu de chose près, le jeu de gestes de Vision Pro, de HoloLens et de Quest.
 *
 * Le tracé mérite une remarque, parce qu'il porte à lui seul ce que six poses portaient avant :
 * rien n'est reconnu. On ouvre un tracé et on le laisse suivre la main, et c'est **le système**
 * qui l'interprète — en défilement, en balayage, en glisser-déposer — exactement comme il le fait
 * pour un doigt posé sur la vitre. Il n'y a donc ni geste de défilement, ni geste de balayage à
 * distinguer : il y a un doigt, et Android sait déjà quoi faire d'un doigt.
 *
 * ## L'arbitrage se fait au déplacement, jamais à la vitesse
 *
 * C'est le point sur lequel une tentative précédente a échoué, et il vaut d'être explicite. Un
 * défilement avait été distingué d'un déplacement de curseur par sa **vitesse** : assez loin,
 * assez vite. Le critère est intenable, parce qu'une traversée d'écran rapide satisfait « assez
 * loin et assez vite » sur n'importe lequel de ses segments ; le geste partait tout seul, et le
 * tenir correctement fatiguait.
 *
 * Ici les trois issues se départagent sur deux grandeurs sans ambiguïté :
 *
 *  - un **déplacement** depuis le point d'enfoncement ([Config.touchSlop]) : au-delà, c'est un
 *    tracé, parce qu'on ne déplace pas la main d'un centimètre en voulant appuyer ;
 *  - une **durée** d'immobilité ([Config.longPressMs]) : au-delà, c'est un appui long.
 *
 * Ce sont les deux mêmes grandeurs qu'Android applique à un doigt sur l'écran, pour la même
 * raison : elles sont monotones et ne dépendent d'aucune dérivée du signal.
 *
 * Kotlin pur, sans dépendance Android : entièrement testable en JVM.
 */
class Pointer(
    private val config: Config = Config(),
    private val detector: PinchDetector = PinchDetector()
) {

    data class Config(
        /**
         * Déplacement, en fraction de diagonale d'écran, au-delà duquel un appui devient un tracé.
         *
         * Le réglage est un compromis assumé : trop petit, le tremblement de la main tendue
         * transformerait chaque appui en micro-glisser ; trop grand, un défilement court ne
         * partirait pas. La valeur correspond à environ un demi-centimètre sur un téléphone, soit
         * le double de l'amplitude du tremblement physiologique mesuré après filtrage.
         */
        val touchSlop: Float = 0.022f,

        /**
         * Durée d'immobilité, bouton enfoncé, au-delà de laquelle l'appui devient un appui long.
         *
         * Plus long que le délai d'Android (500 ms) d'une marge délibérée : l'appui est ici tenu
         * en l'air, donc moins stable, et un appui long parti par accident est beaucoup plus
         * gênant qu'un appui long qui tarde.
         */
        val longPressMs: Long = 550L,

        /**
         * Perte de main tolérée avant remise à zéro. Un trou d'une ou deux images — la main passe
         * devant une source de lumière, le suivi décroche — ne doit pas interrompre un geste.
         */
        val handLossGraceMs: Long = 250L,

        /** En deçà de cette plausibilité, la détection est traitée comme une absence de main. */
        val minQuality: Float = 0.35f,

        /**
         * Clic par maintien : garder le curseur immobile sur une cible déclenche un appui.
         *
         * ## Pourquoi un second chemin vers le même appui
         *
         * Le pincement a échoué sur l'appareil trois fois de suite, et la cause s'est révélée
         * structurelle : quand le pouce touche l'index, les deux extrémités se confondent dans
         * l'image et le modèle conserve entre elles une distance plancher. Le seuil de contact
         * pouvait donc se trouver **hors d'atteinte**, et aucun soin mis à pincer n'y changeait
         * rien. Ce défaut précis est corrigé dans [com.example.gesture.PinchDetector], mais la
         * leçon dépasse le correctif : **tout appui qui repose sur l'articulation des doigts hérite
         * de la fiabilité avec laquelle la caméra voit ces doigts**, et cette fiabilité n'est pas
         * garantie.
         *
         * Le maintien ne demande aucune articulation. Il ne demande qu'une position, c'est-à-dire
         * la grandeur la mieux mesurée de toute la chaîne — celle qui marchait déjà quand rien
         * d'autre ne marchait. C'est le mécanisme de tous les suivis oculaires et céphaliques, pour
         * cette raison exactement : il ne peut pas être bloqué par ce qui bloque un geste.
         *
         * Il ne remplace pas le pincement, qui reste plus rapide et plus intentionnel. Il garantit
         * que l'appareil reste utilisable quand le pincement ne part pas.
         */
        val dwellEnabled: Boolean = true,

        /**
         * Rayon dans lequel le curseur doit rester pour que le maintien se poursuive.
         *
         * Plus large que [touchSlop] : viser puis attendre demande de tenir la main en l'air plus
         * longtemps qu'un appui, et le tremblement s'y accumule. Un rayon trop serré rendrait
         * l'attente interminable, puisque le moindre frémissement la relancerait.
         */
        val dwellRadius: Float = 0.030f,

        /**
         * Durée d'immobilité qui déclenche l'appui.
         *
         * Plus long que l'appui long d'Android, délibérément : c'est un appui qu'on ne demande pas
         * explicitement, donc il doit laisser le temps de l'éviter. L'anneau de progression le rend
         * visible, de sorte que bouger la main l'annule avant la fin.
         */
        val dwellMs: Long = 900L,

        /**
         * Déplacement à parcourir avant qu'un nouveau maintien puisse déclencher.
         *
         * C'est ce qui empêche la répétition, et c'est indispensable : sans cela, une main posée
         * sur une cible produirait un appui par seconde indéfiniment. Il faut donc avoir
         * **quitté** la cible pour pouvoir en viser une autre — ce qui correspond exactement à
         * l'usage, et rend la rafale impossible.
         */
        val dwellRearmTravel: Float = 0.07f
    )

    /** Où en est le geste courant. */
    enum class Phase {
        /** Aucune main exploitable. */
        IDLE,

        /** Main présente, bouton relâché : le curseur survole. */
        HOVER,

        /** Bouton enfoncé, sens encore indécis : appui, appui long ou tracé. */
        PENDING,

        /** Bouton enfoncé, déplacement franc : un tracé est en cours. */
        STROKE,

        /** Bouton encore enfoncé mais le geste a déjà rendu son verdict. */
        CONSUMED
    }

    var phase = Phase.IDLE
        private set

    private var pressedAt = 0L
    private var lastSeenAt = Long.MIN_VALUE

    /** Position courante du curseur, telle que la dernière image l'a donnée. */
    var cursorX = 0.5f
        private set
    var cursorY = 0.5f
        private set

    /** Centre de la paume de la dernière image : mesure du déplacement de la main. */
    var palmX = 0.5f
        private set
    var palmY = 0.5f
        private set

    /** Centre de la paume à l'instant de l'enfoncement, origine du déplacement mesuré. */
    private var palmAnchorX = 0.5f
    private var palmAnchorY = 0.5f

    /** Vrai tant que le pouce touche l'index. */
    val isPressed: Boolean get() = detector.isPressed

    /** Progression de la fermeture en cours, 0..1 : alimente l'anneau du curseur. */
    val pinchProgress: Float get() = detector.travelProgress

    /**
     * Progression de l'appui long, 0..1.
     *
     * Affichée autour du curseur, elle fait d'une attente subie une attente lisible : on voit
     * l'appui long venir, et relâcher avant la fin donne un appui simple.
     */
    var longPressProgress = 0f
        private set

    /** Progression du maintien en cours, 0..1. */
    var dwellProgress = 0f
        private set

    /**
     * La seule progression affichable à un instant donné.
     *
     * L'appui long ne court que bouton enfoncé, le maintien que bouton relâché : les deux ne
     * peuvent pas être en cours ensemble. Ils partagent donc le même anneau sans ambiguïté, ce qui
     * évite d'encombrer le curseur d'un troisième indicateur pour une information qui est, des deux
     * côtés, la même — un compte à rebours qu'un mouvement annule.
     */
    val holdProgress: Float get() = if (isPressed) longPressProgress else dwellProgress

    /** Vrai quand un maintien peut déclencher : la main a quitté le dernier point cliqué. */
    private var dwellArmed = false
    private var dwellAnchorX = 0.5f
    private var dwellAnchorY = 0.5f
    private var dwellSince = 0L
    private var lastDwellX = 0.5f
    private var lastDwellY = 0.5f

    fun reset() {
        phase = Phase.IDLE
        longPressProgress = 0f
        disarmDwell(cursorX, cursorY)
        lastSeenAt = Long.MIN_VALUE
        detector.reset()
    }

    /** Remet à zéro les compteurs de la session sans perdre la course apprise. */
    fun resetStatistics() = detector.resetStatistics()

    /** État du pincement pour l'affichage : course apprise, réussites, cause du dernier échec. */
    fun telemetry(): PinchTelemetry = PinchTelemetry.of(detector)

    /**
     * Traite une image.
     *
     * @param handPresent la caméra voit-elle une main exploitable
     * @param quality plausibilité de la détection, 0..1
     * @param x,y position du curseur en coordonnées écran normalisées, déjà filtrée
     * @param pinchRatio écart pouce-index normalisé par la taille de paume
     * @param nowMs horloge monotone
     * @param palmX,palmY centre de la paume, dans le même espace que le curseur. Sert à mesurer le
     *   déplacement de la main, que la fermeture des doigts ne perturbe pas.
     */
    fun update(
        handPresent: Boolean,
        quality: Float,
        x: Float,
        y: Float,
        pinchRatio: Float,
        nowMs: Long,
        palmX: Float = x,
        palmY: Float = y
    ): List<PointerEvent> {
        if (!handPresent || quality < config.minQuality) return onHandLost(nowMs)

        lastSeenAt = nowMs
        cursorX = x
        cursorY = y
        this.palmX = palmX
        this.palmY = palmY

        return when (detector.update(pinchRatio, x, y, nowMs)) {
            PinchDetector.Event.PRESS -> {
                pressedAt = nowMs
                phase = Phase.PENDING
                longPressProgress = 0f
                palmAnchorX = palmX
                palmAnchorY = palmY
                // Un pincement abouti désarme le maintien : sans cela, viser puis pincer
                // produirait les deux appuis, et le second sur une cible déjà quittée.
                disarmDwell(x, y)
                emptyList()
            }

            PinchDetector.Event.RELEASE -> onRelease()

            PinchDetector.Event.NONE -> onHeld(nowMs)
        }
    }

    /**
     * Main perdue.
     *
     * Un tracé en cours est **clos**, et non abandonné : un tracé laissé ouvert resterait un doigt
     * posé sur l'écran du point de vue du système, ce qui bloquerait l'application du dessous
     * jusqu'au chien de garde. Un appui en attente de verdict, lui, est simplement annulé — on ne
     * devine pas ce que l'utilisateur voulait d'un geste qu'on n'a pas vu finir.
     */
    private fun onHandLost(nowMs: Long): List<PointerEvent> {
        if (phase == Phase.IDLE) return emptyList()

        val withinGrace = lastSeenAt != Long.MIN_VALUE &&
            nowMs - lastSeenAt <= config.handLossGraceMs
        if (withinGrace) return emptyList()

        val closing =
            if (phase == Phase.STROKE) listOf(strokeEnd()) else emptyList()

        reset()
        return closing
    }

    /**
     * Le maintien : bouton relâché, curseur immobile, l'appui part tout seul.
     *
     * Trois conditions, et chacune écarte un défaut précis. Il faut être **armé**, c'est-à-dire
     * avoir quitté le dernier point cliqué — sinon une main posée produirait un appui par seconde.
     * Il faut rester dans le rayon — sinon ce serait un appui à l'aveugle pendant un déplacement.
     * Et il faut y rester la durée entière, visible dans l'anneau, donc annulable en bougeant.
     */
    private fun dwell(nowMs: Long): List<PointerEvent> {
        if (!config.dwellEnabled) {
            dwellProgress = 0f
            return emptyList()
        }

        if (!dwellArmed) {
            if (hypot(cursorX - lastDwellX, cursorY - lastDwellY) >= config.dwellRearmTravel) {
                dwellArmed = true
                dwellAnchorX = cursorX
                dwellAnchorY = cursorY
                dwellSince = nowMs
            }
            dwellProgress = 0f
            return emptyList()
        }

        // Le curseur a quitté le rayon : la visée recommence d'ici.
        if (hypot(cursorX - dwellAnchorX, cursorY - dwellAnchorY) > config.dwellRadius) {
            dwellAnchorX = cursorX
            dwellAnchorY = cursorY
            dwellSince = nowMs
            dwellProgress = 0f
            return emptyList()
        }

        val heldMs = nowMs - dwellSince
        dwellProgress = (heldMs.toFloat() / config.dwellMs).coerceIn(0f, 1f)
        if (heldMs < config.dwellMs) return emptyList()

        // L'appui porte sur l'ancre, et non sur la position courante : c'est l'endroit visé
        // pendant toute l'attente, donc celui que l'utilisateur a sous les yeux.
        val tap = PointerEvent.Tap(dwellAnchorX, dwellAnchorY)
        disarmDwell(dwellAnchorX, dwellAnchorY)
        return listOf(tap)
    }

    private fun disarmDwell(x: Float, y: Float) {
        dwellArmed = false
        dwellProgress = 0f
        lastDwellX = x
        lastDwellY = y
    }

    /** Bouton relâché : c'est ici que l'appui bref se décide, puisqu'il n'est rien d'autre. */
    private fun onRelease(): List<PointerEvent> {
        val events = when (phase) {
            Phase.PENDING -> listOf(PointerEvent.Tap(detector.anchorX, detector.anchorY))
            Phase.STROKE -> listOf(strokeEnd())
            // Appui long déjà rendu : le relâchement ne doit pas produire un appui de plus.
            else -> emptyList()
        }
        phase = Phase.HOVER
        longPressProgress = 0f
        return events
    }

    /**
     * Fin de tracé, lue avant toute remise à zéro : l'ancre du détecteur tient encore le point
     * d'enfoncement, puisqu'elle n'est réécrite qu'au pincement suivant.
     */
    private fun strokeEnd() =
        PointerEvent.StrokeEnd(detector.anchorX, detector.anchorY, cursorX, cursorY)

    /** Bouton enfoncé ou relâché sans changement d'état : reste à voir si le geste se décide. */
    private fun onHeld(nowMs: Long): List<PointerEvent> {
        if (!detector.isPressed) {
            phase = Phase.HOVER
            longPressProgress = 0f
            return dwell(nowMs)
        }
        dwellProgress = 0f

        // Déplacement mesuré sur la **paume**, et non sur le curseur : c'est la main qui décide si
        // le geste est un appui ou un tracé, et la paume en est la mesure fidèle. Le bout de
        // l'index, lui, bouge aussi quand le pouce vient s'y poser — ce qui rendait l'arbitrage
        // sensible au geste même qui déclenche l'appui.
        val travel = hypot(palmX - palmAnchorX, palmY - palmAnchorY)

        if (phase == Phase.STROKE) {
            return listOf(PointerEvent.StrokeMove(cursorX, cursorY, palmX, palmY))
        }

        if (phase == Phase.PENDING) {
            // Le déplacement l'emporte sur la durée : si la main part, c'est un tracé, même si le
            // délai de l'appui long est écoulé dans la même image.
            if (travel >= config.touchSlop) {
                phase = Phase.STROKE
                longPressProgress = 0f
                return listOf(
                    PointerEvent.StrokeBegin(
                        detector.anchorX, detector.anchorY, palmAnchorX, palmAnchorY
                    ),
                    PointerEvent.StrokeMove(cursorX, cursorY, palmX, palmY)
                )
            }

            val heldMs = nowMs - pressedAt
            longPressProgress = (heldMs.toFloat() / config.longPressMs).coerceIn(0f, 1f)
            if (heldMs >= config.longPressMs) {
                phase = Phase.CONSUMED
                longPressProgress = 1f
                return listOf(PointerEvent.LongPress(detector.anchorX, detector.anchorY))
            }
        }

        return emptyList()
    }
}
