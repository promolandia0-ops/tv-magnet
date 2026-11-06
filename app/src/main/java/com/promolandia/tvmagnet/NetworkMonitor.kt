package com.promolandia.tvmagnet

import android.content.Context
import android.net.*
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/** Binds torrent sockets to the Wi-Fi interface to avoid multicast/VPN ambiguity. */
class NetworkMonitor(context: Context) {
    private val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
        }
    }
    init { try { manager.registerDefaultNetworkCallback(callback) } catch (e: Exception) { Log.i("TVMagnet", "Network callback unavailable") } }
    fun close() { try { manager.unregisterNetworkCallback(callback) } catch (_: Throwable) {} }
}

fun lanIp(): String? {
    val interfaces = NetworkInterface.getNetworkInterfaces()
    return Collections.list(interfaces).asSequence().filter { it.isUp && !it.isLoopback }
        .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }?.hostAddress
}
