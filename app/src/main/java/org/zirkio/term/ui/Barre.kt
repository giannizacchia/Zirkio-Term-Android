package org.zirkio.term.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * La barra dei comandi dell'elenco file: invia/scarica, sposta, elimina, nuova cartella.
 *
 * Quando si sta scegliendo dove spostare, la barra cambia faccia: sparisce tutto e
 * restano «Sposta qui» e «Annulla», così non si può sbagliare bersaglio.
 */
@Composable
fun BarraAzioniFile(
    quanti: Int,
    scegliendoDestinazione: Boolean,
    principaleDescrizione: String,
    principaleIcona: ImageVector = Icons.Filled.Send,
    onPrincipale: () -> Unit,
    onSposta: () -> Unit,
    onElimina: () -> Unit,
    onCartella: () -> Unit,
    onSpostaQui: () -> Unit,
    onAnnullaSpostamento: () -> Unit,
) {
    if (scegliendoDestinazione) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Scegli la cartella di arrivo, poi «Sposta qui»",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = BLU_CARTELLA,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSpostaQui) { Text("Sposta qui", fontSize = 13.sp) }
            TextButton(onClick = onAnnullaSpostamento) { Text("Annulla", fontSize = 13.sp) }
        }
    } else {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (quanti == 0) "tocca per segnare" else "$quanti segnati",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onPrincipale) {
                Icon(principaleIcona, contentDescription = principaleDescrizione, tint = BLU_CARTELLA)
            }
            IconButton(onClick = onSposta, enabled = quanti > 0) {
                Icon(
                    Icons.Filled.DriveFileMove,
                    contentDescription = "sposta",
                    tint = if (quanti > 0) MaterialTheme.colorScheme.onSurface else Color(0xFF4A4F58),
                )
            }
            IconButton(onClick = onElimina, enabled = quanti > 0) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "elimina",
                    tint = if (quanti > 0) Color(0xFFE01B24) else Color(0xFF4A4F58),
                )
            }
            IconButton(onClick = onCartella) {
                Icon(
                    Icons.Filled.CreateNewFolder,
                    contentDescription = "nuova cartella",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Icona da usare per il comando principale: inviare o scaricare. */
val ICONA_SCARICA: ImageVector = Icons.Filled.Download
