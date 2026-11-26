package com.hassasin.music

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class Credit(val name: String, val what: String)

private val CREDITS = listOf(
    "SOUND" to listOf(
        Credit("USB Direct engine", "From Hassasin's Stream: bit-perfect output straight to a USB DAC"),
        Credit("Audio EQ Cookbook", "Robert Bristow-Johnson's filter formulas, used by the EQ, tone and effects"),
        Credit("Freeverb", "Jezar at Dreampoint's public-domain reverb design"),
        Credit("AutoEq", "Jaakko Pasanen's headphone and earphone corrections (MIT licence)"),
    ),
    "MUSIC DATA" to listOf(
        Credit("LRCLIB", "Synced and plain lyrics (lrclib.net)"),
        Credit("MusicBrainz", "Release and recording data for finding covers (CC0)"),
        Credit("Cover Art Archive", "Album covers linked to MusicBrainz releases"),
        Credit("Apple iTunes Search", "Covers for releases MusicBrainz lacks, mainly Indian music"),
    ),
    "BUILT WITH" to listOf(
        Credit("AndroidX Media3 / ExoPlayer", "Playback, media session, lock-screen and notification controls (Apache 2.0)"),
        Credit("Jellyfin FFmpeg decoder", "ALAC, APE, DSD and other formats the phone can't decode (GPL-3.0, FFmpeg)"),
        Credit("Jetpack Compose", "Every screen (Apache 2.0)"),
        Credit("Kotlin, coroutines, serialization", "JetBrains (Apache 2.0)"),
        Credit("OkHttp", "Square: online lookups (Apache 2.0)"),
    ),
    "TYPE" to listOf(
        Credit("Unbounded", "Titles (SIL Open Font Licence)"),
        Credit("Manrope", "Text (SIL Open Font Licence)"),
        Credit("JetBrains Mono", "Numbers and quality details (SIL Open Font Licence)"),
    ),
    "IDEAS FROM" to listOf(
        Credit("Poweramp", "Equalizer, earphone profiles and library ideas"),
        Credit("ViPER4Android", "The families of effects in the Effects tab"),
        Credit("HiBy MSEB", "The idea of the Tuning sliders"),
        Credit("Flowie and NYX", "The feel of the Flow look and the visualizer"),
    ),
)

@Composable
fun CreditsScreen(nav: Nav) {
    Backdrop(Color(0xFF2A1F5C)) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Header("Credits", { nav.back() }) }
            item {
                Column(Modifier.fillMaxWidth().raised().padding(vertical = 24.dp, horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(96.dp).shadow(28.dp, RoundedCornerShape(26.dp), ambientColor = C.Amber, spotColor = C.Amber).clip(RoundedCornerShape(26.dp))) {
                        Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
                        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("Hassasin Music", style = display(24.sp))
                    Text("Version ${BuildConfig.VERSION_NAME}", style = mono(12.sp, color = C.TextMid))
                    Spacer(Modifier.height(10.dp))
                    Text("Made by Hassasin", style = body(15.sp, FontWeight.Bold, C.AmberText))
                    Text("github.com/Dataman252", style = mono(11.sp, color = C.TextMid))
                }
            }
            CREDITS.forEach { (section, list) ->
                item { Text(section, style = eyebrow(), modifier = Modifier.padding(start = 4.dp, top = 6.dp)) }
                item {
                    Column(Modifier.fillMaxWidth().raised().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        list.forEach { c ->
                            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                                Box(Modifier.padding(top = 6.dp).size(6.dp).background(C.Amber, RoundedCornerShape(3.dp)))
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(c.name, style = body(14.sp, FontWeight.Bold))
                                    Text(c.what, style = body(12.sp, color = C.TextMid).copy(lineHeight = 17.sp))
                                }
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "Ideas only: all code and design in this app are its own. Product names belong to their owners.",
                    style = body(11.sp, color = C.TextLow), modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}
