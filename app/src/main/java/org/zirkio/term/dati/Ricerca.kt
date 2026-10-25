package org.zirkio.term.dati

import java.io.File

/** I collegamenti in cima all'elenco: tutto, solo pacchetti, solo documenti, i più recenti. */
enum class Filtro(val etichetta: String) {
    TUTTI("Tutti"),
    APK("APK"),
    DOCUMENTI("Documenti"),
    RECENTI("Recenti"),
}

/** Cartelle che non vale la pena attraversare: sono enormi e quasi sempre illeggibili. */
private val SALTATE = setOf("Android", "Lost.Dir", "LOST.DIR")

/**
 * Cerca dentro `cartella` e nelle sue sottocartelle i file che interessano al filtro,
 * **dal più recente al più vecchio**. `TUTTI` non è una ricerca: è l'elenco normale
 * della cartella (lo fa [Memoria.elenco]).
 *
 * Il giro si ferma a [limite] risultati e a [profondita] livelli, così un albero
 * enorme non blocca il telefono.
 */
fun cerca(cartella: File, filtro: Filtro, limite: Int = 300, profondita: Int = 12): List<Voce> {
    require(filtro != Filtro.TUTTI) { "il filtro TUTTI è l'elenco normale della cartella" }
    val trovati = mutableListOf<Voce>()
    val daVisitare = ArrayDeque<Pair<File, Int>>()
    daVisitare.addLast(cartella to 0)
    var visitate = 0
    while (daVisitare.isNotEmpty() && trovati.size < limite && visitate < 3000) {
        val (attuale, livello) = daVisitare.removeFirst()
        visitate++
        val figli = runCatching { attuale.listFiles() }.getOrNull() ?: continue
        for (figlio in figli) {
            val nome = figlio.name
            if (nome.startsWith(".")) continue
            if (runCatching { figlio.isDirectory }.getOrDefault(false)) {
                if (livello + 1 <= profondita && nome !in SALTATE) {
                    daVisitare.addLast(figlio to livello + 1)
                }
                continue
            }
            val ext = estensione(nome)
            val interessa = when (filtro) {
                Filtro.APK -> ext == "apk"
                Filtro.DOCUMENTI -> ext in ESTENSIONI_DOCUMENTI
                Filtro.RECENTI -> true
                Filtro.TUTTI -> false
            }
            if (!interessa) continue
            trovati.add(
                Voce(
                    nome = nome,
                    percorso = figlio.absolutePath,
                    cartella = false,
                    dimensione = runCatching { figlio.length() }.getOrDefault(0L),
                    tipo = tipoFile(nome, false),
                    modificato = runCatching { figlio.lastModified() }.getOrDefault(0L),
                    contenitore = attuale.name.ifEmpty { attuale.absolutePath },
                ),
            )
        }
    }
    return trovati.sortedByDescending { it.modificato }.take(limite)
}

/** Tiene solo le voci che il filtro accetta, dentro una cartella già elencata. */
fun filtraVoci(voci: List<Voce>, filtro: Filtro): List<Voce> {
    if (filtro == Filtro.TUTTI) return voci
    return voci.filter { v ->
        if (v.cartella) return@filter false
        val ext = estensione(v.nome)
        when (filtro) {
            Filtro.APK -> ext == "apk"
            Filtro.DOCUMENTI -> ext in ESTENSIONI_DOCUMENTI
            Filtro.RECENTI -> true
            Filtro.TUTTI -> true
        }
    }
}
