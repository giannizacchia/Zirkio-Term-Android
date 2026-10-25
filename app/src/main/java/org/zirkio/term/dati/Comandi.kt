package org.zirkio.term.dati

/**
 * I comandi che l'app prende per sé invece di mandarli alla shell.
 *
 * Oggi uno solo: `@sd <nome>` spegne una macchina salvata.  Il tipo di comando si
 * riconosce dalla riga digitata, come sul desktop (`@sh`, `@scp`, `@sd`).
 */
object Comandi {

    /**
     * Il comando che spegne la macchina, lato remoto.
     *
     * `sudo -S` legge la password dallo **standard input** (`-p ''` per non stampare
     * un prompt che non servirebbe a nessuno): così non serve un terminale remoto e
     * la password non finisce mai dentro alla riga di comando — dove la vedrebbe
     * `ps` sulla macchina.
     */
    const val SPEGNI = "sudo -S -p '' shutdown -h now"

    /**
     * Se [riga] è `@sd <nome>`, restituisce il nome della macchina; altrimenti null.
     *
     * Il confronto è insensibile alle maiuscole: `@sd sara` e `@sd SARA` sono la
     * stessa macchina.
     */
    fun macchinaDaSpegnere(riga: String): String? {
        val testo = riga.trim()
        if (!testo.startsWith("@sd", ignoreCase = true)) return null
        val resto = testo.removePrefix("@sd").trim()
        if (resto.isEmpty() || testo.length < 4) return null
        // `@sdx` non è `@sd x`
        if (testo[3] != ' ') return null
        return resto
    }
}
