package com.hassasin.music

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File

/**
 * Anonymous reports, only when the user allows it: once per new version after a minute of playback,
 * when playback fails, and after a crash (sent on the next start). They hold the phone model, Android
 * version, the output chain (formats and rates), what offload/direct output the phone supports and
 * error details: no song names, file paths or accounts. They go to the developer's Google Sheet.
 */
object Reports {
    /** The Google Sheet's Apps Script web app (docs/report-sheet.gs). Empty: reports are shared by hand. */
    const val URL = "https://script.google.com/macros/s/AKfycbwk_YD-fMqvsmF-TEMPzFBDd8OscqoqVddVqDlL3NCXPmtA9M-IJo_F8WjG2ebPxYki/exec"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastErrorAt = 0L
    private var playedSec = 0

    fun canSend() = URL.isNotBlank()
    fun allowed() = AppState.settings.value.helpImprove == 1

    /** Keeps a crash's details so the next start can report it. */
    fun installCrashHandler(context: Context) {
        val app = context.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev is CrashKeeper) return
        Thread.setDefaultUncaughtExceptionHandler(CrashKeeper(app, prev))
    }

    private class CrashKeeper(val app: Context, val prev: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching { File(app.filesDir, "crash.txt").writeText("Thread ${t.name}\n" + Log.getStackTraceString(e).take(6000)) }
            prev?.uncaughtException(t, e)
        }
    }

    /** At app start: a crash from last time is reported (if allowed), then forgotten. */
    fun onStart(context: Context) {
        val f = File(context.filesDir, "crash.txt")
        if (!f.isFile) return
        val text = runCatching { f.readText() }.getOrNull()
        f.delete()
        if (text != null && allowed() && canSend()) scope.launch { send(context, "crash", text) }
    }

    /** Called every second of playback by the player loop: one report per version after a minute. */
    fun onPlaybackSecond(context: Context) {
        if (!allowed() || !canSend()) return
        if (++playedSec != 60) return
        val prefs = context.getSharedPreferences("reports", Context.MODE_PRIVATE)
        if (prefs.getString("auto_sent", null) == BuildConfig.VERSION_NAME) return
        prefs.edit().putString("auto_sent", BuildConfig.VERSION_NAME).apply()
        scope.launch { send(context, "auto", null) }
    }

    /** Playback failed: report it (at most every 10 minutes). */
    fun onPlayerError(context: Context, error: Throwable) {
        if (!allowed() || !canSend()) return
        val now = System.currentTimeMillis()
        if (now - lastErrorAt < 10 * 60_000L) return
        lastErrorAt = now
        val detail = Log.getStackTraceString(error).take(4000)
        scope.launch { send(context, "error", detail) }
    }

    fun payload(context: Context, kind: String, error: String?): JSONObject = JSONObject().apply {
        put("app", "HassasinMusic")
        put("kind", kind)
        put("version", BuildConfig.VERSION_NAME)
        put("phone", "${Build.MANUFACTURER} ${Build.MODEL}")
        put("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        put("output", AppState.settings.value.output)
        put("error", error ?: "")
        put("report", OutputChain.report(context))
    }

    /** Sends one report; false when it could not be sent. */
    suspend fun send(context: Context, kind: String, error: String?): Boolean = withContext(Dispatchers.IO) {
        if (!canSend()) return@withContext false
        runCatching {
            val body = payload(context, kind, error).toString().toRequestBody("text/plain; charset=utf-8".toMediaType())
            Net.client.newCall(Request.Builder().url(URL).post(body).header("User-Agent", Net.USER_AGENT).build()).execute().use { it.isSuccessful || it.code in 300..399 }
        }.onFailure { Log.w("Reports", "not sent", it) }.getOrDefault(false)
    }
}

/** Settings → About: allow anonymous reports, and send one now. */
@Composable
fun HelpImproveRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by AppState.settings.collectAsState()
    var status by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().raised().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Help improve", style = body(14.sp, FontWeight.Bold))
                Text("Send anonymous reports so problems on your phone can be fixed without asking you", style = body(12.sp, color = C.TextMid))
            }
            Toggle(s.helpImprove == 1, "Help improve") { on -> AppState.update { it.copy(helpImprove = if (on) 1 else 2) } }
        }
        Text("Sent: phone model, Android version, the output chain (formats and rates), what your phone supports, and error details. Never song names, file paths or accounts.", style = body(11.sp, color = C.TextLow))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Send a report now", false) {
                if (Reports.canSend()) { status = "Sending…"; scope.launch { status = if (Reports.send(context, "manual", null)) "Sent. Thank you!" else "Not sent: no connection?" } }
                else OutputChain.share(context, OutputChain.report(context))
            }
        }
        if (status.isNotBlank()) Text(status, style = body(12.sp, color = C.TextMid))
    }
}
