package org.zirkio.term.dati

import android.content.Context
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import net.schmizz.sshj.userauth.keyprovider.PuTTYKeyFile
import net.schmizz.sshj.userauth.password.PasswordFinder
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil
import org.bouncycastle.crypto.util.PublicKeyFactory
import java.io.File
import java.io.StringReader
import java.math.BigInteger
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.util.Base64

enum class TipoChiave(val etichetta: String) {
    ED25519("Ed25519"),
    RSA("RSA 3072"),
}

/**
 * Una chiave private custodita nell'area privata dell'app, con la sua parte
 * pubblica pronta da copiare in `authorized_keys`.
 */
data class Chiave(
    val nome: String,
    val file: File,
    val tipo: String,
    val impronta: String,
    val pubblica: String,
)

/**
 * Gestione delle chiavi SSH: genera (Ed25519 o RSA 3072), importa una chiave che
 * l'utente ha già nel telefono, elenca e restituisce la riga pubblica da dare
 * alla macchina remota.
 *
 * La chiave privata non lascia mai `filesDir/chiavi` se non la esporta l'utente.
 */
object Chiavi {

    fun cartella(context: Context): File = File(context.filesDir, "chiavi").apply { mkdirs() }

    fun elenco(context: Context): List<Chiave> = elencoIn(cartella(context))

    /** Le chiavi presenti, in ordine di nome. I `.pub` sono di contorno, non chiavi. */
    fun elencoIn(cartella: File): List<Chiave> =
        (cartella.listFiles() ?: emptyArray())
            .filter { it.isFile && !it.name.endsWith(".pub") }
            .sortedBy { it.name.lowercase() }
            .mapNotNull { leggi(it) }

    fun file(context: Context, nome: String): File = fileIn(cartella(context), nome)

    fun fileIn(cartella: File, nome: String): File =
        File(cartella, nome.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    /**
     * Genera una coppia nuova e la salva in formato OpenSSH
     * (`-----BEGIN OPENSSH PRIVATE KEY-----`), la stessa forma di `ssh-keygen -t ed25519`.
     */
    fun genera(context: Context, nome: String, tipo: TipoChiave): Chiave =
        generaIn(cartella(context), nome, tipo)

    fun generaIn(cartella: File, nome: String, tipo: TipoChiave): Chiave {
        val coppia: AsymmetricCipherKeyPair = when (tipo) {
            TipoChiave.ED25519 -> {
                val g = Ed25519KeyPairGenerator()
                g.init(Ed25519KeyGenerationParameters(SecureRandom()))
                g.generateKeyPair()
            }
            TipoChiave.RSA -> {
                val g = RSAKeyPairGenerator()
                g.init(RSAKeyGenerationParameters(BigInteger.valueOf(65537), SecureRandom(), 3072, 100))
                g.generateKeyPair()
            }
        }
        val der = OpenSSHPrivateKeyUtil.encodePrivateKey(coppia.private)
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(70, "\n".toByteArray()).encodeToString(der) +
            "\n-----END OPENSSH PRIVATE KEY-----\n"
        val file = fileIn(cartella, nome)
        require(!file.exists()) { "esiste già una chiave con questo nome" }
        scrivi(file, pem)
        val pubblica = rigaPubblica(
            tipo = if (tipo == TipoChiave.ED25519) "ssh-ed25519" else "ssh-rsa",
            blob = OpenSSHPublicKeyUtil.encodePublicKey(coppia.public),
            commento = file.name,
        )
        File(file.absolutePath + ".pub").writeText(pubblica + "\n")
        return leggi(file) ?: error("chiave appena creata non leggibile")
    }

    /**
     * Importa una chiave che l'utente ha già: OpenSSH (nuova e vecchia forma),
     * PKCS#8 o PuTTY. Il testo viene conservato tale e quale, così una chiave
     * con frase d'ordine resta protetta.
     */
    fun importa(context: Context, nome: String, testo: String, frase: String): Chiave =
        importaIn(cartella(context), nome, testo, frase)

    fun importaIn(cartella: File, nome: String, testo: String, frase: String): Chiave {
        val pulito = testo.trim()
        require(pulito.isNotEmpty()) { "incolla il contenuto del file della chiave" }
        require(!Regex("^(ssh-|ecdsa-)[a-z0-9-]+ AAAA").containsMatchIn(pulito)) {
            "questo è il file pubblico; serve quello privato (senza .pub)"
        }
        require(apri(pulito, frase) != null) { "chiave non riconosciuta o frase d'ordine sbagliata" }
        val file = fileIn(cartella, nome)
        require(!file.exists()) { "esiste già una chiave con questo nome" }
        scrivi(file, if (pulito.endsWith("\n")) pulito else pulito + "\n")
        val chiave = leggi(file, frase) ?: error("chiave importata non leggibile")
        File(file.absolutePath + ".pub").writeText(chiave.pubblica + "\n")
        return chiave
    }

    /** Rilegge una chiave dal disco; con frase d'ordine nulla si intende senza. */
    fun leggi(file: File, frase: String = ""): Chiave? {
        val testo = runCatching { file.readText() }.getOrNull() ?: return null
        val prov = apri(testo, frase)
        val salvata = runCatching { File(file.absolutePath + ".pub").readText().trim() }
            .getOrNull()?.takeIf { it.isNotEmpty() }
        val blob = blobDaPrivata(testo) ?: prov?.let { p -> runCatching { blobPubblico(p.public) }.getOrNull() }
        val riga = when {
            salvata != null -> salvata
            blob != null -> rigaPubblica(nomeTipo(blob) ?: "ssh-ed25519", blob, file.name)
            else -> ""
        }
        val tipo = blob?.let { nomeTipo(it) }
            ?: riga.substringBefore(' ').takeIf { riga.isNotEmpty() }
            ?: "con frase d'ordine"
        val impronta = when {
            blob != null -> impronta(blob)
            riga.isNotEmpty() -> improntaDiRiga(riga)
            else -> "—"
        }
        return Chiave(nome = file.name, file = file, tipo = tipo, impronta = impronta, pubblica = riga)
    }

    /** Impronta di una riga pubblica `ssh-ed25519 AAAA… commento`. */
    private fun improntaDiRiga(riga: String): String = runCatching {
        val blob = Base64.getDecoder().decode(riga.split(" ")[1])
        "SHA256:" + Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
    }.getOrDefault("—")

    /** Testo della chiave privata, per le sessioni che ne hanno bisogno. */
    fun testo(chiave: Chiave): String = runCatching { chiave.file.readText() }.getOrDefault("")

    fun elimina(context: Context, chiave: Chiave): Boolean {
        File(chiave.file.absolutePath + ".pub").delete()
        return chiave.file.delete()
    }

    fun rinomina(context: Context, chiave: Chiave, nuovo: String): Chiave? {
        val destinazione = file(context, nuovo)
        if (destinazione.exists()) return null
        if (!chiave.file.renameTo(destinazione)) return null
        val vecchioPub = File(chiave.file.absolutePath + ".pub")
        if (vecchioPub.exists()) {
            val nuovoPub = File(destinazione.absolutePath + ".pub")
            vecchioPub.renameTo(nuovoPub)
            val riga = leggi(destinazione)?.pubblica
            if (riga != null && riga.isNotEmpty()) {
                nuovoPub.writeText(riga.replace(Regex(" \\S+$"), " ") + destinazione.name + "\n")
            }
        }
        return leggi(destinazione)
    }

    // ---- interni -------------------------------------------------------------

    private fun scrivi(file: File, testo: String) {
        file.writeText(testo)
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
    }

    private fun apri(testo: String, frase: String): KeyProvider? {
        val trova: PasswordFinder? = if (frase.isEmpty()) null else object : PasswordFinder {
            override fun reqPassword(risorsa: net.schmizz.sshj.userauth.password.Resource<*>?): CharArray =
                frase.toCharArray()

            override fun shouldRetry(risorsa: net.schmizz.sshj.userauth.password.Resource<*>?): Boolean = false
        }
        fun lettore() = StringReader(testo)
        val prove: List<() -> KeyProvider> = listOf(
            { OpenSSHKeyFile().also { k -> if (trova == null) k.init(lettore()) else k.init(lettore(), trova) } },
            { PKCS8KeyFile().also { k -> if (trova == null) k.init(lettore()) else k.init(lettore(), trova) } },
            { PuTTYKeyFile().also { k -> if (trova == null) k.init(lettore()) else k.init(lettore(), trova) } },
        )
        for (prova in prove) {
            val esito = runCatching { prova() }
            if (esito.isSuccess) return esito.getOrNull()
        }
        return null
    }

    /**
     * Parte pubblica ricavata dal file privato OpenSSH (`-----BEGIN OPENSSH PRIVATE KEY-----`).
     * sshj sa leggere la privata ma non sempre ne ricava la pubblica, quindi qui si fa da
     * soli con BouncyCastle: il blob contiene già tutto.
     */
    private fun blobDaPrivata(testo: String): ByteArray? {
        for (intestazione in listOf("OPENSSH PRIVATE KEY", "PRIVATE KEY", "RSA PRIVATE KEY")) {
            val inizio = "-----BEGIN $intestazione-----"
            val fine = "-----END $intestazione-----"
            val da = testo.indexOf(inizio)
            if (da < 0) continue
            val a = testo.indexOf(fine, da)
            if (a < 0) continue
            val base64 = testo.substring(da + inizio.length, a).replace(Regex("\\s"), "")
            val der = runCatching { Base64.getDecoder().decode(base64) }.getOrNull() ?: continue
            val privata = runCatching { OpenSSHPrivateKeyUtil.parsePrivateKeyBlob(der) }.getOrNull() ?: continue
            val pubblica = when (privata) {
                is Ed25519PrivateKeyParameters -> privata.generatePublicKey()
                is RSAPrivateCrtKeyParameters -> RSAKeyParameters(false, privata.modulus, privata.publicExponent)
                else -> null
            } ?: continue
            runCatching { OpenSSHPublicKeyUtil.encodePublicKey(pubblica) }.getOrNull()?.let { return it }
        }
        return null
    }

    /** Blob SSH della parte pubblica (`string algoritmo` + dati), via BouncyCastle. */
    private fun blobPubblico(pubblica: PublicKey): ByteArray =
        OpenSSHPublicKeyUtil.encodePublicKey(PublicKeyFactory.createKey(pubblica.encoded))

    /** Nome del tipo leggendolo dal blob pubblico: il primo campo è la stringa dell'algoritmo. */
    private fun nomeTipo(blob: ByteArray): String? = runCatching {
        val lunghezza = ((blob[0].toInt() and 0xff) shl 24) or ((blob[1].toInt() and 0xff) shl 16) or
            ((blob[2].toInt() and 0xff) shl 8) or (blob[3].toInt() and 0xff)
        String(blob, 4, lunghezza, Charsets.US_ASCII)
    }.getOrNull()

    private fun rigaPubblica(tipo: String, blob: ByteArray, commento: String): String =
        "$tipo ${Base64.getEncoder().encodeToString(blob)} $commento"

    /** Impronta come la mostra OpenSSH: `SHA256:` + base64 senza riempimento. */
    private fun impronta(blob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }
}
