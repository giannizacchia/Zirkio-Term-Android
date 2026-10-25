package org.zirkio.term.ssh

import android.content.Context
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.PTYMode
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.zirkio.term.dati.Sessione
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

/**
 * Un collegamento SSH con un canale shell (il terminale).
 *
 * Il canale è uno solo e serve tutto: la shell qui, più avanti la stessa connessione
 * porterà anche SFTP per i trasferimenti, come sul desktop.
 *
 * Tutto il blocco (rete, lettura) sta in thread di lavoro: il filo dell'interfaccia non
 * aspetta mai.
 */
class Collegamento(
    private val sessione: Sessione,
    private val chiave: String?,
    /** Frase d'ordine della chiave privata, se ne ha una. */
    private val fraseChiave: String? = null,
) {

    @Volatile private var client: SSHClient? = null
    @Volatile private var canale: Session? = null
    @Volatile private var shell: Session.Shell? = null
    @Volatile private var uscita: OutputStream? = null
    @Volatile private var lettore: Thread? = null
    @Volatile private var scrittore: Thread? = null
    @Volatile private var chiuso = false

    private val coda = java.util.concurrent.LinkedBlockingQueue<Comando>()

    /** Un pezzo di lavoro per il thread che scrive: testo da mandare, o nuova misura. */
    private data class Comando(
        val testo: String? = null,
        val colonne: Int = 0,
        val righe: Int = 0,
        val fine: Boolean = false,
    )

    /** Testo decodificato in arrivo dalla macchina. Chiamato dal thread di lettura. */
    var suDati: ((String) -> Unit)? = null
    /** Il collegamento si è chiuso; il messaggio è il motivo (o null se voluto). */
    var suChiuso: ((String?) -> Unit)? = null

    /** La macchina ha rifiutato la chiave: si può offrire di registrarla con la password. */
    var suChiaveRifiutata: (() -> Unit)? = null
    /** Errore prima di arrivare alla shell (rete, chiave, password). */
    var suErrore: ((String) -> Unit)? = null
    /** Impronta dell'host key; se `nuova` è true non era mai stata vista. */
    var suImpronta: ((impronta: String, nuova: Boolean) -> Unit)? = null

    private var improntaAccettata = sessione.impronta

    fun apri(colonne: Int, righe: Int, password: String?) {
        Thread({
            try {
                android.util.Log.i(
                    "zirkio",
                    "apro ${sessione.host}:${sessione.porta} utente=${sessione.utente} " +
                        "chiave=${chiave?.substringAfterLast('/')} password=${!password.isNullOrEmpty()} " +
                        "frase=${fraseChiave?.isNotEmpty() == true}",
                )
                val ssh = Configurazione.client()
                ssh.connectTimeout = 12000
                ssh.timeout = 12000
                ssh.addHostKeyVerifier(Verifica(improntaAccettata, suImpronta) { nuova -> improntaAccettata = nuova })
                ssh.connect(sessione.host, sessione.porta)
                android.util.Log.i("zirkio", "connessione riuscita, autentico")
                if (chiave.isNullOrEmpty()) {
                    ssh.authPassword(sessione.utente, password ?: "")
                } else {
                    val provider = if (fraseChiave.isNullOrEmpty()) {
                        ssh.loadKeys(chiave)
                    } else {
                        // chiave protetta da frase d'ordine
                        ssh.loadKeys(chiave, PasswordUtils.createOneOff(fraseChiave.toCharArray()))
                    }
                    ssh.authPublickey(sessione.utente, provider)
                }
                android.util.Log.i("zirkio", "autenticato, chiedo il pty")
                client = ssh
                val s = ssh.startSession()
                s.allocatePTY("xterm-256color", colonne, righe, 0, 0, emptyMap<PTYMode, Int>())
                val sh = s.startShell()
                android.util.Log.i("zirkio", "shell avviata")
                canale = s
                shell = sh
                uscita = sh.outputStream
                avviaScrittore()
                avviaLettura(sh.inputStream)
            } catch (e: Exception) {
                android.util.Log.e(
                    "zirkio",
                    "errore di collegamento: ${e.javaClass.name}: ${e.message}",
                    e,
                )
                if (!chiuso) {
                    val chiaveRifiutata = e is net.schmizz.sshj.userauth.UserAuthException && !chiave.isNullOrEmpty()
                    if (chiaveRifiutata) suChiaveRifiutata?.invoke()
                    suErrore?.invoke(spiega(e) + " (${e.javaClass.simpleName}: ${e.message})")
                }
            }
        }, "ssh-apri").start()
    }

    private fun spiega(e: Exception): String {
        val testo = (e.message ?: e.javaClass.simpleName)
        return when {
            testo.contains("Exhausted available authentication", true) ||
                testo.contains("Auth fail", true) ||
                testo.contains("auth", true) && testo.contains("fail", true) ->
                if (chiave != null) {
                    "la macchina non ha accettato la chiave per ${sessione.utente}: " +
                        "aggiungi la riga pubblica di ${chiave.substringAfterLast('/')} in ~/.ssh/authorized_keys"
                } else {
                    "password rifiutata per ${sessione.utente}@${sessione.host}"
                }
            testo.contains("Connection refused", true) -> "connessione rifiutata da ${sessione.host}:${sessione.porta}"
            testo.contains("UnknownHost", true) -> "nome della macchina non risolto: ${sessione.host}"
            testo.contains("timed out", true) || testo.contains("timeout", true) -> "nessuna risposta da ${sessione.host} (rete o macchina spenta)"
            testo.contains("Host key", true) -> "chiave della macchina non accettata"
            testo.contains("passphrase", true) || testo.contains("EncryptedPrivateKey", true) ->
                "la chiave privata ha una frase d'ordine: va scritta per collegarsi"
            else -> testo
        }
    }

    private fun avviaLettura(ingresso: InputStream) {
        var primo = true
        val t = Thread({
            val decodificatore = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
            val byte = ByteBuffer.allocate(8192)
            val caratteri = CharBuffer.allocate(16384)
            val buffer = ByteArray(8192)
            try {
                while (!chiuso) {
                    val letti = ingresso.read(buffer)
                    if (primo) {
                        primo = false
                        android.util.Log.i(
                            "zirkio",
                            if (letti > 0) "la shell ha mandato $letti byte" else "la shell si è chiusa subito (letti=$letti)",
                        )
                    }
                    if (letti < 0) break
                    byte.clear()
                    byte.put(buffer, 0, letti)
                    byte.flip()
                    caratteri.clear()
                    decodificatore.decode(byte, caratteri, false)
                    caratteri.flip()
                    if (caratteri.hasRemaining()) suDati?.invoke(caratteri.toString())
                }
            } catch (_: Exception) {
                // la connessione è caduta: lo dice il `finally`
            } finally {
                if (!chiuso) suChiuso?.invoke("collegamento chiuso dalla macchina")
            }
        }, "ssh-leggi")
        t.isDaemon = true
        lettore = t
        t.start()
    }

    /**
     * Manda i byte dei tasti alla shell.
     *
     * Non si scrive sul canale dal thread chiamante: i tasti arrivano dal thread
     * dell'interfaccia e Android vieta lì sopra qualunque operazione di rete
     * (`NetworkOnMainThreadException`, che poi lascia il cifrario di sshj a metà e fa
     * saltare ogni scrittura successiva). C'è una coda servita da un thread solo, così
     * i byte partono nell'ordine in cui sono stati digitati.
     */
    fun scrivi(testo: String) {
        if (uscita == null) return
        coda.offer(Comando(testo = testo))
    }

    fun ridimensiona(colonne: Int, righe: Int) {
        if (shell == null) return
        coda.offer(Comando(colonne = colonne, righe = righe))
    }

    private fun avviaScrittore() {
        val t = Thread({
            try {
                while (true) {
                    val comando = coda.take()
                    if (comando.fine) break
                    try {
                        if (comando.testo != null) {
                            val out = uscita ?: break
                            out.write(comando.testo.toByteArray(Charsets.UTF_8))
                            out.flush()
                        } else if (comando.colonne > 0 && comando.righe > 0) {
                            shell?.changeWindowDimensions(
                                comando.colonne,
                                comando.righe,
                                comando.colonne * 8,
                                comando.righe * 16,
                            )
                        }
                    } catch (e: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        android.util.Log.e("zirkio", "scrittura fallita: ${e.javaClass.name}: ${e.message}", e)
                        if (!chiuso) suChiuso?.invoke("non riesco a scrivere: ${e.message}")
                        break
                    }
                }
            } catch (_: InterruptedException) {
            }
        }, "ssh-scrivi")
        t.isDaemon = true
        scrittore = t
        t.start()
    }

    /** La connessione, per chi deve aprirci sopra SFTP (trasferimenti). */
    fun clientSsh(): SSHClient? = client

    fun nomeHost(): String = sessione.host

    /** True quando l'autenticazione è passata e il terminale è pronto. */
    fun pronto(): Boolean = uscita != null

    fun chiudi() {
        chiuso = true
        coda.offer(Comando(fine = true))
        scrittore?.interrupt()
        lettore?.interrupt()
        runCatching { canale?.close() }
        runCatching { client?.disconnect() }
    }

    companion object {
        /** Percorso del file della chiave, se questa sessione ne usa una. */
        fun percorsoChiave(context: Context, sessione: Sessione): String? {
            if (sessione.chiave.isBlank()) return null
            val f = java.io.File(context.filesDir, "chiavi/${sessione.chiave}")
            return if (f.exists()) f.absolutePath else null
        }
    }
}
