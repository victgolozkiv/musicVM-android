package com.musicplayer.manager

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.*

class CastManager private constructor(context: Context) {
    private val context: Context = context.applicationContext
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    val discoveredDevices = MutableLiveData<List<CastDevice>>(emptyList())
    private var isScanning = false

    data class CastDevice(
        var name: String,
        var address: String,
        var type: String, // "DLNA" or "Bluetooth"
        var btDevice: BluetoothDevice? = null
    )

    companion object {
        private const val TAG = "CastManager"
        @Volatile
        private var instance: CastManager? = null

        @JvmStatic
        fun getInstance(context: Context): CastManager {
            return instance ?: synchronized(this) {
                instance ?: CastManager(context).also { instance = it }
            }
        }
    }

    fun startScan() {
        if (isScanning) return
        isScanning = true
        discoveredDevices.postValue(emptyList())
        
        coroutineScope.launch {
            // 1. ESCANEO SSDP (Wi-Fi)
            launch { scanSSDP() }
            
            // 2. ESCANEO BLUETOOTH (Echo Pop Shortcut)
            launch { scanBluetooth() }
        }
    }

    private suspend fun scanSSDP() {
        var lock: WifiManager.MulticastLock? = null
        try {
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifi != null) {
                lock = wifi.createMulticastLock("SSDP_LOCK")
                lock.setReferenceCounted(true)
                lock.acquire()
            }

            val socket = DatagramSocket()
            socket.soTimeout = 3000
            
            val query = "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\n" +
                    "ST: ssdp:all\r\n" + 
                    "\r\n"
            
            val sendData = query.toByteArray()
            val sendPacket = DatagramPacket(sendData, sendData.size, 
                    InetAddress.getByName("239.255.255.250"), 1900)
            
            withContext(Dispatchers.IO) {
                socket.send(sendPacket)
            }
            Log.d(TAG, "SSDP: Búsqueda iniciada...")

            val receiveData = ByteArray(1024)
            val endTime = System.currentTimeMillis() + 5000
            
            val seen = mutableSetOf<String>()
            while (System.currentTimeMillis() < endTime) {
                try {
                    val receivePacket = DatagramPacket(receiveData, receiveData.size)
                    withContext(Dispatchers.IO) {
                        socket.receive(receivePacket)
                    }
                    val address = receivePacket.address.hostAddress ?: continue
                    
                    if (!seen.contains(address)) {
                        seen.add(address)
                        Log.d(TAG, "SSDP: Encontrado -> $address")
                        addDevice(CastDevice("Smart Device ($address)", address, "Wi-Fi / DLNA"))
                    }
                } catch (ignored: Exception) {}
            }
            socket.close()
        } catch (e: Exception) {
            Log.e(TAG, "SSDP: Error en escaneo", e)
        } finally {
            if (lock?.isHeld == true) lock.release()
            isScanning = false
        }
    }

    private fun scanBluetooth() {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null || !adapter.isEnabled) return

            val pairedDevices = adapter.bondedDevices
            if (pairedDevices == null) return

            for (device in pairedDevices) {
                var name = device.name
                if (name == null) name = "Dispositivo Desconocido"
                
                val cd = CastDevice(name, device.address, "Bluetooth")
                cd.btDevice = device
                addDevice(cd)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "BT: Error de permisos", e)
        }
    }

    @Synchronized
    private fun addDevice(device: CastDevice) {
        val current = discoveredDevices.value ?: emptyList()
        val newList = current.toMutableList()
        
        if (newList.any { it.address == device.address }) return
        
        newList.add(device)
        discoveredDevices.postValue(newList)
    }
}
