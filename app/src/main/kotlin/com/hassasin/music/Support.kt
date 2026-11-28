package com.hassasin.music

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** The developer's UPI payment link (the same one inside the QR code). Empty hides the pop-up. */
const val DONATE_URL = "upi://pay?pa=7016587945-2@axl&pn=HASSANKHAN%20PATHAN&cu=INR&tn=Coffee%20for%20Hassasin%20Music"

/** Where anyone can download the newest version. */
const val DOWNLOAD_URL = "https://github.com/Dataman252/HassasinMusic/releases/latest"

/** Opens Android's share sheet with the download link, so friends can get the app. */
object ShareApp {
    fun share(context: Context) {
        val text = "Hassasin Music: a free hi-res music player for Android, with bit-perfect USB DAC output, synced lyrics and a full EQ.\n\nDownload: $DOWNLOAD_URL"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Hassasin Music")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, "Share Hassasin Music").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * The app is free; once per new version it asks, gently, for a coffee. "Not now" asks again next
 * time the app opens; "Don't ask again" stays quiet until the next version.
 */
object Donate {
    private const val PREFS = "donate"
    var showing by mutableStateOf(false)

    fun shouldAsk(context: Context): Boolean {
        if (DONATE_URL.isBlank()) return false
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("quiet_for", null) != Updater.installedVersion(context)
    }

    fun quietForThisVersion(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("quiet_for", Updater.installedVersion(context)).apply()
    }

    /** Opens a UPI app (PhonePe, Google Pay, Paytm, BHIM…) with the payment filled in. */
    fun open(context: Context): Boolean = runCatching {
        val pay = Intent(Intent.ACTION_VIEW, Uri.parse(DONATE_URL))
        context.startActivity(Intent.createChooser(pay, "Pay with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}

/** The coffee card, a few seconds after the app opens (once per version unless "Not now"). */
@Composable
fun DonatePrompt() {
    val context = LocalContext.current
    var asked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!asked && Donate.shouldAsk(context)) {
            kotlinx.coroutines.delay(8_000)
            // Never on top of the What's new card: wait until it is closed.
            while (Changelog.showing || Tour.active) kotlinx.coroutines.delay(1_000)
            kotlinx.coroutines.delay(3_000)
            if (Tour.busyThisSession) return@LaunchedEffect
            asked = true
            Donate.showing = true
        }
    }
    AnimatedVisibility(Donate.showing, enter = fadeIn() + scaleIn(initialScale = 0.94f), exit = fadeOut()) {
        DonateCard { Donate.showing = false }
    }
}

@Composable
fun DonateCard(onClose: () -> Unit) {
    val context = LocalContext.current
    var noApp by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(onBack = onClose)
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() },
        contentAlignment = Alignment.Center,
    ) {
        val qrHeight = (maxHeight * 0.40f).coerceAtMost(340.dp)
        val shape = RoundedCornerShape(28.dp)
        Column(
            Modifier.padding(18.dp).widthIn(max = 440.dp)
                .shadow(40.dp, shape, ambientColor = C.Amber, spotColor = C.Amber)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(Color(0xFF1E1A33), Color(0xFF0E0D16))))
                .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.04f))), shape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(
                painterResource(R.drawable.upi_qr), "UPI code to pay the developer",
                Modifier.size(width = qrHeight * 0.525f, height = qrHeight).clip(RoundedCornerShape(16.dp)),
            )
            Text("☕  Enjoying Hassasin Music?", style = display(20.sp), textAlign = TextAlign.Center)
            Text(
                "It's free, with no ads and no accounts, and it's built in spare time. If it makes your music sound better, a coffee keeps new versions coming.",
                style = body(13.sp, color = C.TextMid).copy(lineHeight = 19.sp), textAlign = TextAlign.Center,
            )
            Text(if (noApp) "No UPI app on this phone: scan the code from another phone." else "Scan the code, or pay from a UPI app on this phone.", style = body(12.sp, color = C.TextLow), textAlign = TextAlign.Center)
            Box(
                Modifier.fillMaxWidth().height(50.dp).amberOrb(RoundedCornerShape(25.dp)).clip(RoundedCornerShape(25.dp))
                    .clickable { if (Donate.open(context)) onClose() else noApp = true }.semantics { contentDescription = "Pay with a UPI app" },
                contentAlignment = Alignment.Center,
            ) { Text("Pay with UPI app", style = body(15.sp, FontWeight.Bold, C.OnAccent)) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip("Not now", false) { onClose() }
                Chip("Don't ask again", false) { Donate.quietForThisVersion(context); onClose() }
            }
        }
    }
}

/** A banner when a newer version is on GitHub: Update downloads it and Android asks to install. */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = Updater.state
    val info = when (state) {
        is UpdateState.Available -> state.info
        is UpdateState.Downloading -> state.info
        is UpdateState.Installing -> state.info
        is UpdateState.Failed -> state.info
        else -> null
    } ?: return
    if (state is UpdateState.Available && Updater.dismissedVersion == info.version) return
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier.fillMaxWidth().clip(shape)
            .background(Brush.linearGradient(listOf(C.Amber.copy(alpha = 0.22f), Color.Black.copy(alpha = 0.35f))))
            .border(1.dp, C.Amber.copy(alpha = 0.45f), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Hassasin Music ${info.version} is out", style = body(15.sp, FontWeight.Bold))
        Text(
            when (state) {
                is UpdateState.Downloading -> "Downloading… ${(state.fraction * 100).toInt()}%"
                is UpdateState.Installing -> "Android will ask to install it."
                is UpdateState.Failed -> state.message
                else -> "You have ${Updater.installedVersion(context)}. Your library, settings, covers and lyrics stay."
            },
            style = body(12.sp, color = C.TextMid),
        )
        if (state is UpdateState.Downloading) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.12f))) {
                Box(Modifier.fillMaxWidth(state.fraction).height(4.dp).background(C.Amber))
            }
        } else if (state !is UpdateState.Installing) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.height(40.dp).amberOrb(RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp))
                        .clickable { scope.launch { Updater.downloadAndInstall(context, info) } }.padding(horizontal = 22.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Update", style = body(14.sp, FontWeight.Bold, C.OnAccent)) }
                Chip("Later", false) { Updater.dismiss(context, info.version) }
                Spacer(Modifier.width(1.dp))
            }
        }
    }
}

/** Settings row: current version, check now, and the update banner when one is found. */
@Composable
fun UpdateRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = Updater.state
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingRow(
            "Updates",
            when (state) {
                UpdateState.Checking -> "Checking GitHub…"
                UpdateState.UpToDate -> "Up to date (${Updater.installedVersion(context)})"
                is UpdateState.Available -> "Version ${state.info.version} is ready"
                is UpdateState.Failed -> state.message
                else -> "Version ${Updater.installedVersion(context)} · checks by itself when the app opens"
            },
        ) { RoundButton(I.Reset, "Check for updates", { scope.launch { Updater.check(context, force = true) } }, 40.dp) }
        if (state is UpdateState.Available && Updater.dismissedVersion == state.info.version) {
            Chip("Show the update", false) { Updater.dismiss(context, "") }
        }
        UpdateBanner()
    }
}
