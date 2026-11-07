package com.cleo.cleos.data

import okhttp3.HttpUrl.Companion.toHttpUrl

/** Build a browser destination only. No fetching, model calls or access to TA/chat credentials. */
object WorldWebSearch {
    fun bing(raw: String): String = "https://cn.bing.com/search".toHttpUrl().newBuilder()
        .addQueryParameter("q", WorldTopicSearch.query(raw)).build().toString()
}
