package com.example.android_obd2_meters

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import android.Manifest
import android.bluetooth.BluetoothManager
import android.os.Build
import android.util.Log

class MainActivity : AppCompatActivity() {
    private val obdUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
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

        // 🌙 システムのダークモード設定に従う
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

        setContentView(R.layout.activity_main)

        // UI の参照
        themeStatusView = findViewById(R.id.theme_status)
        oilTempView = findViewById(R.id.oil_temp)
        coolantTempView = findViewById(R.id.coolant_temp)
        torqueView = findViewById(R.id.torque)
        powerView = findViewById(R.id.power)

        // ダークモードかどうかを `AppCompatDelegate.getDefaultNightMode()` で判定
        themeStatusView.text = when (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> "Dark mode applied"
            else -> "Light mode applied"
        }

        checkBluetoothPermission()
        connectToOBD()
    }


    private fun checkBluetoothPermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            Manifest.permission.BLUETOOTH
        }

        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(permission), 1)
        }
    }


    private fun connectToOBD() {
//        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
        val device: BluetoothDevice? = bluetoothAdapter?.bondedDevices?.find { it.name.contains("OBD") } ?: run {
            Log.e("Bluetooth", "Not found OBD device")
            null
        }


        if (device != null) {
            Thread {
                while (bluetoothSocket == null) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12 (API 31) 以上
                            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                                == PackageManager.PERMISSION_GRANTED) {

                                bluetoothSocket = device.createRfcommSocketToServiceRecord(obdUuid)
                                bluetoothSocket?.connect()
                                outputStream = bluetoothSocket?.outputStream
                                inputStream = bluetoothSocket?.inputStream
                                startReadingOBD()
                            } else {
                                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
                            }
                        } else { // Android 11 (API 30) 以下
                            bluetoothSocket = device.createRfcommSocketToServiceRecord(obdUuid)
                            bluetoothSocket?.connect()
                            outputStream = bluetoothSocket?.outputStream
                            inputStream = bluetoothSocket?.inputStream
                            startReadingOBD()
                        }
                    } catch (e: SecurityException) {
                        e.printStackTrace()
                        bluetoothSocket = null
                        Thread.sleep(5000) // 再接続
                    } catch (e: IOException) {
                        e.printStackTrace()
                        bluetoothSocket = null
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
                        oilTempView.text = getString(R.string.oil_temp, oilTemp)
                        coolantTempView.text = getString(R.string.coolant_temp, coolantTemp)
                        torqueView.text = getString(R.string.torque, torque)
                        powerView.text = getString(R.string.power, power)

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
