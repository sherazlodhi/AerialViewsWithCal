package com.neilturner.aerialviews.services

import android.content.Context
import com.neilturner.aerialviews.models.prefs.GeneralPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.kosert.flowbus.GlobalBus
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.regex.Pattern

class NewsService(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var updateJob: Job? = null
    private val client = OkHttpClient()

    fun startUpdates() {
        updateJob?.cancel()
        updateJob = scope.launch {
            while (true) {
                fetchAndPostInfo()
                delay(UPDATE_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        updateJob?.cancel()
    }

    fun refreshNow() {
        scope.launch { fetchAndPostInfo() }
    }

    private suspend fun fetchAndPostInfo() {
        val newsItems = mutableListOf<NewsItem>()
        
        if (GeneralPrefs.newsIncludeWorld) {
            newsItems.addAll(fetchRss("https://feeds.bbci.co.uk/news/world/rss.xml", "World", "BBC"))
        }
        if (GeneralPrefs.newsIncludePakistan) {
            newsItems.addAll(fetchRss("https://tribune.com.pk/feed/pakistan", "Pakistan", "Express Tribune"))
        }
        if (GeneralPrefs.newsIncludeUAE) {
            newsItems.addAll(fetchRss("https://www.thenationalnews.com/rss/uae/", "UAE", "The National"))
        }

        val stocks = if (GeneralPrefs.newsIncludeStocks) fetchStocks() else emptyList()

        GlobalBus.post(InfoDataEvent(newsItems.take(20), stocks))
    }

    private fun fetchRss(url: String, category: String, sourceName: String): List<NewsItem> {
        val items = mutableListOf<NewsItem>()
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body.string()
                
                // Better regex for items and titles
                val itemPattern = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL)
                val itemMatcher = itemPattern.matcher(body)
                var count = 0
                while (itemMatcher.find() && count < 5) {
                    val itemContent = itemMatcher.group(1) ?: continue
                    val titlePattern = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.DOTALL)
                    val titleMatcher = titlePattern.matcher(itemContent)
                    if (titleMatcher.find()) {
                        var title = titleMatcher.group(1) ?: ""
                        title = title.replace("<!\\[CDATA\\[".toRegex(), "")
                            .replace("\\]\\]>".toRegex(), "")
                            .replace("&amp;", "&")
                            .replace("&quot;", "\"")
                            .replace("&apos;", "'")
                            .replace("<[^>]*>".toRegex(), "") // Strip any HTML tags
                            .trim()
                        
                        if (title.isNotEmpty()) {
                            items.add(NewsItem(title, sourceName, "", category))
                            count++
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "NewsService: Error fetching RSS from $url")
        }
        return items
    }

    private fun fetchStocks(): List<StockItem> {
        val liveStocks = mutableListOf<StockItem>()
        
        // Try to get live data for major indexes
        fetchLiveIndex("^GSPC", "S&P 500")?.let { liveStocks.add(it) }
        fetchLiveIndex("^IXIC", "NASDAQ")?.let { liveStocks.add(it) }
        fetchLiveIndex("GC=F", "Gold")?.let { liveStocks.add(it) }
        
        // If we have live data, return it. Otherwise, return empty to avoid stale data.
        return liveStocks
    }

    private fun fetchLiveIndex(symbol: String, name: String): StockItem? {
        try {
            val url = "https://query1.finance.yahoo.com/v8/finance/chart/$symbol"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body.string()
                
                // Extremely simple JSON parsing for just the price and change
                val pricePattern = Pattern.compile("\"regularMarketPrice\":([\\d.]+)")
                val prevClosePattern = Pattern.compile("\"previousClose\":([\\d.]+)")
                
                val pm = pricePattern.matcher(body)
                val pcm = prevClosePattern.matcher(body)
                
                if (pm.find() && pcm.find()) {
                    val price = pm.group(1).toFloat()
                    val prevClose = pcm.group(1).toFloat()
                    val change = price - prevClose
                    val changePercent = (change / prevClose) * 100
                    
                    val sign = if (change >= 0) "+" else ""
                    val formattedPrice = String.format("%,.2f", price)
                    val formattedChange = String.format("%s%,.2f (%s%.2f%%)", sign, change, sign, changePercent)
                    
                    return StockItem(name, "$$formattedPrice", formattedChange, change >= 0)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "NewsService: Error fetching live stock $symbol")
        }
        return null
    }

    companion object {
        private const val UPDATE_INTERVAL_MS = 30 * 60 * 1000L // 30 minutes
    }
}
