package com.neilturner.aerialviews.ui.overlays

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import com.neilturner.aerialviews.services.NewsItem
import com.neilturner.aerialviews.services.StockItem
import com.neilturner.aerialviews.ui.overlays.state.NewsOverlayState

class NewsOverlay
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : View(context, attrs, defStyleAttr) {

        private var news: List<NewsItem> = emptyList()
        private var stocks: List<StockItem> = emptyList()

        private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val categoryPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stockSymbolPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stockPricePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stockChangePaint = Paint(Paint.ANTI_ALIAS_FLAG)

        private val colorBg = Color.argb(160, 0, 0, 0)
        private val colorCard = Color.argb(100, 40, 50, 70)
        private val colorAccent = Color.argb(255, 74, 144, 226)
        private val colorWhite = Color.WHITE
        private val colorWhite70 = Color.argb(178, 255, 255, 255)
        private val colorPositive = Color.argb(255, 76, 175, 80)
        private val colorNegative = Color.argb(255, 244, 67, 54)

        init {
            titlePaint.color = colorAccent
            titlePaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            
            categoryPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            
            textPaint.color = colorWhite
            textPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }

        fun render(state: NewsOverlayState) {
            this.news = state.news
            this.stocks = state.stocks
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width == 0 || height == 0) return

            val w = width.toFloat()
            val h = height.toFloat()
            val padding = w * 0.03f

            // Full screen background dim
            canvas.drawColor(colorBg)

            // Header
            titlePaint.textSize = h * 0.05f
            canvas.drawText("LATEST NEWS & STOCK MARKETS", padding, padding + titlePaint.textSize, titlePaint)

            // LayoutNews
            val newsWidth = w * 0.65f
            val stocksWidth = w * 0.28f
            
            drawNewsSection(canvas, padding, padding + h * 0.1f, newsWidth, h - padding * 2 - h * 0.1f)
            drawStocksSection(canvas, w - stocksWidth - padding, padding + h * 0.1f, stocksWidth, h - padding * 2 - h * 0.1f)
        }

        private fun drawNewsSection(canvas: Canvas, left: Float, top: Float, width: Float, height: Float) {
            if (news.isEmpty()) {
                textPaint.textSize = height * 0.05f
                canvas.drawText("Fetching news...", left, top + height / 2, textPaint)
                return
            }

            val categories = news.groupBy { it.category }
            var currentTop = top
            val sectionSpacing = height * 0.04f
            
            val headlinePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colorWhite
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }

            categories.forEach { (category, items) ->
                categoryPaint.textSize = height * 0.032f
                categoryPaint.color = colorAccent
                canvas.drawText(category.uppercase(), left, currentTop + categoryPaint.textSize, categoryPaint)
                currentTop += categoryPaint.textSize + 8f

                val displayItems = items.take(3)
                val maxW = (width - 40f).toInt()
                
                displayItems.forEach { item ->
                    headlinePaint.textSize = height * 0.040f
                    
                    val staticLayout = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        StaticLayout.Builder.obtain("• ${item.title}", 0, item.title.length + 2, headlinePaint, maxW)
                            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                            .setLineSpacing(0f, 1f)
                            .setIncludePad(false)
                            .setMaxLines(2)
                            .setEllipsize(android.text.TextUtils.TruncateAt.END)
                            .build()
                    } else {
                        @Suppress("DEPRECATION")
                        StaticLayout("• ${item.title}", headlinePaint, maxW, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false)
                    }

                    canvas.save()
                    canvas.translate(left, currentTop)
                    staticLayout.draw(canvas)
                    canvas.restore()
                    
                    currentTop += staticLayout.height + 15f
                }
                currentTop += sectionSpacing
            }
        }

        private fun drawStocksSection(canvas: Canvas, left: Float, top: Float, width: Float, height: Float) {
            val cardRect = RectF(left, top, left + width, top + height)
            cardBgPaint.color = colorCard
            canvas.drawRoundRect(cardRect, 20f, 20f, cardBgPaint)

            categoryPaint.textSize = height * 0.06f
            categoryPaint.color = colorWhite
            canvas.drawText("MARKET WATCH", left + 20f, top + height * 0.1f, categoryPaint)

            if (stocks.isEmpty()) {
                textPaint.textSize = height * 0.05f
                canvas.drawText("Updating stocks...", left + 20f, top + height * 0.3f, textPaint)
                return
            }

            val rowHeight = height * 0.10f
            stocks.forEachIndexed { i, stock ->
                val rowTop = top + height * 0.15f + i * rowHeight
                
                stockSymbolPaint.textSize = height * 0.05f
                stockSymbolPaint.color = colorWhite
                stockSymbolPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                canvas.drawText(stock.symbol, left + 20f, rowTop + stockSymbolPaint.textSize, stockSymbolPaint)

                stockPricePaint.textSize = height * 0.04f
                stockPricePaint.color = colorWhite70
                canvas.drawText(stock.price, left + 20f, rowTop + stockSymbolPaint.textSize + stockPricePaint.textSize + 2f, stockPricePaint)

                stockChangePaint.textSize = height * 0.05f
                stockChangePaint.color = if (stock.isPositive) colorPositive else colorNegative
                val changeText = stock.change
                val changeWidth = stockChangePaint.measureText(changeText)
                canvas.drawText(changeText, left + width - changeWidth - 20f, rowTop + stockChangePaint.textSize * 1.1f, stockChangePaint)
            }
        }

        private fun truncateText(text: String, paint: Paint, maxWidth: Float): String {
            if (paint.measureText(text) <= maxWidth) return text
            val ellipsis = "..."
            var truncated = text
            while (truncated.isNotEmpty() && paint.measureText(truncated + ellipsis) > maxWidth) {
                truncated = truncated.dropLast(1)
            }
            return truncated + ellipsis
        }
    }
