package org.zirkio.term.ssh

import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient

/**
 * Costruzione del client SSH con gli algoritmi che Android sa fare.
 *
 * Su Android il provider BouncyCastle di sistema è ridotto: non ha X25519, quindi lo
 * scambio di chiavi `curve25519-sha256` muore subito con
 * `no such algorithm: X25519 for provider BC` e la connessione resta appesa senza
 * prompt. Togliendo quelle opzioni sshj ripiega su ECDH (nistp256/384/521) o su
 * Diffie-Hellman di gruppo, che funzionano ovunque.
 */
object Configurazione {

    private val SCAMBIO_NON_DISPONIBILI = setOf(
        "curve25519-sha256",
        "curve25519-sha256@libssh.org",
    )

    /**
     * Su Android sshj userebbe il provider «BC» di sistema, che è ridotto all'osso
     * (niente X25519, niente EC): ogni scambio di chiavi muore con
     * `no such algorithm: EC for provider BC`. Qui gli si dice di usare il provider
     * vero di Android, che è Conscrypt (si chiama «AndroidOpenSSL» o «Conscrypt»).
     */
    private fun preparaProvider() {
        val candidati = listOf("Conscrypt", "AndroidOpenSSL")
        val trovato = candidati.firstOrNull { java.security.Security.getProvider(it) != null }
        val nomi = java.security.Security.getProviders().take(6).map { it.name }
        if (trovato != null) {
            net.schmizz.sshj.common.SecurityUtils.setSecurityProvider(trovato)
            android.util.Log.i("zirkio", "provider crittografico per ssh: $trovato (disponibili: $nomi)")
        } else {
            android.util.Log.w("zirkio", "nessun Conscrypt trovato, resto sul provider predefinito (disponibili: $nomi)")
        }
        // su Android BC c'è già e non va toccato: sshj non deve registrare il suo
        runCatching { net.schmizz.sshj.common.SecurityUtils.setRegisterBouncyCastle(false) }
    }

    fun client(): SSHClient {
        preparaProvider()
        val config = DefaultConfig()
        val prima = config.keyExchangeFactories.map { it.name }
        val buoni = config.keyExchangeFactories.filterNot { it.name in SCAMBIO_NON_DISPONIBILI }
        if (buoni.isEmpty()) {
            // non dovrebbe succedere: meglio un client con tutto che nessuno
            android.util.Log.w("zirkio", "nessuno scambio di chiavi è rimasto: uso l'elenco intero")
        } else {
            config.keyExchangeFactories = buoni
        }
        android.util.Log.i(
            "zirkio",
            "scambio di chiavi disponibili: ${buoni.map { it.name }} (togliendo ${prima - buoni.map { it.name }.toSet()})",
        )
        return SSHClient(config)
    }
}
