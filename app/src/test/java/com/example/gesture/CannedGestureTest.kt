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
    fun `les trois poses du vocabulaire sont reconnues`() {
        assertEquals(GestureType.FIST, CannedGesture.from("Closed_Fist", confident))
        assertEquals(GestureType.OPEN_PALM, CannedGesture.from("Open_Palm", confident))
        assertEquals(GestureType.POINT, CannedGesture.from("Pointing_Up", confident))
    }

    /**
     * Le classifieur sait reconnaître le « V », le pouce levé et le pouce baissé. Ne pas les
     * traduire est un choix, pas un oubli : ces trois formes se distinguent de nos poses par un
     * seul doigt — le plus mal suivi pour les pouces, un doigt occulté pour le « V » — et c'est
     * exactement ce qui rendait le vocabulaire instable. Les laisser entrer par le classifieur
     * réintroduirait le conflit que la réduction du vocabulaire a supprimé.
     */
    @Test
    fun `les categories ecartees du vocabulaire ne produisent aucune pose`() {
        assertNull(CannedGesture.from("Thumb_Up", confident))
        assertNull(CannedGesture.from("Thumb_Down", confident))
        assertNull(CannedGesture.from("Victory", confident))
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
     * Les deux voies de reconnaissance — classifieur entraîné et score géométrique — doivent
     * couvrir **exactement** le même vocabulaire.
     *
     * C'est la propriété qui empêche la refonte de se défaire en silence. Si le classifieur
     * reconnaissait une pose que la géométrie ignore, cette pose apparaîtrait de façon
     * intermittente, selon que le classifieur se prononce ou s'abstient — c'est-à-dire de la
     * manière la plus déroutante possible.
     */
    @Test
    fun `les deux voies de reconnaissance couvrent le meme vocabulaire`() {
        val canned = CannedGesture.recognizedLabels
            .mapNotNull { CannedGesture.from(it, confident) }
            .toSet()
        assertEquals(PoseScorer().poses.toSet(), canned)
    }
}
