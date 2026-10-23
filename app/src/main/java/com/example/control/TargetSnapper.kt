package com.example.control

import kotlin.math.max
import kotlin.math.min

/**
 * Aimantation du clic vers la cible interactive la plus proche.
 *
 * ## Pourquoi c'est le levier de précision qui restait
 *
 * Toutes les corrections précédentes — filtrage, ancrage du curseur au contact, seuil adaptatif —
 * s'attaquent au **bruit** du geste. Elles ne peuvent pas franchir une limite physique : le
 * tremblement physiologique de la main tendue couvre plusieurs millimètres à l'écran, et aucune
 * quantité de lissage ne rend un pointage aérien aussi précis qu'un doigt posé sur du verre.
 *
 * La seule façon de dépasser cette limite est de cesser de viser des pixels pour viser des
 * **objets**. Le service d'accessibilité connaît la position exacte de chaque élément cliquable
 * de l'écran ; un appui à quelques pixels du bord d'un bouton peut donc être ramené à son centre
 * au lieu d'être perdu. La précision utile ne dépend plus de la stabilité de la main mais de la
 * densité des cibles — et sur une interface Android ordinaire, elles sont largement espacées.
 *
 * ## Les règles, et leur raison
 *
 *  - **Un point déjà à l'intérieur d'une cible ne bouge pas du tout.** Le ramener au centre
 *    semblait inoffensif, et c'était le principal défaut de précision de l'application : dans un
 *    champ de texte, le curseur partait toujours au milieu ; sur un curseur de volume, on ne
 *    pouvait régler que la position médiane ; dans une carte, une image ou une grande vue, on
 *    visait le centre quoi qu'on fasse. Être dedans, c'est avoir réussi son geste : il n'y a rien
 *    à corriger, et corriger quand même revient à **perdre volontairement** l'information que
 *    l'utilisateur venait de fournir.
 *  - **En cas d'imbrication, la plus petite l'emporte.** Les listes Android emboîtent
 *    régulièrement un élément cliquable dans un conteneur lui-même cliquable ; retenir le plus
 *    grand ferait perdre l'élément précis que l'utilisateur visait.
 *  - **Hors de toute cible, la plus proche est retenue dans un rayon donné, et l'appui est alors
 *    ramené à son centre.** C'est le seul cas où déplacer l'appui apporte quelque chose : sans
 *    cela il tombait à côté et ne faisait rien. Sans la borne de rayon, en revanche, un appui dans
 *    une zone volontairement vide serait attiré vers un bouton lointain — l'aimantation deviendrait
 *    un déplacement arbitraire, et l'on perdrait la capacité de cliquer là où il n'y a rien, qui
 *    sert entre autres à fermer les menus.
 *
 * Toutes les mesures sont dans une unité unique, laissée à l'appelant. Le service travaille en
 * pixels, ce qui rend le rayon isotrope malgré un écran non carré.
 *
 * Kotlin pur, sans dépendance Android : entièrement testable en JVM.
 */
object TargetSnapper {

    /** Rectangle d'un élément interactif. */
    data class Target(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        val centerX: Float get() = (left + right) * 0.5f
        val centerY: Float get() = (top + bottom) * 0.5f
        val area: Float get() = max(0f, right - left) * max(0f, bottom - top)

        fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

        /** Distance du point au rectangle, nulle s'il est à l'intérieur. */
        fun distanceTo(x: Float, y: Float): Float {
            val dx = max(max(left - x, 0f), x - right)
            val dy = max(max(top - y, 0f), y - bottom)
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }
    }

    /**
     * Résultat de l'aimantation : la cible retenue, et le point où l'appui doit réellement partir.
     *
     * Les deux sont distincts, et c'est tout l'intérêt : la cible sert à **montrer** ce qui a été
     * touché, le point à l'atteindre. Quand l'appui était déjà dans la cible, le point est celui
     * de l'utilisateur, inchangé.
     */
    data class Snap(
        val target: Target,
        val x: Float,
        val y: Float,
        /** Vrai quand l'appui a été déplacé, donc quand l'aimantation a réellement servi. */
        val moved: Boolean
    )

    /**
     * Aimantation d'un appui en ([x], [y]), ou null si aucune cible n'est assez proche — auquel cas
     * l'appui doit partir exactement là où l'utilisateur a pointé.
     *
     * @param radius distance maximale, dans la même unité que les rectangles
     */
    fun snap(x: Float, y: Float, targets: List<Target>, radius: Float): Snap? {
        if (targets.isEmpty()) return null

        // Une cible qui contient le point l'emporte sur toute cible voisine, quelle que soit la
        // distance de cette dernière au point : être dedans est une intention non ambiguë. Et
        // l'appui n'est alors **pas** déplacé — il a déjà atteint ce qu'il visait.
        val containing = targets.filter { it.contains(x, y) }
        if (containing.isNotEmpty()) {
            val target = containing.minByOrNull { it.area } ?: return null
            return Snap(target, x, y, moved = false)
        }

        val nearest = targets
            .map { it to it.distanceTo(x, y) }
            .filter { (_, distance) -> distance <= radius }
            .minWithOrNull(compareBy({ it.second }, { it.first.area }))
            ?.first ?: return null

        return Snap(nearest, nearest.centerX, nearest.centerY, moved = true)
    }

    /**
     * Écarte les cibles inexploitables avant l'aimantation.
     *
     * Deux cas, tous deux observés sur des interfaces réelles :
     *
     *  - les rectangles dégénérés ou hors écran, que certaines vues déclarent cliquables sans
     *    jamais les afficher ;
     *  - les cibles occupant une part majeure de l'écran. Un fond de page cliquable — pour fermer
     *    une boîte de dialogue, par exemple — contient à peu près tous les points ; le retenir
     *    reviendrait à ramener chaque appui au centre de l'écran.
     */
    fun usable(
        targets: List<Target>,
        screenWidth: Float,
        screenHeight: Float,
        maxAreaFraction: Float = 0.35f
    ): List<Target> {
        val screenArea = screenWidth * screenHeight
        if (screenArea <= 0f) return emptyList()

        return targets.filter { target ->
            val width = target.right - target.left
            val height = target.bottom - target.top
            width > 0f && height > 0f &&
                target.right > 0f && target.bottom > 0f &&
                target.left < screenWidth && target.top < screenHeight &&
                target.area <= screenArea * maxAreaFraction
        }
    }

    /** Rayon d'aimantation par défaut : une fraction de la plus petite dimension de l'écran. */
    fun defaultRadius(screenWidth: Float, screenHeight: Float): Float =
        min(screenWidth, screenHeight) * 0.06f
}
