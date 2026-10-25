package org.zirkio.term.dati

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoceTest {

    private fun voce(
        nome: String,
        cartella: Boolean = false,
        dimensione: Long = 0,
        modificato: Long = 0,
    ) = Voce(
        nome = nome,
        percorso = "/finto/$nome",
        cartella = cartella,
        dimensione = dimensione,
        tipo = tipoFile(nome, cartella),
        modificato = modificato,
    )

    @Test
    fun le_dimensioni_usano_le_unita_giuste() {
        assertEquals("999 B", formattaByte(999))
        assertEquals("1,0 kB", formattaByte(1000))
        assertEquals("1,2 kB", formattaByte(1234))
        assertEquals("3,5 MB", formattaByte(3_500_000))
        assertEquals("2,0 GB", formattaByte(2_000_000_000))
        assertEquals("1,5 TB", formattaByte(1_500_000_000_000))
    }

    @Test
    fun il_tipo_viene_dal_nome_in_maiuscolo() {
        assertEquals("CARTELLA", tipoFile("Download", true))
        assertEquals("IMMAGINE", tipoFile("foto.JPG", false))
        assertEquals("TESTO", tipoFile("note.md", false))
        assertEquals("ARCHIVIO", tipoFile("backup.tar.gz", false))
        assertEquals("PDF", tipoFile("contratto.pdf", false))
        assertEquals("PACCHETTO", tipoFile("ZirkioTerm.apk", false))
        assertEquals("FILE", tipoFile("senzaestensione", false))
        assertEquals("FILE", tipoFile(".nascosto", false))
        assertEquals("XYZ", tipoFile("strano.xyz", false))
    }

    @Test
    fun le_cartelle_stanno_sempre_prima_dei_file() {
        val voci = listOf(
            voce("zeta.txt"),
            voce("alfa", cartella = true),
            voce("beta.txt"),
            voce("gamma", cartella = true),
        )
        val ordinati = ordina(voci, Campo.NOME, crescente = true)
        assertEquals(listOf("alfa", "gamma", "beta.txt", "zeta.txt"), ordinati.map { it.nome })

        // anche al contrario: cartelle prima, poi i file dal più alto
        val rovescio = ordina(voci, Campo.NOME, crescente = false)
        assertEquals(listOf("gamma", "alfa", "zeta.txt", "beta.txt"), rovescio.map { it.nome })
    }

    @Test
    fun si_ordina_per_dimensione_e_per_tipo() {
        val voci = listOf(
            voce("grande.bin", dimensione = 9_000),
            voce("piccolo.bin", dimensione = 10),
            voce("medio.bin", dimensione = 500),
        )
        assertEquals(
            listOf("piccolo.bin", "medio.bin", "grande.bin"),
            ordina(voci, Campo.DIMENSIONE, true).map { it.nome },
        )
        assertEquals(
            listOf("grande.bin", "medio.bin", "piccolo.bin"),
            ordina(voci, Campo.DIMENSIONE, false).map { it.nome },
        )

    }

    @Test
    fun si_ordina_per_data_dal_piu_recente() {
        val voci = listOf(
            voce("vecchio.txt", modificato = 1_000),
            voce("nuovo.txt", modificato = 9_000),
            voce("mezzo.txt", modificato = 5_000),
        )
        assertEquals(
            listOf("nuovo.txt", "mezzo.txt", "vecchio.txt"),
            ordina(voci, Campo.DATA, crescente = false).map { it.nome },
        )
        assertEquals(
            listOf("vecchio.txt", "mezzo.txt", "nuovo.txt"),
            ordina(voci, Campo.DATA, crescente = true).map { it.nome },
        )
    }

    @Test
    fun la_data_si_legge_senza_secondi() {
        val quando = java.time.LocalDateTime.of(2026, 10, 5, 9, 7, 42)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertEquals("05/10/26 09:07", formattaData(quando))
        assertEquals("", formattaData(0))
    }

    @Test
    fun i_nomi_si_confrontano_senza_badare_alle_maiuscole() {
        val voci = listOf(voce("beta"), voce("Alfa"), voce("gamma"))
        assertEquals(listOf("Alfa", "beta", "gamma"), ordina(voci, Campo.NOME, true).map { it.nome })
    }
}
