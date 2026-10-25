package org.zirkio.term.ssh

import org.zirkio.term.dati.Sessione

/**
 * Prima registrazione della chiave su una macchina: si entra con la password (chiesta
 * all'utente, tenuta solo in memoria per questo collegamento) e si aggiunge la riga
 * pubblica a `~/.ssh/authorized_keys`. Dal collegamento successivo si entra con la chiave
 * e la password non serve più.
 */
object Installa {

    /**
     * Aggiunge [rigaPubblica] alle chiavi autorizzate di [sessione] usando [password].
     * Restituisce "" se è andata, altrimenti il motivo.
     */
    fun registra(
        sessione: Sessione,
        rigaPubblica: String,
        password: String,
        /** Chiamata con l'impronta della macchina se è la prima volta che la si vede. */
        suImpronta: ((String) -> Unit)? = null,
    ): String {
        val riga = rigaPubblica.replace("'", "").trim()
        if (riga.isBlank()) return "la chiave pubblica è vuota"
        val ssh = Configurazione.client()
        return try {
            ssh.addHostKeyVerifier(Verifica(sessione.impronta, suNuova = suImpronta))
            ssh.connect(sessione.host, sessione.porta)
            ssh.authPassword(sessione.utente, password)
            android.util.Log.i("zirkio", "password accettata, registro la chiave su ${sessione.host}")
            val comando = listOf(
                "mkdir -p ~/.ssh",
                "chmod 700 ~/.ssh",
                "touch ~/.ssh/authorized_keys",
                "chmod 600 ~/.ssh/authorized_keys",
                "grep -qxF '$riga' ~/.ssh/authorized_keys || printf '%s\\n' '$riga' >> ~/.ssh/authorized_keys",
                "echo ZIRKIO-OK",
            ).joinToString(" && ")
            val sessioneSsh = ssh.startSession()
            val esito = try {
                val cmd = sessioneSsh.exec(comando)
                val uscita = cmd.inputStream.bufferedReader().readText()
                val errore = cmd.errorStream.bufferedReader().readText()
                cmd.close()
                uscita to errore
            } finally {
                runCatching { sessioneSsh.close() }
            }
            if (esito.first.contains("ZIRKIO-OK")) {
                android.util.Log.i("zirkio", "chiave registrata su ${sessione.host}")
                ""
            } else {
                "la macchina non ha confermato (${esito.first.take(120)}${esito.second.take(120)})"
            }
        } catch (e: Exception) {
            android.util.Log.e("zirkio", "registrazione della chiave fallita: ${e.javaClass.simpleName}: ${e.message}")
            when {
                e.message?.contains("Auth", true) == true || e.message?.contains("auth", true) == true ->
                    "password rifiutata per ${sessione.utente}"
                else -> "non riesco a registrare la chiave: ${e.message}"
            }
        } finally {
            runCatching { ssh.disconnect() }
        }
    }
}
