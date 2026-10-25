package org.zirkio.term.dati

/**
 * Una voce di elenco, sia essa sul telefono o su una macchina remota.
 * Le cartelle valgono 0 nella colonna della dimensione; la data è quella di ultima
 * modifica. Il tipo non si mostra più in colonna ma serve ai collegamenti in cima
 * all'elenco (APK, documenti).
 */
data class Voce(
    val nome: String,
    val percorso: String,
    val cartella: Boolean,
    val dimensione: Long,
    val tipo: String,
    /** Ultima modifica, in millisecondi dall'epoca. */
    val modificato: Long = 0L,
    /** Per i risultati delle ricerche: la cartella che contiene la voce. */
    val contenitore: String = "",
)

/** Colonna su cui è ordinato l'elenco: nome, dimensione o data. */
enum class Campo { NOME, DIMENSIONE, DATA }

/**
 * Ordina come la finestra Trasferimenti del computer: **prima le cartelle**,
 * poi i file, e dentro ciascun gruppo il campo scelto. Le cartelle restano
 * davanti anche ordinando al contrario.
 */
fun ordina(voci: List<Voce>, campo: Campo, crescente: Boolean): List<Voce> {
    val confronto: Comparator<Voce> = when (campo) {
        Campo.NOME -> compareBy(String.CASE_INSENSITIVE_ORDER) { v: Voce -> v.nome }
        Campo.DIMENSIONE -> compareBy<Voce> { it.dimensione }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.nome }
        Campo.DATA -> compareBy<Voce> { it.modificato }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.nome }
    }
    val perCampo = if (crescente) confronto else confronto.reversed()
    val cartelle = voci.filter { it.cartella }.sortedWith(perCampo)
    val file = voci.filterNot { it.cartella }.sortedWith(perCampo)
    return cartelle + file
}

/**
 * Dimensione leggibile, base 1000 e virgola decimale: 999 B, 1,2 kB, 3,4 MB, 1,1 GB.
 */
fun formattaByte(byte: Long): String {
    if (byte < 1000) return "$byte B"
    var valore = byte.toDouble()
    var unita = ""
    for (u in listOf("kB", "MB", "GB", "TB", "PB")) {
        valore /= 1000.0
        unita = u
        if (valore < 1000.0) break
    }
    val testo = String.format(java.util.Locale.ITALY, "%.1f", valore)
    return "$testo $unita"
}

/** Data e ora senza secondi: `25/10/26 11:44`. */
fun formattaData(millis: Long): String {
    if (millis <= 0L) return ""
    val quando = java.time.Instant.ofEpochMilli(millis)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDateTime()
    return String.format(
        java.util.Locale.ITALY,
        "%02d/%02d/%02d %02d:%02d",
        quando.dayOfMonth,
        quando.monthValue,
        quando.year % 100,
        quando.hour,
        quando.minute,
    )
}

private val TIPI: Map<String, String> = mapOf(
    // testo e codice
    "txt" to "TESTO", "md" to "TESTO", "log" to "TESTO", "csv" to "TESTO",
    "json" to "TESTO", "xml" to "TESTO", "yaml" to "TESTO", "yml" to "TESTO",
    "toml" to "TESTO", "ini" to "TESTO", "conf" to "TESTO", "cfg" to "TESTO",
    "html" to "TESTO", "htm" to "TESTO", "css" to "TESTO", "js" to "TESTO",
    "kt" to "TESTO", "java" to "TESTO", "py" to "TESTO", "rs" to "TESTO",
    "c" to "TESTO", "h" to "TESTO", "cpp" to "TESTO", "sh" to "TESTO",
    "diff" to "TESTO", "patch" to "TESTO", "sql" to "TESTO",
    // immagini
    "jpg" to "IMMAGINE", "jpeg" to "IMMAGINE", "png" to "IMMAGINE", "gif" to "IMMAGINE",
    "bmp" to "IMMAGINE", "webp" to "IMMAGINE", "svg" to "IMMAGINE", "heic" to "IMMAGINE",
    "tif" to "IMMAGINE", "tiff" to "IMMAGINE", "raw" to "IMMAGINE", "dng" to "IMMAGINE",
    // video
    "mp4" to "VIDEO", "mkv" to "VIDEO", "avi" to "VIDEO", "mov" to "VIDEO",
    "webm" to "VIDEO", "m4v" to "VIDEO", "3gp" to "VIDEO",
    // audio
    "mp3" to "AUDIO", "flac" to "AUDIO", "wav" to "AUDIO", "ogg" to "AUDIO",
    "m4a" to "AUDIO", "aac" to "AUDIO", "opus" to "AUDIO", "amr" to "AUDIO",
    // archivi
    "zip" to "ARCHIVIO", "tar" to "ARCHIVIO", "gz" to "ARCHIVIO", "bz2" to "ARCHIVIO",
    "xz" to "ARCHIVIO", "7z" to "ARCHIVIO", "rar" to "ARCHIVIO", "tgz" to "ARCHIVIO",
    // documenti
    "pdf" to "PDF", "doc" to "DOCUMENTO", "docx" to "DOCUMENTO", "odt" to "DOCUMENTO",
    "rtf" to "DOCUMENTO", "xls" to "FOGLIO", "xlsx" to "FOGLIO", "ods" to "FOGLIO",
    "ppt" to "PRESENTAZIONE", "pptx" to "PRESENTAZIONE", "odp" to "PRESENTAZIONE",
    "epub" to "LIBRO",
    // eseguibili e pacchetti
    "apk" to "PACCHETTO", "deb" to "PACCHETTO", "rpm" to "PACCHETTO", "aab" to "PACCHETTO",
    "exe" to "ESEGUIBILE", "bin" to "ESEGUIBILE", "so" to "ESEGUIBILE", "appimage" to "ESEGUIBILE",
    // chiavi e certificati
    "pem" to "CHIAVE", "key" to "CHIAVE", "pub" to "CHIAVE", "crt" to "CHIAVE", "asc" to "CHIAVE",
)

/** Estensione in minuscolo, vuota se il nome non ne ha. */
fun estensione(nome: String): String {
    val punto = nome.lastIndexOf('.')
    if (punto <= 0 || punto == nome.length - 1) return ""
    return nome.substring(punto + 1).lowercase()
}

/**
 * Tipo dal nome del file, in maiuscolo. Un nome senza punto o con un'estensione
 * sconosciuta è semplicemente FILE.
 */
fun tipoFile(nome: String, cartella: Boolean): String {
    if (cartella) return "CARTELLA"
    val ext = estensione(nome)
    if (ext.isEmpty()) return "FILE"
    return TIPI[ext] ?: ext.uppercase()
}

/** Estensioni che contano come «documenti» per i collegamenti in cima all'elenco. */
val ESTENSIONI_DOCUMENTI: Set<String> = setOf(
    "pdf", "doc", "docx", "odt", "rtf", "txt", "md", "csv", "xls", "xlsx", "ods",
    "ppt", "pptx", "odp", "epub",
)
