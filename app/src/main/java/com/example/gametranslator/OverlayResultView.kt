package com.example.gametranslator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import android.view.WindowManager

/** Прозрачный слой поверх экрана: рисует перевод там, где был исходный текст. */
class OverlayResultView(context: Context) : View(context) {

    /** Один переведённый кусок: где он был на снимке и сам текст перевода. */
    data class Item(val rect: Rect, val text: String)

    private var items: List<Item> = emptyList()
    private var sourceW = 1
    private var sourceH = 1
    private var message: String? = null

    private val density = resources.displayMetrics.density

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 18, 18, 18)
    }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }

    private val hideMessage = Runnable {
        message = null
        invalidate()
    }

    fun showItems(list: List<Item>, bitmapWidth: Int, bitmapHeight: Int) {
        removeCallbacks(hideMessage)
        message = null
        items = list
        sourceW = bitmapWidth.coerceAtLeast(1)
        sourceH = bitmapHeight.coerceAtLeast(1)
        invalidate()
    }

    fun showMessage(text: String) {
        items = emptyList()
        message = text
        removeCallbacks(hideMessage)
        postDelayed(hideMessage, 3500)
        invalidate()
    }

    fun clear() {
        removeCallbacks(hideMessage)
        items = emptyList()
        message = null
        invalidate()
    }

    /** Реальный размер экрана в пикселях (как у снимка экрана). */
    private fun screenSize(): Pair<Int, Int> {
        val manager = context.getSystemService(WindowManager::class.java)
        val bounds = manager.maximumWindowMetrics.bounds
        return Pair(bounds.width(), bounds.height())
    }

    private fun build(text: String, widthPx: Int, sizePx: Float, align: Layout.Alignment): StaticLayout {
        textPaint.textSize = sizePx
        return StaticLayout.Builder
            .obtain(text, 0, text.length, textPaint, widthPx.coerceAtLeast(1))
            .setAlignment(align)
            .build()
    }

    /** Подбирает самый крупный шрифт, при котором текст помещается в нужную высоту. */
    private fun fit(text: String, widthPx: Int, maxHeightPx: Float): StaticLayout {
        var size = 22f * density
        val smallest = 11f * density
        while (size > smallest) {
            val candidate = build(text, widthPx, size, Layout.Alignment.ALIGN_NORMAL)
            if (candidate.height <= maxHeightPx) return candidate
            size -= density
        }
        return build(text, widthPx, smallest, Layout.Alignment.ALIGN_NORMAL)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val screen = screenSize()
        val location = IntArray(2)
        getLocationOnScreen(location)
        val scaleX = screen.first.toFloat() / sourceW
        val scaleY = screen.second.toFloat() / sourceH

        val pad = 5f * density
        val margin = 4f * density
        val radius = 6f * density
        val minWidth = 150f * density
        val maxWidth = (width - 2 * margin).coerceAtLeast(minWidth)

        for (item in items) {
            val boxSrcW = item.rect.width() * scaleX
            val boxSrcH = item.rect.height() * scaleY
            val srcLeft = item.rect.left * scaleX - location[0]
            val srcTop = item.rect.top * scaleY - location[1]

            val boxWidth = boxSrcW.coerceIn(minWidth, maxWidth)
            val layout = fit(item.text, (boxWidth - 2 * pad).toInt(), boxSrcH - 2 * pad)
            val boxHeight = maxOf(boxSrcH, layout.height + 2 * pad)

            val left = srcLeft.coerceIn(margin, maxOf(margin, width - boxWidth - margin))
            val top = srcTop.coerceIn(0f, maxOf(0f, height - boxHeight))

            canvas.drawRoundRect(
                RectF(left, top, left + boxWidth, top + boxHeight),
                radius,
                radius,
                backgroundPaint
            )
            canvas.save()
            canvas.translate(left + pad, top + pad)
            layout.draw(canvas)
            canvas.restore()
        }

        val note = message
        if (note != null) {
            val bannerWidth = width * 0.86f
            val layout = build(note, (bannerWidth - 2 * pad).toInt(), 15f * density, Layout.Alignment.ALIGN_CENTER)
            val bannerHeight = layout.height + 2 * pad
            val left = (width - bannerWidth) / 2f
            val top = 48f * density
            canvas.drawRoundRect(
                RectF(left, top, left + bannerWidth, top + bannerHeight),
                radius,
                radius,
                backgroundPaint
            )
            canvas.save()
            canvas.translate(left + pad, top + pad)
            layout.draw(canvas)
            canvas.restore()
        }
    }
}
