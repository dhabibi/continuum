package ml.docilealligator.infinityforreddit.broadcastreceivers

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import ml.docilealligator.infinityforreddit.Infinity
import ml.docilealligator.infinityforreddit.R
import ml.docilealligator.infinityforreddit.RedditDataRoomDatabase
import ml.docilealligator.infinityforreddit.account.LocalProfiles
import ml.docilealligator.infinityforreddit.activities.ViewPostDetailActivity
import ml.docilealligator.infinityforreddit.customtheme.CustomThemeWrapper
import ml.docilealligator.infinityforreddit.reminder.Reminder
import ml.docilealligator.infinityforreddit.reminder.ReminderManager
import ml.docilealligator.infinityforreddit.utils.NotificationUtils
import java.util.Random
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

class ReminderAlarmReceiver: BroadcastReceiver() {
    @Inject
    lateinit var mRedditRoomDatabase: RedditDataRoomDatabase
    @Inject
    lateinit var mCustomThemeWrapper: CustomThemeWrapper

    @OptIn(DelicateCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        val reminder: Reminder? = intent.getParcelableExtra(EXTRA_REMINDER)
        reminder?.let {
            (context.applicationContext as Infinity).appComponent.inject(this)

            ReminderManager.sendNotification(context, mCustomThemeWrapper, it)

            doAsync(GlobalScope) {
                try {
                    val owner = intent.getStringExtra(EXTRA_LOCAL_PROFILE) ?: LocalProfiles.DEFAULT_ID
                    val database = if (owner == LocalProfiles.get(context).currentId) mRedditRoomDatabase
                        else RedditDataRoomDatabase.createForLocalProfile(context, owner)
                    try {
                        database.reminderDao().deleteReminder(it)
                    } finally {
                        if (database !== mRedditRoomDatabase) database.close()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    companion object {
        const val EXTRA_REMINDER = "ER"
        const val EXTRA_LOCAL_PROFILE = "ELP"
    }
}

fun BroadcastReceiver.doAsync(
    appScope: CoroutineScope,
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
    block: suspend CoroutineScope.() -> Unit
) {
    val pendingResult = goAsync()
    appScope.launch(coroutineContext) { block() }.invokeOnCompletion { pendingResult.finish() }
}
