package com.example.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.View
import com.example.gesture.GestureType

/**
 * Curseur holographique dessiné par-dessus toutes les applications.
 *
 * Volontairement écrit en View native plutôt qu'en Compose : cette vue est redessinée à la
 * cadence de la caméra au-dessus de l'application au premier plan, et elle doit rester la plus
 * légère possible pour ne pas voler d'images à celle-ci. Un `invalidate()` sur une View qui ne
 * fait que tracer quelques primitives coûte beaucoup moins qu'un arbre de composition.
 *
 * La vue est déclarée non tactile côté fenêtre : elle est purement décorative, les appuis réels
 * étant injectés par le service d'accessibilité.
 */
class CursorOverlayView(context: Context) : View(context) {

    private var cursorX = 0.5f
    private var cursorY = 0.5f
    private var gesture = GestureType.IDLE
    private var pinchStrength = 0f
    private var visible = false

    private val density = resources.displayMetrics.density

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        pathEffect = DashPathEffect(floatArrayOf(10f * density, 6f * density), 0f)
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strengthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var rotation = 0f

    /** Met à jour l'état affiché. Appelée depuis le thread principal. */
    fun update(
        x: Float,
        y: Float,
        gesture: GestureType,
        pinchStrength: Float,
        visible: Boolean
    ) {
        // Une fenêtre de superposition invalidée en continu fait recomposer tout l'écran par le
        // compositeur, y compris l'application au premier plan. Quand le curseur est masqué et
        // le reste inchangé, ne rien redessiner est une économie directe de GPU.
        val unchanged = !visible && !this.visible
        this.cursorX = x
        this.cursorY = y
        this.gesture = gesture
        this.pinchStrength = pinchStrength
        this.visible = visible
        if (unchanged) return

        rotation = (rotation + 2f) % 360f
        invalidate()
    }

    /** Masque le curseur et cesse de redessiner. */
    fun hide() {
        if (!visible) return
        visible = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!visible) return

        val cx = cursorX * width
        val cy = cursorY * height
        val color = colorFor(gesture)
        val radius = if (gesture == GestureType.PINCH) 20f * density else 28f * density

        // Halo : rend le curseur repérable au-dessus d'un contenu clair comme sombre.
        haloPaint.color = withAlpha(color, 0.14f)
        canvas.drawCircle(cx, cy, radius * 1.5f, haloPaint)

        ringPaint.color = color
        canvas.save()
        canvas.rotate(rotation, cx, cy)
        canvas.drawCircle(cx, cy, radius, ringPaint)
        canvas.restore()

        // Réticule.
        crossPaint.color = color
        val gap = radius * 0.45f
        val arm = 10f * density
        canvas.drawLine(cx - gap - arm, cy, cx - gap, cy, crossPaint)
        canvas.drawLine(cx + gap, cy, cx + gap + arm, cy, crossPaint)
        canvas.drawLine(cx, cy - gap - arm, cx, cy - gap, crossPaint)
        canvas.drawLine(cx, cy + gap, cx, cy + gap + arm, crossPaint)

        corePaint.color = color
        canvas.drawCircle(cx, cy, 3.5f * density, corePaint)

        // Anneau de force du pincement : l'utilisateur voit le clic approcher avant qu'il ne parte.
        if (pinchStrength > 0.05f) {
            strengthPaint.color = withAlpha(COLOR_GOLD, 0.25f + 0.6f * pinchStrength)
            canvas.drawCircle(cx, cy, radius * (1.45f - 0.4f * pinchStrength), strengthPaint)
        }
    }

    private fun colorFor(gesture: GestureType): Int = when (gesture) {
        GestureType.PINCH -> COLOR_GOLD
        GestureType.FIST, GestureType.THUMB_UP -> COLOR_RED
        GestureType.TWO_FINGER, GestureType.THREE_FINGER -> COLOR_BLUE
        else -> COLOR_CYAN
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha * 255).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private companion object {
        const val COLOR_CYAN = 0xFF00F0FF.toInt()
        const val COLOR_GOLD = 0xFFFFB703.toInt()
        const val COLOR_RED = 0xFFFF0055.toInt()
        const val COLOR_BLUE = 0xFF3D8BFF.toInt()
    }
}
