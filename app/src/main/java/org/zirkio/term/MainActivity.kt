package org.zirkio.term

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.zirkio.term.dati.ArchivioSessioni
import org.zirkio.term.dati.Campo
import org.zirkio.term.dati.Chiavi
import org.zirkio.term.dati.Filtro
import org.zirkio.term.dati.Coda
import org.zirkio.term.dati.Lavoro
import org.zirkio.term.dati.Memoria
import org.zirkio.term.dati.Operazioni
import org.zirkio.term.dati.Sessione
import org.zirkio.term.dati.Verso
import org.zirkio.term.dati.Voce
import org.zirkio.term.dati.cerca
import org.zirkio.term.dati.estensione
import org.zirkio.term.dati.ordina
import org.zirkio.term.ssh.Accesso
import org.zirkio.term.ssh.Collegamento
import org.zirkio.term.ssh.Installa
import org.zirkio.term.ssh.Trasferimenti
import org.zirkio.term.ui.BLU_CARTELLA
import org.zirkio.term.ui.BarraAzioniFile
import org.zirkio.term.ui.CampoPassword
import org.zirkio.term.ui.BarraPercorso
import org.zirkio.term.ui.CollegamentiFiltri
import org.zirkio.term.ui.ICONA_SCARICA
import org.zirkio.term.ui.DialogoPassword
import org.zirkio.term.ui.DialogoSessione
import org.zirkio.term.ui.ElencoFile
import org.zirkio.term.ui.SchedaMacchina
import org.zirkio.term.ui.SchermoChiavi
import org.zirkio.term.ui.SchermoCoda
import org.zirkio.term.ui.SchermoMacchine
import org.zirkio.term.ui.SchermoTerminale
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val archivio = ArchivioSessioni(this)
        val collaudo = intent?.getBooleanExtra("collaudo", false) == true
        setContent {
            MaterialTheme(colorScheme = temaScuro()) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    App(archivio, collaudo)
                }
            }
        }
    }
}

/** Tema scuro con gli stessi colori del terminale sul computer. */
private fun temaScuro(): ColorScheme = darkColorScheme(
    primary = BLU_CARTELLA,
    onPrimary = Color.White,
    background = Color(0xFF15171C),
    onBackground = Color(0xFFD8DEE9),
    surface = Color(0xFF1B1E25),
    onSurface = Color(0xFFD8DEE9),
    surfaceVariant = Color(0xFF23262E),
    onSurfaceVariant = Color(0xFF9AA3B2),
)

private enum class Schermata { FILE, REMOTO, MACCHINE, CHIAVI, CODA, TERMINALE }

/** Uno spostamento in attesa di destinazione: le voci segnate e dove sono. */
private data class Spostamento(val remoto: Boolean, val percorsi: List<String>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(archivio: ArchivioSessioni, collaudo: Boolean = false) {
    val contesto = LocalContext.current
    val ambito = rememberCoroutineScope()
    val coda = remember { Coda() }

    var sessioni by remember { mutableStateOf(archivio.leggi()) }
    var schermata by remember {
        mutableStateOf(if (collaudo) Schermata.TERMINALE else Schermata.FILE)
    }

    // ---- parte locale
    var permesso by remember { mutableStateOf(Memoria.tuttoConcesso()) }
    var percorso by rememberSaveable { mutableStateOf(Memoria.radice(contesto)) }
    var voci by remember { mutableStateOf<List<Voce>>(emptyList()) }
    var scelte by remember { mutableStateOf<Set<String>>(emptySet()) }
    var campo by rememberSaveable { mutableStateOf(Campo.NOME) }
    var crescente by rememberSaveable { mutableStateOf(true) }
    var filtro by rememberSaveable { mutableStateOf(Filtro.TUTTI) }
    var trovati by remember { mutableStateOf<List<Voce>>(emptyList()) }
    var cercando by remember { mutableStateOf(false) }

    // ---- parte remota
    var macchina by remember { mutableStateOf<Sessione?>(null) }
    var percorsoRemoto by remember { mutableStateOf("") }
    var vociRemoto by remember { mutableStateOf<List<Voce>>(emptyList()) }
    var scelteRemoto by remember { mutableStateOf<Set<String>>(emptySet()) }
    var campoRemoto by remember { mutableStateOf(Campo.NOME) }
    var crescenteRemoto by remember { mutableStateOf(true) }
    var accesso by remember { mutableStateOf<Accesso?>(null) }
    var trasferitore by remember { mutableStateOf<Trasferimenti?>(null) }

    // ---- dialoghi e stato generale
    var caricando by remember { mutableStateOf(false) }
    var avviso by remember { mutableStateOf<String?>(null) }
    var dialogoSessione by remember { mutableStateOf<Pair<Boolean, Sessione?>>(false to null) }
    var passwordPer by remember { mutableStateOf<Sessione?>(null) }
    var scegliPer by remember { mutableStateOf<String?>(null) }
    var cartellaNuova by remember { mutableStateOf(false) }
    var spostando by remember { mutableStateOf<Spostamento?>(null) }
    var daEliminare by remember { mutableStateOf<List<Voce>?>(null) }
    var cancellazioneRemota by remember { mutableStateOf(false) }
    var terminaleDi by remember { mutableStateOf<Sessione?>(null) }
    val passwordInMemoria = remember { mutableStateMapOf<String, String>() }
    val fraseInMemoria = remember { mutableStateMapOf<String, String>() }
    var frasePer by remember { mutableStateOf<Sessione?>(null) }
    var installaChiave by remember { mutableStateOf(false) }

    /** Apre il file con l'app che il telefono ritiene adatta (doppio tocco). */
    fun apriFile(voce: Voce) {
        val file = File(voce.percorso)
        if (!file.exists()) {
            avviso = "il file non c'è più: ${voce.nome}"
            return
        }
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(estensione(voce.nome)) ?: "*/*"
        val esito = runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                contesto,
                "${contesto.packageName}.file",
                file,
            )
            contesto.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        }
        if (esito.isFailure) avviso = "nessuna app sa aprire i file ${voce.tipo.lowercase()}"
    }

    fun caricaLocale(destinazione: String, azzeraFiltro: Boolean = true, pulisciScelte: Boolean = true) {
        if (azzeraFiltro) {
            filtro = Filtro.TUTTI
            trovati = emptyList()
        }
        ambito.launch {
            caricando = true
            val esito = withContext(Dispatchers.IO) { runCatching { Memoria.elenco(destinazione) } }
            caricando = false
            esito.onSuccess {
                percorso = destinazione
                voci = it
                if (pulisciScelte) scelte = emptySet()
            }.onFailure { avviso = it.message ?: "non riesco a leggere la cartella" }
        }
    }

    /** Rifà la ricerca del filtro attivo senza cambiare né filtro né ordinamento. */
    fun aggiornaRicerca() {
        if (filtro == Filtro.TUTTI) return
        val cartella = File(percorso)
        val quale = filtro
        ambito.launch {
            val esito = withContext(Dispatchers.IO) { runCatching { cerca(cartella, quale) } }
            esito.onSuccess { trovati = it }
        }
    }

    /** Applica un collegamento: APK, documenti, recenti (ricerca) oppure l'elenco normale. */
    fun scegliFiltro(nuovo: Filtro) {
        filtro = nuovo
        if (nuovo == Filtro.TUTTI) {
            trovati = emptyList()
            caricaLocale(percorso)
            return
        }
        // questi elenchi vanno sempre dal più recente al più vecchio
        campo = Campo.DATA
        crescente = false
        val cartella = File(percorso)
        ambito.launch {
            cercando = true
            val esito = withContext(Dispatchers.IO) { runCatching { cerca(cartella, nuovo) } }
            cercando = false
            esito.onSuccess { trovati = it }
                .onFailure { avviso = it.message ?: "ricerca non riuscita" }
        }
    }

    fun chiudiRemoto() {
        trasferitore?.chiudi()
        accesso?.chiudi()
        trasferitore = null
        accesso = null
        percorsoRemoto = ""
        vociRemoto = emptyList()
        scelteRemoto = emptySet()
    }

    /** True se la chiave della macchina è protetta e non ne conosciamo ancora la frase. */
    fun mancaFrase(sessione: Sessione): Boolean {
        val percorso = Collegamento.percorsoChiave(contesto, sessione) ?: return false
        val protetta = Chiavi.leggi(File(percorso))?.tipo == "con frase d'ordine"
        return protetta && fraseInMemoria[sessione.id].isNullOrEmpty()
    }

    fun apriRemoto(sessione: Sessione) {
        val password = passwordInMemoria[sessione.id]
        if (sessione.chiave.isBlank() && password.isNullOrEmpty()) {
            passwordPer = sessione
            return
        }
        if (mancaFrase(sessione)) {
            frasePer = sessione
            return
        }
        if (macchina?.id != sessione.id) chiudiRemoto()
        macchina = sessione
        schermata = Schermata.REMOTO
        if (percorsoRemoto.isEmpty()) {
            val a = Accesso(
                sessione,
                Collegamento.percorsoChiave(contesto, sessione),
                password,
                fraseInMemoria[sessione.id],
            )
            val t = Trasferimenti({ runCatching { a.client() }.getOrNull() }, sessione.host)
            accesso = a
            trasferitore = t
            ambito.launch {
                caricando = true
                val esito = withContext(Dispatchers.IO) {
                    runCatching {
                        val casa = t.casa()
                        casa to t.elenco(casa)
                    }
                }
                caricando = false
                esito.onSuccess { (casa, elenco) ->
                    percorsoRemoto = casa
                    vociRemoto = elenco
                }.onFailure { errore ->
                    val rifiutata = errore is net.schmizz.sshj.userauth.UserAuthException && sessione.chiave.isNotBlank()
                    avviso = if (rifiutata) {
                        "la macchina non conosce la chiave: inserisci la password una volta e la registro io"
                    } else {
                        errore.message ?: "non riesco a leggere la cartella"
                    }
                    if (rifiutata) {
                        installaChiave = true
                        passwordPer = sessione
                    } else {
                        schermata = Schermata.FILE
                    }
                }
            }
        }
    }

    fun caricaRemoto(destinazione: String) {
        val t = trasferitore ?: return
        ambito.launch {
            caricando = true
            val esito = withContext(Dispatchers.IO) { runCatching { t.elenco(destinazione) } }
            caricando = false
            esito.onSuccess {
                percorsoRemoto = destinazione
                vociRemoto = it
                scelteRemoto = emptySet()
            }.onFailure { avviso = it.message ?: "non riesco a leggere la cartella" }
        }
    }

    fun accodaInvi(selezione: Set<String>, sorgente: List<Voce>, sessione: Sessione, destinazione: String) {
        val chiave = Collegamento.percorsoChiave(contesto, sessione)
        val password = passwordInMemoria[sessione.id]
        var quanti = 0
        selezione.forEach { percorsoScelto ->
            val voce = sorgente.firstOrNull { it.percorso == percorsoScelto } ?: return@forEach
            coda.aggiungi(
                Lavoro(
                    id = coda.nuovoId(),
                    verso = Verso.INVIA,
                    macchina = sessione.host,
                    sessione = sessione,
                    percorsoChiave = chiave,
                    password = password,
                    fraseChiave = fraseInMemoria[sessione.id],
                    locale = File(voce.percorso),
                    remoto = destinazione,
                    remotoCartella = false,
                    nome = voce.nome,
                ),
            )
            quanti++
        }
        if (quanti > 0) schermata = Schermata.CODA
    }

    fun accodaScarica(selezione: Set<String>, sorgente: List<Voce>, sessione: Sessione, arrivo: String) {
        val chiave = Collegamento.percorsoChiave(contesto, sessione)
        val password = passwordInMemoria[sessione.id]
        selezione.forEach { percorsoScelto ->
            val voce = sorgente.firstOrNull { it.percorso == percorsoScelto } ?: return@forEach
            coda.aggiungi(
                Lavoro(
                    id = coda.nuovoId(),
                    verso = Verso.RICEVI,
                    macchina = sessione.host,
                    sessione = sessione,
                    percorsoChiave = chiave,
                    password = password,
                    fraseChiave = fraseInMemoria[sessione.id],
                    locale = File(arrivo),
                    remoto = voce.percorso,
                    remotoCartella = voce.cartella,
                    nome = voce.nome,
                ),
            )
        }
        schermata = Schermata.CODA
    }

    // primo caricamento, e ritorno dalla schermata del permesso di sistema
    val ciclo = LocalLifecycleOwner.current
    DisposableEffect(ciclo) {
        val osservatore = LifecycleEventObserver { _, evento ->
            if (evento == Lifecycle.Event.ON_RESUME) {
                val prima = permesso
                permesso = Memoria.tuttoConcesso()
                if (permesso != prima && permesso) {
                    // appena concessa la memoria condivisa: si riparte dalla radice
                    percorso = Memoria.radice(contesto)
                    caricaLocale(percorso)
                } else {
                    // si torna da un'altra app (o dallo schermo spento): si aggiorna
                    // l'elenco senza toccare filtro, ordinamento e segnature
                    caricaLocale(percorso.ifEmpty { Memoria.radice(contesto) }, azzeraFiltro = false, pulisciScelte = false)
                    aggiornaRicerca()
                }
            }
        }
        ciclo.lifecycle.addObserver(osservatore)
        onDispose { ciclo.lifecycle.removeObserver(osservatore) }
    }

    /** Esegue lo spostamento delle voci segnate nella cartella indicata. */
    fun eseguiSpostamento(destinazione: String) {
        val lavoro = spostando ?: return
        spostando = null
        if (lavoro.remoto) {
            val t = trasferitore
            if (t == null) {
                avviso = "non sono collegato alla macchina"
                return
            }
            ambito.launch {
                caricando = true
                val esito = withContext(Dispatchers.IO) { runCatching { t.spostaRemoto(lavoro.percorsi, destinazione) } }
                caricando = false
                esito.onSuccess { errori ->
                    if (errori.isNotEmpty()) avviso = errori.joinToString("\n")
                    scelteRemoto = emptySet()
                    caricaRemoto(percorsoRemoto)
                }.onFailure { avviso = it.message ?: "spostamento non riuscito" }
            }
        } else {
            ambito.launch {
                caricando = true
                val esito = withContext(Dispatchers.IO) {
                    runCatching { Operazioni.spostaLocale(lavoro.percorsi.map { File(it) }, File(destinazione)) }
                }
                caricando = false
                esito.onSuccess { errori ->
                    if (errori.isNotEmpty()) avviso = errori.joinToString("\n")
                    scelte = emptySet()
                    caricaLocale(percorso, azzeraFiltro = false, pulisciScelte = false)
                    aggiornaRicerca()
                }.onFailure { avviso = it.message ?: "spostamento non riuscito" }
            }
        }
    }

    /** Cancella le voci confermate nel dialogo, qui o sulla macchina. */
    fun eseguiCancellazione(voci: List<Voce>, remota: Boolean) {
        daEliminare = null
        if (remota) {
            val t = trasferitore
            if (t == null) {
                avviso = "non sono collegato alla macchina"
                return
            }
            ambito.launch {
                caricando = true
                val esito = withContext(Dispatchers.IO) {
                    runCatching { t.eliminaRemoto(voci.map { it.percorso }) }
                }
                caricando = false
                esito.onSuccess { errori ->
                    if (errori.isNotEmpty()) avviso = errori.joinToString("\n")
                    scelteRemoto = emptySet()
                    caricaRemoto(percorsoRemoto)
                }.onFailure { avviso = it.message ?: "cancellazione non riuscita" }
            }
        } else {
            ambito.launch {
                caricando = true
                val esito = withContext(Dispatchers.IO) {
                    runCatching { Operazioni.eliminaLocale(voci.map { File(it.percorso) }) }
                }
                caricando = false
                esito.onSuccess { errori ->
                    if (errori.isNotEmpty()) avviso = errori.joinToString("\n")
                    scelte = emptySet()
                    caricaLocale(percorso, azzeraFiltro = false, pulisciScelte = false)
                    aggiornaRicerca()
                }.onFailure { avviso = it.message ?: "cancellazione non riuscita" }
            }
        }
    }

    when (schermata) {
        Schermata.FILE -> {
            BackHandler { (contesto as? android.app.Activity)?.finish() }
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text("Zirkio Term", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    if (permesso) "memoria del telefono" else "solo area privata dell'app",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = { schermata = Schermata.CHIAVI }) {
                                Icon(Icons.Filled.VpnKey, contentDescription = "chiavi")
                            }
                            IconButton(onClick = { scegliPer = null; schermata = Schermata.MACCHINE }) {
                                Icon(Icons.Filled.Dns, contentDescription = "macchine")
                            }
                            IconButton(onClick = { schermata = Schermata.CODA }) {
                                BadgedBox(badge = {
                                    if (coda.inCorso > 0) Badge { Text("${coda.inCorso}") }
                                }) {
                                    Icon(Icons.Filled.SwapVert, contentDescription = "trasferimenti")
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    )
                },
                bottomBar = {
                    Column {
                        if (macchina != null) {
                            SchedaMacchina(macchina, onScegli = {
                                scegliPer = "invia"
                                schermata = Schermata.MACCHINE
                            })
                        }
                        BarraAzioniFile(
                            quanti = scelte.size,
                            scegliendoDestinazione = spostando?.remoto == false,
                            principaleDescrizione = "invia",
                            onPrincipale = {
                                val s = macchina
                                if (s == null || percorsoRemoto.isEmpty()) {
                                    scegliPer = "invia"
                                    if (s != null) apriRemoto(s) else schermata = Schermata.MACCHINE
                                } else {
                                    accodaInvi(scelte, voci, s, percorsoRemoto)
                                }
                            },
                            onSposta = { if (scelte.isNotEmpty()) spostando = Spostamento(remoto = false, percorsi = scelte.toList()) },
                            onElimina = {
                                if (scelte.isNotEmpty()) {
                                    cancellazioneRemota = false
                                    daEliminare = voci.filter { scelte.contains(it.percorso) }
                                }
                            },
                            onCartella = { cartellaNuova = true },
                            onSpostaQui = { eseguiSpostamento(percorso) },
                            onAnnullaSpostamento = { spostando = null },
                        )
                    }
                },
            ) { riquadro ->
                Column(Modifier.fillMaxSize().padding(riquadro)) {
                    if (!permesso) {
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                contesto.startActivity(
                                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                        .setData(Uri.parse("package:${contesto.packageName}")),
                                )
                            }.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Per vedere tutte le cartelle del telefono serve «Accesso a tutti i file» — tocca qui",
                                fontSize = 12.sp,
                                color = Color(0xFFE5A50A),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    BarraPercorso(
                        percorso = percorso,
                        onIndietro = {
                            if (filtro != Filtro.TUTTI) scegliFiltro(Filtro.TUTTI)
                            else caricaLocale(Memoria.su(percorso))
                        },
                        azioni = {
                            if (macchina != null) {
                                TextButton(onClick = { apriRemoto(macchina!!) }) {
                                    Text("macchina", fontSize = 12.sp, color = BLU_CARTELLA)
                                }
                            }
                        },
                    )
                    CollegamentiFiltri(filtro = filtro, quanti = trovati.size, onScegli = { scegliFiltro(it) })
                    Box(Modifier.weight(1f)) {
                        ElencoFile(
                            voci = ordina(if (filtro == Filtro.TUTTI) voci else trovati, campo, crescente),
                            scelte = scelte,
                            campo = campo,
                            crescente = crescente,
                            onCampo = { c ->
                                if (c == campo) crescente = !crescente else {
                                    campo = c
                                    crescente = true
                                }
                            },
                            onTocco = { v ->
                                if (v.cartella) caricaLocale(v.percorso) else {
                                    scelte = if (scelte.contains(v.percorso)) scelte - v.percorso else scelte + v.percorso
                                }
                            },
                            onSpunta = { v ->
                                scelte = if (scelte.contains(v.percorso)) scelte - v.percorso else scelte + v.percorso
                            },
                            onDoppio = { v -> if (!v.cartella) apriFile(v) },
                            vuoto = when {
                                !permesso -> "Concedi l'accesso per vedere la memoria"
                                filtro == Filtro.APK -> "Nessun pacchetto APK qui sotto"
                                filtro == Filtro.DOCUMENTI -> "Nessun documento qui sotto"
                                filtro == Filtro.RECENTI -> "Nessun file qui sotto"
                                else -> "Cartella vuota"
                            },
                        )
                        if (caricando || cercando) {
                            CircularProgressIndicator(Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
                        }
                    }
                }
            }
        }

        Schermata.REMOTO -> {
            val sessione = macchina
            if (sessione == null) {
                schermata = Schermata.FILE
            } else {
                BackHandler { schermata = Schermata.FILE }
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Column {
                                    Text(sessione.nome.ifBlank { sessione.host }, fontSize = 16.sp)
                                    Text(
                                        "${sessione.utente}@${sessione.host}",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = { schermata = Schermata.FILE }) {
                                    Icon(Icons.Filled.ArrowBack, contentDescription = "telefono")
                                }
                            },
                            actions = {
                                IconButton(onClick = { schermata = Schermata.CODA }) {
                                    BadgedBox(badge = {
                                        if (coda.inCorso > 0) Badge { Text("${coda.inCorso}") }
                                    }) {
                                        Icon(Icons.Filled.SwapVert, contentDescription = "trasferimenti")
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                        )
                    },
                    bottomBar = {
                        BarraAzioniFile(
                            quanti = scelteRemoto.size,
                            scegliendoDestinazione = spostando?.remoto == true,
                            principaleDescrizione = "scarica qui",
                            principaleIcona = ICONA_SCARICA,
                            onPrincipale = { accodaScarica(scelteRemoto, vociRemoto, sessione, percorso) },
                            onSposta = {
                                if (scelteRemoto.isNotEmpty()) {
                                    spostando = Spostamento(remoto = true, percorsi = scelteRemoto.toList())
                                }
                            },
                            onElimina = {
                                if (scelteRemoto.isNotEmpty()) {
                                    cancellazioneRemota = true
                                    daEliminare = vociRemoto.filter { scelteRemoto.contains(it.percorso) }
                                }
                            },
                            onCartella = { cartellaNuova = true },
                            onSpostaQui = { eseguiSpostamento(percorsoRemoto) },
                            onAnnullaSpostamento = { spostando = null },
                        )
                    },
                ) { riquadro ->
                    Column(Modifier.fillMaxSize().padding(riquadro)) {
                        BarraPercorso(
                            percorso = percorsoRemoto,
                            onIndietro = {
                                caricaRemoto(
                                    percorsoRemoto.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" },
                                )
                            },
                            azioni = {},
                        )
                        Box(Modifier.weight(1f)) {
                            ElencoFile(
                                voci = ordina(vociRemoto, campoRemoto, crescenteRemoto),
                                scelte = scelteRemoto,
                                campo = campoRemoto,
                                crescente = crescenteRemoto,
                                onCampo = { c ->
                                    if (c == campoRemoto) crescenteRemoto = !crescenteRemoto
                                    else {
                                        campoRemoto = c;
                                        crescenteRemoto = true
                                    }
                                },
                                onTocco = { v ->
                                    if (v.cartella) caricaRemoto(v.percorso) else {
                                        scelteRemoto =
                                            if (scelteRemoto.contains(v.percorso)) scelteRemoto - v.percorso
                                            else scelteRemoto + v.percorso
                                    }
                                },
                                onSpunta = { v ->
                                    scelteRemoto = if (scelteRemoto.contains(v.percorso)) scelteRemoto - v.percorso
                                    else scelteRemoto + v.percorso
                                },
                                onDoppio = { v -> scelteRemoto = scelteRemoto + v.percorso },
                                vuoto = "Cartella vuota",
                            )
                            if (caricando) {
                                CircularProgressIndicator(Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
                            }
                        }
                    }
                }
            }
        }

        Schermata.MACCHINE -> {
            BackHandler { schermata = Schermata.FILE }
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(if (scegliPer == "invia") "Manda a…" else "Macchine", fontSize = 16.sp) },
                        navigationIcon = {
                            IconButton(onClick = { schermata = Schermata.FILE }) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = "indietro")
                            }
                        },
                        actions = {
                            IconButton(onClick = { dialogoSessione = true to null }) {
                                Icon(Icons.Filled.Add, contentDescription = "aggiungi macchina")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    )
                },
            ) { riquadro ->
                Box(Modifier.padding(riquadro)) {
                    SchermoMacchine(
                        sessioni = sessioni,
                        onTerminale = { s ->
                            terminaleDi = s
                            schermata = Schermata.TERMINALE
                        },
                        onSfoglia = { s ->
                            macchina = s
                            apriRemoto(s)
                        },
                        onModifica = { s -> dialogoSessione = true to s },
                        onNuova = { dialogoSessione = true to null },
                        onElimina = { s ->
                            sessioni = archivio.elimina(s.id)
                            if (macchina?.id == s.id) {
                                chiudiRemoto()
                                macchina = null
                            }
                        },
                    )
                }
            }
        }

        Schermata.CHIAVI -> {
            BackHandler { schermata = Schermata.FILE }
            SchermoChiavi(onIndietro = { schermata = Schermata.FILE })
        }

        Schermata.CODA -> {
            BackHandler { schermata = Schermata.FILE }
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Trasferimenti", fontSize = 16.sp) },
                        navigationIcon = {
                            IconButton(onClick = { schermata = Schermata.FILE }) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = "indietro")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    )
                },
            ) { riquadro ->
                Box(Modifier.padding(riquadro)) {
                    SchermoCoda(coda = coda, onTogliFiniti = { coda.togliFiniti() })
                }
            }
        }

        Schermata.TERMINALE -> {
            val sessione = terminaleDi
            if (sessione == null) {
                schermata = Schermata.FILE
            } else {
                SchermoTerminale(
                    sessione = sessione,
                    archivio = archivio,
                    collaudo = collaudo,
                    onChiudi = {
                        terminaleDi = null
                        sessioni = archivio.leggi()
                        schermata = Schermata.FILE
                    },
                )
            }
        }
    }

    // ---------------------------------------------------------------- dialoghi
    if (dialogoSessione.first) {
        DialogoSessione(
            iniziale = dialogoSessione.second,
            chiavi = Chiavi.elenco(contesto),
            onConferma = { s ->
                sessioni = archivio.aggiungi(s)
                dialogoSessione = false to null
            },
            onAnnulla = { dialogoSessione = false to null },
            onChiavi = {
                dialogoSessione = false to null
                schermata = Schermata.CHIAVI
            },
        )
    }

    frasePer?.let { sessione ->
        var scritta by remember(sessione.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { frasePer = null },
            title = { Text("Frase d'ordine della chiave") },
            text = {
                Column {
                    Text(
                        "La chiave di ${sessione.nome.ifBlank { sessione.host }} è protetta: serve la frase d'ordine.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CampoPassword(
                        valore = scritta,
                        onValore = { scritta = it },
                        etichetta = "Frase d'ordine",
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = scritta.isNotEmpty(), onClick = {
                    fraseInMemoria[sessione.id] = scritta
                    frasePer = null
                    macchina = sessione
                    apriRemoto(sessione)
                }) { Text("Entra") }
            },
            dismissButton = { TextButton(onClick = { frasePer = null }) { Text("Annulla") } },
        )
    }

    passwordPer?.let { sessione ->
        DialogoPassword(
            sessione = sessione,
            registraChiave = installaChiave,
            onConferma = { password ->
                val percorso = Collegamento.percorsoChiave(contesto, sessione)
                if (installaChiave && percorso != null) {
                    installaChiave = false
                    passwordPer = null
                    macchina = sessione
                    ambito.launch {
                        caricando = true
                        val riga = Chiavi.leggi(File(percorso))?.pubblica.orEmpty()
                        val esito = withContext(Dispatchers.IO) {
                            Installa.registra(sessione, riga, password) { impronta ->
                                // prima volta che vediamo questa macchina: si memorizza l'impronta
                                val a = org.zirkio.term.dati.ArchivioSessioni(contesto)
                                a.scrivi(a.leggi().map { if (it.id == sessione.id) it.copy(impronta = impronta) else it })
                            }
                        }
                        caricando = false
                        if (esito.isEmpty()) {
                            avviso = "chiave registrata su ${sessione.host}: d'ora in poi entro con la chiave"
                            apriRemoto(sessione)
                        } else {
                            avviso = esito
                            installaChiave = true
                            passwordPer = sessione
                        }
                    }
                } else {
                    passwordInMemoria[sessione.id] = password
                    passwordPer = null
                    macchina = sessione
                    apriRemoto(sessione)
                }
            },
            onAnnulla = { passwordPer = null; installaChiave = false },
        )
    }

    if (cartellaNuova) {
        var nome by remember { mutableStateOf("") }
        val suRemoto = schermata == Schermata.REMOTO
        AlertDialog(
            onDismissRequest = { cartellaNuova = false },
            title = { Text("Nuova cartella") },
            text = {
                Column {
                    Text(
                        if (suRemoto) "Dentro $percorsoRemoto" else "Dentro $percorso",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = nome,
                        onValueChange = { nome = it },
                        label = { Text("Nome") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = nome.isNotBlank(),
                    onClick = {
                        val errore: String? = if (suRemoto) {
                            trasferitore?.creaCartella(percorsoRemoto, nome)
                        } else {
                            Memoria.creaCartella(percorso, nome)
                        }
                        if (errore != null) avviso = errore
                        else if (suRemoto) caricaRemoto(percorsoRemoto) else caricaLocale(percorso)
                        cartellaNuova = false
                    },
                ) { Text("Crea") }
            },
            dismissButton = { TextButton(onClick = { cartellaNuova = false }) { Text("Annulla") } },
        )
    }

    daEliminare?.let { voci ->
        val quanti = voci.size
        AlertDialog(
            onDismissRequest = { daEliminare = null },
            title = { Text(if (quanti == 1) "Eliminare ${voci[0].nome}?" else "Eliminare $quanti elementi?") },
            text = {
                Column {
                    Text(
                        if (cancellazioneRemota) "Sulla macchina non si torna indietro."
                        else "Dal telefono non si torna indietro.",
                        fontSize = 12.sp,
                        color = Color(0xFFE01B24),
                    )
                    Text(
                        voci.take(6).joinToString("\n") { if (it.cartella) "📁 " + it.nome else it.nome } +
                            if (quanti > 6) "\n… e altri ${quanti - 6}" else "",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { eseguiCancellazione(voci, cancellazioneRemota) }) {
                    Text("Elimina", color = Color(0xFFE01B24))
                }
            },
            dismissButton = { TextButton(onClick = { daEliminare = null }) { Text("Annulla") } },
        )
    }

    avviso?.let { messaggio ->
        AlertDialog(
            onDismissRequest = { avviso = null },
            title = { Text("Non riesco") },
            text = { Text(messaggio) },
            confirmButton = { TextButton(onClick = { avviso = null }) { Text("Va bene") } },
        )
    }
}
