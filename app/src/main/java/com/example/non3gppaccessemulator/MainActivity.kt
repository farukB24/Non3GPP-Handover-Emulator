package com.example.non3gppaccessemulator

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.*
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.TextView
import android.widget.Button
import android.content.Intent

class MainActivity : AppCompatActivity() {

    private lateinit var statusTextView: TextView
    private lateinit var goToCoreButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusTextView = findViewById(R.id.statusTextView)
        goToCoreButton = findViewById(R.id.buttonGoToCore)

        // Uygulama açılır açılmaz Wi-Fi durumunu göster
        updateWifiStatus()

        goToCoreButton.setOnClickListener {
            startActivity(Intent(this, CoreConnectionActivity::class.java))
        }
    }

    // =====================================================================
    // Wi-Fi status auto-check on launch (Pixel-safe)
    // =====================================================================
    private fun updateWifiStatus() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(active)

        val wifiActive = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        if (!wifiActive) {
            statusTextView.text =
                "❌ Not connected via Wi-Fi\nNon-3GPP Access: INACTIVE"
            return
        }

        val wifiManager =
            applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wifiManager.connectionInfo

        val ssid = getSafeSsid(info)
        val ip = intToIp(info.ipAddress)

        val text = """
            ✅ Connected via Wi-Fi
            SSID: $ssid
            IP: $ip
            Non-3GPP Access: ACTIVE
        """.trimIndent()

        statusTextView.text = text
    }

    // =====================================================================
    // Pixel/Android 14 Safe SSID Extraction
    // =====================================================================
    private fun getSafeSsid(info: WifiInfo?): String {
        if (info == null) return "Unknown"

        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        val allowed = (fine == PackageManager.PERMISSION_GRANTED
                || coarse == PackageManager.PERMISSION_GRANTED)

        val raw = info.ssid ?: return "Unknown"

        return if (allowed && raw != "<unknown ssid>") {
            raw.replace("\"", "")
        } else {
            "Hidden / Restricted"
        }
    }

    // =====================================================================
    // Convert IP Int → Dotted decimal
    // =====================================================================
    private fun intToIp(ip: Int): String {
        return String.format(
            "%d.%d.%d.%d",
            (ip and 0xff),
            (ip shr 8 and 0xff),
            (ip shr 16 and 0xff),
            (ip shr 24 and 0xff)
        )
    }
}
