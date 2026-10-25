package org.zirkio.term.dati

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import org.zirkio.term.ssh.Accesso
import org.zirkio.term.ssh.Trasferimenti
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

enum class Verso { INVIA, RICEVI }

/** Un trasferimento richiesto: un file o una cartella intera, in un verso. */
data class Lavoro(
    val id: Long,
    val verso: Verso,
    val macchina: String,
    val sessione: Sessione,
    val percorsoChiave: String?,
    val password: String?,
    val fraseChiave: String? = null,
    /** Per INVIA il file/cartella da spedire; per RICEVI la cartella locale di arrivo. */
    val locale: File,
    /** Per RICEVI il percorso remoto da prendere (file o cartella). */
    val remoto: String,
    val remotoCartella: Boolean,
    val nome: String,
)

/** Stato di un lavoro nella coda, osservabile dall'interfaccia. */
class StatoLavoro(val lavoro: Lavoro) {
    var stato: String = "in attesa"
    var frazione: Float = 0f
    var errore: String? = null
    var finito: Boolean = false
}

/**
 * La coda dei trasferimenti: un lavoro per volta, in un thread suo, come la coda
 * della finestra Trasferimenti sul computer. L'interfaccia resta sempre usabile.
 */
class Coda {

    val voci = mutableStateListOf<StatoLavoro>()
    private val attesa = LinkedBlockingQueue<StatoLavoro>()
    private val principale = Handler(Looper.getMainLooper())
    private var lavoratore: Thread? = null
    private val contatore = AtomicLong(0)

    fun nuovoId(): Long = contatore.incrementAndGet()

    fun aggiungi(lavoro: Lavoro) {
        val voce = StatoLavoro(lavoro)
        principale.post { voci.add(0, voce) }
        attesa.put(voce)
        avvia()
    }

    fun togliFiniti() {
        principale.post { voci.removeAll { it.finito } }
    }

    val inCorso: Int get() = voci.count { !it.finito }

    private fun avvia() {
        if (lavoratore?.isAlive == true) return
        val t = Thread({
            while (true) {
                val voce = try {
                    attesa.take()
                } catch (_: InterruptedException) {
                    break
                }
                esegui(voce)
            }
        }, "coda-trasferimenti")
        t.isDaemon = true
        lavoratore = t
        t.start()
    }

    private fun esegui(voce: StatoLavoro) {
        val lavoro = voce.lavoro
        update { voce.stato = "collegamento a ${lavoro.macchina}" }
        val accesso = Accesso(lavoro.sessione, lavoro.percorsoChiave, lavoro.password, lavoro.fraseChiave)
        val trasferimenti = Trasferimenti({ runCatching { accesso.client() }.getOrNull() }, lavoro.macchina)
        val avanzamento: (Long, Long) -> Unit = { fatti, totali ->
            update {
                voce.frazione = if (totali > 0) (fatti.toFloat() / totali).coerceIn(0f, 1f) else 0f
            }
        }
        val novita: (String) -> Unit = { testo -> update { voce.stato = testo } }
        val errore = try {
            if (lavoro.verso == Verso.INVIA) {
                trasferimenti.invia(lavoro.locale, lavoro.remoto, avanzamento, novita)
            } else {
                trasferimenti.ricevi(lavoro.remoto, lavoro.locale, lavoro.remotoCartella, avanzamento, novita)
            }
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        } finally {
            trasferimenti.chiudi()
            accesso.chiudi()
        }
        update {
            voce.errore = errore
            voce.stato = errore ?: "fatto"
            voce.frazione = if (errore == null) 1f else voce.frazione
            voce.finito = true
        }
    }

    private fun update(blocco: () -> Unit) {
        principale.post(blocco)
    }
}
