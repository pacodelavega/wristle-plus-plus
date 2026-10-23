package io.github.unknownprocess.wristle.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import io.github.unknownprocess.wristle.config.WristleConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// @keep(pacodelavega): Coroutine-based TCP/UDP network client for Wristle protocol with HMAC-SHA256 authentication

sealed class ConnectionState {
    object Idle : ConnectionState()
    object NoWifi : ConnectionState()
    object Searching : ConnectionState()
    data class Retrying(val secondsLeft: Int) : ConnectionState()
    object Paused : ConnectionState()
    object Connecting : ConnectionState()
    data class Pairing(val code: String) : ConnectionState()
    data class Ready(val pcName: String) : ConnectionState()
    data class Listening(val pcName: String) : ConnectionState()
    object Sending : ConnectionState()
    object Sent : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

class WristleClient(
    private val context: Context,
    private val deviceId: String
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences(WristleConfig.Preferences.PrefsName, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _offlineQueueCount = MutableStateFlow(0)
    val offlineQueueCount: StateFlow<Int> = _offlineQueueCount.asStateFlow()

    private var socket: Socket? = null
    private var output: DataOutputStream? = null
    private var input: DataInputStream? = null

    private var activePcName: String = "PC"
    private var activeHost: String? = null
    private var isApproved = false

    private val _autoRetryService = MutableStateFlow(
        prefs.getBoolean(WristleConfig.Preferences.KeyAutoRetryService, true)
    )
    val autoRetryService: StateFlow<Boolean> = _autoRetryService.asStateFlow()

    private val _serviceTimeoutSec = MutableStateFlow(
        prefs.getInt(WristleConfig.Preferences.KeyServiceTimeoutSec, WristleConfig.Timing.DefaultServiceTimeoutSec)
    )
    val serviceTimeoutSec: StateFlow<Int> = _serviceTimeoutSec.asStateFlow()

    private val _autoRetryWifi = MutableStateFlow(
        prefs.getBoolean(WristleConfig.Preferences.KeyAutoRetryWifi, false)
    )
    val autoRetryWifi: StateFlow<Boolean> = _autoRetryWifi.asStateFlow()

    private val _wifiTimeoutSec = MutableStateFlow(
        prefs.getInt(WristleConfig.Preferences.KeyWifiTimeoutSec, WristleConfig.Timing.DefaultWifiTimeoutSec)
    )
    val wifiTimeoutSec: StateFlow<Int> = _wifiTimeoutSec.asStateFlow()

    private val _isRetryPaused = MutableStateFlow(false)
    val isRetryPaused: StateFlow<Boolean> = _isRetryPaused.asStateFlow()

    private var connectJob: Job? = null
    private var retryJob: Job? = null

    private val writeLock = Any()
    private val offlineQueue = mutableListOf<String>()

    init {
        loadOfflineQueue()
        registerNetworkCallback()
    }

    private fun loadOfflineQueue() {
        val raw = prefs.getString(io.github.unknownprocess.wristle.config.WristleConfig.Preferences.KeyOfflineQueue, null) ?: run {
            _offlineQueueCount.value = 0
            return
        }
        try {
            val arr = org.json.JSONArray(raw)
            synchronized(offlineQueue) {
                offlineQueue.clear()
                for (i in 0 until arr.length()) {
                    val s = arr.getString(i)
                    if (s.isNotBlank()) offlineQueue.add(s)
                }
                _offlineQueueCount.value = offlineQueue.size
            }
        } catch (_: Exception) {
            _offlineQueueCount.value = 0
        }
    }

    private fun saveOfflineQueue() {
        val arr = org.json.JSONArray(offlineQueue)
        prefs.edit().putString(io.github.unknownprocess.wristle.config.WristleConfig.Preferences.KeyOfflineQueue, arr.toString()).apply()
        _offlineQueueCount.value = offlineQueue.size
    }

    private fun clearSavedOfflineQueue() {
        prefs.edit().remove(io.github.unknownprocess.wristle.config.WristleConfig.Preferences.KeyOfflineQueue).apply()
        _offlineQueueCount.value = 0
    }

    fun getOfflineQueueCount(): Int = synchronized(offlineQueue) {
        val count = offlineQueue.size
        _offlineQueueCount.value = count
        count
    }

    fun refreshOfflineQueueCount() {
        loadOfflineQueue()
    }

    fun isWifiAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun registerNetworkCallback() {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (_state.value is ConnectionState.NoWifi) {
                        connect()
                    }
                }
                override fun onLost(network: Network) {
                    if (!isWifiAvailable()) {
                        retryJob?.cancel()
                        if (_state.value !is ConnectionState.Ready && _state.value !is ConnectionState.Listening) {
                            _state.value = ConnectionState.NoWifi
                        }
                    }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    fun connect() {
        connectJob?.cancel()
        retryJob?.cancel()

        connectJob = scope.launch {
            if (_state.value is ConnectionState.Ready || _state.value is ConnectionState.Listening) {
                return@launch
            }

            if (!isWifiAvailable()) {
                if (_autoRetryWifi.value && !_isRetryPaused.value) {
                    scheduleWifiRetry()
                } else {
                    _state.value = ConnectionState.NoWifi
                }
                return@launch
            }

            _state.value = ConnectionState.Searching
            val discoveryResult = discoverServer()
            if (discoveryResult == null) {
                if (_autoRetryService.value && !_isRetryPaused.value) {
                    scheduleServiceRetry()
                } else {
                    _state.value = ConnectionState.Paused
                }
                return@launch
            }

            val (host, port, pcName) = discoveryResult
            activeHost = host
            activePcName = pcName
            _state.value = ConnectionState.Connecting

            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 4000)
                s.tcpNoDelay = true

                socket = s
                output = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 8192))
                input = DataInputStream(s.getInputStream())

                launch { readLoop(s, input!!) }
                sendHello()
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed to $host:$port", e)
                disconnect()
                if (_autoRetryService.value && !_isRetryPaused.value) {
                    scheduleServiceRetry()
                } else {
                    _state.value = ConnectionState.Paused
                }
            }
        }
    }

    private fun scheduleServiceRetry() {
        retryJob?.cancel()
        retryJob = scope.launch {
            if (!isWifiAvailable()) {
                if (_autoRetryWifi.value && !_isRetryPaused.value) {
                    scheduleWifiRetry()
                } else {
                    _state.value = ConnectionState.NoWifi
                }
                return@launch
            }

            val timeoutSec = _serviceTimeoutSec.value
            for (sec in timeoutSec downTo 1) {
                if (_isRetryPaused.value) {
                    _state.value = ConnectionState.Paused
                    return@launch
                }
                if (!isWifiAvailable()) {
                    if (_autoRetryWifi.value && !_isRetryPaused.value) {
                        scheduleWifiRetry()
                    } else {
                        _state.value = ConnectionState.NoWifi
                    }
                    return@launch
                }
                _state.value = ConnectionState.Retrying(sec)
                delay(WristleConfig.Timing.ReconnectCountdownStepMs)
            }
            if (!_isRetryPaused.value) {
                connect()
            }
        }
    }

    private fun scheduleWifiRetry() {
        retryJob?.cancel()
        retryJob = scope.launch {
            val timeoutSec = _wifiTimeoutSec.value
            for (sec in timeoutSec downTo 1) {
                if (_isRetryPaused.value) {
                    _state.value = ConnectionState.NoWifi
                    return@launch
                }
                if (isWifiAvailable()) {
                    connect()
                    return@launch
                }
                _state.value = ConnectionState.NoWifi
                delay(WristleConfig.Timing.ReconnectCountdownStepMs)
            }
            if (!_isRetryPaused.value) {
                connect()
            }
        }
    }

    fun pauseRetry() {
        _isRetryPaused.value = true
        retryJob?.cancel()
        connectJob?.cancel()
        _state.value = ConnectionState.Paused
    }

    fun resumeRetry() {
        _isRetryPaused.value = false
        connect()
    }

    fun toggleRetry() {
        if (_isRetryPaused.value || _state.value is ConnectionState.Paused) {
            resumeRetry()
        } else {
            pauseRetry()
        }
    }

    fun setAutoRetryService(enabled: Boolean) {
        prefs.edit().putBoolean(WristleConfig.Preferences.KeyAutoRetryService, enabled).apply()
        _autoRetryService.value = enabled
        if (!enabled && (_state.value is ConnectionState.Retrying || _state.value is ConnectionState.Searching)) {
            pauseRetry()
        } else if (enabled && _state.value is ConnectionState.Paused) {
            resumeRetry()
        }
    }

    fun setServiceTimeoutSec(sec: Int) {
        val clamped = sec.coerceIn(WristleConfig.Timing.MinTimeoutSec, WristleConfig.Timing.MaxTimeoutSec)
        prefs.edit().putInt(WristleConfig.Preferences.KeyServiceTimeoutSec, clamped).apply()
        _serviceTimeoutSec.value = clamped
    }

    fun setAutoRetryWifi(enabled: Boolean) {
        prefs.edit().putBoolean(WristleConfig.Preferences.KeyAutoRetryWifi, enabled).apply()
        _autoRetryWifi.value = enabled
    }

    fun setWifiTimeoutSec(sec: Int) {
        val clamped = sec.coerceIn(WristleConfig.Timing.MinTimeoutSec, WristleConfig.Timing.MaxTimeoutSec)
        prefs.edit().putInt(WristleConfig.Preferences.KeyWifiTimeoutSec, clamped).apply()
        _wifiTimeoutSec.value = clamped
    }

    private suspend fun discoverServer(): Triple<String, Int, String>? = withContext(Dispatchers.IO) {
        var ds: DatagramSocket? = null
        try {
            ds = DatagramSocket().apply {
                broadcast = true
                soTimeout = 1200
            }
            val probe = "WRISTLE?".toByteArray(Charsets.UTF_8)
            val bcast = InetAddress.getByName("255.255.255.255")

            for (attempt in 1..3) {
                try {
                    ds.send(DatagramPacket(probe, probe.size, bcast, UDP_PORT))
                    val buf = ByteArray(256)
                    val reply = DatagramPacket(buf, buf.size)
                    ds.receive(reply)

                    val text = String(reply.data, 0, reply.length, Charsets.UTF_8)
                    if (text.startsWith("WRISTLE!")) {
                        val parts = text.split(" ", limit = 3)
                        val port = if (parts.size > 1) parts[1].toIntOrNull() ?: TCP_PORT else TCP_PORT
                        val name = if (parts.size > 2) parts[2] else "PC"
                        return@withContext Triple(reply.address.hostAddress ?: "127.0.0.1", port, name)
                    }
                } catch (ignored: IOException) { }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Discovery error", e)
        } finally {
            ds?.close()
        }
        null
    }

    private suspend fun readLoop(s: Socket, inStream: DataInputStream) = withContext(Dispatchers.IO) {
        try {
            while (isActive && socket == s) {
                val type = inStream.readUnsignedByte()
                val len = inStream.readInt()
                if (len < 0 || len > 65536) throw IOException("Bad frame length: $len")

                val payload = ByteArray(len)
                inStream.readFully(payload)

                if (type == 'O'.code) {
                    handleStatus(JSONObject(String(payload, Charsets.UTF_8)))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Read loop terminated: ${e.message}")
            disconnect()
            if (_state.value !is ConnectionState.Error) {
                scheduleServiceRetry()
            }
        }
    }

    private fun handleStatus(st: JSONObject) {
        val stateStr = st.optString("state")
        val host = st.optString("host", activePcName)
        if (host.isNotEmpty()) activePcName = host

        when (stateStr) {
            "auth" -> {
                val nonce = st.optString("nonce")
                val key = prefs.getString("key:$host", null)
                val proof = if (key != null) computeAuthProof(key, nonce, deviceId) else ""
                sendFrame('U', proof.toByteArray(Charsets.UTF_8))
            }
            "ok" -> {
                val key = st.optString("key", "")
                if (key.isNotEmpty()) {
                    prefs.edit().putString("key:$host", key).apply()
                }
                isApproved = true
                _state.value = ConnectionState.Ready(activePcName)
                flushOfflineQueue()
            }
            "pending" -> {
                isApproved = false
                val code = st.optString("code", "----")
                _state.value = ConnectionState.Pairing(code)
            }
            "done" -> {
                _state.value = ConnectionState.Sent
                scope.launch {
                    delay(1500)
                    if (_state.value is ConnectionState.Sent) {
                        _state.value = ConnectionState.Ready(activePcName)
                    }
                }
            }
            "denied" -> {
                isApproved = false
                _state.value = ConnectionState.Error("Pairing was denied")
                disconnect()
            }
        }
    }

    fun startDictation() {
        if (!isApproved) return
        if (_state.value is ConnectionState.Ready) {
            _state.value = ConnectionState.Listening(activePcName)
        }
    }

    fun finishDictation() {
        if (_state.value is ConnectionState.Listening) {
            _state.value = ConnectionState.Ready(activePcName)
        }
    }

    fun cancelDictation() {
        if (_state.value is ConnectionState.Listening) {
            _state.value = ConnectionState.Ready(activePcName)
        }
    }

    fun sendText(text: String, isPartial: Boolean) {
        if (text.isBlank()) return

        val stateVal = _state.value
        val isConnected = (stateVal is ConnectionState.Ready ||
                           stateVal is ConnectionState.Listening ||
                           stateVal is ConnectionState.Sending ||
                           stateVal is ConnectionState.Sent) &&
                          socket?.isConnected == true

        if (!isConnected) {
            if (!isPartial) {
                synchronized(offlineQueue) {
                    offlineQueue.add(text)
                    saveOfflineQueue()
                }
                Log.i(TAG, "Offline: text queued for PC delivery (${offlineQueue.size} in queue)")
            }
            return
        }

        val frameType = if (isPartial) 't' else 'T'
        if (!isPartial) {
            _state.value = ConnectionState.Sending
        }
        sendFrame(frameType, text.toByteArray(Charsets.UTF_8))
    }

    private fun flushOfflineQueue() {
        scope.launch(Dispatchers.IO) {
            while (true) {
                val text = synchronized(offlineQueue) {
                    offlineQueue.firstOrNull()
                } ?: break

                val out = output ?: break
                try {
                    synchronized(writeLock) {
                        val payload = text.toByteArray(Charsets.UTF_8)
                        out.writeByte('T'.code)
                        out.writeInt(payload.size)
                        out.write(payload)
                        out.flush()
                    }
                    synchronized(offlineQueue) {
                        if (offlineQueue.isNotEmpty()) {
                            offlineQueue.removeAt(0)
                            saveOfflineQueue()
                        }
                    }
                    delay(io.github.unknownprocess.wristle.config.WristleConfig.Timing.OfflineFlushDelayMs)
                } catch (e: Exception) {
                    Log.e(TAG, "Error flushing offline queue item", e)
                    disconnect()
                    break
                }
            }
            Log.i(TAG, "Offline queue flush complete, remaining: ${offlineQueue.size}")
        }
    }

    private fun sendHello() {
        val hello = JSONObject().apply {
            put("id", deviceId)
            put("name", Build.MODEL)
            put("v", PROTOCOL_VERSION)
        }
        sendFrame('H', hello.toString().toByteArray(Charsets.UTF_8))
    }

    private fun sendFrame(type: Char, payload: ByteArray?) {
        scope.launch(Dispatchers.IO) {
            val out = output ?: return@launch
            try {
                synchronized(writeLock) {
                    out.writeByte(type.code)
                    out.writeInt(payload?.size ?: 0)
                    if (payload != null && payload.isNotEmpty()) {
                        out.write(payload)
                    }
                    out.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Send frame $type error", e)
                disconnect()
            }
        }
    }

    fun getKeyForCurrentHost(): ByteArray? {
        val keyHex = prefs.getString("key:$activePcName", null) ?: return null
        return hexToBytes(keyHex)
    }

    fun disconnect() {
        retryJob?.cancel()
        try {
            socket?.close()
        } catch (ignored: Exception) { }
        socket = null
        output = null
        input = null
        isApproved = false
    }

    private fun computeAuthProof(keyHex: String, nonce: String, id: String): String {
        val key = hexToBytes(keyHex)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val digest = mac.doFinal("wristle-auth|$nonce|$id".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { String.format(Locale.US, "%02x", it) }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val bytes = ByteArray(hex.length / 2)
        for (i in bytes.indices) {
            bytes[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return bytes
    }

    companion object {
        private const val TAG = "WristleClient"
        const val TCP_PORT = io.github.unknownprocess.wristle.config.WristleConfig.Network.TcpPort
        const val UDP_PORT = io.github.unknownprocess.wristle.config.WristleConfig.Network.UdpPort
        const val PROTOCOL_VERSION = io.github.unknownprocess.wristle.config.WristleConfig.Network.ProtocolVersion
    }
}
