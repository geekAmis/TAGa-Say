package com.tagasay.app.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tagasay.app.MainActivity
import com.tagasay.app.R
import com.tagasay.app.TAGaSayApp
import com.tagasay.app.data.model.Message
import com.tagasay.app.data.model.MessageKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Фоновый приём сообщений без Google/FCM.
 *
 * Держит relay-соединение и расшифровывает конверты локально. Уведомление
 * намеренно не содержит текст сообщения: на экране блокировки видны только
 * имя чата и тип вложения, содержимое открывается после разблокировки.
 */
class SyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: TAGaSayApp

    @Volatile
    private var notificationsEnabled = true

    override fun onCreate() {
        super.onCreate()
        app = application as TAGaSayApp
        createChannels()
        startForegroundCompat()
        app.container.syncManager.start()
        scope.launch {
            app.container.settingsStore.settings.collect { settings ->
                notificationsEnabled = settings.notifications
            }
        }
        scope.launch {
            app.container.repository.incoming.collect { message ->
                if (notificationsEnabled) postMessageNotification(message)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val notification = serviceNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                SERVICE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
            )
        } else {
            startForeground(SERVICE_NOTIFICATION_ID, notification)
        }
    }

    private fun serviceNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_taga)
            .setContentTitle("TAGa-Say")
            .setContentText("Защищённая синхронизация включена")
            .setContentIntent(pending)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun postMessageNotification(message: Message) {
        if (!notificationsEnabled) return
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return

        val chat = app.container.repository.chat(message.chatId)
        val title = chat?.title?.takeIf { it.isNotBlank() }
            ?: message.senderName.takeIf { it.isNotBlank() }
            ?: "TAGa-Say"
        val preview = when (message.kind) {
            MessageKind.IMAGE -> "Фотография"
            MessageKind.VIDEO -> "Видео"
            MessageKind.AUDIO -> "Голосовое сообщение"
            MessageKind.FILE -> "Файл"
            MessageKind.STICKER -> "Стикер"
            MessageKind.SYSTEM -> "Системное сообщение"
            else -> "Новое сообщение"
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(MainActivity.EXTRA_OPEN_CHAT_ID, message.chatId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            this,
            message.chatId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_taga)
            .setContentTitle(title)
            .setContentText(preview)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(GROUP_MESSAGES)
            .build()

        runCatching {
            NotificationManagerCompat.from(this).notify(message.chatId.hashCode(), notification)
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                "Синхронизация",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Держит защищённое соединение с relay"
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGES,
                "Сообщения",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Новые сообщения TAGa-Say"
                enableVibration(true)
            },
        )
    }

    companion object {
        private const val CHANNEL_SERVICE = "taga_sync_service"
        private const val CHANNEL_MESSAGES = "taga_messages"
        private const val GROUP_MESSAGES = "taga_messages_group"
        private const val SERVICE_NOTIFICATION_ID = 1001

        fun start(context: Context) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(Intent(context, SyncService::class.java))
                } else {
                    context.startService(Intent(context, SyncService::class.java))
                }
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SyncService::class.java))
        }
    }
}
