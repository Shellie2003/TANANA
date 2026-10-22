package com.example.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandListTest {

    @Test
    fun `le panneau propose des commandes par defaut`() {
        val list = CommandList()
        assertTrue("un panneau vide enfermerait l'utilisateur hors de son téléphone", list.size > 0)
        assertEquals(list.size, list.labels().size)
    }

    /**
     * « Retour » est de loin l'action la plus utilisée d'Android : elle doit arriver en premier,
     * donc à la position la plus facile à viser.
     */
    @Test
    fun `le retour vient en premier`() {
        assertEquals(Command.BACK, CommandList()[0])
    }

    @Test
    fun `toucher une commande la fait entrer puis sortir`() {
        val base = CommandList()
        assertTrue(Command.LOCK !in base)

        val added = base.toggled(Command.LOCK)
        assertTrue(Command.LOCK in added)
        assertEquals("la commande ajoutée doit aller à la fin", Command.LOCK, added[added.size - 1])

        val removed = added.toggled(Command.LOCK)
        assertEquals(base.commands, removed.commands)
    }

    @Test
    fun `une liste survit a un aller-retour de serialisation`() {
        val custom = CommandList()
            .toggled(Command.SCREENSHOT)
            .toggled(Command.LOCK)
            .toggled(Command.MEDIA_NEXT)
        assertEquals(custom.commands, CommandList.deserialize(custom.serialize()).commands)
    }

    /**
     * Les identifiants sont écrits dans les préférences : les renommer invaliderait silencieusement
     * les configurations déjà enregistrées sur les appareils.
     */
    @Test
    fun `les identifiants sont stables et distincts`() {
        val ids = Command.entries.map { it.id }
        assertEquals("deux commandes partagent un identifiant", ids.size, ids.toSet().size)
        assertTrue(ids.none { it.isBlank() })
        Command.entries.forEach { assertEquals(it, Command.fromId(it.id)) }
    }

    @Test
    fun `une configuration corrompue retombe sur les valeurs par defaut`() {
        assertEquals(CommandList.DEFAULTS, CommandList.deserialize("n_existe_pas,non_plus").commands)
        assertEquals(CommandList.DEFAULTS, CommandList.deserialize("").commands)
        assertEquals(CommandList.DEFAULTS, CommandList.deserialize(null).commands)
    }

    /**
     * Les préférences déjà enregistrées sur les appareils décrivent des associations pose → action,
     * dans un format qui n'a plus rien à voir. Elles doivent retomber sans bruit sur les valeurs
     * par défaut : une configuration héritée ne doit jamais laisser l'utilisateur sans commande.
     */
    @Test
    fun `une configuration heritee du vocabulaire de poses est ignoree`() {
        val legacy = "POINT=cursor;FIST=back;OPEN_PALM=wheel;w0=home;w1=recents"
        assertEquals(CommandList.DEFAULTS, CommandList.deserialize(legacy).commands)
    }

    @Test
    fun `une entree repetee n apparait qu une fois`() {
        assertEquals(
            listOf(Command.BACK, Command.HOME),
            CommandList.deserialize("back,home,back").commands
        )
    }

    /**
     * Chaque commande doit porter un libellé lisible dans le panneau et une description dans
     * l'écran de réglage : une ligne sans texte serait une commande qu'on ne peut pas choisir,
     * puisque le panneau n'offre rien d'autre pour la reconnaître.
     */
    @Test
    fun `chaque commande est libellee et decrite`() {
        Command.entries.forEach {
            assertTrue("${it.name} sans libellé", it.label.isNotBlank())
            assertTrue("${it.name} sans description", it.description.length > 10)
        }
    }

    /** Aucune commande du catalogue ne doit être inerte. */
    @Test
    fun `aucune commande ne porte une action vide`() {
        Command.entries.forEach {
            assertTrue("${it.name} n'exécute rien", it.action != SystemAction.None)
        }
    }
}
