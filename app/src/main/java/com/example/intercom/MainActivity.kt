package com.example.intercom

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var serverButton: Button
    private lateinit var clientButton: Button

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == IntercomService.ACTION_STATUS) {
                val status = intent.getStringExtra(IntercomService.EXTRA_STATUS).orEmpty()
                statusText.text = status
            }
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.RECORD_AUDIO] != true) {
                Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        serverButton = findViewById(R.id.serverButton)
        clientButton = findViewById(R.id.clientButton)

        serverButton.setOnClickListener { startIntercom(IntercomService.MODE_SERVER) }
        clientButton.setOnClickListener { startIntercom(IntercomService.MODE_CLIENT) }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(statusReceiver, IntentFilter(IntercomService.ACTION_STATUS))
        ensurePermissions()
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(statusReceiver)
    }

    private fun ensurePermissions() {
        val pending = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (pending.isNotEmpty()) {
            permissionLauncher.launch(pending.toTypedArray())
        }
    }

    private fun requiredPermissions(): List<String> {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        }
        return permissions
    }

    private fun startIntercom(mode: String) {
        ensurePermissions()
        val intent = Intent(this, IntercomService::class.java).apply {
            action = IntercomService.ACTION_START
            putExtra(IntercomService.EXTRA_MODE, mode)
        }
        ContextCompat.startForegroundService(this, intent)
    }
}
