package org.zirkio.term.ssh

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.StreamCopier
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.xfer.TransferListener
import org.zirkio.term.dati.Voce
import org.zirkio.term.dati.tipoFile
import java.io.File

/**
 * Il lato trasferimenti: SFTP sopra la **stessa** connessione del terminale,
 * come sul desktop dove la finestra Trasferimenti usa la sessione già aperta.
 *
 * Ogni chiamata blocca: va invocata da un thread di lavoro, mai dal filo
 * dell'interfaccia.
 */
class Trasferimenti(
    private val fornitore: () -> SSHClient?,
    private val descrizione: String = "la macchina",
) {

    @Volatile private var canale: SFTPClient? = null

    private fun sftp(): SFTPClient {
        canale?.let { vecchio ->
            // il canale SFTP resta buono finché la connessione è su
            if (runCatching { vecchio.statExistence(".") }.isSuccess) return vecchio
        }
        val ssh = fornitore() ?: error("non collegato a $descrizione")
        return ssh.newSFTPClient().also { canale = it }
    }

    /** Cartella iniziale: la casa dell'utente sulla macchina, in forma assoluta. */
    fun casa(): String = runCatching { sftp().canonicalize(".") }.getOrDefault(".")

    fun elenco(percorso: String): List<Voce> {
        val c = sftp()
        return c.ls(percorso).mapNotNull { info ->
            val attributi = runCatching { c.lstat(info.path) }.getOrNull() ?: info.attributes
            val cartella = attributi?.let {
                runCatching { it.type == net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY }.getOrDefault(false)
            } ?: info.isDirectory
            Voce(
                nome = info.name,
                percorso = info.path,
                cartella = cartella,
                dimensione = if (cartella) 0L else (attributi?.size ?: 0L),
                tipo = tipoFile(info.name, cartella),
                // SFTP dà i secondi, qui si ragiona in millisecondi
                modificato = (attributi?.mtime ?: 0L) * 1000L,
            )
        }
    }

    /** Crea una cartella; restituisce il messaggio d'errore o null. */
    fun creaCartella(percorso: String, nome: String): String? = runCatching {
        sftp().mkdirs(unisca(percorso, nome))
        null
    }.getOrElse { it.message ?: "non riesco a creare $nome" }

    /**
     * Manda un file o una cartella (intera) nella cartella remota indicata.
     * `avanzamento(trasferiti, totali)` viene chiamato durante la copia del singolo file.
     */
    fun invia(
        locale: File,
        cartellaRemota: String,
        avanzamento: (Long, Long) -> Unit,
        novita: (String) -> Unit,
    ): String? = runCatching {
        val c = sftp()
        val destinazione = unisca(cartellaRemota, locale.name)
        if (locale.isDirectory) {
            caricaCartella(c, locale, destinazione, avanzamento, novita)
        } else {
            novita("invio ${locale.name}")
            ascoltatore(c, avanzamento)
            c.put(locale.absolutePath, destinazione, locale.length())
        }
        null
    }.getOrElse { it.message ?: "invio non riuscito" }

    private fun caricaCartella(
        c: SFTPClient,
        locale: File,
        remoto: String,
        avanzamento: (Long, Long) -> Unit,
        novita: (String) -> Unit,
    ) {
        c.mkdirs(remoto)
        locale.listFiles()?.forEach { figlio ->
            val destinazione = unisca(remoto, figlio.name)
            if (figlio.isDirectory) {
                caricaCartella(c, figlio, destinazione, avanzamento, novita)
            } else {
                novita("invio ${figlio.name}")
                ascoltatore(c, avanzamento)
                c.put(figlio.absolutePath, destinazione, figlio.length())
            }
        }
    }

    /**
     * Prende un file remoto e lo salva nella cartella locale indicata.
     * Le cartelle remote vengono ricreate sotto la destinazione.
     */
    fun ricevi(
        remoto: String,
        cartellaLocale: File,
        cartella: Boolean,
        avanzamento: (Long, Long) -> Unit,
        novita: (String) -> Unit,
    ): String? = runCatching {
        val c = sftp()
        val nome = remoto.trimEnd('/').substringAfterLast('/').ifEmpty { "senza-nome" }
        if (cartella) {
            scaricaCartella(c, remoto, File(cartellaLocale, nome), avanzamento, novita)
        } else {
            novita("ricevo $nome")
            val destinazione = File(cartellaLocale, nome)
            val totali = runCatching { c.size(remoto) }.getOrDefault(0L)
            c.fileTransfer.transferListener = ascoltatore(c, avanzamento)
            c.get(remoto, destinazione.absolutePath, totali)
        }
        null
    }.getOrElse { it.message ?: "ricezione non riuscita" }

    private fun scaricaCartella(
        c: SFTPClient,
        remoto: String,
        locale: File,
        avanzamento: (Long, Long) -> Unit,
        novita: (String) -> Unit,
    ) {
        locale.mkdirs()
        c.ls(remoto).forEach { info ->
            val figlioRemoto = info.path
            val figlioLocale = File(locale, info.name)
            if (info.isDirectory) {
                scaricaCartella(c, figlioRemoto, figlioLocale, avanzamento, novita)
            } else {
                novita("ricevo ${info.name}")
                val totali = runCatching { info.attributes?.size ?: 0L }.getOrDefault(0L)
                c.fileTransfer.transferListener = ascoltatore(c, avanzamento)
                c.get(figlioRemoto, figlioLocale.absolutePath, totali)
            }
        }
    }

    private fun ascoltatore(c: SFTPClient, avanzamento: (Long, Long) -> Unit): TransferListener {
        val ascoltatore = object : TransferListener {
            override fun directory(name: String?): TransferListener = this
            override fun file(name: String?, size: Long): StreamCopier.Listener =
                StreamCopier.Listener { trasferiti -> if (size > 0) avanzamento(trasferiti, size) }
        }
        c.fileTransfer.transferListener = ascoltatore
        return ascoltatore
    }

    /** Sposta file e cartelle dentro un'altra cartella della stessa macchina. */
    fun spostaRemoto(percorsi: List<String>, destinazione: String): List<String> {
        val c = sftp()
        val errori = mutableListOf<String>()
        for (percorso in percorsi) {
            val nome = percorso.trimEnd('/').substringAfterLast('/')
            val arrivo = unisca(destinazione, nome)
            if (percorso.trimEnd('/') == arrivo) continue
            if (runCatching { c.statExistence(arrivo) }.getOrNull() != null) {
                errori.add("esiste già: $nome")
                continue
            }
            val rinominata = runCatching { c.rename(percorso, arrivo) }.isSuccess
            if (!rinominata) errori.add("non riesco a spostare $nome")
        }
        return errori
    }

    /** Cancella file e cartelle (per le cartelle: tutto il contenuto). */
    fun eliminaRemoto(percorsi: List<String>): List<String> {
        val c = sftp()
        val errori = mutableListOf<String>()
        for (percorso in percorsi) {
            val esito = runCatching { cancella(c, percorso) }
            esito.exceptionOrNull()?.let {
                errori.add("${percorso.trimEnd('/').substringAfterLast('/')}: ${it.message ?: "non riesco"}")
            }
        }
        return errori
    }

    private fun cancella(c: SFTPClient, percorso: String) {
        val attributi = c.stat(percorso)
        if (attributi.type == net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY) {
            c.ls(percorso).forEach { figlio -> cancella(c, figlio.path) }
            c.rmdir(percorso)
        } else {
            c.rm(percorso)
        }
    }

    fun chiudi() {
        runCatching { canale?.close() }
        canale = null
    }

    companion object {
        /** Unisce cartella e nome tenendo conto della barra finale. */
        fun unisca(cartella: String, nome: String): String =
            if (cartella.isEmpty() || cartella == ".") nome
            else cartella.trimEnd('/') + "/" + nome
    }
}
