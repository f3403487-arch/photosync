package com.photosync.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Превращает телефон или планшет в получателя, как программа на компе:
 * кричит в Wi-Fi «я тут», принимает файлы и кладёт их в «Загрузки/С телефона».
 * Вне дома файлы приходят через интернет — сервис всё время слушает свою трубу (Relay).
 */
class ReceiveService : Service() {
    private val pool = Executors.newCachedThreadPool()
    private lateinit var prefs: Prefs
    private lateinit var name: String
    private var server: ServerSocket? = null

    @Volatile
    private var received = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        name = Net.deviceName(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Приём файлов", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(NOTIFICATION_ID, notification(waitingText()), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        running = true
        pool.execute { serve() }
        pool.execute { beacon() }
        pool.execute { relay() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        running = false
        server?.close()
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun serve() {
        try {
            ServerSocket(Net.HTTP_PORT).also { server = it }.use { ss ->
                while (running) {
                    val socket = ss.accept()
                    pool.execute { handle(socket) }
                }
            }
        } catch (e: IOException) {
            // сервер закрыли при выключении приёма
        }
    }

    private fun beacon() {
        val msg = "PHOTOSYNC|$name|${Net.HTTP_PORT}".toByteArray(Charsets.UTF_8)
        DatagramSocket().use { socket ->
            socket.broadcast = true
            val target = InetAddress.getByName("255.255.255.255")
            while (running) {
                try {
                    socket.send(DatagramPacket(msg, msg.size, target, Net.BEACON_PORT))
                    Thread.sleep(2000)
                } catch (e: InterruptedException) {
                    return
                } catch (e: IOException) {
                    // Wi-Fi пропал, попробуем ещё раз чуть позже
                    try { Thread.sleep(2000) } catch (e: InterruptedException) { return }
                }
            }
        }
    }

    private fun relay() {
        val key = prefs.receiveRelayKey
        while (running) {
            try {
                Relay.receive(key) { fileName, data, size, verify ->
                    val ok = save(cleanName(fileName), data, size, verify)
                    if (ok) {
                        received++
                        notifyText("Принято файлов: $received · код ${prefs.receiveCode}")
                    }
                    ok
                }
            } catch (e: SocketTimeoutException) {
                // никто ничего не слал, ждём заново
            } catch (e: Exception) {
                // нет интернета или труба недоступна — попробуем чуть позже
                try { Thread.sleep(5000) } catch (e: InterruptedException) { return }
            }
        }
    }

    private fun cleanName(raw: String) = raw.substringAfterLast('/').substringAfterLast('\\')
        .ifBlank { "file_${System.currentTimeMillis()}" }

    private fun handle(socket: Socket) {
        socket.use {
            try {
                it.soTimeout = 30_000
                val input = BufferedInputStream(it.getInputStream())
                val out = it.getOutputStream()
                val requestLine = readLine(input) ?: return
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
                val parts = requestLine.split(" ")
                val method = parts.getOrElse(0) { "" }
                val path = parts.getOrElse(1) { "" }

                if (headers["x-code"] != prefs.receiveCode) return respond(out, 403, "wrong code")
                when {
                    // с верным кодом отдаём и ключ трубы: по нему отправитель сможет слать через интернет
                    method == "GET" && path == "/ping" -> respond(out, 200, name, "X-Relay-Key: ${prefs.receiveRelayKey}\r\n")
                    method == "POST" && path == "/upload" -> {
                        val size = headers["content-length"]?.toLongOrNull()
                            ?: return respond(out, 411, "length required")
                        val fileName = cleanName(URLDecoder.decode(headers["x-filename"] ?: "", "UTF-8"))
                        if (save(fileName, input, size)) {
                            received++
                            notifyText("Принято файлов: $received · код ${prefs.receiveCode}")
                            respond(out, 200, "ok")
                        } else {
                            respond(out, 500, "save failed")
                        }
                    }
                    else -> respond(out, 404, "not found")
                }
            } catch (e: IOException) {
                // отправитель отвалился посреди передачи
            }
        }
    }

    /** Сохраняет файл в «Загрузки/С телефона». Разрешения для этого не нужны. */
    private fun save(fileName: String, input: InputStream, size: Long, verify: () -> Boolean = { true }): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/С телефона")
            put(MediaStore.Downloads.IS_PENDING, 1)
            val ext = fileName.substringAfterLast('.', "").lowercase()
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { put(MediaStore.Downloads.MIME_TYPE, it) }
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            contentResolver.openOutputStream(uri)!!.use { copyExactly(input, it, size) }
            if (!verify()) throw IOException("файл повреждён или чужой")
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            Clawd.event("recv", fileName)
            true
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            false
        }
    }

    private fun copyExactly(input: InputStream, out: OutputStream, size: Long) {
        val buf = ByteArray(64 * 1024)
        var left = size
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("связь оборвалась")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) break
            if (b != '\r'.code) line.write(b)
            if (line.size() > 8192) return null
        }
        return line.toString("ISO-8859-1")
    }

    private fun respond(out: OutputStream, status: Int, text: String, extraHeaders: String = "") {
        val body = text.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status X\r\nContent-Type: text/plain; charset=utf-8\r\n" + extraHeaders +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    private fun waitingText() = "Жду файлы · код ${prefs.receiveCode}"

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("PhotoSync — приём")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun notifyText(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))

    companion object {
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL = "receive"

        @Volatile
        var running = false
    }
}
