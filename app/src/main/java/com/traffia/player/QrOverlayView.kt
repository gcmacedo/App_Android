package com.traffia.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Faixa + QR Code por cima do vídeo (mesmo desenho da 0.7.0).
 * Medidas na referência de 1080 px no lado menor da tela.
 */
class QrOverlayView(ctx: Context) : View(ctx) {

    private var qr: Qr? = null
    private var bitmap: Bitmap? = null
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint().apply { isFilterBitmap = false }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    fun setQr(q: Qr?) {
        if (q == qr) return
        qr = q
        bitmap = q?.let { makeQr(it.url) }
        invalidate()
    }

    private fun makeQr(url: String): Bitmap? = try {
        val hints = mapOf(EncodeHintType.MARGIN to 0)
        val m = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 0, 0, hints)
        val w = m.width
        val h = m.height
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) px[y * w + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
        Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    } catch (e: Exception) {
        null
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val q = qr ?: return
        val b = bitmap ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val s = minOf(w, h) / 1080f
        val bandH = 162f * s
        val box = 324f * s
        val frame = 16f * s
        val quiet = 16f * s

        // faixa
        fill.color = q.bg
        c.drawRect(0f, h - bandH, w, h, fill)

        // caixa do QR (sobe acima da faixa)
        val left = w - box
        val top = h - box
        c.drawRect(left, top, w, h, fill)
        fill.color = Color.WHITE
        c.drawRect(left + frame, top + frame, w - frame, h - frame, fill)
        c.drawBitmap(b, null, RectF(left + frame + quiet, top + frame + quiet, w - frame - quiet, h - frame - quiet), bmpPaint)

        // frase
        text.color = q.fg
        text.textSize = 54f * s
        val margin = 74f * s
        val maxW = left - margin - 24f * s
        if (maxW > 0) {
            val cta = TextUtils.ellipsize(q.cta, text, maxW, TextUtils.TruncateAt.END).toString()
            val fm = text.fontMetrics
            val baseline = h - bandH / 2f - (fm.ascent + fm.descent) / 2f
            c.drawText(cta, margin, baseline, text)
        }
    }
}
