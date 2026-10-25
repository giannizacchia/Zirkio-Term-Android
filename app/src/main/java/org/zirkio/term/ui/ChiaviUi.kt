package org.zirkio.term.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.zirkio.term.dati.Chiave
import org.zirkio.term.dati.Chiavi
import org.zirkio.term.dati.TipoChiave

/**
 * Schermata delle chiavi: elenco di quelle presenti, generazione di una nuova
 * (Ed25519 o RSA 3072), importazione di una che l'utente ha già, e la riga
 * pubblica da dare alla macchina.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchermoChiavi(onIndietro: () -> Unit) {
    val contesto = LocalContext.current
    val appunti = LocalClipboardManager.current
    val ambito = rememberCoroutineScope()

    var chiavi by remember { mutableStateOf(Chiavi.elenco(contesto)) }
    var genera by remember { mutableStateOf(false) }
    var importa by remember { mutableStateOf(false) }
    var dettaglio by remember { mutableStateOf<Chiave?>(null) }
    var daEliminare by remember { mutableStateOf<Chiave?>(null) }
    var occupato by remember { mutableStateOf<String?>(null) }
    var errore by remember { mutableStateOf<String?>(null) }

    fun ricarica() {
        chiavi = Chiavi.elenco(contesto)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chiavi") },
                navigationIcon = {
                    IconButton(onClick = onIndietro) {
                        Icon(
                            androidx.compose.material.icons.Icons.Filled.ArrowBack,
                            contentDescription = "indietro",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { importa = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "importa")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { genera = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Genera") },
            )
        },
    ) { riquadro ->
        Box(Modifier.fillMaxSize().padding(riquadro)) {
            if (chiavi.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(28.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Nessuna chiave", fontWeight = FontWeight.Bold)
                    Text(
                        "Genera una chiave Ed25519 e copia la riga pubblica in ~/.ssh/authorized_keys " +
                            "della macchina, oppure importa una chiave che hai già nel telefono.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(chiavi, key = { it.nome }) { k ->
                        Column {
                            Row(
                                Modifier.fillMaxWidth().clickable { dettaglio = k }
                                    .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(k.nome, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    Text(
                                        k.tipo,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        k.impronta,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                                IconButton(onClick = {
                                    appunti.setText(AnnotatedString(k.pubblica))
                                }) { Icon(Icons.Filled.ContentCopy, contentDescription = "copia pubblica") }
                                IconButton(onClick = { condividi(contesto, k.pubblica, k.nome) }) {
                                    Icon(Icons.Filled.Share, contentDescription = "condividi")
                                }
                                IconButton(onClick = { daEliminare = k }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "elimina")
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }

            occupato?.let { testo ->
                Row(
                    Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                    Text(testo, fontSize = 12.sp)
                }
            }
        }
    }

    if (genera) {
        DialogoGeneraChiave(
            onAnnulla = { genera = false },
            onGenera = { nome, tipo ->
                genera = false
                occupato = "genero $nome…"
                ambito.launch {
                    val esito = withContext(Dispatchers.Default) {
                        runCatching { Chiavi.genera(contesto, nome, tipo) }
                    }
                    occupato = null
                    esito.exceptionOrNull()?.let { errore = it.message ?: "generazione non riuscita" }
                    ricarica()
                    esito.getOrNull()?.let { dettaglio = it }
                }
            },
        )
    }

    if (importa) {
        DialogoImportaChiave(
            onAnnulla = { importa = false },
            onImporta = { nome, testo, frase ->
                importa = false
                occupato = "importo $nome…"
                ambito.launch {
                    val esito = withContext(Dispatchers.Default) {
                        runCatching { Chiavi.importa(contesto, nome, testo, frase) }
                    }
                    occupato = null
                    esito.exceptionOrNull()?.let { errore = it.message ?: "importazione non riuscita" }
                    ricarica()
                    esito.getOrNull()?.let { dettaglio = it }
                }
            },
        )
    }

    dettaglio?.let { k ->
        AlertDialog(
            onDismissRequest = { dettaglio = null },
            title = { Text(k.nome) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${k.tipo} · ${k.impronta}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text(
                        "Riga pubblica da mettere in ~/.ssh/authorized_keys sulla macchina:",
                        fontSize = 12.sp,
                    )
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            k.pubblica.ifEmpty { "(non disponibile: chiave con frase d'ordine)" },
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    appunti.setText(AnnotatedString(k.pubblica))
                    dettaglio = null
                }) { Text("Copia") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { condividi(contesto, k.pubblica, k.nome) }) { Text("Condividi") }
                    TextButton(onClick = { dettaglio = null }) { Text("Chiudi") }
                }
            },
        )
    }

    daEliminare?.let { k ->
        AlertDialog(
            onDismissRequest = { daEliminare = null },
            title = { Text("Eliminare ${k.nome}?") },
            text = { Text("La chiave sparisce dal telefono. Se la usavi su una macchina, l'accesso smette di funzionare.") },
            confirmButton = {
                TextButton(onClick = {
                    Chiavi.elimina(contesto, k)
                    daEliminare = null
                    ricarica()
                }) { Text("Elimina") }
            },
            dismissButton = { TextButton(onClick = { daEliminare = null }) { Text("Annulla") } },
        )
    }

    errore?.let { messaggio ->
        AlertDialog(
            onDismissRequest = { errore = null },
            title = { Text("Non riesco") },
            text = { Text(messaggio) },
            confirmButton = { TextButton(onClick = { errore = null }) { Text("Va bene") } },
        )
    }
}

@Composable
private fun DialogoGeneraChiave(
    onAnnulla: () -> Unit,
    onGenera: (String, TipoChiave) -> Unit,
) {
    var nome by remember { mutableStateOf("id_ed25519") }
    var tipo by remember { mutableStateOf(TipoChiave.ED25519) }
    AlertDialog(
        onDismissRequest = onAnnulla,
        title = { Text("Nuova chiave") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = nome,
                    onValueChange = { nome = it },
                    label = { Text("Nome") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Tipo", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                TipoChiave.entries.forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().clickable { tipo = t }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (tipo == t) "◉" else "○",
                            color = if (tipo == t) BLU_CARTELLA else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Column {
                            Text(t.etichetta, fontSize = 13.sp)
                            Text(
                                when (t) {
                                    TipoChiave.ED25519 -> "corta, veloce, oggi la scelta normale"
                                    TipoChiave.RSA -> "compatibile con macchine vecchie"
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = nome.isNotBlank(), onClick = { onGenera(nome.trim(), tipo) }) { Text("Genera") }
        },
        dismissButton = { TextButton(onClick = onAnnulla) { Text("Annulla") } },
    )
}

@Composable
private fun DialogoImportaChiave(
    onAnnulla: () -> Unit,
    onImporta: (String, String, String) -> Unit,
) {
    var nome by remember { mutableStateOf("chiave_importata") }
    var testo by remember { mutableStateOf("") }
    var frase by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onAnnulla,
        title = { Text("Importa una chiave") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Incolla il contenuto del file privato (quello senza .pub). " +
                        "Se è protetta da frase d'ordine, scrivila sotto.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = nome,
                    onValueChange = { nome = it },
                    label = { Text("Nome") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = testo,
                    onValueChange = { testo = it },
                    label = { Text("Chiave privata") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                )
                OutlinedTextField(
                    value = frase,
                    onValueChange = { frase = it },
                    label = { Text("Frase d'ordine (facoltativa)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = nome.isNotBlank() && testo.isNotBlank(),
                onClick = { onImporta(nome.trim(), testo, frase) },
            ) { Text("Importa") }
        },
        dismissButton = { TextButton(onClick = onAnnulla) { Text("Annulla") } },
    )
}

/** Manda la riga pubblica ad altre app: messaggistica, posta, appunti condivisi. */
private fun condividi(contesto: Context, testo: String, nome: String) {
    if (testo.isEmpty()) return
    val invio = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, testo)
        putExtra(Intent.EXTRA_SUBJECT, "chiave pubblica $nome")
    }
    contesto.startActivity(Intent.createChooser(invio, "Manda la chiave pubblica"))
}
