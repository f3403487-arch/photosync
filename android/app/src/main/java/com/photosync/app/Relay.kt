package com.photosync.app

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.xor

/**
 * Отправка через интернет, когда комп не в том же Wi-Fi.
 * Файл идёт через публичную «трубу» ppng.io: комп её всё время слушает, сервер ничего не хранит.
 * Всё шифруется ключом, который комп отдал при подключении по Wi-Fi (такой же шифр в photo_receiver.py).
 */
object Relay {
    private const val BASE = "https://ppng.io/"
    private const val STALL_MS = 20_000L

    fun upload(context: Context, uri: Uri, name: String, keyHex: String): Boolean = try {
        val resolver = context.contentResolver
        var length = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        // размер неизвестен: читаем файл целиком в память
        val bytes = if (length < 0) resolver.openInputStream(uri)!!.use { it.readBytes() }.also { length = it.size.toLong() } else null
        val header = JSONObject().put("name", name).put("size", length).toString().toByteArray()
        val total = 16 + 4 + header.size + length + 32

        val conn = URL(BASE + path(keyHex)).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.requestMethod = "PUT"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        conn.setFixedLengthStreamingMode(total)

        // если комп выключен, труба перестаёт принимать байты и запись зависает — обрываем её
        val lastProgress = AtomicLong(System.currentTimeMillis())
        val watchdog = Executors.newSingleThreadScheduledExecutor()
        watchdog.scheduleAtFixedRate({
            if (System.currentTimeMillis() - lastProgress.get() > STALL_MS) conn.disconnect()
        }, 2, 2, TimeUnit.SECONDS)

        try {
            val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher(hexToBytes(keyHex), iv)
            conn.outputStream.use { out ->
                out.write(iv)
                fun put(buf: ByteArray, n: Int) {
                    cipher.encrypt(buf, 0, n)
                    out.write(buf, 0, n)
                    lastProgress.set(System.currentTimeMillis())
                }
                val prefix = byteArrayOf(
                    (header.size ushr 24).toByte(), (header.size ushr 16).toByte(),
                    (header.size ushr 8).toByte(), header.size.toByte(),
                )
                put(prefix, prefix.size)
                put(header, header.size)
                val input = bytes?.inputStream() ?: resolver.openInputStream(uri)!!
                input.use {
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = it.read(buf)
                        if (n < 0) break
                        put(buf, n)
                    }
                }
                out.write(cipher.tag())
            }
            // труба отвечает сразу, а «received successfully» дописывает, когда комп всё забрал
            conn.responseCode == 200 && "received successfully" in conn.inputStream.bufferedReader().readText()
        } finally {
            watchdog.shutdownNow()
            conn.disconnect()
        }
    } catch (e: Exception) {
        false
    }

    /**
     * Ждёт один файл в своей трубе (для режима «Приём»). Если за 2 минуты никто ничего не прислал —
     * SocketTimeoutException, тогда просто зовём ещё раз.
     * store получает имя, расшифрованные данные, размер и проверку метки — её надо вызвать,
     * когда данные прочитаны до конца, и не сохранять файл, если она вернула false.
     */
    fun receive(keyHex: String, store: (name: String, data: InputStream, size: Long, verify: () -> Boolean) -> Boolean): Boolean {
        val conn = URL(BASE + path(keyHex)).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 120_000
        return try {
            val input = BufferedInputStream(conn.inputStream)
            val cipher = Cipher(hexToBytes(keyHex), readExact(input, 16))
            val prefix = readExact(input, 4).also { cipher.decrypt(it, 0, 4) }
            val headerSize = ((prefix[0].toInt() and 0xff) shl 24) or ((prefix[1].toInt() and 0xff) shl 16) or
                ((prefix[2].toInt() and 0xff) shl 8) or (prefix[3].toInt() and 0xff)
            if (headerSize !in 1..4096) throw IOException("чужие данные в трубе")
            val header = JSONObject(String(readExact(input, headerSize).also { cipher.decrypt(it, 0, headerSize) }, Charsets.UTF_8))
            val size = header.getLong("size")
            var left = size
            val data = object : InputStream() {
                override fun read(): Int {
                    val one = ByteArray(1)
                    return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (left <= 0) return -1
                    val n = input.read(b, off, minOf(len.toLong(), left).toInt())
                    if (n < 0) return -1
                    cipher.decrypt(b, off, n)
                    left -= n
                    return n
                }
            }
            store(header.getString("name"), data, size) {
                left == 0L && MessageDigest.isEqual(cipher.tag(), readExact(input, 32))
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun readExact(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(buf, got, n - got)
            if (r < 0) throw IOException("связь оборвалась")
            got += r
        }
        return buf
    }

    /** Адрес трубы получается из ключа, сам ключ на сервер не уходит. */
    private fun path(keyHex: String): String =
        MessageDigest.getInstance("SHA-256").digest("photosync|$keyHex".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(40)

    private fun hexToBytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun hmac(key: ByteArray) = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }

    /** XOR с HMAC-SHA256(enc_key, iv | номер блока), в конце метка HMAC-SHA256(mac_key, iv | шифр). */
    private class Cipher(key: ByteArray, private val iv: ByteArray) {
        private val stream = hmac(hmac(key).doFinal("enc".toByteArray()))
        private val mac = hmac(hmac(key).doFinal("mac".toByteArray())).apply { update(iv) }
        private var pos = 0L
        private var blockIndex = -1L
        private var block = ByteArray(32)

        fun encrypt(buf: ByteArray, off: Int, n: Int) {
            xor(buf, off, n)
            mac.update(buf, off, n)
        }

        fun decrypt(buf: ByteArray, off: Int, n: Int) {
            mac.update(buf, off, n)
            xor(buf, off, n)
        }

        private fun xor(buf: ByteArray, off: Int, n: Int) {
            for (i in off until off + n) {
                val index = pos / 32
                if (index != blockIndex) {
                    stream.update(iv)
                    stream.update(ByteArray(8) { (index ushr (56 - 8 * it)).toByte() })
                    block = stream.doFinal()
                    blockIndex = index
                }
                buf[i] = buf[i] xor block[(pos % 32).toInt()]
                pos++
            }
        }

        fun tag(): ByteArray = mac.doFinal()
    }
}
