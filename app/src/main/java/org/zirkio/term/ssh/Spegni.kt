package org.zirkio.term.ssh

import net.schmizz.sshj.userauth.password.PasswordUtils
import org.zirkio.term.dati.Comandi
import org.zirkio.term.dati.Sessione
import java.util.concurrent.TimeUnit

/**
 * Spegne una macchina salvata (`@sd <nome>`): un canale SSH di servizio, il comando
 * [`Comandi.SPEGNI`], e la password di sudo passata sullo **standard input**.
 *
 * Non passa dal terminale aperto: quello è la sessione su cui stai lavorando e non va
 * disturbata.  La password di sudo si chiede ogni volta e non si salva (il telefono non
 * ha un portachiavi come quello del computer).
 */
object Spegni {

    /**
     * Esegue lo spegnimento.  Restituisce "" se il comando è partito, altrimenti il
     * motivo (in italiano, da mostrare all'utente).
     */
    fun esegui(
        sessione: Sessione,
        percorsoChiave: String?,
        frase: String?,
        passwordSudo: String,
    ): String {
        if (percorsoChiave.isNullOrEmpty()) {
            // Senza chiave servirebbe la password di accesso, che qui non abbiamo.
            return "«${sessione.nome}» non ha una chiave: collegati una volta dal terminale " +
                "e registrala, poi lo spegnimento funziona"
        }
        val ssh = Configurazione.client()
        return try {
            ssh.connectTimeout = 12000
            ssh.timeout = 15000
            // stessa regola del resto dell'app: impronta nota = deve combaciare
            ssh.addHostKeyVerifier(Verifica(sessione.impronta))
            ssh.connect(sessione.host, sessione.porta)
            val provider = if (frase.isNullOrEmpty()) {
                ssh.loadKeys(percorsoChiave)
            } else {
                ssh.loadKeys(percorsoChiave, PasswordUtils.createOneOff(frase.toCharArray()))
            }
            ssh.authPublickey(sessione.utente, provider)
            val canale = ssh.startSession()
            try {
                val cmd = canale.exec(Comandi.SPEGNI)
                // la password va su stdin: sudo -S la legge da lì, niente terminale
                cmd.outputStream.write((passwordSudo + "\n").toByteArray())
                cmd.outputStream.flush()
                val uscita = cmd.inputStream.bufferedReader().readText()
                val errore = cmd.errorStream.bufferedReader().readText()
                cmd.join(10, TimeUnit.SECONDS)
                val stato = cmd.exitStatus
                cmd.close()
                android.util.Log.i("zirkio", "spegnimento di ${sessione.nome}: stato=$stato uscita=${uscita.take(120)} errore=${errore.take(120)}")
                when {
                    stato == 0 -> ""
                    errore.contains("incorrect password", true) ||
                        errore.contains("Sorry, try again", true) ->
                        "password di sudo rifiutata su ${sessione.host}"
                    errore.isNotBlank() -> "la macchina ha risposto: ${errore.trim().take(200)}"
                    uscita.isNotBlank() -> "la macchina ha risposto: ${uscita.trim().take(200)}"
                    else -> "lo spegnimento non è partito (stato $stato)"
                }
            } finally {
                runCatching { canale.close() }
            }
        } catch (e: Exception) {
            android.util.Log.e("zirkio", "spegnimento fallito: ${e.javaClass.simpleName}: ${e.message}")
            val testo = e.message ?: e.javaClass.simpleName
            when {
                testo.contains("auth", true) && testo.contains("fail", true) ->
                    "la macchina non ha accettato la chiave per ${sessione.utente}"
                testo.contains("Connection refused", true) ->
                    "connessione rifiutata da ${sessione.host}:${sessione.porta}"
                testo.contains("UnknownHost", true) -> "nome della macchina non risolto: ${sessione.host}"
                testo.contains("timed out", true) || testo.contains("timeout", true) ->
                    "nessuna risposta da ${sessione.host}"
                testo.contains("Host key", true) -> "la chiave della macchina è cambiata: non procedo"
                else -> "non riesco a spegnere ${sessione.nome}: $testo"
            }
        } finally {
            runCatching { ssh.disconnect() }
        }
    }
}
