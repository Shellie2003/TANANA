package com.example.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La traduction des étiquettes est la seule couture entre le classifieur entraîné et notre
 * vocabulaire. Une étiquette mal orthographiée ne produirait aucune erreur : la pose cesserait
 * simplement d'être reconnue, en silence, et seul le repli géométrique la rattraperait — c'est-à-
 * dire que la migration n'aurait servi à rien sans que rien ne le signale.
 */
class CannedGestureTest {

    /** Catégories déclarées par MediaPipe, recopiées telles quelles depuis la bibliothèque. */
    private val mediaPipeCategories = listOf(
        "None", "Closed_Fist", "Open_Palm", "Pointing_Up",
        "Thumb_Down", "Thumb_Up", "Victory", "ILoveYou"
    )

    private val confident = 0.9f

    @Test
    fun `chaque etiquette traduite correspond a une categorie de MediaPipe`() {
        CannedGesture.recognizedLabels.forEach { label ->
            assertTrue(
                "« $label » n'est pas une catégorie du classifieur : la pose ne sera jamais reconnue",
                label in mediaPipeCategories
            )
        }
    }

    @Test
    fun `les poses du vocabulaire sont reconnues`() {
        assertEquals(GestureType.FIST, CannedGesture.from("Closed_Fist", confident))
        assertEquals(GestureType.OPEN_PALM, CannedGesture.from("Open_Palm", confident))
        assertEquals(GestureType.POINT, CannedGesture.from("Pointing_Up", confident))
        assertEquals(GestureType.THUMB_UP, CannedGesture.from("Thumb_Up", confident))
        assertEquals(GestureType.THUMB_DOWN, CannedGesture.from("Thumb_Down", confident))
        assertEquals(GestureType.TWO_FINGER, CannedGesture.from("Victory", confident))
    }

    /**
     * « None » signifie que le classifieur s'abstient. Le traduire par une pose serait le pire
     * contresens possible : c'est précisément le cas où le score géométrique doit reprendre la main.
     */
    @Test
    fun `l abstention du classifieur ne produit aucune pose`() {
        assertNull(CannedGesture.from("None", confident))
        assertNull(CannedGesture.from(null, confident))
    }

    /** Les catégories sans équivalent dans le vocabulaire ne doivent rien produire non plus. */
    @Test
    fun `une categorie hors vocabulaire est ignoree`() {
        assertNull(CannedGesture.from("ILoveYou", confident))
        assertNull(CannedGesture.from("Categorie_Inventee", confident))
    }

    @Test
    fun `une etiquette peu sure laisse decider la geometrie`() {
        assertNull(CannedGesture.from("Closed_Fist", 0.4f))
        assertEquals(GestureType.FIST, CannedGesture.from("Closed_Fist", CannedGesture.MIN_CONFIDENCE))
    }

    /**
     * Le pouce baissé n'existe que par le classifieur : le score géométrique ne mesure que
     * l'extension des doigts, pas leur orientation, et ne peut donc pas le distinguer du pouce
     * levé. Aucun modèle géométrique ne doit prétendre le reconnaître.
     */
    @Test
    fun `le pouce baisse n a pas de modele geometrique`() {
        assertTrue(GestureType.THUMB_DOWN !in PoseScorer().poses)
    }
}
