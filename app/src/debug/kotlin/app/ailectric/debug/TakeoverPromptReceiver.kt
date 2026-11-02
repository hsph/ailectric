package app.ailectric.debug

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import app.ailectric.R
import java.io.File

/**
 * Asks on the phone whether a test may take over the screen, without touching what is on it.
 * Android shows three action buttons at most, so the four answers are a custom view.
 * The answer lands in `files/takeover_answer`, where scripts/ask-phone.sh picks it up.
 */
class TakeoverPromptReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val answer = intent.getStringExtra(EXTRA_ANSWER)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (answer != null) {
            File(context.filesDir, ANSWER_FILE).writeText(answer)
            manager.cancel(NOTIFICATION_ID)
        } else {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Test-Abfragen", NotificationManager.IMPORTANCE_HIGH),
            )
            manager.notify(NOTIFICATION_ID, prompt(context))
        }
    }

    private fun prompt(context: Context): android.app.Notification {
        val views = RemoteViews(context.packageName, R.layout.takeover_prompt)
        BUTTONS.forEachIndexed { index, (viewId, answer) ->
            val tap = Intent(context, TakeoverPromptReceiver::class.java).putExtra(EXTRA_ANSWER, answer)
            views.setOnClickPendingIntent(
                viewId,
                PendingIntent.getBroadcast(context, index, tap, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        }
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_bolt)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(views)
            .setCustomHeadsUpContentView(views)
            .setCustomBigContentView(views)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val CHANNEL = "takeover"
        const val NOTIFICATION_ID = 7001
        const val EXTRA_ANSWER = "answer"
        const val ANSWER_FILE = "takeover_answer"
        val BUTTONS = listOf(
            R.id.prompt_ready to "ready",
            R.id.prompt_10 to "10",
            R.id.prompt_30 to "30",
            R.id.prompt_60 to "60",
        )
    }
}
