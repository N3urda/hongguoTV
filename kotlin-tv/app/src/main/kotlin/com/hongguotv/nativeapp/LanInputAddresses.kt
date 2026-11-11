// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

internal data class LanInputAddress(val address: String, val label: String)

internal object LanInputAddresses {
    fun privateV4(addresses: List<InetAddress>): List<String> =
        addresses
            .filter {
                it is Inet4Address &&
                    it.isSiteLocalAddress &&
                    !it.isLoopbackAddress &&
                    !it.isLinkLocalAddress
            }
            .mapNotNull { it.hostAddress }
            .distinct()

    fun find(connectivity: ConnectivityManager): List<LanInputAddress> {
        val active = connectivity.activeNetwork
        val rows =
            connectivity.allNetworks
                .sortedBy { if (it == active) 0 else 1 }
                .flatMap { network ->
                    val caps =
                        connectivity.getNetworkCapabilities(network) ?: return@flatMap emptyList()
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                        return@flatMap emptyList()
                    val label =
                        when {
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "网线网络"
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                            else -> return@flatMap emptyList()
                        }
                    privateV4(
                            connectivity.getLinkProperties(network)?.linkAddresses.orEmpty().map {
                                it.address
                            }
                        )
                        .map { LanInputAddress(it, label) }
                }
        if (rows.isNotEmpty()) return rows.distinctBy { it.address }
        // Some TV firmwares omit the underlying network when a VPN is the default.
        return NetworkInterface.getNetworkInterfaces()
            ?.toList()
            .orEmpty()
            .filter { iface ->
                iface.isUp &&
                    !iface.isLoopback &&
                    Regex("^(wlan|wifi|wlp|eth|en)[0-9a-z_.-]*$").matches(iface.name)
            }
            .flatMap { iface ->
                privateV4(iface.inetAddresses.toList()).map { LanInputAddress(it, iface.name) }
            }
            .distinctBy { it.address }
    }
}
