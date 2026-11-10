// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import java.net.Inet4Address

/** Keep startup, network recovery and LAN tools usable on API 21/22 TVs. */
internal object NetworkCompat {
    fun manager(context: Context): ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun register(manager: ConnectivityManager, callback: ConnectivityManager.NetworkCallback) {
        if (Build.VERSION.SDK_INT >= 24) manager.registerDefaultNetworkCallback(callback)
        else manager.registerNetworkCallback(NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback)
    }

    @Suppress("DEPRECATION")
    fun available(manager: ConnectivityManager): Boolean =
        if (Build.VERSION.SDK_INT >= 23) manager.getNetworkCapabilities(manager.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        else manager.activeNetworkInfo?.isConnected == true

    @Suppress("DEPRECATION")
    private fun active(manager: ConnectivityManager): Network? {
        if (Build.VERSION.SDK_INT >= 23) return manager.activeNetwork
        val current = manager.activeNetworkInfo?.takeIf { it.isConnected } ?: return null
        return manager.allNetworks.firstOrNull { network ->
            manager.getNetworkInfo(network)?.let { it.isConnected && it.type == current.type } == true
        }
    }

    /** Never offer a cellular/VPN address as a same-LAN pairing endpoint. */
    fun lanAddress(manager: ConnectivityManager): String? {
        val network = active(manager) ?: return null
        val capabilities = manager.getNetworkCapabilities(network) ?: return null
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return null
        return manager.getLinkProperties(network)?.linkAddresses?.map { it.address }
            ?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }
}
