package com.hassasin.music

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews

/** Home-screen widget: cover, song, quality, and previous / play-pause / next. */
class PlayerWidget : AppWidgetProvider() {
    companion object {
        private const val ACT = "com.hassasin.music.WIDGET"
        private var lastKey = ""

        private fun pending(context: Context, what: String): PendingIntent =
            PendingIntent.getBroadcast(context, what.hashCode(), Intent(context, PlayerWidget::class.java).setAction(ACT).putExtra("what", what), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        /** Redraws every widget (called when the song, the play state or the cover changes). */
        fun update(context: Context, art: Bitmap? = null, force: Boolean = false) {
            val st = Playback.state.value
            val t = st.current
            val key = "${t?.id}-${st.playing}-${art?.generationId}"
            if (!force && key == lastKey) return
            lastKey = key
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, PlayerWidget::class.java))
            if (ids.isEmpty()) return
            val v = RemoteViews(context.packageName, R.layout.widget_player)
            v.setTextViewText(R.id.w_title, t?.let { ArtRepo.cleanSongTitle(it.title) } ?: "Hassasin Music")
            v.setTextViewText(R.id.w_artist, t?.artist?.ifBlank { t.album } ?: "Tap to open")
            v.setTextViewText(R.id.w_quality, t?.formatLabel ?: "")
            if (art != null) v.setImageViewBitmap(R.id.w_cover, art) else v.setImageViewResource(R.id.w_cover, R.drawable.ic_launcher_foreground)
            v.setImageViewResource(R.id.w_play, if (st.playing) R.drawable.w_pause else R.drawable.w_play)
            v.setOnClickPendingIntent(R.id.w_prev, pending(context, "prev"))
            v.setOnClickPendingIntent(R.id.w_play, pending(context, "toggle"))
            v.setOnClickPendingIntent(R.id.w_next, pending(context, "next"))
            v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(context, 7, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            mgr.updateAppWidget(ids, v)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context, force = true)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACT) return
        if (!Playback.isReady()) {
            // The app is not running: open it (playback starts from where it was).
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        when (intent.getStringExtra("what")) {
            "prev" -> Playback.previous()
            "next" -> Playback.next()
            else -> Playback.toggle()
        }
    }
}
