package ml.docilealligator.infinityforreddit.reminder

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import ml.docilealligator.infinityforreddit.R
import ml.docilealligator.infinityforreddit.RedditDataRoomDatabase
import ml.docilealligator.infinityforreddit.account.LocalProfiles
import ml.docilealligator.infinityforreddit.activities.ViewPostDetailActivity
import ml.docilealligator.infinityforreddit.broadcastreceivers.ReminderAlarmReceiver
import ml.docilealligator.infinityforreddit.customtheme.CustomThemeWrapper
import ml.docilealligator.infinityforreddit.utils.NotificationUtils
import java.util.Random

class ReminderManager(
    private val applicationContext: Application,
    private val redditRoomDatabase: RedditDataRoomDatabase,
    private val alarmManager: AlarmManager,
    private val customThemeWrapper: CustomThemeWrapper
) {
    private val profileId = LocalProfiles.get(applicationContext).currentId

    suspend fun setReminder(reminder: Reminder) {
        redditRoomDatabase.reminderDao().insert(reminder)
        setAlarm(reminder)
    }

    fun setAlarm(reminder: Reminder) {
        setAlarm(reminder, profileId)
    }

    private fun receiverIntent(owner: String): Intent =
        Intent(applicationContext, ReminderAlarmReceiver::class.java).apply {
            // Preserve existing Default-account alarms; other accounts need a distinct identity.
            if (owner != LocalProfiles.DEFAULT_ID) {
                data = Uri.parse("continuum-local-reminder:$owner")
            }
            putExtra(ReminderAlarmReceiver.EXTRA_LOCAL_PROFILE, owner)
        }

    private fun setAlarm(reminder: Reminder, owner: String) {
        val alarmIntent = receiverIntent(owner).let { intent ->
            intent.putExtra(ReminderAlarmReceiver.EXTRA_REMINDER, reminder)
            PendingIntent.getBroadcast(
                applicationContext, reminder.createdAt.toInt(), intent,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        alarmManager.set(
            AlarmManager.RTC_WAKEUP,
            reminder.reminderTime,
            alarmIntent
        )
    }

    fun checkAndSetAllAlarms() {
        MainScope().launch {
            checkAndSetAllAlarmsSync()
        }
    }

    suspend fun checkAndSetAllAlarmsSync() {
        // A boot or account switch must also restore alarms belonging to inactive accounts.
        for (profile in LocalProfiles.get(applicationContext).profiles) {
            val database = if (profile.id == profileId) redditRoomDatabase
                else RedditDataRoomDatabase.createForLocalProfile(applicationContext, profile.id)
            try {
                checkAndSetAlarms(database, profile.id)
            } finally {
                if (database !== redditRoomDatabase) database.close()
            }
        }
    }

    private suspend fun checkAndSetAlarms(database: RedditDataRoomDatabase, owner: String) {
        for (reminder in database.reminderDao().getAllReminders()) {
            if (PendingIntent.getBroadcast(applicationContext, reminder.createdAt.toInt(), receiverIntent(owner),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE) == null) {
                if (System.currentTimeMillis() >= reminder.reminderTime) {
                    sendNotification(applicationContext, customThemeWrapper, reminder)
                    database.reminderDao().deleteReminder(reminder)
                } else {
                    setAlarm(reminder, owner)
                }
            }
        }
    }

    fun getAllRemindersFlow(): Flow<List<Reminder>> {
        return redditRoomDatabase.reminderDao().getAllRemindersFlow()
    }

    companion object {
        fun sendNotification(
            context: Context,
            customThemeWrapper: CustomThemeWrapper,
            reminder: Reminder
        ) {
            val notificationManager = NotificationUtils.getNotificationManager(context)
            val builder = NotificationUtils.buildNotification(
                notificationManager,
                context,
                context.getString(R.string.reminder),
                reminder.content,
                context.getString(if (reminder.commentId.isNotEmpty()) R.string.comment else R.string.post),
                NotificationUtils.CHANNEL_ID_NEW_MESSAGES,
                NotificationUtils.CHANNEL_NEW_MESSAGES,
                NotificationUtils.GROUP_REMINDER, customThemeWrapper.colorPrimaryLightTheme
            )

            val intent = Intent(context, ViewPostDetailActivity::class.java)
            intent.putExtra(ViewPostDetailActivity.EXTRA_POST_ID, reminder.postId)
            intent.putExtra(ViewPostDetailActivity.EXTRA_SINGLE_COMMENT_ID, reminder.commentId)
            val pendingIntent =
                PendingIntent.getActivity(context, reminder.createdAt.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.setContentIntent(pendingIntent)

            try {
                notificationManager.notify(
                    NotificationUtils.REMINDER_NOTIFICATION_ID + Random().nextInt(10000), builder.build()
                )
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
        }
    }
}
