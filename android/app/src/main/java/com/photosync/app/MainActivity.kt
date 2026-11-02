package com.photosync.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val bg = Executors.newSingleThreadExecutor()
    private val photoPermission =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private lateinit var prefs: Prefs
    private lateinit var ui: Ui
    private lateinit var status: TextView
    private lateinit var statusDot: View
    private lateinit var codeInput: EditText
    private lateinit var hostInput: EditText
    private lateinit var sendButtons: List<Button>
    private lateinit var syncSwitch: Switch
    private lateinit var receiveSwitch: Switch
    private lateinit var receiveInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ui = Ui(this)
        val c = ui.c
        actionBar?.hide()
        paintSystemBars()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(24), ui.dp(40), ui.dp(24), ui.dp(32))
        }

        root.addView(ui.title("PhotoSync"))
        root.addView(ui.body("Фото и файлы — на компьютер или другое устройство", muted = true).apply {
            setPadding(0, ui.dp(4), 0, 0)
        })

        statusDot = ui.dot(c.muted)
        status = ui.body()
        root.addView(ui.card().apply {
            addView(statusDot)
            addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })

        root.addView(ui.label("Подключение"))
        codeInput = ui.input("Код с экрана компа или планшета").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.code)
        }
        root.addView(codeInput)
        hostInput = ui.input("Адрес компа (необязательно)").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        root.addView(hostInput)
        root.addView(ui.primaryButton("Подключиться") { connect() })

        root.addView(ui.label("Отправка"))
        sendButtons = listOf(
            ui.primaryButton("Выбрать фото") { pickFiles("image/*") },
            ui.secondaryButton("Выбрать файлы") { pickFiles("*/*") },
            ui.secondaryButton("Отправить последнее фото") { sendLatest() },
        )
        sendButtons.forEach { root.addView(it) }

        root.addView(ui.label("Автоматически"))
        syncSwitch = ui.switch()
        root.addView(ui.card().apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(ui.body("Автоотправка новых фото", size = 16f))
                addView(ui.body("Каждое новое фото само уйдёт на комп", muted = true, size = 13f))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(syncSwitch)
        })

        root.addView(ui.label("Приём"))
        receiveSwitch = ui.switch()
        root.addView(ui.card().apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(ui.body("Принимать файлы", size = 16f))
                addView(ui.body("Другой телефон или планшет сможет слать файлы сюда", muted = true, size = 13f))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(receiveSwitch)
        })
        receiveInfo = ui.body(muted = true, size = 14f).apply { setPadding(ui.dp(4), ui.dp(10), 0, 0) }
        root.addView(receiveInfo)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(c.background)
            isFillViewport = true
            addView(root)
        })

        requestNeededPermissions()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        bg.shutdown()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun paintSystemBars() {
        window.statusBarColor = ui.c.background
        window.navigationBarColor = ui.c.background
        if (!ui.c.isDark) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    private fun refresh() {
        val connected = prefs.host.isNotEmpty()
        status.text = if (connected) {
            "${prefs.pcName} · ${prefs.host}\nОтправлено файлов: ${prefs.sentCount}\n" +
                if (prefs.relayKey.isNotEmpty()) "Вне дома: через интернет ✓"
                else "Вне дома не работает: переподключись к компу по Wi-Fi"
        } else {
            "Не подключено. Введи код с экрана компа или планшета и нажми «Подключиться»."
        }
        setDot(if (connected) ui.c.ok else ui.c.muted)
        sendButtons.forEach { it.alpha = if (connected) 1f else 0.4f }

        syncSwitch.setOnCheckedChangeListener(null)
        syncSwitch.isChecked = SyncService.running
        syncSwitch.isEnabled = connected
        syncSwitch.setOnCheckedChangeListener { _, checked -> setSync(checked) }

        val receiving = ReceiveService.running
        receiveSwitch.setOnCheckedChangeListener(null)
        receiveSwitch.isChecked = receiving
        receiveSwitch.setOnCheckedChangeListener { _, checked -> setReceive(checked) }
        receiveInfo.visibility = if (receiving) View.VISIBLE else View.GONE
        receiveInfo.text = "Код для отправителя: ${prefs.receiveCode}\n" +
            "Имя: ${Net.deviceName(this)} · ${Net.localIp() ?: "нет Wi-Fi"}\n" +
            "Файлы сохраняются в «Загрузки → С телефона»\n" +
            "Вне дома тоже принимает — через интернет, если с этого устройства уже раз отправляли по Wi-Fi"
    }

    private fun setReceive(enabled: Boolean) {
        val intent = Intent(this, ReceiveService::class.java)
        if (enabled) startForegroundService(intent) else stopService(intent)
        receiveSwitch.postDelayed({ refresh() }, 300)
    }

    private fun setDot(color: Int) = (statusDot.background as GradientDrawable).setColor(color)

    private fun showStatus(text: String, color: Int = ui.c.accent) {
        status.text = text
        setDot(color)
    }

    private fun connect() {
        val code = codeInput.text.toString().trim()
        if (code.length != 4) {
            toast("Введи 4-значный код с экрана компа")
            return
        }
        val manualHost = hostInput.text.toString().trim()
        showStatus("Ищу устройства в Wi-Fi…")
        bg.execute {
            val found = if (manualHost.isNotEmpty()) {
                val port = manualHost.substringAfter(':', "8765").toIntOrNull() ?: Net.HTTP_PORT
                listOf(Pc(manualHost.substringBefore(':'), port, manualHost))
            } else {
                Net.discoverAll(this)
            }
            runOnUiThread {
                when (found.size) {
                    0 -> showStatus(
                        "Ничего не найдено. Проверь, что на компе запущена программа " +
                            "или на планшете включён приём, и вы в одном Wi-Fi."
                    )
                    1 -> finishConnect(found[0], code)
                    else -> AlertDialog.Builder(this)
                        .setTitle("Куда отправлять?")
                        .setItems(found.map { it.name }.toTypedArray()) { _, i -> finishConnect(found[i], code) }
                        .setOnCancelListener { refresh() }
                        .show()
                }
            }
        }
    }

    private fun finishConnect(pc: Pc, code: String) {
        showStatus("Подключаюсь к ${pc.name}…")
        bg.execute {
            val error = when (val result = Net.ping(this, pc.host, pc.port, code)) {
                is PingResult.Ok -> {
                    prefs.host = pc.host
                    prefs.port = pc.port
                    prefs.code = code
                    prefs.pcName = result.pcName
                    prefs.relayKey = result.relayKey
                    null
                }
                PingResult.WrongCode -> "Неверный код для «${pc.name}». Введи код с экрана этого устройства."
                PingResult.NoConnection ->
                    "«${pc.name}» найден, но не отвечает. Попробуй выключить VPN на телефоне. " +
                        "Если это комп — проверь брандмауэр Windows."
            }
            runOnUiThread {
                refresh()
                if (error != null) showStatus(error) else toast("Подключено к ${prefs.pcName}")
            }
        }
    }

    private fun setSync(enabled: Boolean) {
        if (enabled && !hasPhotoPermission()) {
            toast("Разреши доступ ко всем фото")
            requestNeededPermissions()
            refresh()
            return
        }
        val intent = Intent(this, SyncService::class.java)
        if (enabled) startForegroundService(intent) else stopService(intent)
        syncSwitch.postDelayed({ refresh() }, 300)
    }

    private fun sendLatest() {
        if (prefs.host.isEmpty()) {
            toast("Сначала подключись к компу")
            return
        }
        bg.execute {
            val photo = Photos.newest(this)
            val ok = photo != null && Net.upload(this, photo.uri, photo.name, prefs)
            if (ok) prefs.sentCount += 1
            runOnUiThread {
                refresh()
                toast(if (ok) "Отправлено: ${photo!!.name}" else "Не получилось отправить")
            }
        }
    }

    private fun pickFiles(mimeType: String) {
        if (prefs.host.isEmpty()) {
            toast("Сначала подключись к компу")
            return
        }
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = mimeType
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, PICK_REQUEST)
    }

    @Deprecated("Activity API без AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_REQUEST || resultCode != RESULT_OK || data == null) return
        val clip = data.clipData
        val uris = if (clip != null) (0 until clip.itemCount).map { clip.getItemAt(it).uri } else listOfNotNull(data.data)
        sendPicked(uris)
    }

    private fun sendPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        showStatus("Отправляю 1 из ${uris.size}…")
        bg.execute {
            var sent = 0
            uris.forEachIndexed { i, uri ->
                if (Net.upload(this, uri, Photos.displayName(this, uri), prefs)) {
                    sent++
                    prefs.sentCount += 1
                }
                if (i + 1 < uris.size) runOnUiThread { showStatus("Отправляю ${i + 2} из ${uris.size}…") }
            }
            runOnUiThread {
                refresh()
                toast(
                    if (sent == uris.size) "Отправлено файлов: $sent"
                    else "Отправлено $sent из ${uris.size}. Проверь, что программа на компе запущена."
                )
            }
        }
    }

    private fun hasPhotoPermission() =
        checkSelfPermission(photoPermission) == PackageManager.PERMISSION_GRANTED

    private fun requestNeededPermissions() {
        val needed = mutableListOf(photoPermission)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        private const val PICK_REQUEST = 1001
    }
}
