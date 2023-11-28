package com.example.bluetooth_motor

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.core.app.ActivityCompat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {

    private val con = 1
    private val reCode = 1
    private var connectedThread: ConnectedThread? = null
    private var connectThread: ConnectThread? = null
    private val esp32 = "esp32"
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        //BLEがサポートされているか
        //BLEがサポートされていないときの処理
        if (bluetoothAdapter == null) {
            Toast.makeText(applicationContext, "Bluetoothがサポートされていません", Toast.LENGTH_SHORT).show()
            //終了する
            finish()
            return
        }

        //BLEが有効になっているか
        //BLEが有効でない場合の処理
        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)

            //BLUETOOTH_CONNECTが許可されていない場合の処理
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), reCode)
                }
            }
            startActivityForResult(enableBtIntent, con)
        }

        //ペア設定済みの端末の問い合わせ処理
        val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter.bondedDevices
        pairedDevices?.forEach { device ->
            val deviceName = device.name
            val deviceHardwareAddress = device.address // MAC address
            if (device.name == esp32) {
                Log.d(
                    ContentValues.TAG,
                    "name = %s, MAC <%s>".format(deviceName, deviceHardwareAddress)
                )
                device.uuids.forEach { uuid ->
                    Log.d(ContentValues.TAG, "uuid is %s".format(uuid.uuid))
                }
                //デバイスの接続
                connectThread = ConnectThread(device)
                connectThread?.start()
                return
            }
        }

    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            reCode -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // パーミッションが許可された場合の処理
                    Toast.makeText(applicationContext, "許可しました", Toast.LENGTH_SHORT).show()
                } else {
                    // パーミッションが拒否された場合の処理
                    Toast.makeText(applicationContext, "拒否しました", Toast.LENGTH_SHORT).show()
                }
                return
            }
        }
    }

    @Deprecated("Deprecated in Java")
    //startActivityForResultの結果を受け取る
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (resultCode) {
            //BLE接続「許可」が押された場合の処理
            RESULT_OK -> {
                Toast.makeText(applicationContext, "接続しました", Toast.LENGTH_SHORT).show()
            }
            //BLE接続「許可しない」が押された場合の処理
            RESULT_CANCELED -> {
                Toast.makeText(applicationContext, "未接続です", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private inner class ConnectThread(device: BluetoothDevice) : Thread() {
        //UUIDを取得しソケットに入れる
        val mmSocket: BluetoothSocket? =
            device.createRfcommSocketToServiceRecord(device.uuids[0].uuid)

        override fun run() {
            try {
                //デバイス検出をキャンセル
                bluetoothAdapter?.cancelDiscovery()
                val socket = mmSocket
                socket ?: return
                socket.connect()
                manageMyConnectedSocket(socket)
            } catch (e: IOException) {
                try {
                    mmSocket?.close()
                } catch (e: IOException) {
                    Log.e(ContentValues.TAG, "Could not close the connect socket", e)
                }
            }

        }

    }

    fun manageMyConnectedSocket(socket: BluetoothSocket) {
        //ソケットの接続
        connectedThread = ConnectedThread(socket)
        connectedThread?.start()
    }

    private inner class ConnectedThread(mmSocket: BluetoothSocket) : Thread() {
        private val mmInStream: InputStream = mmSocket.inputStream
        private val mmOutStream: OutputStream = mmSocket.outputStream
        private val mmBuffer: ByteArray = ByteArray(1024)

        val charset = Charsets.UTF_8
        var i = 0
        val array: ArrayList<Byte> = arrayListOf(0)
        val bt: Button = findViewById(R.id.bt)//(R.id.bt)のbtを必要に応じて変更
        val wifi: EditText = findViewById(R.id.wifi)//(R.id.wifi)のwifiを必要に応じて変更

        @SuppressLint("SuspiciousIndentation")
        override fun run() {
            //ボタンが押された時の処理
            bt.setOnClickListener {
                //入力データを変数に代入
                val angle = wifi.text.toString()
                //JSON形式のデータを変数に代入
                val json = "{\"motor\":\"$angle\"}"
                //esp32にデータを送信
                write(json.toByteArray(charset))
            }

            while (true) {
                //tryは失敗するかもしれないときに使う
                //catchはエラーが発生したときの処理
                try {
                    //esp32からのデータを受信
                    mmInStream.read(mmBuffer)
                    //esp32から"/"が送られてくるまで繰り返す
                    while (mmBuffer[i].toInt() != 47) {
                        array[i] = mmBuffer[i]
                        array.add(0)
                        i++
                    }
                    array.remove(0)
                    //array配列に入れたデータを文字列にする
                    val espData = String(array.toByteArray(), Charsets.UTF_8)
                    Log.d("array", espData)
                    //array配列を空にする
                    array.clear()
                    array.add(0)
                    i = 0

                } catch (e: IOException) {
                    Log.d(ContentValues.TAG, "Input stream was disconnected", e)
                    break
                }

            }

        }

        //esp32にデータを送るための処理
        fun write(bytes: ByteArray) {
            try {
                mmOutStream.write(bytes)
            } catch (e: IOException) {
                Log.e(ContentValues.TAG, "Error occurred when sending data", e)
            }
            return
        }
    }
}