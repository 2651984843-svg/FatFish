package com.example.fatfishphone

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

/** 获取本机 WiFi 局域网 IP（192./10./172. 网段），失败返回提示文案 */
object NetworkUtils {

    fun getWifiIp(context: Context): String {
        // 1. 优先走 WifiManager
        try {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ip = wm?.connectionInfo?.ipAddress ?: 0
            if (ip != 0) {
                val addr = String.format(
                    Locale.US, "%d.%d.%d.%d",
                    ip and 0xff,
                    (ip shr 8) and 0xff,
                    (ip shr 16) and 0xff,
                    (ip shr 24) and 0xff
                )
                if (isLan(addr)) return addr
            }
        } catch (_: Exception) {
        }

        // 2. 兜底遍历网络接口
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addresses = ni.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress
                        if (host != null && isLan(host)) return host
                    }
                }
            }
        } catch (_: Exception) {
        }

        return "未连接 WiFi"
    }

    private fun isLan(addr: String): Boolean =
        addr.startsWith("192.") || addr.startsWith("10.") || addr.startsWith("172.")
}
