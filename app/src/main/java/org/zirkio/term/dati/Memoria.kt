package org.zirkio.term.dati

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * Elenco dei file del telefono. Su Android 11 e successivi la memoria condivisa
 * si legge solo con «Accesso a tutti i file» (`MANAGE_EXTERNAL_STORAGE`), che
 * l'utente concede dalla schermata di sistema; senza, si vedono le cartelle
 * private dell'app (`filesDir`) e nient'altro.
 */
object Memoria {

    /** Cartella da cui parte l'app: la memoria condivisa, o la sua area privata. */
    fun radice(context: Context): String =
        if (tuttoConcesso()) {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            context.filesDir.absolutePath
        }

    fun tuttoConcesso(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true

    /**
     * Voci della cartella. Cartelle non leggibili restano fuori dall'elenco
     * (su Android ce ne sono parecchie: `Android/data`, `Android/obb`).
     */
    fun elenco(percorso: String): List<Voce> {
        val cartella = File(percorso)
        val figli = cartella.listFiles() ?: throw java.io.IOException(
            if (cartella.exists()) "cartella non leggibile: $percorso"
            else "cartella inesistente: $percorso",
        )
        return figli.mapNotNull { f ->
            val cartellaFiglia = runCatching { f.isDirectory }.getOrElse { return@mapNotNull null }
            val dimensione = if (cartellaFiglia) 0L else runCatching { f.length() }.getOrDefault(0L)
            Voce(
                nome = f.name,
                percorso = f.absolutePath,
                cartella = cartellaFiglia,
                dimensione = dimensione,
                tipo = tipoFile(f.name, cartellaFiglia),
                modificato = runCatching { f.lastModified() }.getOrDefault(0L),
            )
        }
    }

    /** Crea una cartella dentro `percorso`; restituisce il messaggio d'errore o null. */
    fun creaCartella(percorso: String, nome: String): String? {
        val nuova = File(percorso, nome)
        if (nuova.exists()) return "esiste già: $nome"
        return if (nuova.mkdir()) null else "non riesco a creare $nome"
    }

    /** Cartella superiore, senza uscire da `/`. */
    fun su(percorso: String): String {
        val f = File(percorso).parentFile ?: return percorso
        return if (f.absolutePath.length < 2) "/" else f.absolutePath
    }
}
