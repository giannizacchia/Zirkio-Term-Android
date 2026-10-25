package org.zirkio.term.ssh

import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

/**
 * Controllo della chiave della macchina: al primo collegamento si accetta e si memorizza
 * l'impronta (TOFU), dai successivi si pretende identica. Se cambia, qualcuno potrebbe
 * essersi messo in mezzo fra telefono e macchina.
 */
class Verifica(
    private val improntaAccettata: String,
    private val suImpronta: ((String, Boolean) -> Unit)? = null,
    private val suNuova: ((String) -> Unit)? = null,
) : HostKeyVerifier {

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val sha = MessageDigest.getInstance("SHA-256").digest(key.encoded)
        val impronta = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(sha)
        val nuova = improntaAccettata.isEmpty()
        suImpronta?.invoke(impronta, nuova)
        if (nuova) suNuova?.invoke(impronta)
        return nuova || impronta == improntaAccettata
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
}
