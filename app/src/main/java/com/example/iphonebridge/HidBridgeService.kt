package com.example.iphonebridge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class HidBridgeService : Service() {
    private var adapter: BluetoothAdapter? = null
    private var hid: BluetoothHidDevice? = null
    private var hidRegistered = false
    private var hidProfileRequested = false
    private var pendingRegisterHid = false
    private var iPhone: BluetoothDevice? = null
    private var socket: Socket? = null
    private var targetPcIp: String? = null
    private val reconnecting = AtomicBoolean(false)

    private val hidExecutor = Executors.newSingleThreadExecutor()
    private val netExecutor = Executors.newSingleThreadExecutor()
    private val monitorExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val keyboard = ByteArray(8)
    private val mouse = ByteArray(4)
    private var receivedPackets = 0L

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE || proxy !is BluetoothHidDevice) return
            hid = proxy
            hidProfileRequested = true
            postStatus("HID Device profile готов")
            refreshConnectedIPhone(true)
            if (pendingRegisterHid) {
                pendingRegisterHid = false
                registerHidNow()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hid = null
                hidProfileRequested = false
                hidRegistered = false
                iPhone = null
                postStatus("HID profile отключён")
            }
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            hidRegistered = registered
            if (registered) {
                postStatus("HID зарегистрирован. Теперь подключи iPhone в Bluetooth.")
            } else {
                iPhone = null
                postStatus("HID регистрация остановлена")
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    iPhone = device
                    postStatus("iPhone подключён по Bluetooth HID: ${deviceName(device)}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (iPhone?.address == device.address) iPhone = null
                    postStatus("iPhone отключён")
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val h = hid ?: return
            if (type != BluetoothHidDevice.REPORT_TYPE_INPUT) return
            val source = when (id.toInt()) { 1 -> keyboard; 2 -> mouse; else -> return }
            val length = minOf(bufferSize, source.size)
            try { h.replyReport(device, BluetoothHidDevice.REPORT_TYPE_INPUT, id, source.copyOf(length)) } catch (_: SecurityException) { }
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) { }
        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) { }
        override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) { }
        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            if (iPhone?.address == device.address) iPhone = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1001, buildNotification("Bluetooth HID service работает в фоне"))
        initBluetooth()
        postStatus("Фоновый HID-сервис запущен")
        monitorExecutor.scheduleWithFixedDelay({ refreshConnectedIPhone(false) }, 500, 1000, TimeUnit.MILLISECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REGISTER_HID -> registerHid()
            ACTION_CONNECT_PC -> connectPc(intent.getStringExtra(EXTRA_IP) ?: "")
            ACTION_STOP_PC -> { targetPcIp = null; closeSocket() }
            ACTION_TAP_ABSOLUTE -> {
                val x = intent.getIntExtra(EXTRA_X, 0).coerceIn(0, 828)
                val y = intent.getIntExtra(EXTRA_Y, 0).coerceIn(0, 1792)
                hidExecutor.execute { tapAtIPhone(x, y) }
            }
            ACTION_KEY -> {
                val usage = intent.getIntExtra(EXTRA_KEY, 0)
                hidExecutor.execute { tapKey(usage, 0) }
            }
            ACTION_KEY_CODE -> {
                val usage = intent.getIntExtra(EXTRA_USAGE, 0)
                val modifier = intent.getIntExtra(EXTRA_MODIFIER, 0)
                hidExecutor.execute { tapKey(usage, modifier) }
            }
        }
        return START_STICKY
    }

    private fun initBluetooth() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            postStatus("Нужно разрешение Bluetooth Connect")
            return
        }
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        adapter = manager.adapter
        if (adapter == null) { postStatus("На Android нет Bluetooth"); return }
        if (adapter?.isEnabled != true) { postStatus("Включи Bluetooth на Android"); return }
        postStatus("Bluetooth включён. Загружаю HID Device...")
        try {
            adapter?.getProfileProxy(this, serviceListener, BluetoothProfile.HID_DEVICE)
        } catch (e: Exception) {
            postStatus("HID Device недоступен: ${e.message}")
        }
    }

    private fun registerHid() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            postStatus("Нет разрешения Bluetooth Connect")
            return
        }
        if (hidRegistered) {
            postStatus("HID уже зарегистрирован")
            return
        }
        val h = hid
        if (h == null) {
            pendingRegisterHid = true
            postStatus("Дождись загрузки HID Device...")
            if (!hidProfileRequested) initBluetooth()
            return
        }
        registerHidNow()
    }

    private fun registerHidNow() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            postStatus("Нет разрешения Bluetooth Connect")
            return
        }
        val h = hid ?: run {
            pendingRegisterHid = true
            postStatus("HID Device ещё загружается...")
            return
        }
        if (hidRegistered) {
            postStatus("HID уже зарегистрирован")
            return
        }
        try {
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "iPhone Android Touch",
                "Bluetooth keyboard and mouse",
                "AndroidTouch",
                BluetoothHidDevice.SUBCLASS1_COMBO,
                MainActivity.REPORT_DESCRIPTOR
            )
            h.registerApp(sdp, null, null, hidExecutor, hidCallback)
            postStatus("Запрос регистрации HID отправлен")
        } catch (e: SecurityException) {
            postStatus("Нет Bluetooth-разрешения: ${e.message}")
        } catch (e: Exception) {
            postStatus("Ошибка регистрации HID: ${e.message}")
        }
    }

    private fun connectPc(ip: String) {
        if (ip.isEmpty()) { postStatus("Введи IP компьютера"); return }
        targetPcIp = ip
        closeSocket()
        startReconnectLoop()
    }

    private fun startReconnectLoop() {
        if (!reconnecting.compareAndSet(false, true)) return
        netExecutor.execute {
            try {
                while (targetPcIp != null) {
                    if (socket == null || socket?.isClosed == true) {
                        val ip = targetPcIp ?: break
                        try {
                            val s = Socket()
                            s.tcpNoDelay = true
                            s.keepAlive = true
                            s.connect(InetSocketAddress(ip, 45678), 2500)
                            socket = s
                            postStatus("ПК подключён: $ip:45678")
                            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                            while (targetPcIp != null && s.isConnected && !s.isClosed) {
                                val line = reader.readLine() ?: break
                                handlePacket(line)
                            }
                        } catch (e: Exception) {
                            if (targetPcIp != null) postStatus("ПК недоступен, переподключение...")
                        } finally {
                            try { socket?.close() } catch (_: Exception) { }
                            socket = null
                        }
                    }
                    if (targetPcIp != null) Thread.sleep(800)
                }
            } finally {
                reconnecting.set(false)
            }
        }
    }

    private fun handlePacket(line: String) {
        val p = line.split(',')
        try {
            when (p.firstOrNull()) {
                "K" -> {
                    if (p.size != 8) return
                    for (i in 0 until 8) keyboard[i] = p[i + 1].toInt().coerceIn(0, 255).toByte()
                    receivedPackets++
                    if (receivedPackets <= 3 || receivedPackets % 25L == 0L) postStatus("Получена команда K от ПК (#$receivedPackets)")
                    send(1, keyboard)
                }
                "M" -> {
                    if (p.size != 5) return
                    mouse[0] = p[1].toInt().coerceIn(0, 7).toByte()
                    mouse[1] = p[2].toInt().coerceIn(-127, 127).toByte()
                    mouse[2] = p[3].toInt().coerceIn(-127, 127).toByte()
                    mouse[3] = p[4].toInt().coerceIn(-127, 127).toByte()
                    receivedPackets++
                    if (receivedPackets <= 3 || receivedPackets % 25L == 0L) postStatus("Получена команда M от ПК (#$receivedPackets)")
                    send(2, mouse)
                }
            }
        } catch (_: NumberFormatException) { }
    }

    private fun refreshConnectedIPhone(reportStatus: Boolean) {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        val h = hid ?: return
        if (!hidRegistered) return
        try {
            val devices = h.connectedDevices
            val found = devices.firstOrNull()
            if (found != null) {
                val changed = iPhone?.address != found.address
                iPhone = found
                if (changed || reportStatus) {
                    postStatus("iPhone подключён по Bluetooth HID: ${deviceName(found)}")
                }
            } else if (iPhone != null) {
                iPhone = null
                postStatus("iPhone HID больше не подключён")
            }
        } catch (_: SecurityException) { }
    }

    /**
     * Relative HID is used as the compatibility path for iOS. We deliberately
     * "home" the cursor first with large negative movements, then move to the
     * requested iPhone-11 coordinate. This makes an Android tap behave like a
     * screen tap instead of a normal trackpad movement.
     */
    private fun tapAtIPhone(x: Int, y: Int) {
        if (!ensureIPhone()) return
        repeat(18) {
            sendMouseRelative(-127, -127, 0)
            Thread.sleep(3)
        }
        moveAxis(x, true)
        moveAxis(y, false)
        Thread.sleep(12)
        sendMouseRelative(0, 0, 1)
        Thread.sleep(18)
        sendMouseRelative(0, 0, 0)
        postStatus("Tap sent: iPhone $x,$y")
    }

    private fun moveAxis(value: Int, xAxis: Boolean) {
        var remaining = value.coerceIn(0, 1792)
        if (xAxis) remaining = value.coerceIn(0, 828)
        while (remaining > 0) {
            val step = minOf(remaining, 127)
            if (xAxis) sendMouseRelative(step, 0, 0) else sendMouseRelative(0, step, 0)
            remaining -= step
            Thread.sleep(2)
        }
    }

    private fun sendMouseRelative(dx: Int, dy: Int, buttons: Int) {
        mouse[0] = buttons.coerceIn(0, 7).toByte()
        mouse[1] = dx.coerceIn(-127, 127).toByte()
        mouse[2] = dy.coerceIn(-127, 127).toByte()
        mouse[3] = 0
        send(2, mouse)
    }

    private fun tapKey(usage: Int, modifier: Int) {
        keyboard.fill(0)
        keyboard[0] = modifier.coerceIn(0, 255).toByte()
        keyboard[2] = usage.coerceIn(0, 255).toByte()
        send(1, keyboard)
        Thread.sleep(12)
        keyboard.fill(0)
        send(1, keyboard)
    }

    private fun ensureIPhone(): Boolean {
        refreshConnectedIPhone(false)
        if (iPhone == null) {
            postStatus("iPhone HID не подключён — включи AssistiveTouch и подключи iPhone")
            return false
        }
        return true
    }

    private fun send(reportId: Int, data: ByteArray) {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        val h = hid ?: return
        if (!hidRegistered) return
        var phone = iPhone
        if (phone == null) {
            refreshConnectedIPhone(true)
            phone = iPhone
        }
        if (phone == null) return
        try {
            val ok = h.sendReport(phone, reportId, data)
            if (!ok) postStatus("HID sendReport отклонён iPhone (report $reportId)")
        } catch (_: SecurityException) { }
    }

    private fun closeSocket() {
        try { socket?.close() } catch (_: Exception) { }
        socket = null
    }

    private fun postStatus(message: String) {
        getSharedPreferences("bridge_state", MODE_PRIVATE).edit().putString("last_status", message).apply()
        val intent = Intent(ACTION_STATUS).setPackage(packageName)
        intent.putExtra(EXTRA_MESSAGE, message)
        sendBroadcast(intent)
    }

    private fun deviceName(device: BluetoothDevice): String = try { device.name ?: device.address } catch (_: SecurityException) { device.address }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "iPhone Android Touch", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun buildNotification(text: String): Notification {
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("iPhone Android Touch")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("iPhone Android Touch")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .build()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        monitorExecutor.shutdownNow()
        closeSocket()
        try { hid?.unregisterApp() } catch (_: Exception) { }
        try { hid?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) } } catch (_: Exception) { }
        hidExecutor.shutdownNow()
        netExecutor.shutdownNow()
        hid = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_REGISTER_HID = "com.example.iphonebridge.REGISTER_HID"
        const val ACTION_CONNECT_PC = "com.example.iphonebridge.CONNECT_PC"
        const val ACTION_STOP_PC = "com.example.iphonebridge.STOP_PC"
        const val ACTION_TAP_ABSOLUTE = "com.example.iphonebridge.TAP_ABSOLUTE"
        const val ACTION_KEY = "com.example.iphonebridge.KEY"
        const val ACTION_KEY_CODE = "com.example.iphonebridge.KEY_CODE"
        const val ACTION_STATUS = "com.example.iphonebridge.STATUS"
        const val EXTRA_IP = "ip"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_X = "x"
        const val EXTRA_Y = "y"
        const val EXTRA_KEY = "key"
        const val EXTRA_USAGE = "usage"
        const val EXTRA_MODIFIER = "modifier"
        private const val CHANNEL_ID = "iphone_bridge_hid"
    }
}
