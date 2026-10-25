package org.zirkio.term.dati

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ChiaviTest {

    private fun cartellaTemporanea() = Files.createTempDirectory("chiavi").toFile()

    @Test
    fun genera_ed25519_e_la_rilegge() {
        val cartella = cartellaTemporanea()
        val chiave = Chiavi.generaIn(cartella, "id_ed25519", TipoChiave.ED25519)

        assertEquals("ssh-ed25519", chiave.tipo)
        assertTrue(chiave.impronta.startsWith("SHA256:"))
        assertTrue(chiave.pubblica.startsWith("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5"))
        assertTrue(chiave.pubblica.endsWith(" id_ed25519"))
        assertTrue(Chiavi.testo(chiave).startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))

        // il punto che prima non tornava: l'elenco deve contenere la chiave
        val elenco = Chiavi.elencoIn(cartella)
        assertEquals(1, elenco.size)
        assertEquals(chiave.impronta, elenco[0].impronta)
        assertEquals("ssh-ed25519", elenco[0].tipo)
    }

    @Test
    fun genera_rsa_a_tre_mila_centosessanta_bit() {
        val cartella = cartellaTemporanea()
        val chiave = Chiavi.generaIn(cartella, "id_rsa", TipoChiave.RSA)
        assertEquals("ssh-rsa", chiave.tipo)
        assertTrue(chiave.impronta.startsWith("SHA256:"))
        assertEquals(1, Chiavi.elencoIn(cartella).size)
    }

    @Test
    fun importa_una_chiave_gia_esistente() {
        val origine = cartellaTemporanea()
        val generata = Chiavi.generaIn(origine, "originale", TipoChiave.ED25519)
        val testo = Chiavi.testo(generata)

        val destinazione = cartellaTemporanea()
        val importata = Chiavi.importaIn(destinazione, "copiata", testo, "")
        assertEquals(generata.impronta, importata.impronta)
        assertEquals("ssh-ed25519", importata.tipo)
        assertEquals(1, Chiavi.elencoIn(destinazione).size)
    }

    @Test
    fun rifiuta_un_nome_ripetuto_e_un_file_pubblico() {
        val cartella = cartellaTemporanea()
        Chiavi.generaIn(cartella, "uno", TipoChiave.ED25519)
        val ripetuta = runCatching { Chiavi.generaIn(cartella, "uno", TipoChiave.ED25519) }
        assertNotNull(ripetuta.exceptionOrNull())

        val pubblica = runCatching {
            Chiavi.importaIn(cartella, "due", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAAA due", "")
        }
        assertNotNull(pubblica.exceptionOrNull())
        assertTrue(pubblica.exceptionOrNull()?.message?.contains("pubblico") == true)
    }

    @Test
    fun i_nomi_pericolosi_vengono_ripuliti() {
        val cartella = cartellaTemporanea()
        val chiave = Chiavi.generaIn(cartella, "../chiave mia", TipoChiave.ED25519)
        assertEquals(".._chiave_mia", chiave.nome)
        assertEquals(cartella.absolutePath, chiave.file.parentFile?.absolutePath)
    }
}
