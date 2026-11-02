package com.photosync.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** Работает в фоне, замечает новые фото в галерее и отправляет их на комп. */
class SyncService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val scanQueued = AtomicBoolean(false)
    private val retry = Runnable { queueScan() }
    private lateinit var prefs: Prefs

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = queueScan()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Отправка фото", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(NOTIFICATION_ID, notification("Жду новые фото…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        // при первом запуске старую галерею не отправляем, только новые фото
        if (prefs.lastId < 0) prefs.lastId = Photos.newest(this)?.id ?: 0
        contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        running = true
        queueScan()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        running = false
        contentResolver.unregisterContentObserver(observer)
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun queueScan() {
        handler.removeCallbacks(retry)
        if (!scanQueued.compareAndSet(false, true)) return
        // небольшая пауза: камера сообщает о новом фото несколько раз подряд
        handler.postDelayed({
            try {
                worker.execute {
                    scanQueued.set(false)
                    sendNewPhotos()
                }
            } catch (e: RejectedExecutionException) {
                // сервис уже остановлен
            }
        }, 1500)
    }

    private fun sendNewPhotos() {
        for (photo in Photos.newerThan(this, prefs.lastId)) {
            if (!Net.upload(this, photo.uri, photo.name, prefs)) {
                notify("Комп не отвечает, повторю через 30 сек")
                handler.postDelayed(retry, 30_000)
                return
            }
            prefs.lastId = photo.id
            prefs.sentCount += 1
            notify("Отправлено фото: ${prefs.sentCount}")
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("PhotoSync")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun notify(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL = "sync"

        @Volatile
        var running = false
    }
}
