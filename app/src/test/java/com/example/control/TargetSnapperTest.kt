package com.example.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetSnapperTest {

    private val screenW = 1080f
    private val screenH = 2400f
    private val radius = TargetSnapper.defaultRadius(screenW, screenH)

    private fun target(l: Float, t: Float, r: Float, b: Float) = TargetSnapper.Target(l, t, r, b)

    /** Un bouton ordinaire : 200 x 120 pixels, largement au-dessus de la taille de cible minimale. */
    private val button = target(400f, 1000f, 600f, 1120f)

    @Test
    fun `un appui dans une cible retient cette cible`() {
        assertEquals(button, TargetSnapper.snap(450f, 1050f, listOf(button), radius))
    }

    /**
     * Le cas qui justifie tout : le tremblement fait tomber l'appui à côté du bouton. Sans
     * aimantation, le clic est perdu — et l'utilisateur ne sait même pas pourquoi.
     */
    @Test
    fun `un appui juste a cote est ramene sur la cible`() {
        val snapped = TargetSnapper.snap(620f, 1050f, listOf(button), radius)
        assertEquals(button, snapped)
        assertEquals(500f, snapped!!.centerX, 1e-3f)
    }

    /**
     * Symétriquement, l'aimantation doit rester bornée. Cliquer dans le vide est une action
     * légitime — c'est ce qui ferme un menu — et ne doit jamais être détournée vers un bouton
     * lointain.
     */
    @Test
    fun `un appui loin de toute cible n est pas devie`() {
        assertNull(TargetSnapper.snap(100f, 200f, listOf(button), radius))
    }

    @Test
    fun `la cible la plus proche l emporte`() {
        val left = target(100f, 1000f, 200f, 1100f)
        val right = target(900f, 1000f, 1000f, 1100f)
        assertEquals(left, TargetSnapper.snap(240f, 1050f, listOf(left, right), radius))
    }

    /**
     * Les listes Android emboîtent régulièrement un élément cliquable dans une ligne elle-même
     * cliquable. Retenir le conteneur ferait perdre le bouton précis que l'utilisateur visait.
     */
    @Test
    fun `entre deux cibles imbriquees la plus petite l emporte`() {
        val row = target(0f, 1000f, 1080f, 1200f)
        val icon = target(900f, 1050f, 1000f, 1150f)
        assertEquals(icon, TargetSnapper.snap(950f, 1100f, listOf(row, icon), radius))
    }

    /** Être à l'intérieur d'une cible est sans ambiguïté, même si une autre est plus proche du bord. */
    @Test
    fun `une cible contenant le point prime sur une voisine plus proche`() {
        val inside = target(400f, 1000f, 700f, 1300f)
        val neighbour = target(710f, 1140f, 760f, 1160f)
        assertEquals(inside, TargetSnapper.snap(695f, 1150f, listOf(inside, neighbour), radius))
    }

    @Test
    fun `sans cible connue rien n est aimante`() {
        assertNull(TargetSnapper.snap(540f, 1200f, emptyList(), radius))
    }

    // --- Filtrage des cibles inexploitables ---------------------------------------------------

    @Test
    fun `un fond de page cliquable est ecarte`() {
        val backdrop = target(0f, 0f, screenW, screenH)
        val usable = TargetSnapper.usable(listOf(backdrop, button), screenW, screenH)
        assertEquals(listOf(button), usable)
    }

    @Test
    fun `les rectangles degeneres ou hors ecran sont ecartes`() {
        val empty = target(300f, 300f, 300f, 400f)
        val offscreen = target(-500f, 100f, -100f, 200f)
        val below = target(100f, screenH + 10f, 300f, screenH + 100f)
        val usable = TargetSnapper.usable(
            listOf(empty, offscreen, below, button), screenW, screenH
        )
        assertEquals(listOf(button), usable)
    }

    /**
     * Le filtre ne doit pas être si strict qu'il écarte les grandes cibles légitimes : une carte
     * ou une image dans une liste occupe couramment un tiers de l'écran.
     */
    @Test
    fun `une grande carte reste une cible valide`() {
        val card = target(40f, 400f, 1040f, 1100f)
        assertTrue(card.area < screenW * screenH * 0.35f)
        assertEquals(listOf(card), TargetSnapper.usable(listOf(card), screenW, screenH))
    }

    @Test
    fun `le rayon d aimantation est isotrope`() {
        // Fondé sur la plus petite dimension : le même écart en pixels aimante horizontalement
        // comme verticalement, alors qu'un rayon exprimé en fraction d'écran ne le ferait pas.
        assertEquals(screenW * 0.06f, TargetSnapper.defaultRadius(screenW, screenH), 1e-3f)
        val horizontal = TargetSnapper.snap(600f + radius - 1f, 1060f, listOf(button), radius)
        val vertical = TargetSnapper.snap(500f, 1120f + radius - 1f, listOf(button), radius)
        assertEquals(button, horizontal)
        assertEquals(button, vertical)
    }

    @Test
    fun `un ecran de taille nulle ne produit aucune cible`() {
        assertTrue(TargetSnapper.usable(listOf(button), 0f, 0f).isEmpty())
    }
}
