package com.hassasin.music

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** One HTTP client for the online look-ups (lyrics, album art, headphone profiles). */
object Net {
    val USER_AGENT = "HassasinMusic/${BuildConfig.VERSION_NAME} ( https://github.com/Dataman252 )"
    private const val TAG = "Net"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** False when the user allows online look-ups on Wi-Fi only and the phone is on mobile data. */
    fun allowed(context: Context, wifiOnly: Boolean): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return !wifiOnly || !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    /**
     * GET returning (status, body). Retries "busy" answers (HTTP 503) a few times with growing waits.
     * Always runs off the main thread (Android refuses network calls there).
     */
    suspend fun get(url: String, retries: Int = 3): Pair<Int, String?> = withContext(Dispatchers.IO) { getNow(url, retries) }

    private suspend fun getNow(url: String, retries: Int): Pair<Int, String?> {
        var wait = 1500L
        repeat(retries + 1) { attempt ->
            val result = runCatching {
                client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept", "application/json").build())
                    .execute().use { r -> r.code to r.body?.string() }
            }.getOrElse { e -> Log.w(TAG, "GET failed: $url", e); -1 to null }
            val busy = result.first == 503 || (result.second?.contains("currently busy") == true)
            if (!busy && result.first != -1) return result
            if (attempt < retries) { delay(wait); wait *= 2 }
            else return result
        }
        return -1 to null
    }

    suspend fun bytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { r ->
                if (r.isSuccessful) r.body?.bytes() else { Log.w(TAG, "GET ${r.code}: $url"); null }
            }
        }.onFailure { Log.w(TAG, "GET failed: $url", it) }.getOrNull()
    }

    private val itLock = Mutex()
    private var itLast = 0L

    /** The iTunes catalogue allows about 20 searches a minute. */
    suspend fun itunes(url: String): Pair<Int, String?> = itLock.withLock {
        val wait = 3100 - (System.currentTimeMillis() - itLast)
        if (wait > 0) delay(wait)
        try { get(url, retries = 1) } finally { itLast = System.currentTimeMillis() }
    }

    private val mbLock = Mutex()
    private var mbLast = 0L

    /** MusicBrainz allows one request a second per app. */
    suspend fun musicBrainz(url: String): Pair<Int, String?> = mbLock.withLock {
        val wait = 1100 - (System.currentTimeMillis() - mbLast)
        if (wait > 0) delay(wait)
        try { get(url) } finally { mbLast = System.currentTimeMillis() }
    }
}
