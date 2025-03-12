package com.example.android_obd2_meters

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*

class MainActivity : AppCompatActivity() {
    private val OBD_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null

    private lateinit var themeStatusView: TextView
    private lateinit var oilTempView: TextView
    private lateinit var coolantTempView: TextView
    private lateinit var torqueView: TextView
    private lateinit var powerView: TextView

    private var maxTorque = 380.0
    private var maxPower = 310.0

    private val handler = Handler(Looper.getMainLooper())
    private val obdHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 🌙 ダークモード設定
        val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        when (nightModeFlags) {
            Configuration.UI_MODE_NIGHT_YES -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_UNDEFINED -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }

        setContentView(R.layout.activity_main)

        // UI の参照
        themeStatusView = findViewById(R.id.theme_status)
        oilTempView = findViewById(R.id.oil_temp)
        coolantTempView = findViewById(R.id.coolant_temp)
        torqueView = findViewById(R.id.torque)
        powerView = findViewById(R.id.power)

        themeStatusView.text = if (nightModeFlags == Configuration.UI_MODE_NIGHT_YES) "ダークモード適用中" else "ライトモード適用中"

        connectToOBD()
    }

    private fun connectToOBD() {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        val device: BluetoothDevice? = bluetoothAdapter.bondedDevices.find { it.name.contains("OBD") }

        if (device != null) {
            Thread {
                while (bluetoothSocket == null) {
                    try {
                        bluetoothSocket = device.createRfcommSocketToServiceRecord(OBD_UUID)
                        bluetoothSocket?.connect()
                        outputStream = bluetoothSocket?.outputStream
                        inputStream = bluetoothSocket?.inputStream
                        startReadingOBD()
                    } catch (e: IOException) {
                        bluetoothSocket = null
                        e.printStackTrace()
                        Thread.sleep(5000) // 再接続
                    }
                }
            }.start()
        }
    }

    private fun startReadingOBD() {
        obdHandler.post(object : Runnable {
            override fun run() {
                try {
                    val oilTemp = sendCommand("01 5C")
                    val coolantTemp = sendCommand("01 05")
                    val engineLoad = sendCommand("01 04")
                    val rpm = sendCommand("01 0C")

                    val torque = if (engineLoad > 0) (engineLoad / 100.0) * maxTorque else 0.0
                    val power = if (rpm > 0 && torque > 0) (rpm * torque) / 5252 else 0.0

                    if (torque > maxTorque) maxTorque = torque
                    if (power > maxPower) maxPower = power

                    handler.post {
                        oilTempView.text = "油温: ${oilTemp}°C"
                        coolantTempView.text = "水温: ${coolantTemp}°C"
                        torqueView.text = "トルク: ${String.format("%.1f", torque)} Nm"
                        powerView.text = "馬力: ${String.format("%.1f", power)} PS"
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                obdHandler.postDelayed(this, 100)
            }
        })
    }

    private fun sendCommand(command: String): Int {
        return try {
            outputStream?.write((command + "\r").toByteArray())
            outputStream?.flush()
            val buffer = ByteArray(1024)
            val bytesRead = inputStream?.read(buffer) ?: 0
            val response = String(buffer, 0, bytesRead)
            parseOBDResponse(response)
        } catch (e: IOException) {
            e.printStackTrace()
            -1
        }
    }

    private fun parseOBDResponse(response: String): Int {
        return try {
            val hexValue = response.split(" ").last().trim()
            Integer.parseInt(hexValue, 16) - 40
        } catch (e: Exception) {
            -1
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        obdHandler.removeCallbacksAndMessages(null)
        bluetoothSocket?.close()
    }
}
