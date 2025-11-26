package com.example.intercom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.intercom.audio.AudioEngine
import com.example.intercom.network.NetworkUtils
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class IntercomService : Service() {

    private var currentMode: String? = null
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private var runningTask: Future<*>? = null
    private val audioEngine = AudioEngine()
    @Volatile
    private var audioSession: AudioSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val mode = intent.getStringExtra(EXTRA_MODE)
                if (mode != null && mode != currentMode) {
                    currentMode = mode
                    restart(mode)
                }
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCurrentWork()
        executor.shutdownNow()
    }

    private fun restart(mode: String) {
        stopCurrentWork()
        startForegroundService()
        runningTask = when (mode) {
            MODE_SERVER -> executor.submit { runServer() }
            MODE_CLIENT -> executor.submit { runClient() }
            else -> null
        }
    }

    private fun stopCurrentWork() {
        runningTask?.cancel(true)
        audioSession?.close()
        audioSession = null
        audioEngine.stop()
    }

    private fun startForegroundService() {
        createChannel()
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Intercom")
            .setContentText("Voice link running")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Intercom", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun sendStatus(status: String) {
        Log.d(TAG, status)
        val intent = Intent(ACTION_STATUS).apply { putExtra(EXTRA_STATUS, status) }
        sendBroadcast(intent)
    }

    private fun configureAudioRouting() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = false
        try {
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
        } catch (t: Throwable) {
            Log.w(TAG, "Bluetooth SCO unavailable: ${t.message}")
        }
    }

    private fun runServer() {
        configureAudioRouting()
        while (!Thread.currentThread().isInterrupted) {
            var serverSocket: ServerSocket? = null
            var discoverySocket: DatagramSocket? = null
            try {
                val bindAddress = NetworkUtils.getLocalIpAddress(this)
                val listenAddress = InetAddress.getByName("0.0.0.0")
                val statusAddress = bindAddress ?: listenAddress
                sendStatus("Server on ${statusAddress.hostAddress}:$CONTROL_PORT – waiting for client")
                serverSocket = ServerSocket()
                serverSocket.reuseAddress = true
                serverSocket.bind(InetSocketAddress(listenAddress, CONTROL_PORT))
                serverSocket.soTimeout = 1000

                discoverySocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(listenAddress, DISCOVERY_PORT))
                    broadcast = true
                    soTimeout = 1000
                }

                while (!Thread.currentThread().isInterrupted) {
                    respondToDiscovery(discoverySocket)
                    val clientSocket = tryAccept(serverSocket) ?: continue
                    clientSocket.use { socket ->
                        val hello = ByteArray(16)
                        val read = socket.getInputStream().read(hello)
                        if (read <= 0 || !String(hello, 0, read).startsWith("HELLO")) {
                            sendStatus("Handshake failed")
                            return@use
                        }
                        val clientAddress = socket.inetAddress
                        sendStatus("Connected to ${clientAddress.hostAddress}")
                        startAudioSession(clientAddress)
                        sendStatus("Connection ended. Waiting for client…")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Server reconnect: ${e.message}", e)
                sendStatus("Server reconnecting… ${e.message}")
                sleepQuietly(1200)
            } finally {
                discoverySocket?.close()
                serverSocket?.close()
            }
        }
    }

    private fun runClient() {
        configureAudioRouting()
        while (!Thread.currentThread().isInterrupted) {
            try {
                val serverInfo = discoverServer()
                if (serverInfo == null) {
                    sendStatus("No server found. Retrying…")
                    sleepQuietly(1500)
                    continue
                }
                val serverAddress = serverInfo.first
                val controlPort = serverInfo.second
                sendStatus("Connecting to ${serverAddress.hostAddress}:$controlPort")
                var connected = false
                repeat(3) { attempt ->
                    try {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress(serverAddress, controlPort), 3000)
                            socket.getOutputStream().write("HELLO".toByteArray())
                        }
                        connected = true
                        return@repeat
                    } catch (connectError: Exception) {
                        Log.w(TAG, "Client connect attempt ${attempt + 1} failed", connectError)
                        sleepQuietly(500)
                    }
                }
                if (!connected) {
                    sendStatus("Connection failed. Retrying…")
                    sleepQuietly(1500)
                    continue
                }
                sendStatus("Connected to ${serverAddress.hostAddress}")
                startAudioSession(serverAddress)
                sendStatus("Connection ended. Reconnecting…")
                sleepQuietly(1200)
            } catch (e: Exception) {
                Log.w(TAG, "Client reconnect due to ${e.message}", e)
                sendStatus("Client reconnecting… ${e.message}")
                sleepQuietly(1500)
            }
        }
    }

    private fun respondToDiscovery(discoverySocket: DatagramSocket) {
        val buf = ByteArray(64)
        val packet = DatagramPacket(buf, buf.size)
        try {
            discoverySocket.receive(packet)
            val msg = String(packet.data, 0, packet.length)
            if (msg == DISCOVERY_MESSAGE) {
                val reply = "${SERVER_RESPONSE}:$CONTROL_PORT".toByteArray()
                val responsePacket = DatagramPacket(reply, reply.size, packet.address, packet.port)
                discoverySocket.send(responsePacket)
                Log.d(TAG, "Discovery request from ${packet.address.hostAddress}; replied")
            }
        } catch (e: SocketTimeoutException) {
            // expected
        } catch (e: Exception) {
            Log.w(TAG, "Discovery error: ${e.message}", e)
        }
    }

    private fun tryAccept(serverSocket: ServerSocket): Socket? {
        return try {
            serverSocket.accept()
        } catch (e: SocketTimeoutException) {
            null
        }
    }

    private fun discoverServer(): Pair<InetAddress, Int>? {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 1500
            val payload = DISCOVERY_MESSAGE.toByteArray()
            val broadcastTargets = NetworkUtils.broadcastAddresses(this).ifEmpty {
                listOf(InetAddress.getByName("255.255.255.255"))
            }
            broadcastTargets.forEach { target ->
                try {
                    val packet = DatagramPacket(payload, payload.size, target, DISCOVERY_PORT)
                    socket.send(packet)
                    Log.d(TAG, "Sent discovery to ${target.hostAddress}:$DISCOVERY_PORT")
                } catch (e: Exception) {
                    Log.w(TAG, "Discovery send failed to ${target.hostAddress}", e)
                }
            }
            val response = ByteArray(128)
            return try {
                val datagram = DatagramPacket(response, response.size)
                socket.receive(datagram)
                val text = String(datagram.data, 0, datagram.length)
                if (text.startsWith(SERVER_RESPONSE)) {
                    val parts = text.split(":")
                    val port = parts.getOrNull(1)?.toIntOrNull() ?: CONTROL_PORT
                    sendStatus("Discovered server at ${datagram.address.hostAddress}:$port")
                    Pair(datagram.address, port)
                } else {
                    fallbackGatewayTarget()
                }
            } catch (_: Exception) {
                fallbackGatewayTarget()
            }
        }
    }

    private fun fallbackGatewayTarget(): Pair<InetAddress, Int>? {
        val gateway = NetworkUtils.gatewayAddress(this)
        return if (gateway != null) {
            Log.d(TAG, "Using gateway ${gateway.hostAddress} as server candidate")
            sendStatus("Trying gateway ${gateway.hostAddress} as server")
            Pair(gateway, CONTROL_PORT)
        } else {
            sendStatus("No discovery response received")
            null
        }
    }

    private fun startAudioSession(peerAddress: InetAddress) {
        audioSession?.close()
        audioEngine.stop()
        val session = AudioSession(peerAddress)
        audioSession = session
        audioEngine.start { data, length ->
            session.send(data, length)
        }
        session.awaitStop()
        audioEngine.stop()
        audioSession = null
    }

    private fun sleepQuietly(millis: Long) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private inner class AudioSession(private val peerAddress: InetAddress) {
        private val running = AtomicBoolean(true)
        private val sendSocket = DatagramSocket()
        private val receiveSocket = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(AUDIO_PORT))
            soTimeout = 2000
        }
        private val receiveTask = executor.submit {
            val buffer = ByteArray(AUDIO_BUFFER_SIZE)
            try {
                while (running.get()) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    receiveSocket.receive(packet)
                    if (packet.address.hostAddress == peerAddress.hostAddress) {
                        audioEngine.play(buffer, packet.length)
                    }
                }
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "Receive error: ${e.message}", e)
            } finally {
                running.set(false)
                receiveSocket.close()
                sendSocket.close()
            }
        }

        init {
            try {
                sendSocket.connect(peerAddress, AUDIO_PORT)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to connect UDP socket: ${e.message}", e)
            }
            Log.d(TAG, "Audio session started with ${peerAddress.hostAddress}:$AUDIO_PORT")
        }

        fun send(data: ByteArray, length: Int) {
            if (!running.get()) return
            try {
                val packet = DatagramPacket(data.copyOf(length), length, peerAddress, AUDIO_PORT)
                sendSocket.send(packet)
            } catch (e: Exception) {
                Log.w(TAG, "Send failed: ${e.message}", e)
                running.set(false)
            }
        }

        fun awaitStop() {
            try {
                receiveTask.get()
            } catch (_: Exception) {
                // allow reconnect
            } finally {
                close()
            }
        }

        fun close() {
            running.set(false)
            try {
                receiveSocket.close()
            } catch (_: Exception) {
            }
            try {
                sendSocket.close()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        const val ACTION_START = "com.example.intercom.START"
        const val ACTION_STOP = "com.example.intercom.STOP"
        const val ACTION_STATUS = "com.example.intercom.STATUS"
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_STATUS = "extra_status"

        const val MODE_SERVER = "server"
        const val MODE_CLIENT = "client"

        private const val CHANNEL_ID = "intercom_channel"
        private const val NOTIFICATION_ID = 1001

        private const val DISCOVERY_PORT = 50001
        private const val CONTROL_PORT = 50002
        private const val AUDIO_PORT = 50004
        private const val AUDIO_BUFFER_SIZE = 4096

        private const val DISCOVERY_MESSAGE = "DISCOVER_INTERCOM"
        private const val SERVER_RESPONSE = "INTERCOM_SERVER"
        private const val TAG = "IntercomService"
    }
}
