package org.zirkio.term.term

/**
 * Il cuore del terminale: una griglia di celle, il cursore, lo schermo alterno e
 * l'interpretazione delle sequenze ANSI/VT.
 *
 * È scritto in Kotlin puro, **senza Android**: così si collauda sul computer
 * (`./gradlew testReleaseUnitTest`) invece che a occhio sul telefono.
 *
 * Scelte:
 *  - niente riflusso delle righe lunghe al ridimensionamento (le righe si troncano o si
 *    allungano con spazi): è l'unica semplificazione che si nota, e costa poco;
 *  - il testo esce dallo schermo e finisce nello storico solo quando la regione di
 *    scorrimento è tutta la schermata (altrimenti `vim` riempirebbe lo storico di spazzatura).
 */
class Terminale(colonneIniziali: Int = 80, righeIniziali: Int = 24) {

    companion object {
        const val PREDEFINITO = -1
        /** Colore RGB: `RGB or 0x0100_0000`. */
        const val RGB = 0x0100_0000

        const val GRASSETTO = 1
        const val FIOCCO = 2          // dim
        const val CORSIVO = 4
        const val SOTTOLINEATO = 8
        const val INVERTITO = 16
        const val NASCOSTO = 32
        const val SBARRATO = 64

        fun coloreRgb(r: Int, g: Int, b: Int) = RGB or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
    }

    /** Una riga dello schermo o dello storico. */
    class Riga(val colonne: Int) {
        val testo = IntArray(colonne)
        val fg = IntArray(colonne) { PREDEFINITO }
        val bg = IntArray(colonne) { PREDEFINITO }
        val stile = ByteArray(colonne)

        fun copia(colonneNuove: Int): Riga {
            val r = Riga(colonneNuove)
            val n = minOf(colonne, colonneNuove)
            System.arraycopy(testo, 0, r.testo, 0, n)
            System.arraycopy(fg, 0, r.fg, 0, n)
            System.arraycopy(bg, 0, r.bg, 0, n)
            System.arraycopy(stile, 0, r.stile, 0, n)
            if (colonneNuove > colonne) {
                for (i in colonne until colonneNuove) r.fg[i] = PREDEFINITO
                for (i in colonne until colonneNuove) r.bg[i] = PREDEFINITO
            }
            return r
        }

        fun pulisci(fgPre: Int, bgPre: Int) {
            java.util.Arrays.fill(testo, 0)
            java.util.Arrays.fill(fg, fgPre)
            java.util.Arrays.fill(bg, bgPre)
            java.util.Arrays.fill(stile, 0)
        }

        fun testoFinoA(fine: Int): String {
            var ultimo = minOf(fine, colonne) - 1
            while (ultimo >= 0 && testo[ultimo] == 0) ultimo--
            if (ultimo < 0) return ""
            val sb = StringBuilder(ultimo + 1)
            for (i in 0..ultimo) sb.appendCodePoint(if (testo[i] == 0) ' '.code else testo[i])
            return sb.toString()
        }
    }

    var colonne: Int = colonneIniziali
        private set
    var righe: Int = righeIniziali
        private set

    private var schermo: Array<Riga> = Array(righe) { Riga(colonne) }
    private var alterno: Array<Riga>? = null

    /** Righe uscite dallo schermo (in cima). */
    val storico = ArrayDeque<Riga>()
    var storicoMassimo = 4000

    var cursoreRiga = 0
        private set
    var cursoreColonna = 0
        private set
    var cursoreVisibile = true
        private set

    private var fgCorrente = PREDEFINITO
    private var bgCorrente = PREDEFINITO
    private var stileCorrente = 0

    private var regioneAlta = 0
    private var regioneBassa = righe - 1

    private var aCapaAutomatico = true
    private var aCapaPendente = false
    private var origine = false
    private var schermoAlternoAttivo = false

    private var salvatoRiga = 0
    private var salvatoColonna = 0
    private var salvatoFg = PREDEFINITO
    private var salvatoBg = PREDEFINITO
    private var salvatoStile = 0

    private val tabStop = HashSet<Int>()

    /** Risposta da rimandare alla shell (DA, DSR…). */
    var rispondi: ((ByteArray) -> Unit)? = null
    /** Campanello (BEL). */
    var campanello: (() -> Unit)? = null
    /** Avviso «lo schermo è cambiato». */
    var suModifica: (() -> Unit)? = null

    private var ultimoCodice = ' '.code

    // ---------------------------------------------------------------- stato del parser
    private var stato = 0
    private val parametri = StringBuilder()
    private var intermedio: Char = 0.toChar()

    init {
        azzeraTab()
        pulisciTutto()
    }

    // ---------------------------------------------------------------- accesso alla griglia

    fun rigaVisibile(i: Int): Riga = if (i in 0 until righe) schermo[i] else Riga(colonne)

    /** Tutte le righe da mostrare: prima lo storico, poi lo schermo. */
    fun righeTotali(): Int = storico.size + righe

    fun riga(i: Int): Riga {
        val s = storico.size
        return if (i < s) storico.elementAt(i) else schermo[(i - s).coerceIn(0, righe - 1)]
    }

    fun testoVisibile(): String {
        val sb = StringBuilder()
        for (i in 0 until righe) {
            sb.append(schermo[i].testoFinoA(colonne))
            sb.append('\n')
        }
        return sb.toString().trimEnd('\n')
    }

    // ---------------------------------------------------------------- alimentazione

    /** Interpreta il testo arrivato dalla shell o dal comando locale. */
    fun alimenta(testo: CharSequence) {
        var cambiato = false
        for (ch in testo) {
            when (stato) {
                0 -> { // normale
                    val c = ch.code
                    when {
                        c == 0x1B -> stato = 1
                        c == 0x0A || c == 0x0B || c == 0x0C -> { nuovaRiga(); cambiato = true }
                        c == 0x0D -> { cursoreColonna = 0; aCapaPendente = false; cambiato = true }
                        c == 0x08 -> {
                            if (cursoreColonna > 0) cursoreColonna--
                            aCapaPendente = false
                            cambiato = true
                        }
                        c == 0x09 -> { tabulazione(); cambiato = true }
                        c == 0x07 -> campanello?.invoke()
                        c == 0x0E || c == 0x0F -> {} // SI/SO: giochi di caratteri, ignorati
                        c < 0x20 -> {}
                        c == 0x7F -> {}
                        else -> { scriviCarattere(c); cambiato = true }
                    }
                }
                1 -> { // dopo ESC
                    val c = ch.code
                    when (ch) {
                        '[' -> { stato = 2; parametri.setLength(0); intermedio = 0.toChar() }
                        ']' -> stato = 3
                        'P', 'X', '^', '_' -> stato = 3 // DCS/SOS/PM/APC: si scartano come OSC
                        'M' -> { indietroIndice(); cambiato = true; stato = 0 }
                        'D' -> { nuovaRiga(); cambiato = true; stato = 0 }
                        'E' -> { cursoreColonna = 0; nuovaRiga(); cambiato = true; stato = 0 }
                        '7' -> { salvaCursore(); stato = 0 }
                        '8' -> { ripristinaCursore(); cambiato = true; stato = 0 }
                        'c' -> { azzera(); cambiato = true; stato = 0 }
                        'H' -> { tabStop.add(cursoreColonna); stato = 0 }
                        '(' , ')', '*', '+', '#', '%' -> { stato = 5 } // un carattere da scartare
                        '=' , '>' -> stato = 0
                        else -> {
                            if (c in 0x20..0x2F) { stato = 4 } else stato = 0
                        }
                    }
                }
                2 -> { // dentro CSI
                    val c = ch.code
                    when {
                        c in 0x30..0x39 || c == 0x3B || c == 0x3A -> parametri.append(ch)
                        c in 0x3C..0x3F && parametri.isEmpty() -> parametri.append(ch) // < = > ?
                        c in 0x20..0x2F -> { if (intermedio == 0.toChar()) intermedio = ch }
                        c in 0x40..0x7E -> {
                            eseguiCsi(ch)
                            stato = 0
                            cambiato = true
                        }
                        else -> stato = 0
                    }
                }
                3 -> { // OSC/stringa: si scarta fino a BEL o ST
                    if (ch.code == 0x07) stato = 0
                    else if (ch.code == 0x1B) stato = 6
                }
                4 -> { stato = 0 }                 // ESC + intermedio: si scarta la finale
                5 -> { stato = 0 }                 // ESC ( X : si scarta
                6 -> stato = 0                      // ESC \ : fine stringa
            }
        }
        if (cambiato) suModifica?.invoke()
    }

    private fun scriviCarattere(codice: Int) {
        if (aCapaPendente && aCapaAutomatico) {
            cursoreColonna = 0
            nuovaRiga()
        }
        val riga = schermo[cursoreRiga]
        if (cursoreColonna >= colonne) {
            if (!aCapaAutomatico) cursoreColonna = colonne - 1
            else {
                cursoreColonna = 0
                nuovaRiga()
            }
        }
        riga.testo[cursoreColonna] = codice
        riga.fg[cursoreColonna] = fgCorrente
        riga.bg[cursoreColonna] = bgCorrente
        riga.stile[cursoreColonna] = stileCorrente.toByte()
        ultimoCodice = codice
        if (cursoreColonna == colonne - 1) aCapaPendente = true else cursoreColonna++
    }

    private fun tabulazione() {
        if (cursoreColonna >= colonne - 1) return
        var c = cursoreColonna + 1
        while (c < colonne - 1 && !tabStop.contains(c)) c++
        cursoreColonna = c
        aCapaPendente = false
    }

    private fun azzeraTab() {
        tabStop.clear()
        var i = 8
        while (i < 512) {
            tabStop.add(i)
            i += 8
        }
    }

    // ---------------------------------------------------------------- movimento

    fun nuovaRiga() {
        aCapaPendente = false
        if (cursoreRiga == regioneBassa) scorri(1) else if (cursoreRiga < righe - 1) cursoreRiga++
    }

    private fun indietroIndice() {
        aCapaPendente = false
        if (cursoreRiga == regioneAlta) scorri(-1) else if (cursoreRiga > 0) cursoreRiga--
    }

    private fun scorri(quante: Int) {
        if (quante > 0) {
            repeat(quante) {
                val via = schermo[regioneAlta]
                if (regioneAlta == 0 && regioneBassa == righe - 1 && !schermoAlternoAttivo) {
                    storico.addLast(via)
                    while (storico.size > storicoMassimo) storico.removeFirst()
                }
                for (i in regioneAlta until regioneBassa) schermo[i] = schermo[i + 1]
                val nuova = Riga(colonne)
                nuova.pulisci(fgCorrente, bgCorrente)
                schermo[regioneBassa] = nuova
            }
        } else if (quante < 0) {
            repeat(-quante) {
                for (i in regioneBassa downTo regioneAlta + 1) schermo[i] = schermo[i - 1]
                val nuova = Riga(colonne)
                nuova.pulisci(fgCorrente, bgCorrente)
                schermo[regioneAlta] = nuova
            }
        }
    }

    // ---------------------------------------------------------------- sequenze

    private fun numeri(): List<Int> {
        val t = parametri.toString()
        val pulito = if (t.startsWith("?") || t.startsWith("<") || t.startsWith("=") || t.startsWith(">"))
            t.substring(1) else t
        if (pulito.isEmpty()) return emptyList()
        return pulito.split(';').map { p -> p.substringBefore(':').toIntOrNull() ?: 0 }
    }

    private fun n(i: Int, predefinito: Int): Int {
        val v = numeri()
        val x = v.getOrNull(i) ?: 0
        return if (x == 0) predefinito else x
    }

    private fun eseguiCsi(fin: Char) {
        val v = numeri()
        val primo = v.getOrNull(0) ?: 0
        val privato = parametri.startsWith("?")
        when (fin) {
            'A' -> cursoreRiga = maxOf(regioneAlta.coerceAtLeast(0), cursoreRiga - n(0, 1))
            'B' -> cursoreRiga = minOf(regioneBassa, cursoreRiga + n(0, 1))
            'C' -> cursoreColonna = minOf(colonne - 1, cursoreColonna + n(0, 1))
            'D' -> cursoreColonna = maxOf(0, cursoreColonna - n(0, 1))
            'E' -> { nuovaRiga(); cursoreColonna = 0 }
            'F' -> { indietroIndice(); cursoreColonna = 0 }
            'G', '`' -> cursoreColonna = (n(0, 1) - 1).coerceIn(0, colonne - 1)
            'd' -> cursoreRiga = (n(0, 1) - 1 + if (origine) regioneAlta else 0).coerceIn(0, righe - 1)
            'H', 'f' -> {
                val r = (n(0, 1) - 1 + if (origine) regioneAlta else 0).coerceIn(0, righe - 1)
                val c = (n(1, 1) - 1).coerceIn(0, colonne - 1)
                cursoreRiga = r
                cursoreColonna = c
                aCapaPendente = false
            }
            'J' -> {
                when (primo) {
                    0 -> {
                        pulisciRiga(cursoreRiga, cursoreColonna, colonne)
                        for (i in cursoreRiga + 1 until righe) pulisciRiga(i, 0, colonne)
                    }
                    1 -> {
                        for (i in 0 until cursoreRiga) pulisciRiga(i, 0, colonne)
                        pulisciRiga(cursoreRiga, 0, cursoreColonna + 1)
                    }
                    2 -> for (i in 0 until righe) pulisciRiga(i, 0, colonne)
                    3 -> storico.clear()
                }
            }
            'K' -> when (primo) {
                0 -> pulisciRiga(cursoreRiga, cursoreColonna, colonne)
                1 -> pulisciRiga(cursoreRiga, 0, cursoreColonna + 1)
                2 -> pulisciRiga(cursoreRiga, 0, colonne)
            }
            'X' -> pulisciRiga(cursoreRiga, cursoreColonna, minOf(colonne, cursoreColonna + n(0, 1)))
            'P' -> {
                val riga = schermo[cursoreRiga]
                val q = n(0, 1).coerceAtMost(colonne - cursoreColonna)
                for (i in cursoreColonna until colonne - q) {
                    riga.testo[i] = riga.testo[i + q]
                    riga.fg[i] = riga.fg[i + q]
                    riga.bg[i] = riga.bg[i + q]
                    riga.stile[i] = riga.stile[i + q]
                }
                for (i in colonne - q until colonne) {
                    riga.testo[i] = 0; riga.fg[i] = fgCorrente; riga.bg[i] = bgCorrente; riga.stile[i] = stileCorrente.toByte()
                }
            }
            '@' -> {
                val riga = schermo[cursoreRiga]
                val q = n(0, 1).coerceAtMost(colonne - cursoreColonna)
                for (i in colonne - 1 downTo cursoreColonna + q) {
                    riga.testo[i] = riga.testo[i - q]
                    riga.fg[i] = riga.fg[i - q]
                    riga.bg[i] = riga.bg[i - q]
                    riga.stile[i] = riga.stile[i - q]
                }
                for (i in cursoreColonna until cursoreColonna + q) {
                    riga.testo[i] = 0; riga.fg[i] = fgCorrente; riga.bg[i] = bgCorrente; riga.stile[i] = stileCorrente.toByte()
                }
            }
            'L' -> ripeti(true, n(0, 1))
            'M' -> ripeti(false, n(0, 1))
            'S' -> scorri(n(0, 1))
            'T' -> scorri(-n(0, 1))
            'r' -> {
                val a = (n(0, 1) - 1).coerceIn(0, righe - 1)
                val b = (n(1, righe) - 1).coerceIn(0, righe - 1)
                if (a < b) {
                    regioneAlta = a
                    regioneBassa = b
                    cursoreRiga = if (origine) a else 0
                    cursoreColonna = 0
                }
            }
            'm' -> sgr(v.ifEmpty { listOf(0) })
            'b' -> repeat(n(0, 1)) { scriviCarattere(ultimoCodice) }
            'n' -> when (primo) {
                5 -> rispondi?.invoke("\u001B[0n".toByteArray())
                6 -> rispondi?.invoke("\u001B[${cursoreRiga + 1};${cursoreColonna + 1}R".toByteArray())
            }
            'c' -> rispondi?.invoke("\u001B[?1;2c".toByteArray())
            'h', 'l' -> {
                val attivo = fin == 'h'
                for (m in v) modalita(m, privato, attivo)
            }
            's' -> salvaCursore()
            'u' -> ripristinaCursore()
            'g' -> if (primo == 3) tabStop.clear() else tabStop.remove(cursoreColonna)
            'q' -> {} // DECSCA
            'p' -> if (primo == 1) { salvaCursore() }
            else -> {}
        }
    }

    private fun ripeti(righeDentro: Boolean, quante: Int) {
        if (cursoreRiga < regioneAlta || cursoreRiga > regioneBassa) return
        val q = quante.coerceAtMost(regioneBassa - cursoreRiga + 1)
        if (righeDentro) {
            for (i in regioneBassa downTo cursoreRiga + q) schermo[i] = schermo[i - q]
            for (i in cursoreRiga until cursoreRiga + q) {
                val r = Riga(colonne)
                r.pulisci(fgCorrente, bgCorrente)
                schermo[i] = r
            }
        } else {
            for (i in cursoreRiga..regioneBassa - q) schermo[i] = schermo[i + q]
            for (i in regioneBassa - q + 1..regioneBassa) {
                val r = Riga(colonne)
                r.pulisci(fgCorrente, bgCorrente)
                schermo[i] = r
            }
        }
    }

    private fun pulisciRiga(indice: Int, da: Int, a: Int) {
        if (indice !in 0 until righe) return
        val riga = schermo[indice]
        for (i in da.coerceAtLeast(0) until a.coerceAtMost(colonne)) {
            riga.testo[i] = 0
            riga.fg[i] = fgCorrente
            riga.bg[i] = bgCorrente
            riga.stile[i] = stileCorrente.toByte()
        }
    }

    private fun modalita(m: Int, privato: Boolean, attivo: Boolean) {
        if (!privato) {
            when (m) {
                4 -> aCapaAutomatico = false // modalità inserimento: ignorata
                20 -> {}
                else -> {}
            }
            return
        }
        when (m) {
            1 -> {} // cursore a frecce applicativo: lo gestisce la vista
            6 -> {
                origine = attivo
                cursoreRiga = if (attivo) regioneAlta else 0
                cursoreColonna = 0
            }
            7 -> aCapaAutomatico = attivo
            25 -> cursoreVisibile = attivo
            1047, 1049, 47 -> cambiaSchermo(attivo)
            else -> {}
        }
    }

    private fun cambiaSchermo(versoAlterno: Boolean) {
        if (versoAlterno == schermoAlternoAttivo) return
        if (versoAlterno) {
            salvatoRiga = cursoreRiga
            salvatoColonna = cursoreColonna
            alterno = schermo
            schermo = Array(righe) { i ->
                if (i < righe) {
                    val r = Riga(colonne)
                    r.pulisci(PREDEFINITO, PREDEFINITO)
                    r
                } else Riga(colonne)
            }
            regioneAlta = 0
            regioneBassa = righe - 1
            cursoreRiga = 0
            cursoreColonna = 0
            schermoAlternoAttivo = true
        } else {
            alterno?.let { schermo = it }
            alterno = null
            regioneAlta = 0
            regioneBassa = righe - 1
            cursoreRiga = salvatoRiga.coerceIn(0, righe - 1)
            cursoreColonna = salvatoColonna.coerceIn(0, colonne - 1)
            schermoAlternoAttivo = false
        }
        aCapaPendente = false
    }

    private fun sgr(v: List<Int>) {
        var i = 0
        while (i < v.size) {
            when (val c = v[i]) {
                0 -> { fgCorrente = PREDEFINITO; bgCorrente = PREDEFINITO; stileCorrente = 0 }
                1 -> stileCorrente = stileCorrente or GRASSETTO
                2 -> stileCorrente = stileCorrente or FIOCCO
                3 -> stileCorrente = stileCorrente or CORSIVO
                4 -> stileCorrente = stileCorrente or SOTTOLINEATO
                5, 6 -> {}
                7 -> stileCorrente = stileCorrente or INVERTITO
                8 -> stileCorrente = stileCorrente or NASCOSTO
                9 -> stileCorrente = stileCorrente or SBARRATO
                21, 22 -> stileCorrente = stileCorrente and GRASSETTO.inv() and FIOCCO.inv()
                23 -> stileCorrente = stileCorrente and CORSIVO.inv()
                24 -> stileCorrente = stileCorrente and SOTTOLINEATO.inv()
                27 -> stileCorrente = stileCorrente and INVERTITO.inv()
                28 -> stileCorrente = stileCorrente and NASCOSTO.inv()
                29 -> stileCorrente = stileCorrente and SBARRATO.inv()
                in 30..37 -> fgCorrente = c - 30
                38 -> i = coloreEsteso(v, i) { colore -> fgCorrente = colore }
                39 -> fgCorrente = PREDEFINITO
                in 40..47 -> bgCorrente = c - 40
                48 -> i = coloreEsteso(v, i) { colore -> bgCorrente = colore }
                49 -> bgCorrente = PREDEFINITO
                in 90..97 -> fgCorrente = c - 90 + 8
                in 100..107 -> bgCorrente = c - 100 + 8
                else -> {}
            }
            i++
        }
    }

    private inline fun coloreEsteso(v: List<Int>, i: Int, assegna: (Int) -> Unit): Int {
        return when (v.getOrNull(i + 1)) {
            5 -> {
                assegna((v.getOrNull(i + 2) ?: 0).coerceIn(0, 255)); i + 2
            }
            2 -> {
                val r = v.getOrNull(i + 2) ?: 0
                val g = v.getOrNull(i + 3) ?: 0
                val b = v.getOrNull(i + 4) ?: 0
                assegna(coloreRgb(r, g, b)); i + 4
            }
            else -> i
        }
    }

    private fun salvaCursore() {
        salvatoRiga = cursoreRiga
        salvatoColonna = cursoreColonna
        salvatoFg = fgCorrente
        salvatoBg = bgCorrente
        salvatoStile = stileCorrente
    }

    private fun ripristinaCursore() {
        cursoreRiga = salvatoRiga.coerceIn(0, righe - 1)
        cursoreColonna = salvatoColonna.coerceIn(0, colonne - 1)
        fgCorrente = salvatoFg
        bgCorrente = salvatoBg
        stileCorrente = salvatoStile
        aCapaPendente = false
    }

    /** `reset` completo (ESC c). */
    fun azzera() {
        fgCorrente = PREDEFINITO
        bgCorrente = PREDEFINITO
        stileCorrente = 0
        regioneAlta = 0
        regioneBassa = righe - 1
        aCapaAutomatico = true
        aCapaPendente = false
        origine = false
        cursoreVisibile = true
        cursoreRiga = 0
        cursoreColonna = 0
        schermoAlternoAttivo = false
        alterno = null
        azzeraTab()
        pulisciTutto()
    }

    private fun pulisciTutto() {
        for (i in 0 until righe) {
            schermo[i] = Riga(colonne)
            schermo[i].pulisci(PREDEFINITO, PREDEFINITO)
        }
    }

    /** Cancella lo schermo (non lo storico). */
    fun pulisciSchermo() {
        pulisciTutto()
        cursoreRiga = 0
        cursoreColonna = 0
        aCapaPendente = false
        suModifica?.invoke()
    }

    /**
     * Ridimensionamento: le righe in eccesso finiscono nello storico, quelle mancanti si
     * aggiungono in fondo.  Le righe lunghe **non** si rifluiscono.
     */
    fun ridimensiona(colonneNuove: Int, righeNuove: Int) {
        if (colonneNuove == colonne && righeNuove == righe) return
        val vecchie = schermo
        val nuove = Array(righeNuove) { Riga(colonneNuove) }
        for (r in nuove) r.pulisci(fgCorrente, bgCorrente)
        val n = minOf(righe, righeNuove)
        for (i in 0 until n) nuove[i] = vecchie[i].copia(colonneNuove)
        if (righeNuove < righe) {
            for (i in righeNuove until righe) {
                storico.addLast(vecchie[i])
                while (storico.size > storicoMassimo) storico.removeFirst()
            }
        }
        colonne = colonneNuove
        righe = righeNuove
        schermo = nuove
        regioneAlta = 0
        regioneBassa = righe - 1
        cursoreRiga = cursoreRiga.coerceIn(0, righe - 1)
        cursoreColonna = cursoreColonna.coerceIn(0, colonne - 1)
        aCapaPendente = false
        if (tabStop.isEmpty()) azzeraTab()
        suModifica?.invoke()
    }
}
