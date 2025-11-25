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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
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
        sendStatus("Waiting for client")
        configureAudioRouting()
        while (!Thread.currentThread().isInterrupted) {
            var serverSocket: ServerSocket? = null
            var discoverySocket: DatagramSocket? = null
            try {
                serverSocket = ServerSocket(CONTROL_PORT).apply { soTimeout = 1000 }
                discoverySocket = DatagramSocket(DISCOVERY_PORT).apply {
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
                        sendStatus("Reconnecting...")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Server reconnect: ${e.message}")
                sendStatus("Reconnecting...")
                sleepQuietly(1200)
            } finally {
                discoverySocket?.close()
                serverSocket?.close()
            }
        }
    }

    private fun runClient() {
        sendStatus("Scanning for server")
        configureAudioRouting()
        while (!Thread.currentThread().isInterrupted) {
            try {
                val serverInfo = discoverServer() ?: continue
                val serverAddress = serverInfo.first
                val controlPort = serverInfo.second
                sendStatus("Connecting to ${serverAddress.hostAddress}")
                Socket(serverAddress, controlPort).use { socket ->
                    socket.getOutputStream().write("HELLO".toByteArray())
                }
                sendStatus("Connected")
                startAudioSession(serverAddress)
                sendStatus("Reconnecting...")
                sleepQuietly(1200)
            } catch (e: Exception) {
                Log.w(TAG, "Client reconnect due to ${e.message}")
                sendStatus("Reconnecting...")
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
            }
        } catch (e: SocketTimeoutException) {
            // expected
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
            val packet = DatagramPacket(payload, payload.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT)
            socket.send(packet)
            return try {
                val buf = ByteArray(128)
                val response = DatagramPacket(buf, buf.size)
                socket.receive(response)
                val text = String(response.data, 0, response.length)
                if (text.startsWith(SERVER_RESPONSE)) {
                    val parts = text.split(":")
                    val port = parts.getOrNull(1)?.toIntOrNull() ?: CONTROL_PORT
                    Pair(response.address, port)
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
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
        private val receiveSocket = DatagramSocket(AUDIO_PORT).apply { soTimeout = 2000 }
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
                if (running.get()) Log.w(TAG, "Receive error: ${e.message}")
            } finally {
                running.set(false)
                receiveSocket.close()
                sendSocket.close()
            }
        }

        fun send(data: ByteArray, length: Int) {
            if (!running.get()) return
            try {
                val packet = DatagramPacket(data.copyOf(length), length, peerAddress, AUDIO_PORT)
                sendSocket.send(packet)
            } catch (e: Exception) {
                Log.w(TAG, "Send failed: ${e.message}")
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
            receiveSocket.close()
            sendSocket.close()
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
        private const val AUDIO_BUFFER_SIZE = 2048

        private const val DISCOVERY_MESSAGE = "DISCOVER_INTERCOM"
        private const val SERVER_RESPONSE = "INTERCOM_SERVER"
        private const val TAG = "IntercomService"
    }
}
