package org.zirkio.term.dati

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OperazioniTest {

    private fun radice(): File = Files.createTempDirectory("op").toFile()

    @Test
    fun sposta_un_file_in_una_sottocartella() {
        val base = radice()
        val file = File(base, "nota.txt").apply { writeText("ciao") }
        val destinazione = File(base, "archivio").apply { mkdirs() }

        val errori = Operazioni.spostaLocale(listOf(file), destinazione)

        assertTrue(errori.isEmpty())
        assertFalse(file.exists())
        assertEquals("ciao", File(destinazione, "nota.txt").readText())
    }

    @Test
    fun sposta_una_cartella_con_dentro_i_file() {
        val base = radice()
        val cartella = File(base, "progetto").apply { mkdirs() }
        File(cartella, "uno.txt").writeText("1")
        File(cartella, "dentro").mkdirs()
        File(cartella, "dentro/due.txt").writeText("2")
        val destinazione = File(base, "altro").apply { mkdirs() }

        val errori = Operazioni.spostaLocale(listOf(cartella), destinazione)

        assertTrue(errori.isEmpty())
        assertFalse(cartella.exists())
        assertEquals("2", File(destinazione, "progetto/dentro/due.txt").readText())
    }

    @Test
    fun non_sovrascrive_mai() {
        val base = radice()
        val file = File(base, "nota.txt").apply { writeText("nuovo") }
        val destinazione = File(base, "archivio").apply { mkdirs() }
        File(destinazione, "nota.txt").writeText("vecchio")

        val errori = Operazioni.spostaLocale(listOf(file), destinazione)

        assertEquals(1, errori.size)
        assertTrue(errori[0].contains("esiste già"))
        assertEquals("nuovo", file.readText())
        assertEquals("vecchio", File(destinazione, "nota.txt").readText())
    }

    @Test
    fun spostare_dove_gia_si_trova_non_fa_nulla() {
        val base = radice()
        val file = File(base, "nota.txt").apply { writeText("x") }

        val errori = Operazioni.spostaLocale(listOf(file), base)

        assertTrue(errori.isEmpty())
        assertTrue(file.exists())
    }

    @Test
    fun cancella_file_e_cartelle_con_tutto_il_contenuto() {
        val base = radice()
        val file = File(base, "via.txt").apply { writeText("x") }
        val cartella = File(base, "piena").apply { mkdirs() }
        File(cartella, "a.txt").writeText("a")
        File(cartella, "sotto").mkdirs()
        File(cartella, "sotto/b.txt").writeText("b")

        val errori = Operazioni.eliminaLocale(listOf(file, cartella))

        assertTrue(errori.isEmpty())
        assertFalse(file.exists())
        assertFalse(cartella.exists())
        assertTrue(base.exists())
    }

    @Test
    fun una_destinazione_che_non_esiste_viene_segnalata() {
        val base = radice()
        val file = File(base, "nota.txt").apply { writeText("x") }
        val errori = Operazioni.spostaLocale(listOf(file), File(base, "non_esiste"))
        assertEquals(1, errori.size)
        assertTrue(file.exists())
    }
}
