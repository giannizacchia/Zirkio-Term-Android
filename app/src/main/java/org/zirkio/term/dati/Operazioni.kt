package org.zirkio.term.dati

import java.io.File

/**
 * Spostamenti e cancellazioni sulla memoria del telefono.
 *
 * Lo spostamento prova prima la rinomina (istantanea sullo stesso filesystem) e, se
 * non riesce perché la destinazione è su un'altra partizione, copia e poi cancella.
 * Non si sovrascrive mai niente: se il nome esiste già, la voce viene saltata e
 * l'utente lo viene a sapere.
 */
object Operazioni {

    /** Sposta le voci dentro `destinazione`; restituisce i messaggi dei casi andati male. */
    fun spostaLocale(voci: List<File>, destinazione: File): List<String> {
        val errori = mutableListOf<String>()
        if (!destinazione.isDirectory) return listOf("${destinazione.absolutePath} non è una cartella")
        for (voce in voci) {
            val arrivo = File(destinazione, voce.name)
            if (voce.parentFile?.absolutePath == destinazione.absolutePath) continue
            if (!voce.exists()) {
                errori.add("non c'è più: ${voce.name}")
                continue
            }
            if (arrivo.exists()) {
                errori.add("esiste già: ${voce.name}")
                continue
            }
            val rinominata = runCatching { voce.renameTo(arrivo) }.getOrDefault(false)
            if (rinominata) continue
            // partizione diversa: copia e poi butta via l'originale
            val copiata = runCatching { voce.copyRecursively(arrivo, overwrite = false) }.isSuccess
            if (!copiata) {
                errori.add("non riesco a spostare ${voce.name}")
                continue
            }
            val cancellata = runCatching {
                if (voce.isDirectory) voce.deleteRecursively() else voce.delete()
            }.getOrDefault(false)
            if (!cancellata) errori.add("copiato ma non cancellato: ${voce.name}")
        }
        return errori
    }

    /** Cancella le voci (per le cartelle: tutto il contenuto); restituisce gli errori. */
    fun eliminaLocale(voci: List<File>): List<String> {
        val errori = mutableListOf<String>()
        for (voce in voci) {
            val fatto = runCatching {
                if (voce.isDirectory) voce.deleteRecursively() else voce.delete()
            }.getOrDefault(false)
            if (!fatto) errori.add("non riesco a cancellare ${voce.name}")
        }
        return errori
    }
}
