package org.zirkio.term.dati

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RicercaTest {

    private fun albero(): File {
        val radice = Files.createTempDirectory("albero").toFile()
        fun file(percorso: String, contenuto: String, quando: Long): File {
            val f = File(radice, percorso)
            f.parentFile?.mkdirs()
            f.writeText(contenuto)
            f.setLastModified(quando)
            return f
        }
        file("vecchio.txt", "x", 1_000)
        file("recente.pdf", "x", 9_000)
        file("install/ZirkioNote.apk", "x", 5_000)
        file("install/ZirkioTerm.apk", "x", 7_000)
        file("foto/foto.jpg", "x", 3_000)
        File(radice, "vuota").mkdirs()
        return radice
    }

    @Test
    fun solo_apk_e_dal_piu_recente() {
        val trovati = cerca(albero(), Filtro.APK)
        assertEquals(listOf("ZirkioTerm.apk", "ZirkioNote.apk"), trovati.map { it.nome })
        assertTrue(trovati.all { estensione(it.nome) == "apk" })
        assertEquals("install", trovati[0].contenitore)
    }

    @Test
    fun solo_documenti_esclude_foto_e_pacchetti() {
        val trovati = cerca(albero(), Filtro.DOCUMENTI)
        assertEquals(listOf("recente.pdf", "vecchio.txt"), trovati.map { it.nome })
    }

    @Test
    fun recenti_prende_tutto_in_ordine_di_data() {
        val trovati = cerca(albero(), Filtro.RECENTI)
        assertEquals(5, trovati.size)
        val date = trovati.map { it.modificato }
        assertEquals(date.sortedDescending(), date)
    }

    @Test
    fun il_limite_viene_rispettato() {
        assertEquals(2, cerca(albero(), Filtro.RECENTI, limite = 2).size)
    }

    @Test
    fun tutte_le_estensioni_dei_documenti_contano() {
        val radice = Files.createTempDirectory("doc").toFile()
        for (nome in listOf("a.pdf", "b.docx", "c.ods", "d.pptx", "e.md", "f.epub", "g.mp4", "h.apk")) {
            File(radice, nome).writeText("x")
        }
        val documenti = cerca(radice, Filtro.DOCUMENTI).map { it.nome }.sorted()
        assertEquals(listOf("a.pdf", "b.docx", "c.ods", "d.pptx", "e.md", "f.epub"), documenti)
    }
}
