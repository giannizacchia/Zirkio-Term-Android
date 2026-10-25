package org.zirkio.term.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Collaudo del cuore del terminale, senza telefono: `./gradlew testReleaseUnitTest`.
 * Le sequenze qui sotto sono quelle che mandano davvero `bash`, `ls --color`, `vim` e `htop`.
 */
class TerminaleTest {

    private fun testo(t: Terminale): List<String> =
        (0 until t.righe).map { t.rigaVisibile(it).testoFinoA(t.colonne) }

    @Test
    fun scrive_e_va_a_capo() {
        val t = Terminale(10, 3)
        t.alimenta("abc")
        assertEquals("abc", testo(t)[0])
        assertEquals(0, t.cursoreRiga)
        assertEquals(3, t.cursoreColonna)
        t.alimenta("\r\nxyz")
        assertEquals("xyz", testo(t)[1])
    }

    @Test
    fun va_a_capo_da_sola_alla_fine_riga() {
        val t = Terminale(5, 3)
        t.alimenta("abcdefg")
        assertEquals("abcde", testo(t)[0])
        assertEquals("fg", testo(t)[1])
    }

    @Test
    fun colori_sgr() {
        val t = Terminale(20, 2)
        t.alimenta("\u001B[31mrosso\u001B[0m")
        val riga = t.rigaVisibile(0)
        assertEquals(1, riga.fg[0])       // rosso = indice 1
        assertEquals(Terminale.PREDEFINITO, riga.bg[0])
        assertEquals(Terminale.PREDEFINITO, riga.fg[10])
    }

    @Test
    fun grassetto_e_colori_estesi() {
        val t = Terminale(20, 2)
        t.alimenta("\u001B[1;38;5;208mX\u001B[48;2;10;20;30mY")
        val r = t.rigaVisibile(0)
        assertTrue(r.stile[0].toInt() and Terminale.GRASSETTO != 0)
        assertEquals(208, r.fg[0])
        assertEquals(Terminale.coloreRgb(10, 20, 30), r.bg[1])
    }

    @Test
    fun cancella_riga_e_schermo() {
        val t = Terminale(10, 3)
        t.alimenta("abcdef\r\nghijkl\r\nmnopqr")
        t.alimenta("\u001B[2;3H\u001B[K")     // riga 2, colonna 3: cancella fino in fondo
        assertEquals("gh", testo(t)[1])
        t.alimenta("\u001B[2J")
        assertEquals("", testo(t)[0])
        assertEquals("", testo(t)[2])
    }

    @Test
    fun cursore_con_csi() {
        val t = Terminale(10, 4)
        t.alimenta("\u001B[3;4HX")
        assertEquals("   X", testo(t)[2])
        t.alimenta("\u001B[1;1H")
        assertEquals(0, t.cursoreRiga)
        assertEquals(0, t.cursoreColonna)
    }

    @Test
    fun lo_schermo_che_esce_va_nello_storico() {
        val t = Terminale(10, 2)
        t.alimenta("uno\r\ndue\r\ntre\r\n")
        assertEquals(2, t.storico.size)
        assertEquals("uno", t.storico.first().testoFinoA(10))
        assertEquals("due", t.storico.last().testoFinoA(10))
        // l'ultimo a capo ha spinto "tre" in cima allo schermo
        assertEquals("tre", testo(t)[0])
    }

    @Test
    fun schermo_alterno_conserva_quello_di_prima() {
        val t = Terminale(10, 3)
        t.alimenta("prima")
        t.alimenta("\u001B[?1049h")
        assertEquals("", testo(t)[0])
        t.alimenta("alterno")
        assertEquals("alterno", testo(t)[0])
        t.alimenta("\u001B[?1049l")
        assertEquals("prima", testo(t)[0])
    }

    @Test
    fun tabulazione_e_backspace() {
        val t = Terminale(20, 2)
        t.alimenta("a\tb")
        assertEquals("a       b", testo(t)[0])
        t.alimenta("\bb")
        assertEquals("a       b", testo(t)[0].substring(0, 9))
    }

    @Test
    fun ridimensionamento_tiene_il_testo() {
        val t = Terminale(10, 3)
        t.alimenta("ciao\r\nmondo")
        t.ridimensiona(20, 3)
        assertEquals(20, t.colonne)
        assertEquals("ciao", testo(t)[0])
        assertEquals("mondo", testo(t)[1])
    }

    @Test
    fun il_testo_resta_dopo_un_ridimensionamento_stretto() {
        val t = Terminale(80, 24)
        t.alimenta("Zirkio Term ok\r\nseconda riga")
        assertEquals("Zirkio Term ok", testo(t)[0])
        t.ridimensiona(46, 41)   // come quando la vista si misura sul telefono
        assertEquals(46, t.colonne)
        assertEquals(0, t.storico.size)
        assertEquals("Zirkio Term ok", testo(t)[0])
        assertEquals("seconda riga", testo(t)[1])
    }

    @Test
    fun risponde_alla_richiesta_di_posizione() {
        val t = Terminale(10, 3)
        var risposta = ""
        t.rispondi = { risposta = String(it) }
        t.alimenta("\u001B[2;5H\u001B[6n")
        assertEquals("\u001B[2;5R", risposta)
    }
}
