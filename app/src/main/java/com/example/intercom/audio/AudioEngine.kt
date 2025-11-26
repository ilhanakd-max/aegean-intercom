package com.example.intercom.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

class AudioEngine(private val sampleRate: Int = 16000) {
    private val frameSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(sampleRate / 5) * 2

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private val running = AtomicBoolean(false)
    private val recordExecutor = Executors.newSingleThreadExecutor()
    private var recordingTask: Future<*>? = null

    fun start(onAudioFrame: (ByteArray, Int) -> Unit) {
        if (running.get()) return
        running.set(true)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            frameSize
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(frameSize * 2)
            .build()
        audioRecord = record
        audioTrack = track
        track.play()
        record.startRecording()
        recordingTask = recordExecutor.submit {
            val buffer = ByteArray(frameSize)
            try {
                while (running.get()) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        onAudioFrame(buffer.copyOf(read), read)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Audio capture error: ${e.message}", e)
            }
        }
    }

    fun play(data: ByteArray, length: Int) {
        if (!running.get()) return
        try {
            audioTrack?.write(data, 0, length)
        } catch (e: Exception) {
            Log.w(TAG, "Audio playback error: ${e.message}", e)
        }
    }

    fun stop() {
        running.set(false)
        try {
            recordingTask?.cancel(true)
        } catch (_: Exception) {
        }
        audioRecord?.apply {
            try {
                stop()
                release()
            } catch (_: Exception) {
            }
        }
        audioTrack?.apply {
            try {
                flush()
                stop()
                release()
            } catch (_: Exception) {
            }
        }
        audioRecord = null
        audioTrack = null
        recordingTask = null
    }

    companion object {
        private const val TAG = "AudioEngine"
    }
}
