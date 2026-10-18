package com.example.iphonebridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Android-only controller. No PC connection is used.
 * AirPlay receiver displays the iPhone screen; ControlOverlayActivity sits on top
 * and turns taps on that picture into Bluetooth HID mouse clicks.
 */
class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        makeUi()
        requestBluetoothPermissionsThenStartService()
    }

    private fun requestBluetoothPermissionsThenStartService() {
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                missing += Manifest.permission.BLUETOOTH_CONNECT
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED)
                missing += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            missing += Manifest.permission.POST_NOTIFICATIONS
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 100)
        else startHid()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 100) startHid()
    }

    private fun startHid() {
        val i = Intent(this, HidBridgeService::class.java).apply { action = HidBridgeService.ACTION_REGISTER_HID }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        status.text = "HID запускается…"
    }

    private fun openController() {
        startActivity(Intent(this, ControlOverlayActivity::class.java))
    }

    private fun openAirPlay() {
        val candidates = listOf("io.github.jqssun.airplay", "io.github.prat3ik.imirror")
        for (pkg in candidates) {
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                startActivity(launch)
                window.decorView.postDelayed({ openController() }, 1000)
                return
            }
        }
        status.text = "AirPlay Receiver не найден. Открой установленный AirPlay Server вручную."
    }

    private fun makeUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
        }
        root.addView(TextView(this).apply {
            text = "iPhone → Android Touch Control"
            textSize = 24f
        })
        status = TextView(this).apply {
            text = "Готово"
            textSize = 16f
            setPadding(0, 18, 0, 18)
        }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "AirPlay + управление"
            setOnClickListener { openAirPlay() }
        })
        root.addView(Button(this).apply {
            text = "Открыть управление"
            setOnClickListener { openController() }
        })
        root.addView(Button(this).apply {
            text = "Зарегистрировать Bluetooth HID"
            setOnClickListener { startHid() }
        })
        root.addView(Button(this).apply {
            text = "Настройки Bluetooth"
            setOnClickListener { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        })
        root.addView(TextView(this).apply {
            text = "\n1. На iPhone включи AssistiveTouch.\n2. Подключи Android к iPhone как Bluetooth-мышь.\n3. Запусти AirPlay Server.\n4. Нажимай пальцем прямо по картинке iPhone — Android отправит клик в эту область.\n\nПК не используется."
            textSize = 15f
        })
        setContentView(root)
    }

    companion object {
        val REPORT_DESCRIPTOR = intArrayOf(
            0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, 0x01,
            0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00,
            0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x08, 0x81, 0x01, 0x95, 0x05,
            0x75, 0x01, 0x05, 0x08, 0x19, 0x01, 0x29, 0x05,
            0x91, 0x02, 0x95, 0x01, 0x75, 0x03, 0x91, 0x01,
            0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
            0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
            0xC0, 0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85,
            0x02, 0x09, 0x01, 0xA1, 0x00, 0x05, 0x09, 0x19,
            0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95,
            0x03, 0x75, 0x01, 0x81, 0x02, 0x95, 0x01, 0x75,
            0x05, 0x81, 0x01, 0x05, 0x01, 0x09, 0x30, 0x09,
            0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75,
            0x08, 0x95, 0x03, 0x81, 0x06, 0xC0, 0xC0
        ).map { it.toByte() }.toByteArray()
    }
}
