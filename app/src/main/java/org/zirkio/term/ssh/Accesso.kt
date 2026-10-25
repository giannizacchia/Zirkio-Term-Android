package org.zirkio.term.ssh

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import org.zirkio.term.dati.Sessione
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

/**
 * Connessione SSH usata **solo** per i trasferimenti: niente shell, quindi niente
 * terminale da aprire. Si apre alla prima richiesta e si chiude alla fine del lavoro.
 *
 * Autenticazione: chiave della sessione se c'è, altrimenti la password che l'utente
 * ha appena digitato (che non viene mai salvata su disco).
 */
class Accesso(
    private val sessione: Sessione,
    private val percorsoChiave: String?,
    private val password: String?,
    /** Frase d'ordine della chiave, se è protetta. */
    private val fraseChiave: String? = null,
) {

    @Volatile private var ssh: SSHClient? = null

    fun client(): SSHClient {
        ssh?.let { if (it.isConnected) return it }
        val nuovo = Configurazione.client()
        nuovo.connectTimeout = 12000
        nuovo.timeout = 12000
        nuovo.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                val sha = MessageDigest.getInstance("SHA-256").digest(key.encoded)
                val impronta = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(sha)
                // primo collegamento: si accetta e si ricorda; dopo, deve combaciare
                if (sessione.impronta.isEmpty()) return true
                return impronta == sessione.impronta
            }

            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
        })
        android.util.Log.i(
            "zirkio",
            "accesso per i file a ${sessione.host}:${sessione.porta} utente=${sessione.utente} " +
                "chiave=${percorsoChiave?.substringAfterLast('/')} frase=${fraseChiave?.isNotEmpty() == true}",
        )
        nuovo.connect(sessione.host, sessione.porta)
        if (percorsoChiave.isNullOrEmpty()) {
            nuovo.authPassword(sessione.utente, password ?: "")
        } else {
            val provider = if (fraseChiave.isNullOrEmpty()) {
                nuovo.loadKeys(percorsoChiave)
            } else {
                nuovo.loadKeys(
                    percorsoChiave,
                    net.schmizz.sshj.userauth.password.PasswordUtils.createOneOff(fraseChiave.toCharArray()),
                )
            }
            nuovo.authPublickey(sessione.utente, provider)
        }
        ssh = nuovo
        return nuovo
    }

    fun chiudi() {
        runCatching { ssh?.disconnect() }
        ssh = null
    }
}
