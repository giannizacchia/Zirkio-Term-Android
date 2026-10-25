package org.zirkio.term.dati

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Una macchina salvata. */
@Serializable
data class Sessione(
    val id: String = UUID.randomUUID().toString(),
    var nome: String = "",
    var host: String = "",
    var porta: Int = 22,
    var utente: String = "",
    /** Percorso del file della chiave privata (in `filesDir/chiavi`), vuoto = password. */
    var chiave: String = "",
    /** Impronta dell'host key accettata al primo collegamento (`SHA256:…`). */
    var impronta: String = "",
    var descrizione: String = "",
) {
    fun etichetta(): String = if (descrizione.isBlank()) "$nome · $utente@$host" else "$nome · $utente@$host · $descrizione"
}

/**
 * Le sessioni stanno in un file JSON nella cartella privata dell'app: quella cartella è
 * leggibile solo da questa app (isolamento dell'UID), quindi le chiavi non finiscono dove
 * possono leggerle gli altri.
 */
class ArchivioSessioni(context: Context) {

    private val file = File(context.filesDir, "sessioni.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun leggi(): List<Sessione> = runCatching {
        if (!file.exists()) emptyList() else json.decodeFromString<List<Sessione>>(file.readText())
    }.getOrDefault(emptyList())

    fun scrivi(sessioni: List<Sessione>) {
        runCatching { file.writeText(json.encodeToString(ListSerializer(Sessione.serializer()), sessioni)) }
    }

    fun aggiungi(sessione: Sessione): List<Sessione> {
        val attuali = leggi().filterNot { it.id == sessione.id } + sessione
        scrivi(attuali)
        return attuali
    }

    fun elimina(id: String): List<Sessione> {
        val attuali = leggi().filterNot { it.id == id }
        scrivi(attuali)
        return attuali
    }

    /** Cartella delle chiavi private (creata al bisogno). */
    fun cartellaChiavi(context: Context): File =
        File(context.filesDir, "chiavi").apply { mkdirs() }

    fun salvaChiave(context: Context, id: String, testo: String): File {
        val f = File(cartellaChiavi(context), id)
        f.writeText(testo)
        runCatching { f.setReadable(false, false) }
        f.setReadable(true, true)
        f.setWritable(true, true)
        return f
    }
}
