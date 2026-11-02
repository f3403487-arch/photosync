package com.photosync.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Сообщает питомцу Clawd (если он стоит на телефоне), что файл полетел или пришёл. Без Clawd — тихо ничего. */
object Clawd {
    private val pool = Executors.newSingleThreadExecutor()

    fun event(type: String, name: String) {
        pool.execute {
            try {
                val conn = URL("http://127.0.0.1:8766/event").openConnection() as HttpURLConnection
                conn.connectTimeout = 500
                conn.readTimeout = 1000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(JSONObject().put("type", type).put("name", name).toString().toByteArray()) }
                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) {
                // Clawd не установлен или не запущен
            }
        }
    }
}
