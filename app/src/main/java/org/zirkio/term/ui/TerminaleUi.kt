package org.zirkio.term.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.zirkio.term.dati.ArchivioSessioni
import org.zirkio.term.dati.Chiavi
import org.zirkio.term.ssh.Installa
import org.zirkio.term.ssh.Spegni
import org.zirkio.term.dati.Comandi
import org.zirkio.term.dati.Sessione
import org.zirkio.term.ssh.Collegamento
import org.zirkio.term.term.Terminale
import org.zirkio.term.term.VistaTerminale
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchermoTerminale(
    sessione: Sessione,
    archivio: ArchivioSessioni,
    collaudo: Boolean = false,
    onChiudi: () -> Unit,
) {
    val contesto = androidx.compose.ui.platform.LocalContext.current
    val appunti = LocalClipboardManager.current
    val terminale = remember(sessione.id) { Terminale(80, 24) }
    var vista by remember(sessione.id) { mutableStateOf<VistaTerminale?>(null) }
    var stato by remember(sessione.id) { mutableStateOf("connessione…") }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var dimensione by remember { mutableStateOf(14f) }

    val percorsoChiave = remember(sessione.id) { Collegamento.percorsoChiave(contesto, sessione) }
    // una chiave protetta da frase d'ordine non si può usare senza chiederla
    val serveFrase = remember(sessione.id) {
        percorsoChiave != null && Chiavi.leggi(File(percorsoChiave))?.tipo == "con frase d'ordine"
    }
    var frase by remember(sessione.id) { mutableStateOf<String?>(null) }
    var fraseScritta by remember(sessione.id) { mutableStateOf("") }

    // registrazione della chiave al primo collegamento, con la password usata una volta sola
    var installata by remember(sessione.id) { mutableStateOf(false) }
    var chiediPassword by remember(sessione.id) { mutableStateOf(false) }
    var passwordScritta by remember(sessione.id) { mutableStateOf("") }
    var lavoroInstallazione by remember(sessione.id) { mutableStateOf(false) }
    var messaggioInstallazione by remember(sessione.id) { mutableStateOf<String?>(null) }
    var offertoUnaVolta by remember(sessione.id) { mutableStateOf(false) }
    val ambito = rememberCoroutineScope()

    // --- `@sd <nome>`: la macchina da spegnere, la conferma e la password di sudo
    var daSpegnere by remember(sessione.id) { mutableStateOf<Sessione?>(null) }
    var chiediSudo by remember(sessione.id) { mutableStateOf(false) }
    var sudoScritto by remember(sessione.id) { mutableStateOf("") }
    var spegnimentoInCorso by remember(sessione.id) { mutableStateOf(false) }

    /** Riga di servizio dentro al terminale (verde = fatto, rossa = problema). */
    fun scrivi(messaggio: String, rosso: Boolean) {
        val colore = if (rosso) "\u001B[1;31m" else "\u001B[1;32m"
        terminale.alimenta("\r\n$colore$messaggio\u001B[0m\r\n")
        vista?.postInvalidateOnAnimation()
    }

    val collegamento = remember(sessione.id, frase, installata) {
        Collegamento(sessione, percorsoChiave, frase)
    }

    DisposableEffect(sessione.id, frase, installata) {
        // alcune shell chiedono al terminale che cosa è (DA, posizione del cursore):
        // senza risposta certi programmi restano in attesa
        terminale.rispondi = { bytes -> collegamento.scrivi(String(bytes)) }
        collegamento.suDati = { testo -> terminale.alimenta(testo) }
        // l'errore va scritto anche dentro al terminale: la righetta piccola in alto
        // non la legge nessuno
        fun scriviRosso(messaggio: String) {
            terminale.alimenta(
                "\r\n\u001B[1;31m" + messaggio.replace("\r", "").replace("\n", "\r\n") + "\u001B[0m\r\n",
            )
            vista?.postInvalidateOnAnimation()
        }
        collegamento.suChiaveRifiutata = {
            // la macchina non conosce la chiave: si offre di registrarla subito
            if (!offertoUnaVolta && percorsoChiave != null) {
                offertoUnaVolta = true
                chiediPassword = true
            }
        }
        collegamento.suErrore = { messaggio ->
            stato = messaggio
            scriviRosso("non riesco a collegarmi: $messaggio")
        }
        collegamento.suChiuso = { motivo ->
            stato = motivo ?: "chiuso"
            if (motivo != null) scriviRosso("collegamento chiuso: $motivo")
            vista?.postInvalidateOnAnimation()
        }
        collegamento.suImpronta = { impronta, nuova ->
            if (nuova) {
                // primo collegamento: si memorizza l'impronta (TOFU)
                archivio.scrivi(
                    archivio.leggi().map { if (it.id == sessione.id) it.copy(impronta = impronta) else it },
                )
            }
        }
        if (collaudo) {
            stato = "collaudo della vista (nessun collegamento)"
            terminale.alimenta(PROVA)
            vista?.postInvalidateOnAnimation()
        } else if (serveFrase && frase == null) {
            stato = "la chiave privata ha una frase d'ordine"
        } else {
            stato = "connessione a ${sessione.host}…"
            collegamento.apri(80, 24, null)
        }
        onDispose { collegamento.chiudi() }
    }

    BackHandler { onChiudi() }

    if (chiediPassword && !collaudo) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { chiediPassword = false },
            title = { Text("Registro la chiave") },
            text = {
                Column {
                    Text(
                        "La macchina non conosce ancora questa chiave. La aggiungo io alle chiavi " +
                            "autorizzate: la password serve solo per questo collegamento e non viene salvata. " +
                            "Dalla prossima volta si entra con la chiave.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CampoPassword(
                        valore = passwordScritta,
                        onValore = { passwordScritta = it },
                        etichetta = "Password di ${sessione.utente}",
                        abilitato = !lavoroInstallazione,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    messaggioInstallazione?.let {
                        Text(it, fontSize = 12.sp, color = Color(0xFFE01B24), modifier = Modifier.padding(top = 6.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = passwordScritta.isNotEmpty() && !lavoroInstallazione,
                    onClick = {
                        lavoroInstallazione = true
                        messaggioInstallazione = null
                        val riga = percorsoChiave?.let { Chiavi.leggi(File(it))?.pubblica }.orEmpty()
                        ambito.launch {
                            val esito = withContext(Dispatchers.IO) {
                                Installa.registra(sessione, riga, passwordScritta) { impronta ->
                                    // prima volta che vediamo questa macchina: si memorizza
                                    archivio.scrivi(
                                        archivio.leggi().map {
                                            if (it.id == sessione.id) it.copy(impronta = impronta) else it
                                        },
                                    )
                                }
                            }
                            lavoroInstallazione = false
                            if (esito.isEmpty()) {
                                chiediPassword = false
                                passwordScritta = ""
                                // riparte il collegamento, stavolta con la chiave
                                installata = true
                            } else {
                                messaggioInstallazione = esito
                            }
                        }
                    },
                ) { Text(if (lavoroInstallazione) "Registro…" else "Registra ed entra") }
            },
            dismissButton = {
                TextButton(onClick = { chiediPassword = false; passwordScritta = "" }) { Text("Annulla") }
            },
        )
    }

    if (serveFrase && frase == null && !collaudo) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onChiudi,
            title = { Text("Frase d'ordine") },
            text = {
                Column {
                    Text(
                        "La chiave privata di questa macchina è protetta: serve la frase d'ordine per usarla.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CampoPassword(
                        valore = fraseScritta,
                        onValore = { fraseScritta = it },
                        etichetta = "Frase d'ordine",
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = fraseScritta.isNotEmpty(),
                    onClick = { frase = fraseScritta },
                ) { Text("Entra") }
            },
            dismissButton = { TextButton(onClick = onChiudi) { Text("Annulla") } },
        )
    }

    // --- conferma dello spegnimento (`@sd <nome>`)
    daSpegnere?.let { macchina ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { daSpegnere = null },
            title = { Text("Spegnere «${macchina.nome}»?") },
            text = {
                Text(
                    "${macchina.utente}@${macchina.host} · porta ${macchina.porta}\n" +
                        "La macchina si spegne subito.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = { chiediSudo = true }) { Text("Spegni") }
            },
            dismissButton = {
                TextButton(onClick = { daSpegnere = null }) { Text("Annulla") }
            },
        )
    }

    if (chiediSudo) {
        val macchina = daSpegnere
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { chiediSudo = false; sudoScritto = "" },
            title = { Text("Password di sudo") },
            text = {
                Column {
                    Text(
                        "Serve la password di amministrazione su ${macchina?.host ?: ""}. " +
                            "Il telefono non la ricorda: la chiede ogni volta.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CampoPassword(
                        valore = sudoScritto,
                        onValore = { sudoScritto = it },
                        etichetta = "Password di sudo",
                        abilitato = !spegnimentoInCorso,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = sudoScritto.isNotEmpty() && !spegnimentoInCorso,
                    onClick = {
                        if (macchina != null) {
                            spegnimentoInCorso = true
                            ambito.launch {
                                val esito = withContext(Dispatchers.IO) {
                                    Spegni.esegui(
                                        macchina,
                                        Collegamento.percorsoChiave(contesto, macchina),
                                        frase,
                                        sudoScritto,
                                    )
                                }
                                spegnimentoInCorso = false
                                chiediSudo = false
                                sudoScritto = ""
                                daSpegnere = null
                                if (esito.isEmpty()) {
                                    scrivi("spegnimento inviato a ${macchina.nome}", rosso = false)
                                } else {
                                    scrivi(esito, rosso = true)
                                }
                            }
                        }
                    },
                ) { Text(if (spegnimentoInCorso) "Spengo…" else "Spegni") }
            },
            dismissButton = {
                TextButton(onClick = { chiediSudo = false; sudoScritto = "" }) { Text("Annulla") }
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(sessione.nome.ifBlank { sessione.host }, fontSize = 16.sp)
                    Text(
                        "${sessione.utente}@${sessione.host} · $stato",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onChiudi) {
                    Icon(Icons.Filled.Close, contentDescription = "chiudi")
                }
            },
            actions = {
                IconButton(onClick = {
                    if (vista?.copiaSelezione() == true) stato = "copiato"
                    else {
                        appunti.setText(AnnotatedString(terminale.testoVisibile()))
                        stato = "schermata copiata"
                    }
                }) { Icon(Icons.Filled.ContentCopy, contentDescription = "copia") }
                IconButton(onClick = {
                    val testo = appunti.getText()?.text ?: ""
                    if (testo.isNotEmpty()) collegamento.scrivi(testo)
                }) { Icon(Icons.Filled.ContentPaste, contentDescription = "incolla") }
                IconButton(onClick = {
                    terminale.pulisciSchermo()
                    vista?.postInvalidateOnAnimation()
                }) { Icon(Icons.Filled.CleaningServices, contentDescription = "pulisci") }
                IconButton(onClick = {
                    dimensione = if (dimensione >= 20f) 11f else dimensione + 1.5f
                    vista?.dimensioneTesto = dimensione
                }) { Icon(Icons.Filled.FormatSize, contentDescription = "dimensione testo") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )

        Box(Modifier.weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    VistaTerminale(ctx).also { v ->
                        v.terminale = terminale
                        v.suTesto = { testo -> collegamento.scrivi(testo) }
                        // `@sd <nome>`: la riga non va alla shell, la prendiamo qui
                        v.suComando = { riga ->
                            val nome = Comandi.macchinaDaSpegnere(riga)
                            if (nome == null) {
                                false
                            } else {
                                val scelta = archivio.leggi().firstOrNull {
                                    it.nome.equals(nome, ignoreCase = true)
                                }
                                if (scelta == null) {
                                    android.util.Log.i("zirkio", "@sd: nessuna macchina «$nome»")
                                    val nomi = archivio.leggi().map { it.nome }.filter { it.isNotBlank() }
                                    scrivi(
                                        if (nomi.isEmpty()) {
                                            "nessuna macchina salvata: «$nome» non esiste"
                                        } else {
                                            "nessuna macchina «$nome»: ci sono ${nomi.joinToString(", ")}"
                                        },
                                        rosso = true,
                                    )
                                } else {
                                    android.util.Log.i("zirkio", "@sd: chiedo conferma per «${scelta.nome}»")
                                    daSpegnere = scelta
                                }
                                true
                            }
                        }
                        v.suRidimensiona = { c, r -> collegamento.ridimensiona(c, r) }
                        vista = v
                    }
                },
                update = { v ->
                    v.dimensioneTesto = dimensione
                    v.ctrlPremuto = ctrl
                    v.altPremuto = alt
                },
            )
        }

        BarraTasti(
            ctrl = ctrl,
            alt = alt,
            onCtrl = { ctrl = !ctrl },
            onAlt = { alt = !alt },
            onSequenza = { vista?.manda(it) },
            onScorri = { direzione -> vista?.scorri(direzione) },
        )
    }
}

@Composable
fun BarraTasti(
    ctrl: Boolean,
    alt: Boolean,
    onCtrl: () -> Unit,
    onAlt: () -> Unit,
    onSequenza: (String) -> Unit,
    onScorri: (Int) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tasto("Ctrl", attivo = ctrl, onCtrl)
            Tasto("Alt", attivo = alt, onAlt)
            Tasto("Esc") { onSequenza("\u001B") }
            Tasto("Tab") { onSequenza("\t") }
            Tasto("←") { onSequenza("\u001B[D") }
            Tasto("↑") { onSequenza("\u001B[A") }
            Tasto("↓") { onSequenza("\u001B[B") }
            Tasto("→") { onSequenza("\u001B[C") }
            Tasto("PgSu") { onSequenza("\u001B[5~") }
            Tasto("PgGiù") { onSequenza("\u001B[6~") }
            Tasto("-") { onSequenza("-") }
            Tasto("|") { onSequenza("|") }
            Tasto("/") { onSequenza("/") }
            Tasto("~") { onSequenza("~") }
            Tasto("⤒") { onScorri(1) }
            Tasto("⤓") { onScorri(-1) }
        }
    }
}

@Composable
private fun Tasto(etichetta: String, attivo: Boolean = false, onClic: () -> Unit) {
    TextButton(
        onClick = onClic,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (attivo) MaterialTheme.colorScheme.primary else Color.Transparent,
            contentColor = if (attivo) Color.White else MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Text(etichetta, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
}

/**
 * Schermata di collaudo: si apre con
 * `adb shell am start -n org.zirkio.term/.MainActivity --ez collaudo true`
 * e serve a controllare colori, caratteri larghi, cornici e cursore senza dover avere
 * una macchina a portata di mano (come lo `ZIRKIO_SELFTEST` della versione per computer).
 */
private const val PROVA = "\u001B[1;34mZirkio Term\u001B[0m \u001B[32mok\u001B[0m\r\n" +
    "prova della vista: colori, cornici, caratteri larghi\r\n\r\n" +
    "\u001B[1mgrassetto\u001B[0m \u001B[4msottolineato\u001B[0m \u001B[7minvertito\u001B[0m " +
    "\u001B[38;5;208marancio 256\u001B[0m\r\n" +
    "\u001B[34m┌──────────┬──────────┐\u001B[0m\r\n" +
    "\u001B[34m│\u001B[0m cartella │ \u001B[34m┐\u001B[0m        │\r\n" +
    "\u001B[34m└──────────┴──────────┘\u001B[0m\r\n" +
    "larghe: WWWW iiiii MMMM 0123456789\r\n" +
    "\u001B[90mfondo grigio normale, testo chiaro\u001B[0m\r\n" +
    "\u001B[42;30msfondo verde\u001B[0m \u001B[41;97msfondo rosso\u001B[0m\r\n\r\n" +
    "utente@macchina:~$ \u001B[?25h"
