package com.example.intercom.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {

    fun getLocalIpAddress(context: Context): InetAddress? {
        val preferred = mutableListOf<InetAddress>()
        val fallbacks = mutableListOf<InetAddress>()
        NetworkInterface.getNetworkInterfaces()?.let { interfaces ->
            for (ni in Collections.list(interfaces)) {
                if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
                val isWifi = ni.displayName.contains("wlan", ignoreCase = true) ||
                    ni.displayName.contains("ap", ignoreCase = true)
                for (address in Collections.list(ni.inetAddresses)) {
                    if (address !is Inet4Address || address.isLoopbackAddress || address.isLinkLocalAddress) continue
                    if (isWifi) preferred += address else fallbacks += address
                }
            }
        }
        val address = preferred.firstOrNull() ?: fallbacks.firstOrNull()
        if (address == null) {
            Log.w(TAG, "No suitable local IP found")
        }
        return address
    }

    fun broadcastAddresses(context: Context): List<InetAddress> {
        val broadcasts = mutableListOf<InetAddress>()
        linkProperties(context)?.let { lp ->
            lp.linkAddresses.forEach { la: LinkAddress ->
                val addr = la.address
                if (addr is Inet4Address && la.prefixLength in 0..32) {
                    val mask = prefixToMask(la.prefixLength)
                    val ip = addr.address.toUInt()
                    val broadcast = (ip and mask.inv()) or mask
                    broadcasts += InetAddress.getByAddress(uintToBytes(broadcast))
                }
            }
        }
        if (broadcasts.isEmpty()) {
            // fallback to common hotspot broadcast
            gatewayAddress(context)?.let { gw ->
                val octets = gw.address
                octets[3] = 0xFF.toByte()
                broadcasts += InetAddress.getByAddress(octets)
            }
        }
        return broadcasts
    }

    fun gatewayAddress(context: Context): InetAddress? {
        try {
            val lp = linkProperties(context)
            val gateway = lp?.routes
                ?.mapNotNull { it.gateway }
                ?.firstOrNull { it is Inet4Address } as? Inet4Address
            if (gateway != null) return gateway
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to read LinkProperties gateway: ${t.message}")
        }
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val dhcp = wifiManager.dhcpInfo
            val gatewayInt = dhcp?.gateway ?: 0
            if (gatewayInt != 0) {
                val bytes = byteArrayOf(
                    (gatewayInt and 0xFF).toByte(),
                    (gatewayInt shr 8 and 0xFF).toByte(),
                    (gatewayInt shr 16 and 0xFF).toByte(),
                    (gatewayInt shr 24 and 0xFF).toByte()
                )
                return InetAddress.getByAddress(bytes)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to read gateway from WifiManager: ${t.message}")
        }
        return null
    }

    private fun linkProperties(context: Context): LinkProperties? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cm.getLinkProperties(cm.activeNetwork)
            } else {
                @Suppress("DEPRECATION")
                cm.activeNetworkInfo?.let { cm.getLinkProperties(cm.activeNetwork) }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun prefixToMask(prefix: Int): UInt {
        return if (prefix == 0) 0u else (-0x1 shl (32 - prefix)).toUInt()
    }

    private fun uintToBytes(value: UInt): ByteArray {
        return byteArrayOf(
            (value and 0xFFu).toByte(),
            (value shr 8 and 0xFFu).toByte(),
            (value shr 16 and 0xFFu).toByte(),
            (value shr 24 and 0xFFu).toByte()
        )
    }

    private const val TAG = "NetworkUtils"
}

private fun ByteArray.toUInt(): UInt {
    var result = 0u
    for (i in indices) {
        result = result or ((this[i].toUByte().toUInt()) shl (8 * i))
    }
    return result
}
