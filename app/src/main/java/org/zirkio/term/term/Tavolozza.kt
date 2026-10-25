package org.zirkio.term.term

/** I colori del terminale: tavolozza a 256 voci e i due colori predefiniti del tema. */
object Tavolozza {

    /** Sfondo e testo del tema scuro «Zirkio». */
    const val SFONDO = 0xFF15171C.toInt()
    const val TESTO = 0xFFD8DEE9.toInt()

    private val base = intArrayOf(
        0xFF2E3440.toInt(), // 0 nero
        0xFFBF616A.toInt(), // 1 rosso
        0xFFA3BE8C.toInt(), // 2 verde
        0xFFEBCB8B.toInt(), // 3 giallo
        0xFF3584E4.toInt(), // 4 blu (lo stesso delle cartelle nell'app)
        0xFFB48EAD.toInt(), // 5 magenta
        0xFF88C0D0.toInt(), // 6 ciano
        0xFFE5E9F0.toInt(), // 7 bianco
        0xFF4C566A.toInt(), // 8 nero chiaro
        0xFFD08770.toInt(), // 9 rosso chiaro
        0xFFB9D7A0.toInt(), // 10 verde chiaro
        0xFFF5D9A0.toInt(), // 11 giallo chiaro
        0xFF5CA0F2.toInt(), // 12 blu chiaro
        0xFFCBA3C5.toInt(), // 13 magenta chiaro
        0xFF9FD3DF.toInt(), // 14 ciano chiaro
        0xFFF5F7FA.toInt(), // 15 bianco brillante
    )

    private val tavolozza = IntArray(256)

    init {
        for (i in 0 until 16) tavolozza[i] = base[i]
        var i = 16
        val passi = intArrayOf(0, 95, 135, 175, 215, 255)
        for (r in 0 until 6) for (g in 0 until 6) for (b in 0 until 6) {
            tavolozza[i++] = (0xFF shl 24) or (passi[r] shl 16) or (passi[g] shl 8) or passi[b]
        }
        for (k in 0 until 24) {
            val v = 8 + k * 10
            tavolozza[i++] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
    }

    /** Da valore di cella (`-1`, indice, o `RGB|x`) a colore ARGB. */
    fun argb(valore: Int, predefinito: Int, grassetto: Boolean = false): Int = when {
        valore == Terminale.PREDEFINITO -> predefinito
        valore >= Terminale.RGB -> (0xFF shl 24) or (valore and 0xFFFFFF)
        valore in 0..255 ->
            if (grassetto && valore < 8) tavolozza[valore + 8] else tavolozza[valore]
        else -> predefinito
    }
}
