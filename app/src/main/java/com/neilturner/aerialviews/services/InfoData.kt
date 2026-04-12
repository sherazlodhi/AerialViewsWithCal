package com.neilturner.aerialviews.services

data class NewsItem(
    val title: String,
    val source: String,
    val time: String,
    val category: String, // World, Pakistan, UAE
)

data class StockItem(
    val symbol: String,
    val price: String,
    val change: String,
    val isPositive: Boolean,
)

data class InfoDataEvent(
    val news: List<NewsItem>,
    val stocks: List<StockItem>,
)
