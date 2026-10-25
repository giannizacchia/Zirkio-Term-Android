package org.zirkio.term.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.zirkio.term.dati.Chiave
import org.zirkio.term.dati.Sessione

/**
 * Elenco delle macchine salvate. Da qui si apre il terminale o si sfogliano i file,
 * e si aggiunge o modifica una macchina.
 */
@Composable
fun SchermoMacchine(
    sessioni: List<Sessione>,
    onTerminale: (Sessione) -> Unit,
    onSfoglia: (Sessione) -> Unit,
    onModifica: (Sessione) -> Unit,
    onNuova: () -> Unit,
    onElimina: (Sessione) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        if (sessioni.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Nessuna macchina salvata", fontWeight = FontWeight.Bold)
                Text(
                    "Aggiungi l'accesso a un computer: serve solo il nome, l'utente e una chiave " +
                        "(la generi dalla schermata Chiavi).",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(onClick = onNuova) { Text("Aggiungi macchina") }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(sessioni, key = { it.id }) { s ->
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f).clickable { onTerminale(s) }) {
                                Text(s.nome.ifBlank { s.host }, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text(
                                    "${s.utente}@${s.host}:${s.porta}" +
                                        if (s.chiave.isBlank()) " · password" else " · ${s.chiave}",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { onTerminale(s) }) {
                                Icon(Icons.Filled.Terminal, contentDescription = "terminale")
                            }
                            IconButton(onClick = { onSfoglia(s) }) {
                                Icon(Icons.Filled.Folder, contentDescription = "file")
                            }
                            IconButton(onClick = { onModifica(s) }) {
                                Icon(Icons.Filled.Edit, contentDescription = "modifica", modifier = Modifier.size(20.dp))
                            }
                            IconButton(onClick = { onElimina(s) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "elimina", modifier = Modifier.size(20.dp))
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

/**
 * Dialogo per creare o modificare una macchina. La chiave si sceglie da quelle
 * presenti, oppure si usa la password (chiesta a ogni collegamento e mai salvata).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogoSessione(
    iniziale: Sessione?,
    chiavi: List<Chiave>,
    onConferma: (Sessione) -> Unit,
    onAnnulla: () -> Unit,
    onChiavi: () -> Unit,
) {
    val base = iniziale ?: Sessione()
    var nome by remember { mutableStateOf(if (iniziale == null) "" else base.nome) }
    var host by remember { mutableStateOf(if (iniziale == null) "" else base.host) }
    var porta by remember { mutableStateOf(if (iniziale == null) "22" else base.porta.toString()) }
    var utente by remember { mutableStateOf(if (iniziale == null) "" else base.utente) }
    var conPassword by remember { mutableStateOf(iniziale != null && base.chiave.isBlank()) }
    var chiave by remember { mutableStateOf(if (base.chiave.isNotBlank()) base.chiave else chiavi.firstOrNull()?.nome ?: "") }

    AlertDialog(
        onDismissRequest = onAnnulla,
        title = { Text(if (iniziale == null) "Nuova macchina" else "Modifica macchina") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Campo(nome, { nome = it }, "Nome (facoltativo)")
                Campo(host, { host = it }, "Macchina o indirizzo")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Campo(porta, { porta = it.filter { c -> c.isDigit() } }, "Porta", Modifier.weight(0.4f))
                    Campo(utente, { utente = it }, "Utente", Modifier.weight(0.6f))
                }

                Text("Come entrare", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { conPassword = false }) {
                        Text(
                            "Chiave",
                            fontWeight = if (!conPassword) FontWeight.Bold else FontWeight.Normal,
                            color = if (!conPassword) BLU_CARTELLA else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { conPassword = true }) {
                        Text(
                            "Password",
                            fontWeight = if (conPassword) FontWeight.Bold else FontWeight.Normal,
                            color = if (conPassword) BLU_CARTELLA else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (conPassword) {
                    Text(
                        "La password viene chiesta a ogni collegamento e non viene salvata.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (chiavi.isEmpty()) {
                    Text(
                        "Nessuna chiave presente: generala o importala dalla schermata Chiavi.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onChiavi) { Text("Vai alle chiavi") }
                } else {
                    chiavi.forEach { k ->
                        Row(
                            Modifier.fillMaxWidth().clickable { chiave = k.nome }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (chiave == k.nome) "◉" else "○",
                                color = if (chiave == k.nome) BLU_CARTELLA else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Column {
                                Text(k.nome, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    "${k.tipo} · ${k.impronta}",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    TextButton(onClick = onChiavi) { Text("Gestisci le chiavi") }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = host.isNotBlank() && utente.isNotBlank() && (conPassword || chiave.isNotBlank()),
                onClick = {
                    onConferma(
                        base.copy(
                            nome = nome.ifBlank { host },
                            host = host.trim(),
                            porta = porta.toIntOrNull() ?: 22,
                            utente = utente.trim(),
                            chiave = if (conPassword) "" else chiave,
                        ),
                    )
                },
            ) { Text("Salva") }
        },
        dismissButton = { TextButton(onClick = onAnnulla) { Text("Annulla") } },
    )
}

/** Chiede la password di una macchina che non usa la chiave. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogoPassword(
    sessione: Sessione,
    onConferma: (String) -> Unit,
    onAnnulla: () -> Unit,
    registraChiave: Boolean = false,
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onAnnulla,
        title = {
            Text(
                if (registraChiave) "Registro la chiave su ${sessione.nome.ifBlank { sessione.host }}"
                else "Password per ${sessione.nome.ifBlank { sessione.host }}",
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (registraChiave) {
                        "La macchina non conosce ancora la chiave. La aggiungo io alle chiavi autorizzate: " +
                            "la password serve solo adesso e non viene salvata, dalla prossima volta si entra con la chiave."
                    } else {
                        "${sessione.utente}@${sessione.host} usa la password: serve solo per questa volta."
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CampoPassword(valore = password, onValore = { password = it }, etichetta = "Password")
            }
        },
        confirmButton = {
            TextButton(enabled = password.isNotEmpty(), onClick = { onConferma(password) }) {
                Text(if (registraChiave) "Registra ed entra" else "Entra")
            }
        },
        dismissButton = { TextButton(onClick = onAnnulla) { Text("Annulla") } },
    )
}

@Composable
fun Campo(
    valore: String,
    onCambia: (String) -> Unit,
    etichetta: String,
    modifier: Modifier = Modifier,
    tastiera: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
) {
    OutlinedTextField(
        value = valore,
        onValueChange = onCambia,
        label = { Text(etichetta) },
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = tastiera),
    )
}

/** Scheda di una macchina nello schermo File, per scegliere dove mandare le cose. */
@Composable
fun SchedaMacchina(sessione: Sessione?, onScegli: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { onScegli() }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    sessione?.nome?.ifBlank { sessione.host } ?: "Nessuna macchina",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
                Text(
                    sessione?.let { "${it.utente}@${it.host}:${it.porta}" } ?: "tocca per scegliere la destinazione",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("cambia", fontSize = 12.sp, color = BLU_CARTELLA)
        }
    }
}
