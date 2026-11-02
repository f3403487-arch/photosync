package com.photosync.app

import android.content.Context
import java.security.SecureRandom

/** Настройки подключения, которые переживают перезапуск приложения. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("photosync", Context.MODE_PRIVATE)

    var host: String
        get() = sp.getString("host", "") ?: ""
        set(value) = sp.edit().putString("host", value).apply()

    var port: Int
        get() = sp.getInt("port", 8765)
        set(value) = sp.edit().putInt("port", value).apply()

    var code: String
        get() = sp.getString("code", "") ?: ""
        set(value) = sp.edit().putString("code", value).apply()

    var pcName: String
        get() = sp.getString("pcName", "") ?: ""
        set(value) = sp.edit().putString("pcName", value).apply()

    /** Ключ для отправки через интернет. Комп отдаёт его при подключении по Wi-Fi. */
    var relayKey: String
        get() = sp.getString("relayKey", "") ?: ""
        set(value) = sp.edit().putString("relayKey", value).apply()

    /** ID последнего отправленного фото: всё, что новее, уходит на комп. */
    var lastId: Long
        get() = sp.getLong("lastId", -1)
        set(value) = sp.edit().putLong("lastId", value).apply()

    var sentCount: Int
        get() = sp.getInt("sentCount", 0)
        set(value) = sp.edit().putInt("sentCount", value).apply()

    /** Ключ своей трубы для приёма через интернет. Отдаём его отправителю вместе с ответом на ping. */
    val receiveRelayKey: String
        get() = sp.getString("receiveRelayKey", null)
            ?: ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
                .also { sp.edit().putString("receiveRelayKey", it).apply() }

    /** Код, который нужно ввести на другом устройстве, чтобы слать файлы сюда. */
    val receiveCode: String
        get() = sp.getString("receiveCode", null)
            ?: "%04d".format(SecureRandom().nextInt(10000)).also { sp.edit().putString("receiveCode", it).apply() }
}
