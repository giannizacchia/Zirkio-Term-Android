package org.zirkio.term.dati

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComandiTest {

    @Test
    fun spegnere_riconosce_il_nome_della_macchina() {
        assertEquals("201", Comandi.macchinaDaSpegnere("@sd 201"))
        assertEquals("sara", Comandi.macchinaDaSpegnere("@sd sara"))
        assertEquals("SARA", Comandi.macchinaDaSpegnere("   @sd   SARA  "))
    }

    @Test
    fun non_tutto_quello_che_comincia_per_at_sd_e_un_comando() {
        assertNull("senza nome non si spegne niente", Comandi.macchinaDaSpegnere("@sd"))
        assertNull("@sdx non è @sd", Comandi.macchinaDaSpegnere("@sdx 201"))
        assertNull("@sh è un'altra cosa", Comandi.macchinaDaSpegnere("@sh 201"))
        assertNull("una riga normale non si tocca", Comandi.macchinaDaSpegnere("sudo shutdown -h now"))
        assertNull("riga vuota", Comandi.macchinaDaSpegnere(""))
    }

    @Test
    fun la_password_non_sta_nella_riga_di_comando() {
        // sudo -S legge dallo standard input: la password non compare in `ps` sul remoto
        assertTrue(Comandi.SPEGNI, Comandi.SPEGNI.contains("sudo -S"))
        assertTrue(Comandi.SPEGNI, Comandi.SPEGNI.contains("shutdown"))
    }
}
