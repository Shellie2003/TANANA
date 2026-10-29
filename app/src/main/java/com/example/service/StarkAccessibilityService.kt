package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.control.BindingsStore
import com.example.control.EdgeScroller
import com.example.control.ScrollTuning
import com.example.control.StrokeKinematics
import com.example.control.SystemAction
import com.example.control.TargetSnapper
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Exécute les actions système décidées par la politique gestuelle.
 *
 * C'est la seule voie par laquelle une application ordinaire peut agir en dehors d'elle-même :
 * `dispatchGesture` synthétise des appuis et des glissers n'importe où à l'écran, et
 * `performGlobalAction` déclenche Retour, Accueil et Applications récentes.
 *
 * Le service doit être activé manuellement par l'utilisateur dans les réglages d'accessibilité ;
 * aucune permission ne peut s'y substituer.
 */
class StarkAccessibilityService : AccessibilityService() {

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Aucun traitement : le service n'est utilisé que pour émettre des actions.
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Service d'accessibilité connecté")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Le service disparaît : un trait resté ouvert laisserait un appui pendant sur l'écran.
        abandonStroke()
        instance = null
    }

    // --- Exécution des actions ---------------------------------------------------------------

    /** Exécute une action. [width] et [height] sont les dimensions de l'écran en pixels. */
    private fun execute(action: SystemAction, width: Float, height: Float) {
        when (action) {
            is SystemAction.Tap -> tap(action.x * width, action.y * height)
            is SystemAction.LongPress -> {
                // Le maintien immobile s'est résolu en appui long : le trait diffusé, qui n'a
                // fait que rester sur place, est abandonné au profit d'un appui franc.
                strokeEnd()
                longPress(action.x * width, action.y * height)
            }
            // Le doigt se pose, suit la main, puis se lève. Si la diffusion continue est
            // indisponible — Android 7, ou refus du système — `streaming` reste faux et le
            // glisser complet émis au relâchement prend le relais.
            is SystemAction.DragStart -> {
                pendingRecenter = null
                kinematics = StrokeKinematics(scrollTuning().toConfig())
                val area = findScrollableArea(action.x, action.y, width, height)
                val start = kinematics.begin(action.x, action.y, area, action.driveX, action.driveY)
                strokeBegin(start.x * width, start.y * height)
            }

            is SystemAction.ScrollStep ->
                scrollStep(action.x, action.y, action.direction, width, height)

            is SystemAction.DragMove ->
                advanceStroke(action.x, action.y, action.driveX, action.driveY, width, height)

            is SystemAction.Drag -> {
                pendingRecenter = null
                if (streaming) {
                    // Dégagement éventuel avant la levée : un tracé qui n'a presque pas bougé
                    // depuis sa pose serait lu comme un appui, et ouvrirait l'élément sous le
                    // doigt au lieu de défiler.
                    kinematics.endAdjustment()?.let { strokeMoveTo(it.x * width, it.y * height) }
                    strokeEnd()
                } else {
                    drag(
                        action.fromX * width, action.fromY * height,
                        action.toX * width, action.toY * height
                    )
                }
                kinematics.reset()
            }

            SystemAction.Back -> global(GLOBAL_ACTION_BACK)
            SystemAction.Home -> global(GLOBAL_ACTION_HOME)
            SystemAction.Recents -> global(GLOBAL_ACTION_RECENTS)
            SystemAction.Notifications -> global(GLOBAL_ACTION_NOTIFICATIONS)

            // Les réglages rapides donnent accès aux données mobiles, au Wi-Fi et à la lampe :
            // sans cette action, une part entière du téléphone restait hors d'atteinte.
            SystemAction.QuickSettings -> global(GLOBAL_ACTION_QUICK_SETTINGS)

            SystemAction.PowerDialog -> global(GLOBAL_ACTION_POWER_DIALOG)
            SystemAction.SplitScreen -> global(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)

            SystemAction.Screenshot ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    global(GLOBAL_ACTION_TAKE_SCREENSHOT)
                } else {
                    Log.i(TAG, "Capture d'écran indisponible avant Android 11")
                }

            SystemAction.LockScreen ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    global(GLOBAL_ACTION_LOCK_SCREEN)
                } else {
                    Log.i(TAG, "Verrouillage indisponible avant Android 9")
                }

            SystemAction.PlayPause -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            SystemAction.MediaNext -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            SystemAction.MediaPrevious -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

            SystemAction.VolumeUp -> adjustVolume(AudioManager.ADJUST_RAISE)
            SystemAction.VolumeDown -> adjustVolume(AudioManager.ADJUST_LOWER)
            SystemAction.VolumeMute -> adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE)

            is SystemAction.TypeText -> typeText(action.text)
            SystemAction.Backspace -> backspace()

            SystemAction.SwitchNext -> quickSwitch(forward = true, width = width, height = height)
            SystemAction.SwitchPrevious -> quickSwitch(forward = false, width = width, height = height)

            // Traitées ailleurs : ce sont des modes locaux au contrôle gestuel, qui ne demandent
            // rien au système. Le service de vision les intercepte avant d'arriver ici.
            SystemAction.ToggleKeyboard,
            SystemAction.ToggleScrollRate,
            SystemAction.None -> Unit
        }
    }

    // --- Saisie de texte ---------------------------------------------------------------------

    /**
     * Trouve le champ de saisie actif et y insère du texte.
     *
     * `ACTION_SET_TEXT` remplace intégralement le contenu : on relit donc l'existant et on
     * réécrit la concaténation. C'est la seule voie disponible sans écrire un clavier système
     * complet, et elle suppose de pouvoir lire le contenu de la fenêtre.
     */
    private fun typeText(text: String) {
        val node = findFocusedEditable() ?: run {
            Log.i(TAG, "Aucun champ de saisie actif")
            return
        }
        val current = node.text?.toString() ?: ""
        setText(node, current + text)
        node.recycle()
    }

    private fun backspace() {
        val node = findFocusedEditable() ?: return
        val current = node.text?.toString() ?: ""
        if (current.isNotEmpty()) setText(node, current.dropLast(1))
        node.recycle()
    }

    private fun setText(node: AccessibilityNodeInfo, value: String) {
        val arguments = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) }
            .onFailure { Log.w(TAG, "Écriture refusée", it) }
    }

    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        val focused = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        return if (focused?.isEditable == true) focused else null
    }

    private fun global(action: Int) {
        runCatching { performGlobalAction(action) }
            .onFailure { Log.w(TAG, "Action globale $action refusée", it) }
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatch(path, durationMs = TAP_DURATION_MS)
    }

    /** L'appui long se distingue du bref par la seule durée du contact. */
    private fun longPress(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatch(path, durationMs = LONG_PRESS_MS)
    }

    private fun mediaKey(keyCode: Int) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val now = android.os.SystemClock.uptimeMillis()
        runCatching {
            audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        }.onFailure { Log.w(TAG, "Commande média refusée", it) }
    }

    private fun adjustVolume(direction: Int) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                direction,
                AudioManager.FLAG_SHOW_UI
            )
        }.onFailure { Log.w(TAG, "Réglage du volume refusé", it) }
    }

    private fun drag(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        // Durée proportionnelle à la distance : un glisser instantané est interprété comme un
        // lancer (fling) par la plupart des vues, ce qui n'est presque jamais l'intention.
        val distance = hypot(toX - fromX, toY - fromY)
        val duration = (distance / 2f).toLong().coerceIn(MIN_DRAG_MS, MAX_DRAG_MS)
        dispatch(path, duration)
    }

    /**
     * Commutation rapide entre applications.
     *
     * Sur un appareil en navigation gestuelle, un balayage horizontal le long du bord inférieur
     * fait défiler les applications récentes sans passer par leur écran de sélection : c'est le
     * geste le plus fluide, et le seul qu'une application puisse synthétiser. En navigation par
     * boutons ce geste n'existe pas ; l'écran des récents sert alors de repli.
     */
    private fun quickSwitch(forward: Boolean, width: Float, height: Float) {
        val y = height - EDGE_MARGIN_PX
        val from = if (forward) width * 0.25f else width * 0.75f
        val to = if (forward) width * 0.85f else width * 0.15f

        val path = Path().apply {
            moveTo(from, y)
            lineTo(to, y)
        }
        val dispatched = dispatch(path, QUICK_SWITCH_MS)
        if (!dispatched) global(GLOBAL_ACTION_RECENTS)
    }

    /**
     * Rectangles des éléments cliquables de la fenêtre au premier plan, en pixels.
     *
     * Le parcours est borné en profondeur et en nombre de nœuds. Une hiérarchie de vues peut être
     * profonde et large — une liste longue en compte des centaines — et ce parcours a lieu au
     * moment du clic, sur le thread principal : une exploration exhaustive s'y verrait.
     *
     * Le parcours est itératif plutôt que récursif : la profondeur d'une hiérarchie de vues n'est
     * pas bornée par construction, et une pile d'appels ne doit pas dépendre de ce qu'affiche
     * l'application au premier plan.
     */
    /**
     * Cibles interactives au **voisinage** d'un point.
     *
     * ## Pourquoi un voisinage, et non l'écran entier
     *
     * Cette méthode énumérait toutes les cibles cliquables de l'écran — jusqu'à 400 nœuds visités —
     * pour que l'appelant n'en retienne ensuite que celles situées dans un rayon de 6 % de l'écran.
     * Tout le reste du parcours était jeté.
     *
     * Ce n'était pas qu'un gaspillage. Chaque nœud d'accessibilité se lit par un **appel
     * interprocessus**, et le parcours a lieu sur le **thread principal**, à l'instant précis où
     * l'utilisateur appuie. Sur une liste longue, la somme de ces appels est une saccade visible,
     * déclenchée par le geste lui-même — exactement le « ça rame » le plus exaspérant, puisqu'il
     * frappe au moment où l'on attend une réponse.
     *
     * L'élagage est géométrique et sûr : on ne descend que dans les nœuds dont les limites
     * rencontrent la zone cherchée. Les limites d'un enfant sont contenues dans celles de son
     * parent, aux débordements près — d'où la marge appliquée à la zone de test, qui évite de
     * perdre une cible dont le parent est rapporté légèrement trop petit.
     */
    private fun collectTargets(
        centerX: Float,
        centerY: Float,
        radius: Float
    ): List<TargetSnapper.Target> {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return emptyList()

        // Zone de recherche, élargie d'une marge pour absorber les limites approximatives.
        val margin = radius * PRUNE_MARGIN
        val searchLeft = centerX - radius - margin
        val searchTop = centerY - radius - margin
        val searchRight = centerX + radius + margin
        val searchBottom = centerY + radius + margin

        val targets = ArrayList<TargetSnapper.Target>(MAX_TARGETS)
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.addLast(root to 0)
        var visited = 0
        val bounds = android.graphics.Rect()

        while (queue.isNotEmpty() && visited < MAX_NODES && targets.size < MAX_TARGETS) {
            val (node, depth) = queue.removeFirst()
            visited++

            node.getBoundsInScreen(bounds)
            // Hors de la zone cherchée : ni le nœud ni sa descendance ne peuvent y contribuer.
            if (bounds.right < searchLeft || bounds.left > searchRight ||
                bounds.bottom < searchTop || bounds.top > searchBottom
            ) {
                continue
            }

            if (node.isClickable && node.isVisibleToUser && node.isEnabled) {
                targets += TargetSnapper.Target(
                    bounds.left.toFloat(), bounds.top.toFloat(),
                    bounds.right.toFloat(), bounds.bottom.toFloat()
                )
            }

            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                    queue.addLast(child to depth + 1)
                }
            }
        }

        return targets
    }

    // --- Geste continu ---------------------------------------------------------------------------

    /**
     * Trait en cours, quand le « doigt » est posé et suit la main.
     *
     * ## Pourquoi diffuser le geste plutôt que le synthétiser à la fin
     *
     * Le glisser était émis d'un bloc au relâchement : la main bougeait sans que rien ne se passe,
     * puis la page sautait d'un coup. Cela suffisait à déplacer une icône, mais ne pouvait pas
     * servir à faire défiler — sans retour pendant le mouvement, on ne sait pas où l'on s'arrête —
     * et perdait l'inertie, qui est ce qui rend le défilement tactile agréable.
     *
     * En diffusant le trait segment par segment, le pointeur se comporte comme un doigt posé sur
     * la vitre. **Rien n'a plus besoin d'être reconnu** : défilement, inertie, glisser-déposer,
     * balayage pour écarter une notification, tirer le volet — tout cela est interprété par le
     * système, avec sa propre physique, sans qu'aucun geste ne soit ajouté au vocabulaire.
     *
     * ## Contraintes, et ce qu'elles imposent
     *
     * `continueStroke` exige Android 8, et un segment ne peut être envoyé qu'une fois le précédent
     * terminé — d'où la pompe pilotée par le rappel de fin plutôt qu'un envoi par image.
     *
     * La levée du doigt doit être garantie. Un trait laissé ouvert immobiliserait l'interface sous
     * un appui permanent, ce qui est de loin le pire résultat possible ici. La demande de fin est
     * donc mémorisée et honorée dès que la voie est libre, jamais abandonnée parce qu'un segment
     * était en vol ; et un chien de garde ferme le trait si les rappels cessent d'arriver.
     *
     * Si la diffusion est indisponible ou refusée, [streaming] reste faux et l'appelant retombe
     * sur le glisser synthétisé au relâchement, qui reste correct pour déplacer un élément.
     */
    /**
     * Cinématique du tracé, reconstruite au début de chaque geste pour prendre en compte la
     * vivacité choisie par l'utilisateur.
     *
     * Ce service est distinct de celui qui lit les préférences au démarrage, et il ne redémarre pas
     * quand l'autre le fait : relire au début du geste est donc la seule façon qu'un changement de
     * réglage prenne effet sans redémarrer le téléphone. La lecture est servie par le cache mémoire
     * des préférences partagées, donc sans accès disque.
     */
    private var kinematics = StrokeKinematics()
    private var bindingsStore: BindingsStore? = null

    /**
     * Repositionnement en attente, en coordonnées écran normalisées.
     *
     * Il est **étalé sur deux images**, et ce n'est pas un détail d'implémentation : enchaîner
     * `strokeEnd()` et `strokeBegin()` dans la même image ferait appeler `abandonStroke()` avant
     * que la levée n'ait été envoyée, et le doigt resterait posé sur l'écran — précisément le pire
     * défaut possible ici. On attend donc que [streaming] retombe, preuve que la levée est partie.
     */
    private var pendingRecenter: Pair<Float, Float>? = null

    private var activeStroke: GestureDescription.StrokeDescription? = null
    private var strokeX = 0f
    private var strokeY = 0f
    private var pendingX = Float.NaN
    private var pendingY = Float.NaN
    private var dispatching = false
    private var endRequested = false

    /** Vrai tant qu'un trait continu est réellement en cours. */
    private var streaming = false

    private val strokeWatchdog = Runnable {
        if (streaming) {
            Log.w(TAG, "Trait continu sans rappel de fin : levée forcée")
            abandonStroke()
        }
    }

    /**
     * Avance le doigt d'une image, en déléguant la cinématique à [StrokeKinematics].
     *
     * Sur une zone défilable, le doigt est amplifié et confiné : quand il atteint le bord, il est
     * relevé puis reposé de l'autre côté. C'est ce qui lui évite de se figer — et donc ce qui rend
     * son élan à Android au relâchement. Partout ailleurs, le rapport reste 1:1.
     */
    private fun advanceStroke(
        xNorm: Float,
        yNorm: Float,
        driveX: Float,
        driveY: Float,
        width: Float,
        height: Float
    ) {
        pendingRecenter?.let { (rx, ry) ->
            // La levée précédente n'est pas encore partie : on laisse passer cette image.
            if (streaming) return
            pendingRecenter = null
            strokeBegin(rx * width, ry * height)
        }

        val advance = kinematics.move(xNorm, yNorm, driveX, driveY)
        if (advance.recenter) {
            if (!streaming) return
            strokeEnd()
            pendingRecenter = advance.x to advance.y
            return
        }
        strokeMoveTo(advance.x * width, advance.y * height)
    }

    /**
     * Zone défilable sous un point, ou null s'il n'y en a aucune d'exploitable.
     *
     * C'est **le système qui répond**, et non un geste de l'utilisateur : on lui demande ce qui se
     * trouve sous le doigt. Quand il ne déclare rien — l'application n'expose pas ses zones
     * défilables — on retombe sur le rapport 1:1, c'est-à-dire sur le comportement précédent.
     * Jamais pire, donc, et aucun mode à apprendre.
     *
     * Les axes viennent des actions que le nœud déclare. À défaut, ils sont déduits de sa forme :
     * laisser passer les deux axes serait risqué, puisqu'un déplacement horizontal sur une liste
     * verticale y écarte souvent l'élément sous le doigt.
     */
    private fun findScrollableArea(
        xNorm: Float,
        yNorm: Float,
        width: Float,
        height: Float
    ): StrokeKinematics.Area? {
        if (width <= 0f || height <= 0f) return null
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return null

        val x = xNorm * width
        val y = yNorm * height
        val bounds = android.graphics.Rect()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.addLast(root to 0)
        var visited = 0
        var best: StrokeKinematics.Area? = null

        // Parcours en largeur : la profondeur croît, donc la dernière correspondance trouvée est
        // la plus imbriquée — une liste dans une page, et non la page entière.
        //
        // Et **on ne descend que dans ce qui contient le point** : la zone cherchée est un point
        // unique, donc tout nœud qui ne le contient pas a une descendance qui ne le contient pas
        // non plus. L'élagage ramène un parcours de plusieurs centaines de nœuds — chacun un appel
        // interprocessus, sur le thread principal, au début du geste — à la profondeur de la
        // hiérarchie.
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            visited++

            node.getBoundsInScreen(bounds)
            val containsPoint =
                x >= bounds.left && x <= bounds.right && y >= bounds.top && y <= bounds.bottom
            if (!containsPoint) continue

            if (node.isScrollable && node.isVisibleToUser) {
                val w = (bounds.right - bounds.left).toFloat()
                val h = (bounds.bottom - bounds.top).toFloat()
                // Une zone minuscule ne laisserait pas de course utile : le doigt y serait
                // repositionné à chaque image, ce qui hacherait le défilement.
                if (w >= width * MIN_SCROLL_AREA && h >= height * MIN_SCROLL_AREA) {
                    best = StrokeKinematics.Area(
                        left = bounds.left / width,
                        top = bounds.top / height,
                        right = bounds.right / width,
                        bottom = bounds.bottom / height,
                        horizontal = scrollsHorizontally(node, w, h),
                        vertical = scrollsVertically(node, w, h)
                    )
                }
            }

            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                    queue.addLast(child to depth + 1)
                }
            }
        }

        return best?.takeIf { it.horizontal || it.vertical }
    }

    /**
     * Le nœud défilable le plus imbriqué contenant le point visé.
     *
     * Même parcours élagué que [findScrollableArea], mais sans la contrainte de taille minimale :
     * celle-ci existe pour qu'un tracé synthétique ait de la course, et un pas de défilement n'a
     * besoin d'aucune course — il s'adresse directement au nœud. Une petite liste imbriquée est
     * donc défilable par les bords alors qu'elle ne l'était pas au pincement.
     */
    private fun findScrollableNode(
        xNorm: Float,
        yNorm: Float,
        width: Float,
        height: Float
    ): AccessibilityNodeInfo? {
        if (width <= 0f || height <= 0f) return null
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return null

        val x = xNorm * width
        val y = yNorm * height
        val bounds = android.graphics.Rect()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.addLast(root to 0)
        var visited = 0
        var best: AccessibilityNodeInfo? = null

        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            visited++

            node.getBoundsInScreen(bounds)
            val containsPoint =
                x >= bounds.left && x <= bounds.right && y >= bounds.top && y <= bounds.bottom
            if (!containsPoint) continue

            if (node.isScrollable && node.isVisibleToUser) best = node

            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                    queue.addLast(child to depth + 1)
                }
            }
        }

        return best
    }

    /**
     * Demande un pas de défilement au nœud défilable sous le point visé.
     *
     * Rien n'est synthétisé : on appelle l'action que le nœud déclare. C'est ce qui rend ce chemin
     * insensible au bruit de suivi — aucune trajectoire n'est transmise — et cohérent avec le
     * contenu, puisque le pas est celui que l'application a choisi pour elle-même.
     *
     * Les actions directionnelles (`ACTION_SCROLL_UP` et compagnie) n'existent qu'à partir
     * d'Android 7.1. En dessous, `ACTION_SCROLL_FORWARD` et `ACTION_SCROLL_BACKWARD` prennent le
     * relais : elles ne savent pas l'axe, mais le nœud, lui, le sait — c'est son orientation
     * propre qui décide, et elle est presque toujours celle qu'on attend.
     */
    private fun scrollStep(
        x: Float,
        y: Float,
        direction: EdgeScroller.Direction,
        width: Float,
        height: Float
    ) {
        val node = findScrollableNode(x, y, width, height) ?: return
        val directional = when (direction) {
            EdgeScroller.Direction.UP ->
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
            EdgeScroller.Direction.DOWN ->
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
            EdgeScroller.Direction.LEFT ->
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT
            EdgeScroller.Direction.RIGHT ->
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT
        }

        val actions = runCatching { node.actionList }.getOrNull().orEmpty()
        val performed = if (directional in actions) {
            runCatching { node.performAction(directional.id) }.getOrDefault(false)
        } else {
            false
        }
        if (performed) return

        // Repli sur les actions sans axe. « Avant » signifie vers le bas ou vers la droite, ce qui
        // correspond au sens de lecture : c'est la convention d'Android, pas un choix d'ici.
        val fallback = when (direction) {
            EdgeScroller.Direction.DOWN, EdgeScroller.Direction.RIGHT ->
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            EdgeScroller.Direction.UP, EdgeScroller.Direction.LEFT ->
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        runCatching { node.performAction(fallback) }
    }

    private fun scrollsVertically(node: AccessibilityNodeInfo, w: Float, h: Float): Boolean {
        val actions = runCatching { node.actionList }.getOrNull().orEmpty()
        val up = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
        val down = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
        if (up in actions || down in actions) return true
        if (hasDirectionalScroll(actions)) return false
        // Aucun axe déclaré : déduit de la forme, le cas le plus fréquent étant une liste haute.
        return h >= w
    }

    private fun scrollsHorizontally(node: AccessibilityNodeInfo, w: Float, h: Float): Boolean {
        val actions = runCatching { node.actionList }.getOrNull().orEmpty()
        val left = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT
        val right = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT
        if (left in actions || right in actions) return true
        if (hasDirectionalScroll(actions)) return false
        return w > h
    }

    private fun hasDirectionalScroll(
        actions: List<AccessibilityNodeInfo.AccessibilityAction>
    ): Boolean = actions.any {
        it == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP ||
            it == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN ||
            it == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT ||
            it == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT
    }

    private fun scrollTuning(): ScrollTuning {
        val store = bindingsStore ?: BindingsStore(this).also { bindingsStore = it }
        return runCatching { store.loadScrollTuning() }.getOrDefault(ScrollTuning.DEFAULT)
    }

    private fun strokeBegin(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        abandonStroke()

        strokeX = x
        strokeY = y
        pendingX = Float.NaN
        pendingY = Float.NaN
        endRequested = false

        // Un trait de longueur nulle est refusé : on pose le doigt sur un segment imperceptible.
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, STROKE_SEGMENT_MS, true)
        streaming = true
        if (!send(stroke)) {
            streaming = false
            return
        }
        armWatchdog()
    }

    private fun strokeMoveTo(x: Float, y: Float) {
        if (!streaming) return
        pendingX = x
        pendingY = y
        armWatchdog()
        pumpStroke()
    }

    /** Demande la levée du doigt. Elle est honorée dès que la voie est libre. */
    private fun strokeEnd() {
        if (!streaming) return
        endRequested = true
        pumpStroke()
    }

    /** Abandonne le trait sans chercher à le clore proprement : dernier recours. */
    private fun abandonStroke() {
        pendingRecenter = null
        mainHandler.removeCallbacks(strokeWatchdog)
        streaming = false
        endRequested = false
        dispatching = false
        activeStroke = null
        pendingX = Float.NaN
        pendingY = Float.NaN
    }

    private fun armWatchdog() {
        mainHandler.removeCallbacks(strokeWatchdog)
        mainHandler.postDelayed(strokeWatchdog, STROKE_WATCHDOG_MS)
    }

    /**
     * Envoie le segment suivant, s'il y en a un et si le précédent est terminé.
     *
     * Une demande de fin est prioritaire sur un déplacement en attente : lever le doigt ne peut
     * jamais être différé par un mouvement.
     */
    private fun pumpStroke() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!streaming || dispatching) return
        val previous = activeStroke ?: return

        val ending = endRequested
        if (!ending && pendingX.isNaN()) return

        // Cible du segment : la position en attente, ou la position courante décalée d'un pixel
        // quand il s'agit seulement de lever le doigt — un segment de longueur nulle est refusé.
        var targetX = if (pendingX.isNaN()) strokeX + 1f else pendingX
        var targetY = if (pendingY.isNaN()) strokeY else pendingY
        pendingX = Float.NaN
        pendingY = Float.NaN

        if (abs(targetX - strokeX) < 0.5f && abs(targetY - strokeY) < 0.5f) {
            if (!ending) return
            targetX = strokeX + 1f
            targetY = strokeY
        }

        val path = Path().apply {
            moveTo(strokeX, strokeY)
            lineTo(targetX, targetY)
        }
        val stroke = runCatching {
            previous.continueStroke(path, 0L, STROKE_SEGMENT_MS, !ending)
        }.getOrNull() ?: run {
            abandonStroke()
            return
        }

        strokeX = targetX
        strokeY = targetY
        if (ending) {
            endRequested = false
            streaming = false
            mainHandler.removeCallbacks(strokeWatchdog)
        }
        if (!send(stroke) && !ending) abandonStroke()
    }

    private fun send(stroke: GestureDescription.StrokeDescription): Boolean {
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val callback = object : GestureResultCallback() {
            override fun onCompleted(description: GestureDescription?) {
                dispatching = false
                activeStroke = stroke
                pumpStroke()
            }

            override fun onCancelled(description: GestureDescription?) {
                dispatching = false
                abandonStroke()
            }
        }

        dispatching = true
        activeStroke = stroke
        val accepted = runCatching { dispatchGesture(gesture, callback, mainHandler) }
            .onFailure { Log.w(TAG, "Trait continu refusé", it) }
            .getOrDefault(false)
        if (!accepted) dispatching = false
        return accepted
    }

    private fun dispatch(path: Path, durationMs: Long): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return runCatching { dispatchGesture(gesture, null, null) }
            .onFailure { Log.w(TAG, "Geste refusé", it) }
            .getOrDefault(false)
    }

    companion object {
        private const val TAG = "StarkA11yService"

        private const val TAP_DURATION_MS = 60L
        private const val LONG_PRESS_MS = 700L
        private const val MIN_DRAG_MS = 120L
        private const val MAX_DRAG_MS = 600L
        private const val QUICK_SWITCH_MS = 220L

        /**
         * Durée annoncée pour un segment de trait continu. Légèrement supérieure à l'intervalle
         * entre deux images de la caméra : un segment plus court se terminerait avant l'arrivée
         * du suivant, et le doigt serait perçu comme immobile entre les deux.
         */
        private const val STROKE_SEGMENT_MS = 40L

        /**
         * Délai au-delà duquel un trait sans nouvelle est fermé d'autorité. Un doigt resté posé
         * immobiliserait l'interface : mieux vaut interrompre un geste légitime que laisser
         * l'écran sous un appui permanent.
         */
        private const val STROKE_WATCHDOG_MS = 2000L

        /** Distance au bord inférieur pour la commutation rapide, en pixels. */
        private const val EDGE_MARGIN_PX = 2f

        // Bornes du parcours de l'arbre d'accessibilité. Il a lieu au moment du clic, sur le
        // thread principal : une exploration exhaustive d'une liste longue s'y verrait.
        private const val MAX_NODES = 400
        private const val MAX_DEPTH = 24
        private const val MAX_TARGETS = 120

        /**
         * Taille minimale d'une zone défilable, en fraction d'écran, pour mériter l'amplification.
         * En deçà, la course disponible est si courte que le doigt y serait repositionné sans
         * cesse, et le défilement en serait haché.
         */
        private const val MIN_SCROLL_AREA = 0.25f

        /**
         * Marge, en fraction du rayon, dont la zone de recherche est élargie avant l'élagage.
         *
         * Les limites d'un enfant sont contenues dans celles de son parent, aux débordements près :
         * un conteneur dont les limites sont rapportées légèrement trop petites ferait perdre ses
         * enfants. La marge rend l'élagage sûr au prix de quelques nœuds de plus.
         */
        private const val PRUNE_MARGIN = 0.5f

        @Volatile
        var instance: StarkAccessibilityService? = null
            private set

        /** Vrai si l'utilisateur a activé le service dans les réglages d'accessibilité. */
        val isConnected: Boolean get() = instance != null

        /**
         * Exécute une action système. Retourne false si le service n'est pas activé, ce qui
         * permet à l'appelant de proposer l'activation plutôt que d'échouer en silence.
         */
        fun perform(action: SystemAction, screenWidth: Float, screenHeight: Float): Boolean {
            val service = instance ?: return false
            service.execute(action, screenWidth, screenHeight)
            return true
        }

        /** Raccourci pour les actions globales sans géométrie. */
        fun perform(action: SystemAction): Boolean = perform(action, 1f, 1f)

        /**
         * Aimantation d'un appui, en coordonnées écran normalisées, ou null s'il n'y a aucune
         * cible assez proche — l'appui doit alors partir exactement là où l'utilisateur a pointé.
         *
         * Retourne le rectangle **et** le point d'application, qui sont distincts : le rectangle
         * sert à *montrer* ce qui a été touché, le point à l'atteindre. Une aimantation invisible
         * se comporterait comme un curseur qui ment, et détruirait la confiance qu'elle est censée
         * créer. Et quand l'appui était déjà dans la cible, le point reste celui de l'utilisateur.
         */
        fun snapTarget(
            xNorm: Float,
            yNorm: Float,
            screenWidth: Float,
            screenHeight: Float
        ): TargetSnapper.Snap? {
            val service = instance ?: return null
            if (screenWidth <= 0f || screenHeight <= 0f) return null

            val radius = TargetSnapper.defaultRadius(screenWidth, screenHeight)
            val px = xNorm * screenWidth
            val py = yNorm * screenHeight

            // Seules les cibles du voisinage sont collectées : l'aimantation n'en retient de toute
            // façon aucune au-delà du rayon, et le parcours de l'arbre a lieu sur le thread
            // principal, au moment de l'appui.
            val targets = TargetSnapper.usable(
                service.collectTargets(px, py, radius), screenWidth, screenHeight
            )
            val snapped = TargetSnapper.snap(
                x = px,
                y = py,
                targets = targets,
                radius = radius
            ) ?: return null

            return TargetSnapper.Snap(
                target = TargetSnapper.Target(
                    left = snapped.target.left / screenWidth,
                    top = snapped.target.top / screenHeight,
                    right = snapped.target.right / screenWidth,
                    bottom = snapped.target.bottom / screenHeight
                ),
                x = snapped.x / screenWidth,
                y = snapped.y / screenHeight,
                moved = snapped.moved
            )
        }
    }
}
