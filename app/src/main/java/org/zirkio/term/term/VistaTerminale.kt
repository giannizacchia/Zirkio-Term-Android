package org.zirkio.term.term

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * La vista del terminale: disegna la griglia con Canvas (accelerato dall'hardware) e si
 * occupa di tastiera, scorrimento e selezione.
 *
 * Come resta fluida: il testo si disegna **a pezzi**, non cella per cella — si accumula una
 * riga finché colore e stile restano uguali e si fa una sola `drawText` per pezzo. Con 80×24
 * si passa da ~1900 chiamate a poche decine per fotogramma.
 */
class VistaTerminale @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var terminale: Terminale = Terminale()
        set(valore) {
            field = valore                      // senza questo la vista resta col suo terminale vuoto
            field.suModifica = { suModificaTerminale() }
            ricalcolaMisure()
            invalidate()
        }

    /** Testo digitato (tastiera virtuale o fisica). */
    var suTesto: ((String) -> Unit)? = null
    /**
     * Comandi dell'app (`@sd <nome>`) digitati nel terminale: chi ci ospita restituisce
     * true se li ha presi in carico (allora la riga non va mandata alla shell).
     */
    var suComando: ((String) -> Boolean)? = null

    /** La riga digitata dall'ultimo Invio: serve a riconoscere `@sd <nome>`. */
    private val rigaDigitata = StringBuilder()
    /** Nuova dimensione in caratteri: chi ci ospita deve dire alla shell di ridimensionarsi. */
    var suRidimensiona: ((Int, Int) -> Unit)? = null

    /** Ctrl/Alt armati dalla barra dei tasti: valgono per il prossimo carattere. */
    var ctrlPremuto = false
    var altPremuto = false

    var dimensioneTesto = 14f
        set(valore) {
            field = valore.coerceIn(6f, 32f)
            ricalcolaMisure()
        }

    private var larghezzaCarattere = 0f
    // Dichiarate prima di tutto il resto: il costruttore chiama ricalcolaMisure(), che le usa.
    private val cacheRighe = HashMap<Int, RigaPronta>()
    private val cachePittori = HashMap<Long, Paint>()

    private var altezzaRiga = 0f
    private var baseRiga = 0f
    private var colonneCalcolate = 0
    private var righeCalcolate = 0

    private val grassetti = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val corsivi = Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC)
    private val grassettiCorsivi = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD_ITALIC)

    private val pittoreTesto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        isSubpixelText = true
    }
    private val pittoreSfondo = Paint().apply { style = Paint.Style.FILL }

    private val scroller = OverScroller(context)
    /** Quanti pixel di storico stanno sopra il fondo: 0 = fondo (riga viva). */
    private var scorrimentoPx = 0f
    /** Frazione di riga non intera, per far scorrere il testo fluido e non a scatti. */
    private var resto = 0f
    private var righeStoricoNoto = 0
    /** Di quanto si può tirare oltre i bordi: il ritorno lo fa lo scroller. */
    private var rimbalzo = 0f
    private var velocita: VelocityTracker? = null
    private var ultimoY = 0f
    private var distanzaTocco = 0f
    private var trascinando = false

    private var selInizio: Pair<Int, Int>? = null
    private var selFine: Pair<Int, Int>? = null
    private val rettangolo = RectF()

    private var cursoreAcceso = true

    // La finestra visibile viene disegnata in una bitmap e poi copiata spostata: mentre si
    // scorre non si tocca più il testo.
    private var tela: android.graphics.Bitmap? = null
    private var telaCanvas: Canvas? = null
    private var telaValida = false
    private var telaPrima = -1
    private var srcY_ = 0
    private val rettangoloSorgente = android.graphics.Rect()
    private val rettangoloDestinazione = android.graphics.Rect()
    /** Righe disegnate oltre il fondo: più sono, meno ridisegni durante lo scorrimento. */
    private val margine = 10
    private val lampeggio = object : Runnable {
        override fun run() {
            cursoreAcceso = !cursoreAcceso
            telaValida = false
            postInvalidateOnAnimation()
            postDelayed(this, 550)
        }
    }

    private val sogliaTocco = ViewConfiguration.get(context).scaledTouchSlop
    private var inizioToccoX = 0f
    private var inizioToccoY = 0f
    private var toccoLungo = false
    private val rilevatoreToccoLungo = Runnable {
        toccoLungo = true
        selInizio = cellaA(inizioToccoX, inizioToccoY)
        selFine = selInizio
        postInvalidateOnAnimation()
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Tavolozza.SFONDO)
        terminale.suModifica = { suModificaTerminale() }
        righeStoricoNoto = terminale.storico.size
        ricalcolaMisure()
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(lampeggio, 550)
    }

    // ---------------------------------------------------------------- misure

    private fun ricalcolaMisure() {
        pittoreTesto.textSize = dimensioneTesto * resources.displayMetrics.scaledDensity
        val metriche = pittoreTesto.fontMetrics
        // righe di altezza intera: così i glifi cadono sempre sugli stessi pixel e il testo
        // non "frigge" mentre scorre
        altezzaRiga = ceil((metriche.descent - metriche.ascent).coerceAtLeast(1f))
        baseRiga = ceil(-metriche.ascent)
        larghezzaCarattere = max(1f, pittoreTesto.measureText("M"))
        cacheRighe.clear()
        cachePittori.clear()
        telaValida = false
        if (width > 0 && height > 0) adattaGriglia()
        invalidate()
    }

    private fun adattaGriglia() {
        val c = max(1, (width / larghezzaCarattere).toInt())
        val r = max(1, (height / altezzaRiga).toInt())
        if (c == colonneCalcolate && r == righeCalcolate) return
        cacheRighe.clear()
        colonneCalcolate = c
        righeCalcolate = r
        terminale.ridimensiona(c, r)
        val massimo = scorrimentoMassimoPx()
        scorrimentoPx = scorrimentoPx.coerceIn(0f, massimo)
        suRidimensiona?.invoke(c, r)
    }

    override fun onSizeChanged(w: Int, h: Int, vecchiaL: Int, vecchiaA: Int) {
        super.onSizeChanged(w, h, vecchiaL, vecchiaA)
        adattaGriglia()
    }

    // ---------------------------------------------------------------- disegno

    /**
     * Il terminale è cambiato. Se stiamo leggendo più in alto non si viene trascinati
     * in fondo: le righe nuove scorrono sotto, quelle che stiamo guardando restano.
     */
    private fun suModificaTerminale() {
        val nuove = terminale.storico.size
        if (nuove != righeStoricoNoto && altezzaRiga > 0f && scorrimentoPx > 0f) {
            scorrimentoPx = (scorrimentoPx + (nuove - righeStoricoNoto) * altezzaRiga).coerceAtLeast(0f)
        }
        righeStoricoNoto = nuove
        cacheRighe.clear()
        telaValida = false
        postInvalidateOnAnimation()
    }

    private fun massimoPx(): Float = terminale.storico.size * altezzaRiga

    private fun scorrimentoMassimoPx(): Float = max(0f, massimoPx())

    private fun primaRigaVisibile(): Int {
        val totali = terminale.righe + terminale.storico.size
        val righeSu = if (altezzaRiga <= 0f) 0 else floor(scorrimentoPx / altezzaRiga).toInt()
        return (totali - terminale.righe - righeSu).coerceAtLeast(0)
    }

    override fun onDraw(canvas: Canvas) {
        if (larghezzaCarattere <= 0f || altezzaRiga <= 0f) {
            canvas.drawColor(Tavolozza.SFONDO)
            return
        }
        val prima = primaRigaVisibile()
        resto = if (scorrimentoPx <= 0f) 0f else scorrimentoPx - floor(scorrimentoPx / altezzaRiga) * altezzaRiga
        if (!assicuraTela()) {
            canvas.drawColor(Tavolozza.SFONDO)
            return
        }
        val scarto = prima - telaPrima
        if (!telaValida || scarto < 0 || scarto > margine) {
            disegnaTela(prima)
        }
        val tela = tela ?: return
        val srcY = ((prima - telaPrima) * altezzaRiga + resto).toInt()
        if (srcY < 0 || srcY + height > tela.height) {
            disegnaTela(prima)
            srcY_ = 0
        } else {
            srcY_ = srcY
        }
        rettangoloSorgente.set(0, srcY_, width, srcY_ + height)
        rettangoloDestinazione.set(0, 0, width, height)
        canvas.drawBitmap(tela, rettangoloSorgente, rettangoloDestinazione, null)
    }

    /**
     * Disegna la parte di terminale che serve dentro una tela (bitmap) e la tiene da parte.
     *
     * Mentre si scorre non si ridisegna più il testo a ogni fotogramma: si copia la tela
     * spostata di qualche pixel, che costa quasi nulla anche a 120 Hz. Si ridisegna solo
     * quando si esce dal margine di righe già pronte.
     */
    private fun disegnaTela(prima: Int) {
        val c = telaCanvas ?: return
        c.drawColor(Tavolozza.SFONDO)
        val t = terminale
        val righe = righeCalcolate + margine + 1
        for (i in 0 until righe) {
            val indice = prima + i
            if (indice >= t.righeTotali()) break
            disegnaRiga(c, t.riga(indice), i * altezzaRiga, indice)
        }
        disegnaCursore(c, prima, 0f)
        disegnaSelezione(c, prima, 0f)
        telaPrima = prima
        telaValida = true
    }

    private fun assicuraTela(): Boolean {
        val w = width
        val h = height
        if (w <= 0 || h <= 0 || altezzaRiga <= 0f) return false
        if (margine < 1) return false
        val altezzaTela = ((righeCalcolate + margine + 2) * altezzaRiga).toInt()
        val vecchia = tela
        if (vecchia != null && vecchia.width == w && vecchia.height == altezzaTela) return true
        vecchia?.recycle()
        val nuova = android.graphics.Bitmap.createBitmap(w, altezzaTela, android.graphics.Bitmap.Config.ARGB_8888)
        tela = nuova
        telaCanvas = Canvas(nuova)
        telaValida = false
        telaPrima = -1
        return true
    }

    /**
     * Una riga già impaginata: i pezzi di testo con colore e stile omogenei.
     *
     * Impaginare a ogni fotogramma (StringBuilder, cambi di typeface, colore) è quello che
     * rendeva lo scorrimento a scatti: si calcola una volta per riga e poi si ridisegna solo
     * il testo già pronto.
     */
    private class Pezzo(val testo: String, val inizio: Int, val fine: Int, val fg: Int, val bg: Int, val stile: Int)

    private class RigaPronta(val riga: Terminale.Riga, val pezzi: Array<Pezzo>)


    /** Un pennello per ogni combinazione di colore e stile: il typeface non si cambia più a ogni riga. */
    private fun pittorePer(colore: Int, stile: Int): Paint {
        val chiave = (stile.toLong() shl 32) or (colore.toLong() and 0xFFFFFFFFL)
        cachePittori[chiave]?.let { return it }
        if (cachePittori.size > 240) cachePittori.clear()
        val p = Paint(pittoreTesto)
        p.color = colore
        p.typeface = when {
            stile and Terminale.GRASSETTO != 0 && stile and Terminale.CORSIVO != 0 -> grassettiCorsivi
            stile and Terminale.GRASSETTO != 0 -> grassetti
            stile and Terminale.CORSIVO != 0 -> corsivi
            else -> Typeface.MONOSPACE
        }
        p.isUnderlineText = stile and Terminale.SOTTOLINEATO != 0
        p.isStrikeThruText = stile and Terminale.SBARRATO != 0
        p.alpha = if (stile and Terminale.FIOCCO != 0) 150 else 255
        cachePittori[chiave] = p
        return p
    }

    private fun preparaRiga(riga: Terminale.Riga): RigaPronta {
        val pezzi = ArrayList<Pezzo>(4)
        val testo = StringBuilder(terminale.colonne)
        var inizioPezzo = 0
        var fgPezzo = -2
        var bgPezzo = -2
        var stilePezzo = -1

        fun chiudiPezzo(fine: Int) {
            if (testo.isEmpty()) return
            pezzi.add(Pezzo(testo.toString(), inizioPezzo, fine, fgPezzo, bgPezzo, stilePezzo))
            testo.setLength(0)
        }

        for (c in 0 until terminale.colonne) {
            val codice = riga.testo[c]
            val fg = riga.fg[c]
            val bg = riga.bg[c]
            val st = riga.stile[c].toInt()
            if (codice == 0 && bg == Terminale.PREDEFINITO) {
                // spazio vuoto normale: si chiude il pezzo, non si disegna nulla
                if (testo.isNotEmpty()) {
                    chiudiPezzo(c)
                }
                inizioPezzo = c + 1
                continue
            }
            if (stilePezzo == -1) {
                inizioPezzo = c
                fgPezzo = fg; bgPezzo = bg; stilePezzo = st
            } else if (fg != fgPezzo || bg != bgPezzo || st != stilePezzo) {
                chiudiPezzo(c)
                inizioPezzo = c
                fgPezzo = fg; bgPezzo = bg; stilePezzo = st
            }
            testo.appendCodePoint(if (codice == 0) ' '.code else codice)
        }
        if (testo.isNotEmpty()) chiudiPezzo(inizioPezzo + testo.length)
        return RigaPronta(riga, pezzi.toTypedArray())
    }

    private fun rigaPronta(indice: Int, riga: Terminale.Riga): RigaPronta {
        val pronta = cacheRighe[indice]
        if (pronta != null && pronta.riga === riga) return pronta
        if (cacheRighe.size > 600) cacheRighe.clear()
        val nuova = preparaRiga(riga)
        cacheRighe[indice] = nuova
        return nuova
    }

    private fun disegnaRiga(canvas: Canvas, riga: Terminale.Riga, y: Float, indiceRiga: Int) {
        val pronta = rigaPronta(indiceRiga, riga)
        val pezzi = pronta.pezzi
        for (i in pezzi.indices) {
            val pezzo = pezzi[i]
            val x = pezzo.inizio * larghezzaCarattere
            if (pezzo.bg >= -1) {
                pittoreSfondo.color = coloreSfondo(pezzo.bg, pezzo.stile)
                canvas.drawRect(x, y, pezzo.fine * larghezzaCarattere, y + altezzaRiga, pittoreSfondo)
            }
            canvas.drawText(
                pezzo.testo,
                x,
                y + baseRiga,
                pittorePer(coloreTesto(pezzo.fg, pezzo.stile), pezzo.stile),
            )
        }
    }

    private fun coloreTesto(valore: Int, stile: Int): Int {
        val invertito = stile and Terminale.INVERTITO != 0
        val grassetto = stile and Terminale.GRASSETTO != 0
        return if (invertito) {
            if (valore == Terminale.PREDEFINITO) Tavolozza.SFONDO else Tavolozza.argb(valore, Tavolozza.SFONDO)
        } else {
            Tavolozza.argb(valore, Tavolozza.TESTO, grassetto)
        }
    }

    private fun coloreSfondo(valore: Int, stile: Int): Int {
        val invertito = stile and Terminale.INVERTITO != 0
        return if (invertito) {
            Tavolozza.argb(valore, Tavolozza.TESTO)
        } else if (valore == Terminale.PREDEFINITO) {
            Tavolozza.SFONDO
        } else {
            Tavolozza.argb(valore, Tavolozza.SFONDO)
        }
    }

    private fun disegnaCursore(canvas: Canvas, prima: Int, scostamento: Float) {
        val t = terminale
        if (!t.cursoreVisibile || !cursoreAcceso) return
        val riga = t.cursoreRiga - (prima - t.storico.size)
        if (riga < 0 || riga >= righeCalcolate) return
        val x = t.cursoreColonna * larghezzaCarattere
        val y = riga * altezzaRiga - scostamento
        pittoreSfondo.color = 0xFF3584E4.toInt()
        if (hasFocus()) {
            canvas.drawRect(x, y, x + larghezzaCarattere, y + altezzaRiga, pittoreSfondo)
            val r = t.rigaVisibile(t.cursoreRiga)
            val codice = r.testo.getOrNull(t.cursoreColonna) ?: 0
            if (codice != 0) {
                canvas.drawText(
                    String(Character.toChars(codice)),
                    x,
                    y + baseRiga,
                    pittorePer(Tavolozza.SFONDO, 0),
                )
            }
        } else {
            // senza fuoco: cursore a cornice, per non confondere
            pittoreSfondo.style = Paint.Style.STROKE
            pittoreSfondo.strokeWidth = 2f
            canvas.drawRect(x, y, x + larghezzaCarattere, y + altezzaRiga, pittoreSfondo)
            pittoreSfondo.style = Paint.Style.FILL
        }
    }

    private fun disegnaSelezione(canvas: Canvas, prima: Int, scostamento: Float) {
        val a = selInizio ?: return
        val b = selFine ?: return
        val (inizio, fine) = if (a.first < b.first || (a.first == b.first && a.second <= b.second)) a to b else b to a
        pittoreSfondo.color = 0x553584E4
        for (indice in inizio.first..fine.first) {
            val rigaSchermo = indice - prima
            if (rigaSchermo < 0 || rigaSchermo >= righeCalcolate) continue
            val da = if (indice == inizio.first) inizio.second else 0
            val a2 = if (indice == fine.first) fine.second + 1 else terminale.colonne
            rettangolo.set(
                da * larghezzaCarattere,
                rigaSchermo * altezzaRiga - scostamento,
                a2 * larghezzaCarattere,
                (rigaSchermo + 1) * altezzaRiga - scostamento,
            )
            canvas.drawRect(rettangolo, pittoreSfondo)
        }
    }

    // ---------------------------------------------------------------- tocco

    private fun cellaA(x: Float, y: Float): Pair<Int, Int> {
        val colonna = (x / larghezzaCarattere).toInt().coerceIn(0, terminale.colonne - 1)
        val rigaSchermo = ((y + resto) / altezzaRiga).toInt().coerceIn(0, righeCalcolate - 1)
        val indice = primaRigaVisibile() + rigaSchermo
        return indice to colonna
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (velocita == null) velocita = VelocityTracker.obtain()
        velocita?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                inizioToccoX = event.x
                inizioToccoY = event.y
                ultimoY = event.y
                distanzaTocco = 0f
                trascinando = false
                toccoLungo = false
                scroller.forceFinished(true)
                postDelayed(rilevatoreToccoLungo, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (toccoLungo) {
                    selFine = cellaA(event.x, event.y)
                    telaValida = false
                    postInvalidateOnAnimation()
                    ultimoY = event.y
                } else {
                    // il sistema accorpa i movimenti: si applicano tutti i campioni, così il
                    // testo segue il dito anche nei colpi più veloci
                    var y = ultimoY
                    var mosso = 0f
                    for (h in 0 until event.historySize) {
                        val yh = event.getHistoricalY(h)
                        val dy = yh - y
                        if (dy != 0f) {
                            mosso += abs(dy)
                            if (trascinando || distanzaTocco + mosso > sogliaTocco) {
                                removeCallbacks(rilevatoreToccoLungo)
                                trascinando = true
                                scosta(dy)
                            }
                            y = yh
                        }
                    }
                    val dy = event.y - y
                    if (dy != 0f) {
                        mosso += abs(dy)
                        if (trascinando || distanzaTocco + mosso > sogliaTocco) {
                            removeCallbacks(rilevatoreToccoLungo)
                            trascinando = true
                            scosta(dy)
                        }
                    }
                    distanzaTocco += mosso
                    ultimoY = event.y
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(rilevatoreToccoLungo)
                val vy = velocita?.let {
                    it.computeCurrentVelocity(1000, tassoVelocitaMassimo)
                    it.yVelocity
                } ?: 0f
                velocita?.recycle()
                velocita = null
                if (toccoLungo) {
                    copiaSelezione()
                    selInizio = null
                    selFine = null
                    telaValida = false
                    postInvalidateOnAnimation()
                } else if (trascinando) {
                    avviaInerzia(vy)
                } else {
                    if (!hasFocus()) requestFocus()
                    // un tocco semplice fa comparire la tastiera
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                    imm?.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(rilevatoreToccoLungo)
                velocita?.recycle()
                velocita = null
                avviaInerzia(0f)
            }
        }
        return true
    }

    private val massimoFling: Int by lazy {
        (ViewConfiguration.get(context).scaledMaximumFlingVelocity / 2f).toInt()
    }

    private val tassoVelocitaMassimo: Float by lazy {
        ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    }

    /**
     * Scorre di `dy` pixel con il dito. Oltre i bordi il movimento è frenato (gomma
     * elastica), e al rilascio lo scroller riporta tutto dentro con un rimbalzo.
     */
    private fun scosta(dy: Float) {
        if (altezzaRiga <= 0f) return
        val massimo = scorrimentoMassimoPx()
        rimbalzo = altezzaRiga * 3f
        var nuovo = scorrimentoPx + dy
        nuovo = when {
            nuovo < 0f -> nuovo * 0.35f
            nuovo > massimo -> massimo + (nuovo - massimo) * 0.35f
            else -> nuovo
        }
        // solo pixel interi: con offset frazionari i glifi vengono ricampionati a ogni
        // fotogramma e il testo sembra tremare
        scorrimentoPx = nuovo.coerceIn(-rimbalzo, massimo + rimbalzo).roundToInt().toFloat()
        postInvalidateOnAnimation()
    }

    /** Fa continuare lo scorrimento con la velocità del dito (e rimbalzare agli estremi). */
    private fun avviaInerzia(velocitaY: Float) {
        if (altezzaRiga <= 0f) return
        val massimo = scorrimentoMassimoPx().toInt()
        val oltre = (altezzaRiga * 3f).toInt()
        val minimo = -oltre
        val posizione = scorrimentoPx.toInt()
        val fuori = posizione < 0 || posizione > massimo
        if (fuori || abs(velocitaY) < 40f) {
            scroller.springBack(0, posizione, 0, 0, 0, massimo)
        } else {
            // (0,0) sono le coordinate finte: interessa solo l'asse Y
            // il dito che scende (velocità positiva) va verso lo storico: stesso segno.
            // Un 0,55 tiene la corsa rapida ma non fa saltare centinaia di righe per una
            // passata sola.
            val spinta = (velocitaY * 0.55f).toInt().coerceIn(-massimoFling, massimoFling)
            scroller.fling(0, posizione, 0, spinta, 0, 0, minimo, massimo + oltre, 0, oltre)
        }
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scorrimentoPx = scroller.currY.toFloat().roundToInt().toFloat()
            postInvalidateOnAnimation()
        }
    }

    fun tornaInFondo() {
        scorrimentoPx = 0f
        scroller.forceFinished(true)
        postInvalidateOnAnimation()
    }

    /** Testo selezionato (o tutta la schermata se non c'è selezione). */
    fun testoSelezionato(): String {
        val a = selInizio ?: return terminale.testoVisibile()
        val b = selFine ?: return terminale.testoVisibile()
        val (inizio, fine) = if (a.first < b.first || (a.first == b.first && a.second <= b.second)) a to b else b to a
        val sb = StringBuilder()
        for (indice in inizio.first..fine.first) {
            val riga = terminale.riga(indice)
            val da = if (indice == inizio.first) inizio.second else 0
            val a2 = if (indice == fine.first) fine.second + 1 else terminale.colonne
            for (c in da until a2.coerceAtMost(terminale.colonne)) {
                val cod = riga.testo[c]
                sb.appendCodePoint(if (cod == 0) ' '.code else cod)
            }
            if (indice != fine.first) sb.append('\n')
        }
        return sb.toString()
    }

    fun copiaSelezione(): Boolean {
        val testo = testoSelezionato()
        if (testo.isBlank()) return false
        val appunti = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        appunti.setPrimaryClip(ClipData.newPlainText("terminale", testo))
        return true
    }

    // ---------------------------------------------------------------- tastiera

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val testo: String = event.unicodeChar.let { if (it == 0) "" else Character.toChars(it).concatToString() }
        val ctrl = event.isCtrlPressed
        val alt = event.isAltPressed
        val sequenza = when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> "\r"
            KeyEvent.KEYCODE_DEL -> "\u007F"
            KeyEvent.KEYCODE_FORWARD_DEL -> "\u001B[3~"
            KeyEvent.KEYCODE_TAB -> "\t"
            KeyEvent.KEYCODE_ESCAPE -> "\u001B"
            KeyEvent.KEYCODE_DPAD_UP -> "\u001B[A"
            KeyEvent.KEYCODE_DPAD_DOWN -> "\u001B[B"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001B[C"
            KeyEvent.KEYCODE_DPAD_LEFT -> "\u001B[D"
            KeyEvent.KEYCODE_MOVE_HOME -> "\u001B[H"
            KeyEvent.KEYCODE_MOVE_END -> "\u001B[F"
            KeyEvent.KEYCODE_PAGE_UP -> "\u001B[5~"
            KeyEvent.KEYCODE_PAGE_DOWN -> "\u001B[6~"
            else -> null
        }
        if (sequenza != null) {
            val daMandare = if (alt) "\u001B$sequenza" else sequenza
            if (alt) suTesto?.invoke(daMandare) else invia(daMandare)
            return true
        }
        if (testo.isEmpty()) return super.onKeyDown(keyCode, event)
        val daMandare = when {
            ctrl && testo.length == 1 -> {
                val c = testo[0].uppercaseChar()
                if (c in 'A'..'Z') (c.code - 64).toChar().toString() else testo
            }
            alt -> "\u001B$testo"
            else -> testo
        }
        invia(daMandare)
        return true
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        return object : BaseInputConnection(this, false) {
            override fun getEditable(): Editable = Editable.Factory.getInstance().newEditable("")
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!text.isNullOrEmpty()) mandaConModificatori(text.toString())
                return true
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength) { invia("\u007F") }
                return true
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                return if (event.action == KeyEvent.ACTION_DOWN) onKeyDown(event.keyCode, event) else true
            }
        }
    }

    /** Applica Ctrl/Alt armati al testo digitato, poi li consuma. */
    private fun mandaConModificatori(testo: String) {
        val daMandare = when {
            ctrlPremuto && testo.length == 1 -> {
                val c = testo[0].uppercaseChar()
                if (c in 'A'..'Z') (c.code - 64).toChar().toString() else testo
            }
            ctrlPremuto && testo == " " -> "\u0000"
            altPremuto -> "\u001B$testo"
            else -> testo
        }
        if (ctrlPremuto || altPremuto) {
            ctrlPremuto = false
            altPremuto = false
        }
        invia(daMandare)
    }

    /** Manda una sequenza come se fosse digitata (usato dalla barra dei tasti). */
    fun manda(sequenza: String) {
        invia(sequenza)
    }

    /**
     * Manda il testo alla shell, ma prima guarda la riga digitata: se è un comando
     * dell'app (`@sd <nome>`) la riga si cancella dallo schermo con Ctrl+U e il comando
     * viene preso in carico qui, invece di finire alla shell.
     */
    private fun invia(testo: String) {
        if (testo.isEmpty()) return
        if (intercetta(testo)) {
            suTesto?.invoke("\u0015")       // Ctrl+U: via la riga digitata
            return
        }
        suTesto?.invoke(testo)
    }

    /**
     * Tiene il conto di quello che si sta digitando e, all'Invio, decide se la riga è
     * un comando dell'app.  `true` = preso in carico (non va mandato alla shell).
     */
    private fun intercetta(testo: String): Boolean {
        // Frecce, Esc e combinazioni mandano sequenze di escape: non sono testo
        // digitato e non devono sporcare la riga che stiamo contando.
        if (testo.contains('\u001B')) {
            rigaDigitata.setLength(0)
            return false
        }
        var invio = false
        for (c in testo) {
            when {
                c == '\r' || c == '\n' -> invio = true
                c == '\u007F' || c == '\b' -> if (rigaDigitata.isNotEmpty()) {
                    rigaDigitata.deleteCharAt(rigaDigitata.length - 1)
                }
                c == '\u0015' -> rigaDigitata.setLength(0)
                c.code < 0x20 -> Unit                       // altri comandi di controllo
                else -> rigaDigitata.append(c)
            }
        }
        if (!invio) return false
        val riga = rigaDigitata.toString()
        rigaDigitata.setLength(0)
        if (riga.isBlank()) return false
        val gestito = suComando?.invoke(riga) == true
        if (gestito) android.util.Log.i("zirkio", "comando del terminale: $riga")
        return gestito
    }

    /** Scorre di mezza schermata (usato dalle frecce della barra tasti). */
    fun scorri(direzione: Int) {
        val righe = (righeCalcolate / 2).coerceAtLeast(1)
        scorrimentoPx = (scorrimentoPx + direzione * righe * altezzaRiga)
            .coerceIn(0f, scorrimentoMassimoPx())
        postInvalidateOnAnimation()
    }

    fun larghezzaInCaratteri(): Int = colonneCalcolate
    fun altezzaInRighe(): Int = righeCalcolate
    fun scorrimentoMassimo(): Int = terminale.storico.size
    fun scorrimentoCorrente(): Int =
        if (altezzaRiga <= 0f) 0 else (scorrimentoPx / altezzaRiga).roundToInt()

    fun impostaScorrimento(valore: Int) {
        scorrimentoPx = (valore.coerceIn(0, terminale.storico.size) * altezzaRiga)
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(lampeggio)
        super.onDetachedFromWindow()
        tela?.recycle()
        tela = null
        telaCanvas = null
        telaValida = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // si prende tutto lo spazio: è un terminale
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec),
        )
    }

    fun altezzaRigaPx(): Float = altezzaRiga
    fun scostamentoPx(): Float = scorrimentoPx
    fun righeVisibili(): Int = righeCalcolate
    fun colonne(): Int = colonneCalcolate
    fun offsetRotondo(): Int = scorrimentoCorrente()
    fun inizioSelezione(): Pair<Int, Int>? = selInizio
}
