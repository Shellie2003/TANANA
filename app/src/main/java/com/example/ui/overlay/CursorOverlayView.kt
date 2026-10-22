package com.example.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import com.example.control.TargetSnapper
import com.example.input.CommandPanel
import com.example.input.RadialKeyboard
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Curseur dessiné par-dessus toutes les applications, et les deux menus qui l'accompagnent.
 *
 * Le curseur ne porte plus qu'une information de mode, parce qu'il n'y en a plus qu'une : le
 * bouton est enfoncé, ou il ne l'est pas. Il changeait de couleur selon la forme de la main — une
 * couleur par pose — et c'était le symptôme visible du défaut de fond : il fallait un code couleur
 * pour savoir dans quel mode on se trouvait, puisque la main pouvait basculer de mode sans qu'on
 * l'ait voulu. Avec un seul geste, il n'y a plus de mode à signaler.
 *
 * Volontairement écrit en View native plutôt qu'en Compose : cette vue est redessinée à la
 * cadence de la caméra au-dessus de l'application au premier plan, et elle doit rester la plus
 * légère possible pour ne pas voler d'images à celle-ci. Un `invalidate()` sur une View qui ne
 * fait que tracer quelques primitives coûte beaucoup moins qu'un arbre de composition.
 *
 * Trois anneaux restent, et chacun répond à une question que l'utilisateur ne peut pas résoudre
 * autrement : où vais-je appuyer (le réticule), mon pincement est-il en train d'aboutir (l'anneau
 * doré), et un appui long va-t-il partir (l'anneau rouge).
 *
 * La vue est déclarée non tactile côté fenêtre : elle est purement décorative, les appuis réels
 * étant injectés par le service d'accessibilité.
 */
class CursorOverlayView(context: Context) : View(context) {

    private var cursorX = 0.5f
    private var cursorY = 0.5f

    /** Vrai tant que le pouce touche l'index : le bouton est enfoncé. */
    private var pressed = false

    /** Progression de la fermeture en cours, 0..1. */
    private var pinchProgress = 0f

    /** Progression de l'appui long, 0..1. */
    private var longPressProgress = 0f
    private var visible = false

    // Onde de confirmation du dernier appui.
    private var tapX = 0f
    private var tapY = 0f
    private var tapAt = 0L
    private var tapTarget: TargetSnapper.Target? = null

    private val density = resources.displayMetrics.density

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f * density, 6f * density), 0f)
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strengthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var rotation = 0f

    /** État du clavier radial, ou null quand il est fermé. */
    private var keyboard: RadialKeyboard.State? = null

    /** État du panneau de commandes. */
    private var panel: CommandPanel.State? = null

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 17f * density
        isFakeBoldText = true
    }
    private val sectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textBounds = Rect()

    /** Affiche ou masque le clavier radial. */
    fun setKeyboard(state: RadialKeyboard.State?) {
        keyboard = state
        invalidate()
    }

    /**
     * Affiche ou masque le panneau de commandes.
     *
     * Il n'est pas un mode qu'on ouvre et qu'on ferme : il suit la position de la main, donc cette
     * méthode est appelée à chaque image. Comparer avant d'invalider évite de redessiner tout
     * l'écran pour un état identique — ce qui, sur une fenêtre de superposition, coûte directement
     * du GPU à l'application au premier plan.
     */
    fun setPanel(state: CommandPanel.State?) {
        if (state == panel) return
        panel = state
        invalidate()
    }

    /** Met à jour l'état affiché. Appelée depuis le thread principal. */
    fun update(
        x: Float,
        y: Float,
        pressed: Boolean,
        pinchProgress: Float,
        longPressProgress: Float,
        visible: Boolean
    ) {
        // Une fenêtre de superposition invalidée en continu fait recomposer tout l'écran par le
        // compositeur, y compris l'application au premier plan. Quand le curseur est masqué et
        // le reste inchangé, ne rien redessiner est une économie directe de GPU.
        val unchanged = !visible && !this.visible && !tapVisible()
        this.cursorX = x
        this.cursorY = y
        this.pressed = pressed
        this.pinchProgress = pinchProgress
        this.longPressProgress = longPressProgress
        this.visible = visible
        if (unchanged) return

        rotation = (rotation + 2f) % 360f
        invalidate()
    }

    /**
     * Déclenche l'onde de confirmation d'un appui, et le cadre de la cible aimantée s'il y en a une.
     *
     * Un appui gestuel ne produit aucun retour tactile. Si l'application sous-jacente ne réagit
     * pas visiblement — cible manquée, zone inerte — l'utilisateur n'a aucun moyen de savoir si
     * le clic est parti ou si son geste a été rejeté. L'onde lève cette ambiguïté, et c'est elle
     * qui distingue « j'ai mal visé » de « le geste n'a pas été reconnu ».
     *
     * Le cadre joue le même rôle pour l'aimantation. Ramener un appui sur une cible voisine sans
     * le montrer se comporterait comme un curseur qui ment ; en le montrant, l'utilisateur voit ce
     * qui a été touché et apprend jusqu'où il peut être approximatif.
     */
    fun flashTap(x: Float, y: Float, target: TargetSnapper.Target? = null) {
        tapX = x
        tapY = y
        tapTarget = target
        tapAt = SystemClock.uptimeMillis()
        invalidate()
    }

    private fun tapVisible(): Boolean =
        tapAt != 0L && SystemClock.uptimeMillis() - tapAt < TAP_RIPPLE_MS

    /** Masque le curseur et cesse de redessiner. */
    fun hide() {
        if (!visible) return
        visible = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        keyboard?.let { drawKeyboard(canvas, it) }
        panel?.let { drawPanel(canvas, it) }
        drawTapRipple(canvas)
        if (!visible) return

        val cx = cursorX * width
        val cy = cursorY * height
        val accent = if (pressed) COLOR_GOLD else COLOR_CYAN
        val radius = 26f * density

        // Anneau de la pose : contour sombre d'abord, couleur ensuite. Le contour déborde de
        // chaque côté du trait coloré, ce qui le détache d'un fond clair comme d'un fond sombre.
        canvas.save()
        canvas.rotate(rotation, cx, cy)
        ringPaint.color = COLOR_OUTLINE
        ringPaint.strokeWidth = RING_WIDTH_DP * density + 2f * OUTLINE_DP * density
        canvas.drawCircle(cx, cy, radius, ringPaint)
        ringPaint.color = accent
        ringPaint.strokeWidth = RING_WIDTH_DP * density
        canvas.drawCircle(cx, cy, radius, ringPaint)
        canvas.restore()

        // Réticule, en blanc plutôt qu'en couleur d'accent : c'est la partie qui désigne le point
        // exact, elle doit être la plus lisible, et le blanc cerné de noir l'est partout.
        val gap = radius * 0.45f
        val arm = 10f * density
        val arms = floatArrayOf(
            cx - gap - arm, cy, cx - gap, cy,
            cx + gap, cy, cx + gap + arm, cy,
            cx, cy - gap - arm, cx, cy - gap,
            cx, cy + gap, cx, cy + gap + arm
        )
        crossPaint.color = COLOR_OUTLINE
        crossPaint.strokeWidth = CROSS_WIDTH_DP * density + 2f * OUTLINE_DP * density
        canvas.drawLines(arms, crossPaint)
        crossPaint.color = COLOR_CORE
        crossPaint.strokeWidth = CROSS_WIDTH_DP * density
        canvas.drawLines(arms, crossPaint)

        // Noyau : disque blanc opaque cerné de noir. C'est le seul élément dont la visibilité ne
        // dépend d'aucune hypothèse sur le fond, et donc le repère de dernier recours.
        corePaint.color = COLOR_OUTLINE
        canvas.drawCircle(cx, cy, 5f * density, corePaint)
        corePaint.color = COLOR_CORE
        canvas.drawCircle(cx, cy, 3f * density, corePaint)

        // Arc de progression du pincement. Tracé comme un arc et non comme un cercle plein : la
        // part parcourue est alors lisible d'un coup d'œil, alors qu'un simple changement de rayon
        // ou d'opacité ne dit pas à quelle distance du déclenchement on se trouve.
        if (!pressed && pinchProgress > 0.02f) {
            drawContrastedArc(canvas, cx, cy, radius * 1.45f, pinchProgress, COLOR_GOLD)
        }

        // Second arc, à l'extérieur : l'appui long qui vient. Deux anneaux distincts plutôt qu'un
        // seul réutilisé, sans quoi on ne saurait pas lequel des deux se remplit. C'est ce qui
        // rend l'attente lisible au lieu de subie — on voit l'appui long arriver, et bouger la
        // main le transforme en tracé.
        if (longPressProgress > 0.02f) {
            drawContrastedArc(canvas, cx, cy, radius * 1.85f, longPressProgress, COLOR_RED)
        }
    }

    /** Arc de progression doublé d'un contour sombre, lisible sur n'importe quel fond. */
    private fun drawContrastedArc(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        progress: Float,
        color: Int
    ) {
        val sweep = 360f * progress
        strengthPaint.color = COLOR_OUTLINE
        strengthPaint.strokeWidth = ARC_WIDTH_DP * density + 2f * OUTLINE_DP * density
        canvas.drawArc(cx - r, cy - r, cx + r, cy + r, -90f, sweep, false, strengthPaint)
        strengthPaint.color = color
        strengthPaint.strokeWidth = ARC_WIDTH_DP * density
        canvas.drawArc(cx - r, cy - r, cx + r, cy + r, -90f, sweep, false, strengthPaint)
    }

    /** Onde de confirmation : un disque qui s'élargit et s'efface en un tiers de seconde. */
    private fun drawTapRipple(canvas: Canvas) {
        if (!tapVisible()) return
        val progress = (SystemClock.uptimeMillis() - tapAt).toFloat() / TAP_RIPPLE_MS.toFloat()
        val x = tapX * width
        val y = tapY * height
        val r = (10f + 34f * progress) * density

        // Cadre de la cible aimantée, tracé d'abord : il doit se lire sous l'onde, pas par-dessus.
        tapTarget?.let { t ->
            val fade = 0.75f * (1f - progress)
            sectorPaint.color = withAlpha(COLOR_GOLD, 0.16f * (1f - progress))
            canvas.drawRoundRect(
                t.left * width, t.top * height, t.right * width, t.bottom * height,
                8f * density, 8f * density, sectorPaint
            )
            // Contour sombre puis or : le cadre entoure une cible dont on ignore la couleur.
            strengthPaint.color = withAlpha(COLOR_OUTLINE, fade)
            strengthPaint.strokeWidth = ARC_WIDTH_DP * density + 2f * OUTLINE_DP * density
            canvas.drawRoundRect(
                t.left * width, t.top * height, t.right * width, t.bottom * height,
                8f * density, 8f * density, strengthPaint
            )
            strengthPaint.color = withAlpha(COLOR_GOLD, fade)
            strengthPaint.strokeWidth = ARC_WIDTH_DP * density
            canvas.drawRoundRect(
                t.left * width, t.top * height, t.right * width, t.bottom * height,
                8f * density, 8f * density, strengthPaint
            )
        }

        val fade = 1f - progress
        haloPaint.color = withAlpha(COLOR_CORE, 0.22f * fade)
        canvas.drawCircle(x, y, r, haloPaint)
        strengthPaint.color = withAlpha(COLOR_OUTLINE, 0.85f * fade)
        strengthPaint.strokeWidth = ARC_WIDTH_DP * density + 2f * OUTLINE_DP * density
        canvas.drawCircle(x, y, r, strengthPaint)
        strengthPaint.color = withAlpha(COLOR_GOLD, fade)
        strengthPaint.strokeWidth = ARC_WIDTH_DP * density
        canvas.drawCircle(x, y, r, strengthPaint)

        // L'onde s'anime alors que rien d'autre ne change : elle doit demander ses propres images.
        postInvalidateOnAnimation()
    }

    /**
     * Clavier radial : huit secteurs disposés en couronne autour du centre.
     *
     * Les libellés sont placés à distance fixe du centre, dans la direction de leur secteur. Rien
     * n'est aligné sur une grille : la cible n'est pas la pastille dessinée mais la *direction*
     * qu'elle indique, et le secteur s'étend donc jusqu'au bord de l'écran. C'est ce qui rend la
     * sélection insensible à la précision du pointage.
     */
    private fun drawKeyboard(canvas: Canvas, state: RadialKeyboard.State) {
        drawRadialMenu(
            canvas = canvas,
            labels = state.labels,
            hovered = state.hoveredSector,
            progress = state.progress,
            footer = if (state.level == RadialKeyboard.Level.GROUP) "GROUPE" else "LETTRE",
            dim = 0.82f,
            bubbleDp = 30f
        )
    }

    /**
     * Panneau de commandes : une colonne de lignes au bord droit de l'écran.
     *
     * Une **colonne**, et non une couronne, et ce n'est pas un choix graphique. Dans une couronne,
     * une commande se désigne par un angle, donc la précision exigée croît avec le nombre de
     * secteurs. Dans une colonne, elle se désigne par une hauteur : la visée est à une dimension,
     * les lignes restent de la même largeur quel qu'en soit le nombre, et les libellés tiennent
     * sur une ligne au lieu d'être tassés dans une pastille.
     *
     * Les bornes dessinées viennent de [CommandPanel.rowBounds], le même calcul que celui de la
     * sélection : une ligne dessinée ailleurs qu'à l'endroit où elle se sélectionne serait un
     * piège, et le genre de défaut qu'on ne remarque qu'à l'usage.
     */
    private fun drawPanel(canvas: Canvas, state: CommandPanel.State) {
        if (state.openProgress <= 0.01f || state.labels.isEmpty()) return

        val model = CommandPanel()
        val left = width * (1f - (1f - model.leftEdge) * state.openProgress)
        val open = state.openProgress >= 1f

        // Voile limité à la colonne : le panneau sert à agir **sur** ce qui est affiché, donc
        // masquer l'écran entier empêcherait de voir sur quoi on agit. Le clavier, lui, assombrit
        // tout — on n'y regarde pas le dessous.
        dimPaint.color = withAlpha(0xFF040814.toInt(), 0.86f * state.openProgress)
        canvas.drawRect(left, 0f, width.toFloat(), height.toFloat(), dimPaint)

        // Liseré vertical : marque le bord du panneau même quand il n'est qu'esquissé, de sorte
        // qu'on sache qu'il est là avant de l'avoir ouvert.
        strengthPaint.color = withAlpha(COLOR_CYAN, 0.5f + 0.5f * state.openProgress)
        strengthPaint.strokeWidth = ARC_WIDTH_DP * density
        canvas.drawLine(left, 0f, left, height.toFloat(), strengthPaint)

        if (!open) return

        for (index in state.labels.indices) {
            val (top, bottom) = model.rowBounds(index, state.labels.size)
            val y0 = top * height
            val y1 = bottom * height
            val active = index == state.highlighted

            if (active) {
                sectorPaint.color = withAlpha(COLOR_GOLD, 0.26f)
                canvas.drawRect(left, y0, width.toFloat(), y1, sectorPaint)
                strengthPaint.color = COLOR_GOLD
                strengthPaint.strokeWidth = ARC_WIDTH_DP * density
                canvas.drawLine(left, y0, left, y1, strengthPaint)
            }

            labelPaint.color = if (active) COLOR_GOLD else COLOR_CORE
            labelPaint.textAlign = Paint.Align.LEFT
            val label = state.labels[index]
            labelPaint.getTextBounds(label, 0, label.length, textBounds)
            canvas.drawText(
                label,
                left + 16f * density,
                (y0 + y1) / 2f + textBounds.height() / 2f,
                labelPaint
            )
            labelPaint.textAlign = Paint.Align.CENTER
        }
    }

    /**
     * Couronne de huit secteurs : forme commune au clavier gestuel et à la roue de commandes.
     *
     * Les libellés sont placés à distance fixe du centre, dans la direction de leur secteur. Rien
     * n'est aligné sur une grille : la cible n'est pas la pastille dessinée mais la *direction*
     * qu'elle indique, et le secteur s'étend donc jusqu'au bord de l'écran. C'est ce qui rend la
     * sélection insensible à la précision du pointage.
     *
     * Un libellé de plusieurs mots est coupé en deux lignes : écrit sur une seule, il chevaucherait
     * ses voisins, et un menu illisible ne vaut pas mieux qu'un geste à mémoriser.
     */
    private fun drawRadialMenu(
        canvas: Canvas,
        labels: List<String>,
        hovered: Int,
        progress: Float,
        footer: String?,
        dim: Float,
        bubbleDp: Float
    ) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) * 0.34f

        dimPaint.color = withAlpha(0xFF040814.toInt(), dim)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        // Zone morte centrale, où ramener la main sans rien sélectionner.
        ringPaint.color = withAlpha(COLOR_CYAN, 0.35f)
        canvas.drawCircle(cx, cy, radius * 0.34f, ringPaint)

        for (sector in labels.indices) {
            val angle = (sector + 0.5) * (2 * PI / SECTORS)
            val x = cx + (sin(angle) * radius).toFloat()
            val y = cy - (cos(angle) * radius).toFloat()
            val active = sector == hovered

            val bubble = bubbleDp * density
            sectorPaint.color = withAlpha(if (active) COLOR_GOLD else COLOR_CYAN, if (active) 0.28f else 0.10f)
            canvas.drawCircle(x, y, bubble, sectorPaint)

            if (active && progress > 0f) {
                // Arc de progression : la validation est annoncée avant de partir.
                strengthPaint.color = COLOR_GOLD
                strengthPaint.strokeWidth = ARC_WIDTH_DP * density
                canvas.drawArc(
                    x - bubble, y - bubble, x + bubble, y + bubble,
                    -90f, 360f * progress, false, strengthPaint
                )
            }

            labelPaint.color = if (active) COLOR_GOLD else COLOR_CORE
            drawWrappedLabel(canvas, labels[sector], x, y, bubble * 1.9f)
        }

        footer?.let {
            labelPaint.color = withAlpha(COLOR_CYAN, 0.75f)
            canvas.drawText(it, cx, cy + 6f * density, labelPaint)
        }
    }

    /** Libellé centré, coupé sur l'espace le plus proche du milieu s'il est trop large. */
    private fun drawWrappedLabel(canvas: Canvas, label: String, x: Float, y: Float, maxWidth: Float) {
        labelPaint.getTextBounds(label, 0, label.length, textBounds)
        val lineHeight = textBounds.height().toFloat()

        if (labelPaint.measureText(label) <= maxWidth || !label.contains(' ')) {
            canvas.drawText(label, x, y + lineHeight / 2f, labelPaint)
            return
        }

        val middle = label.length / 2
        val cut = label.indices
            .filter { label[it] == ' ' }
            .minBy { kotlin.math.abs(it - middle) }

        val gap = lineHeight * 1.35f
        canvas.drawText(label.substring(0, cut), x, y - gap / 2f + lineHeight / 2f, labelPaint)
        canvas.drawText(label.substring(cut + 1), x, y + gap / 2f + lineHeight / 2f, labelPaint)
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha * 255).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private companion object {
        /**
         * Épaisseur du contour sombre débordant de chaque côté d'un trait clair.
         *
         * Le curseur est dessiné dans une fenêtre de superposition : il ne peut pas lire les
         * pixels qui se trouvent sous lui — il faudrait capturer l'écran à la cadence de la
         * caméra, ce qui serait coûteux et indiscret — et ne peut donc pas s'accorder à la
         * couleur du fond. Le halo translucide qui en tenait lieu ne ressortait que sur fond
         * sombre et disparaissait sur un fond clair.
         *
         * Le double tracé résout le problème sans rien savoir du fond : chaque forme claire est
         * cernée d'un contour sombre. Un fond quelconque est soit plus clair que le contour, soit
         * plus sombre que la forme — dans les deux cas, l'un des deux tranche.
         */
        const val OUTLINE_DP = 1.6f
        const val RING_WIDTH_DP = 2.5f
        const val CROSS_WIDTH_DP = 2.4f
        const val ARC_WIDTH_DP = 3f

        /** Noir et blanc opaques : les deux seules couleurs qui ne dépendent d'aucun contexte. */
        const val COLOR_OUTLINE = 0xE6000000.toInt()
        const val COLOR_CORE = 0xFFFFFFFF.toInt()

        /**
         * Durée de l'onde de confirmation : assez longue pour être vue, assez brève pour ne pas
         * gêner. Exprimée en Long, comme l'horloge à laquelle elle est comparée — la comparaison
         * d'un Long à un Float est légale en Kotlin mais force une conversion implicite dans une
         * expression lue à chaque image.
         */
        const val TAP_RIPPLE_MS = 320L

        /**
         * Nombre de secteurs de la couronne. Le clavier et la roue en comptent huit : c'est le
         * maximum qui laisse à chaque direction 45° de tolérance, soit assez pour qu'une main qui
         * tremble reste dans son secteur.
         */
        val SECTORS = RadialKeyboard.SECTORS

        const val COLOR_CYAN = 0xFF00F0FF.toInt()
        const val COLOR_GOLD = 0xFFFFB703.toInt()
        const val COLOR_RED = 0xFFFF0055.toInt()
    }
}
