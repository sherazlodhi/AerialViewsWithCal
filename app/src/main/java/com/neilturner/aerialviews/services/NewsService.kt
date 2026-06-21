package com.neilturner.aerialviews.services

import android.content.Context
import com.neilturner.aerialviews.models.prefs.GeneralPrefs
import com.neilturner.aerialviews.utils.JsonHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.kosert.flowbus.GlobalBus
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
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
        Timber.i("NewsService: Start fetching info...")
        val newsItems = mutableListOf<NewsItem>()
        
        if (GeneralPrefs.newsIncludeWorld) {
            val rss = fetchRss("https://feeds.bbci.co.uk/news/world/rss.xml", "World", "BBC", limit = 5)
            Timber.i("NewsService: Fetched ${rss.size} BBC items")
            newsItems.addAll(rss)
        }
        if (GeneralPrefs.newsIncludePakistan) {
            val rss = fetchRss("https://tribune.com.pk/feed/pakistan", "Pakistan", "Express Tribune", limit = 4)
            Timber.i("NewsService: Fetched ${rss.size} Pakistan items")
            newsItems.addAll(rss)
        }
        if (GeneralPrefs.newsIncludeUAE) {
            val rss = fetchRss("https://www.gulftoday.ae/rssFeed/0/", "UAE", "Gulf Today", limit = 3)
            Timber.i("NewsService: Fetched ${rss.size} UAE items")
            newsItems.addAll(rss)
        }
        if (GeneralPrefs.newsIncludeHome) {
            val ntfy = fetchNtfy()
            Timber.i("NewsService: Fetched ${ntfy.size} ntfy items")
            newsItems.addAll(ntfy)
        }

        val stocks = if (GeneralPrefs.newsIncludeStocks) {
            val st = fetchStocks()
            Timber.i("NewsService: Fetched ${st.size} stocks")
            st
        } else emptyList()

        val homeStatus = if (GeneralPrefs.newsIncludeHomeAssistant) {
            val ha = fetchHomeAssistant()
            Timber.i("NewsService: Fetched ${ha.size} Home Assistant status items")
            ha
        } else emptyList()

        Timber.i("NewsService: Posting ${newsItems.size} news items, ${stocks.size} stocks, and ${homeStatus.size} Home Assistant items")
        GlobalBus.post(InfoDataEvent(newsItems, stocks, homeStatus))
    }

    private fun fetchRss(url: String, category: String, sourceName: String, limit: Int = 5): List<NewsItem> {
        val items = mutableListOf<NewsItem>()
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .header("Cache-Control", "no-cache")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body.string()
                
                // Better regex for items and titles
                val itemPattern = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL)
                val itemMatcher = itemPattern.matcher(body)
                var count = 0
                while (itemMatcher.find() && count < limit) {
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
                            .replace("&#039;", "'")
                            .replace("&#39;", "'")
                            .replace("&rsquo;", "'")
                            .replace("&lsquo;", "'")
                            .replace("&ldquo;", "\"")
                            .replace("&rdquo;", "\"")
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
        fetchLiveIndex("DFMGI.AE", "DFM")?.let { liveStocks.add(it) }
        fetchLiveIndex("AEDPKR=X", "AED/PKR")?.let { liveStocks.add(it) }
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

    private fun fetchNtfy(): List<NewsItem> {
        val items = mutableListOf<NewsItem>()
        val baseUrl = GeneralPrefs.newsNtfyUrl.trim().removeSuffix("/")
        val topic = GeneralPrefs.newsNtfyTopic.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(",")
        if (baseUrl.isEmpty() || topic.isEmpty()) return emptyList()

        try {
            val url = "$baseUrl/$topic/json?poll=1"
            val request = Request.Builder()
                .url(url)
                .header("Cache-Control", "no-cache")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("NewsService: Failed to fetch ntfy, response code: ${response.code}")
                    return emptyList()
                }
                val body = response.body.string()
                
                val todayStart = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis

                body.lineSequence().forEach { line ->
                    if (line.trim().isEmpty()) return@forEach
                    try {
                        val ntfyMsg = JsonHelper.json.decodeFromString<NtfyMessage>(line)
                        if (ntfyMsg.event == "message") {
                            val msgTimeMs = ntfyMsg.time * 1000L
                            if (msgTimeMs >= todayStart) {
                                val messageText = ntfyMsg.message ?: ""
                                val titleText = ntfyMsg.title
                                
                                val displayTitle = if (!titleText.isNullOrBlank()) {
                                    "$titleText: $messageText"
                                } else {
                                    messageText
                                }
                                if (displayTitle.isNotEmpty()) {
                                    items.add(NewsItem(displayTitle, "ntfy", "", "Home"))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // ignore malformed lines
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "NewsService: Error fetching ntfy messages")
        }
        return items.reversed()
    }

    private fun fetchHomeAssistant(): List<HomeStatusItem> {
        val rawUrl = GeneralPrefs.homeAssistantUrl.trim().removeSuffix("/")
        val token = GeneralPrefs.homeAssistantToken.trim()
        if (rawUrl.isEmpty() || token.isEmpty()) return emptyList()

        val items = mutableListOf<HomeStatusItem>()
        try {
            val url = "$rawUrl/api/states"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .header("Cache-Control", "no-cache")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("NewsService: Failed to fetch Home Assistant states, response code: ${response.code}")
                    return emptyList()
                }
                val body = response.body.string()
                val states = JsonHelper.json.decodeFromString<List<HaState>>(body)

                // Fetch Hijri date from KT Hijri Date or standard hijri_date sensor
                val hijriEntity = states.firstOrNull { it.entity_id.lowercase() == "sensor.kt_hijri_date" }
                    ?: states.firstOrNull { it.entity_id.lowercase() == "sensor.hijri_date" }
                if (hijriEntity != null) {
                    val hijriState = hijriEntity.state
                    if (hijriState.isNotEmpty() && hijriState != "unknown" && hijriState != "unavailable") {
                        items.add(HomeStatusItem("📅 Hijri Date", hijriState, isAlert = false))
                    }
                }
                
                val nextPrayerEntity = states.firstOrNull { it.entity_id.lowercase().contains("next_prayer") }
                val nextPrayerName = nextPrayerEntity?.state?.lowercase()?.trim()

                val prayers = listOf(
                    Triple("fajr", "✨ Fajr", "✨ Fajr"),
                    Triple("dhuhr", "☀️ Dhuhr", "☀️ Dhuhr"),
                    Triple("asr", "⛅ Asr", "⛅ Asr"),
                    Triple("maghrib", "🌥️ Maghrib", "🌥️ Maghrib"),
                    Triple("isha", "🌙 Isha", "🌙 Isha")
                )

                // Local fallback next prayer calculation if HASS next_prayer sensor is unavailable/unknown
                var calculatedNextKey: String? = null
                if (nextPrayerName == null || nextPrayerName == "unavailable" || nextPrayerName == "unknown") {
                    val now = System.currentTimeMillis()
                    var minDiff = Long.MAX_VALUE
                    for ((key, _, _) in prayers) {
                        val entity = states.firstOrNull { 
                            val id = it.entity_id.lowercase()
                            id.startsWith("sensor.islamic_prayer_times_") && id.endsWith("_prayer") && id.contains(key)
                        }
                        if (entity != null) {
                            try {
                                val timeMs = parseIsoToMillis(entity.state)
                                if (timeMs > now) {
                                    val diff = timeMs - now
                                    if (diff < minDiff) {
                                        minDiff = diff
                                        calculatedNextKey = key
                                    }
                                }
                            } catch (e: Exception) {
                                // ignore
                            }
                        }
                    }
                    if (calculatedNextKey == null) {
                        calculatedNextKey = "fajr" // Next prayer tomorrow
                    }
                }

                for ((key, displayName, defaultName) in prayers) {
                    val entity = states.firstOrNull { 
                        val id = it.entity_id.lowercase()
                        id.startsWith("sensor.islamic_prayer_times_") && id.endsWith("_prayer") && id.contains(key)
                    }
                    if (entity != null) {
                        val displayValue = formatPrayerTime(entity.state)
                        val isNext = if (nextPrayerName != null && nextPrayerName != "unavailable" && nextPrayerName != "unknown") {
                            nextPrayerName.contains(key)
                        } else {
                            key == calculatedNextKey
                        }
                        items.add(HomeStatusItem(displayName, displayValue, isAlert = isNext))
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "NewsService: Error fetching Home Assistant states")
        }
        return items
    }

    companion object {
        private const val UPDATE_INTERVAL_MS = 30 * 60 * 1000L // 30 minutes

        fun formatPrayerTime(raw: String): String {
            try {
                if (raw.contains("T")) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        val formatter = java.time.format.DateTimeFormatter.ofPattern("h:mm a")
                        try {
                            val odt = java.time.OffsetDateTime.parse(raw)
                            val localTime = odt.atZoneSameInstant(java.time.ZoneId.systemDefault())
                            return localTime.format(formatter)
                        } catch (e: Exception) {
                            try {
                                val zdt = java.time.ZonedDateTime.parse(raw)
                                val localTime = zdt.withZoneSameInstant(java.time.ZoneId.systemDefault())
                                return localTime.format(formatter)
                            } catch (e2: Exception) {
                                val ldt = java.time.LocalDateTime.parse(raw)
                                return ldt.format(formatter)
                            }
                        }
                    } else {
                        // Timezone-aware fallback for older API levels
                        val parser = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                        val timezoneParser = if (raw.endsWith("Z") || raw.contains("+") || raw.contains("-")) {
                            val pattern = if (raw.contains(".")) "yyyy-MM-dd'T'HH:mm:ss.SSSXXX" else "yyyy-MM-dd'T'HH:mm:ssXXX"
                            java.text.SimpleDateFormat(pattern, java.util.Locale.US)
                        } else {
                            parser
                        }
                        val date = timezoneParser.parse(raw)
                        if (date != null) {
                            val formatter = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US)
                            formatter.timeZone = java.util.TimeZone.getDefault()
                            return formatter.format(date)
                        }
                    }
                }
            } catch (e: Exception) {
                // ignore and fallback
            }
            return raw
        }

        fun parseIsoToMillis(raw: String): Long {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                try {
                    return java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli()
                } catch (e: Exception) {
                    try {
                        return java.time.ZonedDateTime.parse(raw).toInstant().toEpochMilli()
                    } catch (e2: Exception) {
                        return java.time.LocalDateTime.parse(raw).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    }
                }
            } else {
                try {
                    val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
                    return format.parse(raw)?.time ?: 0L
                } catch (e: Exception) {
                    try {
                        val clean = raw.replace("Z", "+0000")
                        val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.US)
                        return format.parse(clean)?.time ?: 0L
                    } catch (e2: Exception) {
                        return 0L
                    }
                }
            }
        }
    }
}

@kotlinx.serialization.Serializable
data class NtfyMessage(
    val event: String,
    val time: Long,
    val message: String? = null,
    val title: String? = null,
    val topic: String? = null,
)

@kotlinx.serialization.Serializable
data class HaState(
    val entity_id: String,
    val state: String,
    val attributes: HaAttributes? = null
)

@kotlinx.serialization.Serializable
data class HaAttributes(
    val device_class: String? = null,
    val friendly_name: String? = null,
    val current_temperature: Double? = null,
    val temperature: Double? = null
)
