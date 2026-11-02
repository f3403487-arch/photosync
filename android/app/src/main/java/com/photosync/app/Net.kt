package com.photosync.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URL
import java.net.URLEncoder

data class Pc(val host: String, val port: Int, val name: String)

sealed class PingResult {
    /** relayKey пустой, если получатель не умеет принимать через интернет (например, планшет). */
    data class Ok(val pcName: String, val relayKey: String) : PingResult()
    object WrongCode : PingResult()
    object NoConnection : PingResult()
}

/** Общение с компом и другими устройствами с PhotoSync. */
object Net {
    const val HTTP_PORT = 8765
    const val BEACON_PORT = 8766

    /**
     * Слушает сигналы «я тут», которые получатели (комп, планшет) шлют каждые 2 секунды,
     * и возвращает всех, кого услышал. Себя не считает.
     */
    fun discoverAll(context: Context, listenMs: Int = 4000): List<Pc> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("photosync").apply {
            setReferenceCounted(false)
            acquire()
        }
        val own = ownAddresses()
        val found = linkedMapOf<String, Pc>()
        try {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(BEACON_PORT))
                val deadline = System.currentTimeMillis() + listenMs
                val buf = ByteArray(512)
                while (true) {
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) break
                    socket.soTimeout = left.toInt()
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val parts = String(packet.data, 0, packet.length, Charsets.UTF_8).split("|")
                    if (parts.size != 3 || parts[0] != "PHOTOSYNC") continue
                    val port = parts[2].toIntOrNull() ?: continue
                    val host = packet.address.hostAddress ?: continue
                    if (host !in own) found[host] = Pc(host, port, parts[1])
                }
            }
        } catch (e: IOException) {
            // время вышло
        } finally {
            lock.release()
        }
        return found.values.toList()
    }

    /** Адрес этого устройства в Wi-Fi, чтобы показать его на экране приёма. */
    fun localIp(): String? = ownAddresses().firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") }
        ?: ownAddresses().firstOrNull()

    fun deviceName(context: Context): String =
        (Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL)
            .replace("|", " ")

    private fun ownAddresses(): Set<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filter { it is Inet4Address && !it.isLoopbackAddress }
            .mapNotNull { it.hostAddress }
            .toSet()
    } catch (e: Exception) {
        emptySet()
    }

    /**
     * Сеть Wi-Fi в обход VPN. Если на телефоне включён VPN, обычные запросы к 192.168.* уходят
     * в туннель и до компа или планшета не доходят — поэтому шлём их прямо через Wi-Fi.
     */
    @Suppress("DEPRECATION")
    private fun wifiNetwork(context: Context): Network? = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.allNetworks.firstOrNull { network ->
            val caps = cm.getNetworkCapabilities(network)
            caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    } catch (e: Exception) {
        null
    }

    /** Сначала через Wi-Fi в обход VPN, потом обычным путём (вдруг VPN не разрешает обход). */
    private fun routes(context: Context): List<Network?> = listOfNotNull(wifiNetwork(context)) + listOf(null)

    fun ping(context: Context, host: String, port: Int, code: String): PingResult {
        for (network in routes(context)) {
            val result = pingVia(network, host, port, code)
            if (result != PingResult.NoConnection) return result
        }
        return PingResult.NoConnection
    }

    private fun pingVia(network: Network?, host: String, port: Int, code: String): PingResult = try {
        val conn = open(network, host, port, "/ping", code)
        try {
            when (conn.responseCode) {
                200 -> PingResult.Ok(
                    conn.inputStream.bufferedReader().readText(),
                    conn.getHeaderField("X-Relay-Key")?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: "",
                )
                403 -> PingResult.WrongCode
                else -> PingResult.NoConnection
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: IOException) {
        PingResult.NoConnection
    }

    /** Когда последний раз не достучались по Wi-Fi: минуту после этого сразу шлём через интернет. */
    @Volatile
    private var localFailedAt = 0L

    /** Сначала напрямую по Wi-Fi, а если комп не отвечает — через интернет. */
    fun upload(context: Context, uri: Uri, name: String, prefs: Prefs): Boolean {
        Clawd.event("send_start", name)
        val ok = uploadAny(context, uri, name, prefs)
        Clawd.event("send_done", if (ok) name else "fail")
        return ok
    }

    private fun uploadAny(context: Context, uri: Uri, name: String, prefs: Prefs): Boolean {
        val hasRelay = prefs.relayKey.isNotEmpty()
        if (!hasRelay || System.currentTimeMillis() - localFailedAt > 60_000) {
            if (routes(context).any { uploadLocal(context, it, uri, name, prefs) }) return true
            localFailedAt = System.currentTimeMillis()
        }
        return hasRelay && Relay.upload(context, uri, name, prefs.relayKey)
    }

    private fun uploadLocal(context: Context, network: Network?, uri: Uri, name: String, prefs: Prefs): Boolean = try {
        val resolver = context.contentResolver
        val length = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        val conn = open(network, prefs.host, prefs.port, "/upload", prefs.code)
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setRequestProperty("X-Filename", URLEncoder.encode(name, "UTF-8"))
            if (length > 0) {
                conn.setFixedLengthStreamingMode(length)
                resolver.openInputStream(uri)!!.use { input -> conn.outputStream.use { input.copyTo(it) } }
            } else {
                // размер неизвестен: читаем фото целиком в память
                val bytes = resolver.openInputStream(uri)!!.use { it.readBytes() }
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            conn.responseCode == 200
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        false
    }

    private fun open(network: Network?, host: String, port: Int, path: String, code: String): HttpURLConnection {
        val url = URL("http://$host:$port$path")
        return ((network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection).apply {
            connectTimeout = 3000
            readTimeout = 30000
            setRequestProperty("X-Code", code)
        }
    }
}
