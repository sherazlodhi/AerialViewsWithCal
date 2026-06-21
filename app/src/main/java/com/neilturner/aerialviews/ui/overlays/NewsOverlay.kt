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
import com.neilturner.aerialviews.services.HomeStatusItem
import com.neilturner.aerialviews.models.prefs.GeneralPrefs
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
        private var homeStatus: List<HomeStatusItem> = emptyList()

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
        private val colorWarning = Color.argb(255, 255, 179, 0)

        init {
            titlePaint.color = colorAccent
            titlePaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            
            categoryPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            
            textPaint.color = colorWhite
            textPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }

        fun render(state: NewsOverlayState) {
            android.util.Log.i("NewsOverlay", "render: received ${state.news.size} news, ${state.stocks.size} stocks, and ${state.homeStatus.size} Home Assistant status items")
            this.news = state.news
            this.stocks = state.stocks
            this.homeStatus = state.homeStatus
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width == 0 || height == 0) return

            val w = width.toFloat()
            val h = height.toFloat()
            val padding = w * 0.025f

            // Full screen background dim
            canvas.drawColor(colorBg)

            // Header
            titlePaint.textSize = h * 0.03f
            canvas.drawText("LATEST NEWS & STOCK MARKETS", padding, padding + titlePaint.textSize, titlePaint)

            // LayoutNews
            val newsWidth = w * 0.71f
            val stocksWidth = w * 0.22f
            val panelTop = padding + h * 0.08f
            val panelHeight = h - padding * 2 - h * 0.08f
            val categoryTitleSize = panelHeight * 0.025f
            
            drawNewsSection(canvas, padding, panelTop, newsWidth, panelHeight, categoryTitleSize)
            drawStocksSection(canvas, w - stocksWidth - padding, panelTop, stocksWidth, panelHeight, categoryTitleSize)

            if (GeneralPrefs.newsIncludeHomeAssistant) {
                val stocksHeight = panelHeight * 0.55f
                val haTop = panelTop + stocksHeight + 20f
                val haHeight = panelHeight - stocksHeight - 20f
                drawHomeAssistantSection(canvas, w - stocksWidth - padding, haTop, stocksWidth, haHeight, categoryTitleSize)
            }
        }

        private fun drawNewsSection(canvas: Canvas, left: Float, top: Float, width: Float, height: Float, categoryTitleSize: Float) {
            if (news.isEmpty()) {
                textPaint.textSize = height * 0.05f
                canvas.drawText("Fetching news...", left, top + height / 2, textPaint)
                return
            }

            val categories = news.groupBy { it.category }
            var currentTop = top
            val sectionSpacing = height * 0.022f
            
            val headlinePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colorWhite
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }

            for ((category, items) in categories) {
                // Check if we have space for the category title and at least one line of text
                if (currentTop + categoryTitleSize + 4f + 30f > top + height) {
                    break // Stop drawing categories if we run out of vertical space
                }

                categoryPaint.textSize = categoryTitleSize
                categoryPaint.color = colorAccent
                canvas.drawText(category.uppercase(), left, currentTop + categoryPaint.textSize, categoryPaint)
                currentTop += categoryPaint.textSize + 4f

                val maxW = (width - 40f).toInt()
                
                for (item in items) {
                    headlinePaint.textSize = height * 0.032f
                    
                    val staticLayout = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        StaticLayout.Builder.obtain("• ${item.title}", 0, item.title.length + 2, headlinePaint, maxW)
                            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                            .setLineSpacing(0f, 1f)
                            .setIncludePad(false)
                            .setMaxLines(3)
                            .setEllipsize(android.text.TextUtils.TruncateAt.END)
                            .build()
                    } else {
                        @Suppress("DEPRECATION")
                        StaticLayout("• ${item.title}", headlinePaint, maxW, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false)
                    }

                    // Check if this item fits in the remaining space
                    if (currentTop + staticLayout.height + 8f > top + height) {
                        break // Stop drawing items for this category if out of space
                    }

                    canvas.save()
                    canvas.translate(left, currentTop)
                    staticLayout.draw(canvas)
                    canvas.restore()
                    
                    currentTop += staticLayout.height + 8f
                }
                currentTop += sectionSpacing
            }
        }

        private fun drawStocksSection(canvas: Canvas, left: Float, top: Float, width: Float, height: Float, categoryTitleSize: Float) {
            val hasHa = GeneralPrefs.newsIncludeHomeAssistant
            val widgetHeight = if (hasHa) height * 0.55f else height * 0.85f
            val cardRect = RectF(left, top, left + width, top + widgetHeight)
            cardBgPaint.color = colorCard
            canvas.drawRoundRect(cardRect, 20f, 20f, cardBgPaint)

            val sizeRef = height * 0.55f

            categoryPaint.textSize = categoryTitleSize
            categoryPaint.color = colorAccent
            canvas.drawText("MARKET WATCH", left + 20f, top + categoryTitleSize + 20f, categoryPaint)

            if (stocks.isEmpty()) {
                textPaint.textSize = sizeRef * 0.1f
                canvas.drawText("Updating stocks...", left + 20f, top + widgetHeight * 0.5f, textPaint)
                return
            }

            val rowHeight = widgetHeight * 0.145f
            stocks.forEachIndexed { i, stock ->
                val rowTop = top + widgetHeight * 0.22f + i * rowHeight
                
                stockSymbolPaint.textSize = sizeRef * 0.048f
                stockSymbolPaint.color = colorWhite
                stockSymbolPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                canvas.drawText(stock.symbol, left + 20f, rowTop + stockSymbolPaint.textSize, stockSymbolPaint)

                stockPricePaint.textSize = sizeRef * 0.04f
                stockPricePaint.color = colorWhite70
                canvas.drawText(stock.price, left + 20f, rowTop + stockSymbolPaint.textSize + stockPricePaint.textSize + 2f, stockPricePaint)

                stockChangePaint.textSize = sizeRef * 0.044f
                stockChangePaint.color = if (stock.isPositive) colorPositive else colorNegative
                val changeText = stock.change
                val changeWidth = stockChangePaint.measureText(changeText)
                canvas.drawText(changeText, left + width - changeWidth - 20f, rowTop + stockChangePaint.textSize * 1.1f, stockChangePaint)
            }
        }

        private fun drawHomeAssistantSection(canvas: Canvas, left: Float, top: Float, width: Float, height: Float, categoryTitleSize: Float) {
            val cardRect = RectF(left, top, left + width, top + height)
            cardBgPaint.color = colorCard
            canvas.drawRoundRect(cardRect, 20f, 20f, cardBgPaint)

            val sizeRef = height

            categoryPaint.textSize = categoryTitleSize
            categoryPaint.color = colorAccent
            canvas.drawText("HOME STATUS", left + 20f, top + categoryTitleSize + 20f, categoryPaint)

            if (homeStatus.isEmpty()) {
                textPaint.textSize = sizeRef * 0.09f
                canvas.drawText("No alerts / data", left + 20f, top + height * 0.55f, textPaint)
                return
            }

            val itemCount = homeStatus.size
            val rowHeight = when {
                itemCount <= 3 -> height * 0.22f
                itemCount <= 5 -> height * 0.14f
                else -> height * 0.12f
            }
            val startTop = when {
                itemCount <= 3 -> height * 0.32f
                itemCount <= 5 -> height * 0.24f
                else -> height * 0.21f
            }

            homeStatus.forEachIndexed { i, item ->
                val rowTop = top + startTop + i * rowHeight
                
                stockSymbolPaint.textSize = when {
                    itemCount <= 3 -> sizeRef * 0.065f
                    itemCount <= 5 -> sizeRef * 0.052f
                    else -> sizeRef * 0.046f
                }
                stockSymbolPaint.color = colorWhite
                stockSymbolPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                canvas.drawText(item.title, left + 20f, rowTop + stockSymbolPaint.textSize, stockSymbolPaint)

                stockChangePaint.textSize = when {
                    itemCount <= 3 -> sizeRef * 0.06f
                    itemCount <= 5 -> sizeRef * 0.048f
                    else -> sizeRef * 0.042f
                }
                stockChangePaint.color = if (item.isAlert) colorWarning else colorWhite70
                val valueText = item.value
                val maxValueWidth = width - (width * 0.45f)
                val truncatedValue = truncateText(valueText, stockChangePaint, maxValueWidth)
                val finalValueWidth = stockChangePaint.measureText(truncatedValue)
                
                canvas.drawText(truncatedValue, left + width - finalValueWidth - 20f, rowTop + stockChangePaint.textSize, stockChangePaint)
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
