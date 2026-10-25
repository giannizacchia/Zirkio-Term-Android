package org.zirkio.term.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.zirkio.term.dati.Coda
import org.zirkio.term.dati.Verso

/**
 * La coda dei trasferimenti: un lavoro per volta, con la barra di avanzamento,
 * la velocità d'insieme e l'esito. Il terminale resta usabile mentre gira.
 */
@Composable
fun SchermoCoda(coda: Coda, onTogliFiniti: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        if (coda.voci.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Nessun trasferimento", fontWeight = FontWeight.Bold)
                Text(
                    "Dalla schermata File scegli una o più voci e tocca «Invia» (o «Scarica» dalla parte della macchina).",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(coda.voci, key = { it.lavoro.id }) { voce ->
                    val lavoro = voce.lavoro
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                lavoro.nome,
                                modifier = Modifier.weight(1f),
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                            Text(
                                (if (lavoro.verso == Verso.INVIA) "→ " else "← ") + lavoro.macchina,
                                fontSize = 11.sp,
                                color = BLU_CARTELLA,
                            )
                        }
                        Text(
                            voce.stato,
                            fontSize = 11.sp,
                            color = when {
                                voce.errore != null -> Color(0xFFE01B24)
                                voce.finito -> Color(0xFF26A269)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        if (!voce.finito) {
                            LinearProgressIndicator(
                                progress = { voce.frazione },
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }

        if (coda.voci.any { it.finito }) {
            Box(Modifier.align(Alignment.BottomCenter).padding(12.dp)) {
                TextButton(onClick = onTogliFiniti) { Text("Togli i finiti") }
            }
        }
    }
}
