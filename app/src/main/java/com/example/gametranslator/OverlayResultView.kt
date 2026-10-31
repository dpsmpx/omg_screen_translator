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

/**
 * Прозрачный слой поверх экрана: рисует перевод там, где был исходный текст.
 *
 * Когда показывать нечего, слой скрыт (GONE). Это важно: на Android 12+ видимое
 * окно другого приложения поверх игры может «съедать» касания, а скрытое — нет.
 */
class OverlayResultView(context: Context) : View(context) {

    /** Один переведённый кусок: где он был на снимке и сам текст перевода. */
    data class Item(val rect: Rect, val text: String)

    private var items: List<Item> = emptyList()
    private var sourceW = 1
    private var sourceH = 1
    private var message: String? = null

    private val density = resources.displayMetrics.density
    private val windowManager: WindowManager = context.getSystemService(WindowManager::class.java)

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(18, 18, 18)
    }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }

    // Переиспользуемые объекты, чтобы не создавать их на каждой перерисовке
    private val boxRect = RectF()
    private val location = IntArray(2)

    private val hideMessage = Runnable {
        message = null
        updateVisibility()
        invalidate()
    }

    init {
        visibility = GONE
    }

    fun showItems(list: List<Item>, bitmapWidth: Int, bitmapHeight: Int) {
        removeCallbacks(hideMessage)
        message = null
        items = list
        sourceW = bitmapWidth.coerceAtLeast(1)
        sourceH = bitmapHeight.coerceAtLeast(1)
        updateVisibility()
        invalidate()
    }

    fun showMessage(text: String) {
        items = emptyList()
        message = text
        removeCallbacks(hideMessage)
        postDelayed(hideMessage, 3500)
        updateVisibility()
        invalidate()
    }

    fun clear() {
        removeCallbacks(hideMessage)
        items = emptyList()
        message = null
        updateVisibility()
        invalidate()
    }

    private fun updateVisibility() {
        visibility = if (items.isEmpty() && message == null) GONE else VISIBLE
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

        // Реальный размер экрана в пикселях (такой же, как у снимка экрана)
        val screen = windowManager.maximumWindowMetrics.bounds
        getLocationOnScreen(location)
        val scaleX = screen.width().toFloat() / sourceW
        val scaleY = screen.height().toFloat() / sourceH

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

            boxRect.set(left, top, left + boxWidth, top + boxHeight)
            canvas.drawRoundRect(boxRect, radius, radius, backgroundPaint)
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
            boxRect.set(left, top, left + bannerWidth, top + bannerHeight)
            canvas.drawRoundRect(boxRect, radius, radius, backgroundPaint)
            canvas.save()
            canvas.translate(left + pad, top + pad)
            layout.draw(canvas)
            canvas.restore()
        }
    }
}
