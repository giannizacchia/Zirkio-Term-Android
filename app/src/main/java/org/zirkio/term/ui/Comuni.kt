package org.zirkio.term.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.zirkio.term.dati.Campo
import org.zirkio.term.dati.Filtro
import org.zirkio.term.dati.Voce
import org.zirkio.term.dati.formattaByte
import org.zirkio.term.dati.formattaData

/** Gli stessi colori della finestra Trasferimenti sul computer. */
val BLU_CARTELLA = Color(0xFF3584E4)
val SEGNATO = Color(0xFF3584E4)

private val LARGHEZZA_DIMENSIONE = 80.dp
private val LARGHEZZA_DATA = 104.dp

/**
 * Intestazione delle tre colonne: nome, dimensione, data. Si tocca il nome della
 * colonna per ordinare, e la freccia dice in che verso. Come sul desktop.
 */
@Composable
fun IntestazioneColonne(campo: Campo, crescente: Boolean, onCampo: (Campo) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (c in listOf(Campo.NOME, Campo.DIMENSIONE, Campo.DATA)) {
            val scelto = c == campo
            val freccia = if (!scelto) "" else if (crescente) " ▲" else " ▼"
            val etichetta = when (c) {
                Campo.NOME -> "NOME"
                Campo.DIMENSIONE -> "DIMENSIONE"
                Campo.DATA -> "DATA"
            }
            Text(
                text = etichetta + freccia,
                modifier = Modifier
                    .then(
                        when (c) {
                            Campo.NOME -> Modifier.weight(1f)
                            Campo.DIMENSIONE -> Modifier.width(LARGHEZZA_DIMENSIONE)
                            Campo.DATA -> Modifier.width(LARGHEZZA_DATA)
                        },
                    )
                    .clickable { onCampo(c) }
                    .padding(vertical = 4.dp),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (scelto) BLU_CARTELLA else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = if (c == Campo.NOME) TextAlign.Start else TextAlign.End,
                maxLines = 1,
            )
        }
    }
}

/**
 * Elenco di file e cartelle: cartelle in blu e in grassetto, prima dei file,
 * riga segnata con lo sfondo blu e il testo bianco (selezione stile mc).
 *
 * Un tocco segna (o entra nella cartella), **due tocchi aprono il file** con l'app
 * che il telefono ritiene adatta, una pressione lunga segna senza aprire.
 */
@Composable
fun ElencoFile(
    voci: List<Voce>,
    scelte: Set<String>,
    campo: Campo,
    crescente: Boolean,
    onCampo: (Campo) -> Unit,
    onTocco: (Voce) -> Unit,
    onSpunta: (Voce) -> Unit,
    onDoppio: (Voce) -> Unit,
    vuoto: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        IntestazioneColonne(campo, crescente, onCampo)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (voci.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    vuoto,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(voci, key = { it.percorso }) { voce ->
                    RigaFile(
                        voce = voce,
                        segnato = scelte.contains(voce.percorso),
                        onTocco = onTocco,
                        onSpunta = onSpunta,
                        onDoppio = onDoppio,
                    )
                }
            }
        }
    }
}

@Composable
private fun RigaFile(
    voce: Voce,
    segnato: Boolean,
    onTocco: (Voce) -> Unit,
    onSpunta: (Voce) -> Unit,
    onDoppio: (Voce) -> Unit,
) {
    val sfondo = if (segnato) SEGNATO else Color.Transparent
    val coloreNome = when {
        segnato -> Color.White
        voce.cartella -> BLU_CARTELLA
        else -> MaterialTheme.colorScheme.onSurface
    }
    val coloreAltro = if (segnato) Color(0xFFE8EEF9) else MaterialTheme.colorScheme.onSurfaceVariant
    val conContenitore = voce.contenitore.isNotEmpty()
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (conContenitore) 54.dp else 44.dp)
            .background(sfondo)
            .pointerInput(voce.percorso) {
                detectTapGestures(
                    onTap = { onTocco(voce) },
                    onDoubleTap = { onDoppio(voce) },
                    onLongPress = { onSpunta(voce) },
                )
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = voce.nome,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (voce.cartella) FontWeight.Bold else FontWeight.Normal,
                color = coloreNome,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (conContenitore) {
                Text(
                    text = voce.contenitore,
                    fontSize = 10.sp,
                    color = coloreAltro,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = if (voce.cartella) "" else formattaByte(voce.dimensione),
            modifier = Modifier.width(LARGHEZZA_DIMENSIONE),
            fontSize = 11.sp,
            color = coloreAltro,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
        Text(
            text = formattaData(voce.modificato),
            modifier = Modifier.width(LARGHEZZA_DATA),
            fontSize = 11.sp,
            color = coloreAltro,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}

/** Riga in cima con il percorso corrente e le azioni della schermata. */
@Composable
fun BarraPercorso(
    percorso: String,
    onIndietro: () -> Unit,
    azioni: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 2.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onIndietro) {
            Icon(
                androidx.compose.material.icons.Icons.Filled.ArrowBack,
                contentDescription = "cartella superiore",
            )
        }
        Text(
            text = percorso,
            modifier = Modifier.weight(1f),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        azioni()
    }
}

/**
 * I collegamenti in cima: tutto, solo APK, solo documenti, i file più recenti.
 * Quello scelto si colora e mostra quanti risultati ha trovato.
 */
@Composable
fun CollegamentiFiltri(filtro: Filtro, quanti: Int, onScegli: (Filtro) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Filtro.entries.forEach { f ->
            val scelto = f == filtro
            Surface(
                color = if (scelto) BLU_CARTELLA else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.clickable { onScegli(f) },
            ) {
                Text(
                    text = f.etichetta + if (scelto && f != Filtro.TUTTI) " · $quanti" else "",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    fontSize = 12.sp,
                    fontWeight = if (scelto) FontWeight.Bold else FontWeight.Normal,
                    color = if (scelto) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Campo per una password: **in chiaro**, perché chi scrive deve poter vedere se ha
 * sbagliato un carattere, con un pulsante per nasconderla se c'è qualcuno intorno.
 */
@Composable
fun CampoPassword(
    valore: String,
    onValore: (String) -> Unit,
    etichetta: String,
    abilitato: Boolean = true,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    var visibile by remember { mutableStateOf(true) }
    OutlinedTextField(
        value = valore,
        onValueChange = onValore,
        label = { Text(etichetta) },
        singleLine = true,
        enabled = abilitato,
        visualTransformation = if (visibile) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            TextButton(onClick = { visibile = !visibile }) {
                Text(if (visibile) "nascondi" else "mostra", fontSize = 11.sp)
            }
        },
        modifier = modifier,
    )
}
