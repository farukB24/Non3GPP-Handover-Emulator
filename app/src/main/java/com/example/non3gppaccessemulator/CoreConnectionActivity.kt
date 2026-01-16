package com.example.non3gppaccessemulator

import android.content.*
import android.graphics.Color
import android.os.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import android.content.pm.PackageManager
import org.json.JSONObject
import java.util.*

class CoreConnectionActivity : AppCompatActivity() {

    // ===== UI =====
    private lateinit var tvCoreStatus: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnBack: Button
    private lateinit var btnClearLogs: Button
    private lateinit var spinnerService: Spinner
    private lateinit var tvLogs: TextView

    // Cellular metrics
    private lateinit var tvCellLatency: TextView
    private lateinit var tvCellDownload: TextView
    private lateinit var tvCellUpload: TextView
    private lateinit var tvCellJitter: TextView
    private lateinit var tvCellLoss: TextView
    private lateinit var tvCellRsrp: TextView
    private lateinit var tvCellSinr: TextView

    // Wi-Fi metrics
    private lateinit var tvWifiLatency: TextView
    private lateinit var tvWifiDownload: TextView
    private lateinit var tvWifiUpload: TextView
    private lateinit var tvWifiJitter: TextView
    private lateinit var tvWifiLoss: TextView
    private lateinit var tvWifiRssi: TextView
    private lateinit var tvWifiSinr: TextView

    private var monitoring = false

    // ===== Data Model =====
    data class Row(
        val t: Long?,
        val sinr: Double?,   // cell
        val rsrp: Double?,   // cell
        val rssi: Double?,   // wifi
        val rtt: Double?,
        val jitter: Double?,
        val loss: Double?,      // LOSS EKLENDİ
        val down: Double?,
        val up: Double?
    )

    private var lastWifiRow: Row? = null
    private var lastCellRow: Row? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_core_connection)

        ensurePermissions()
        bindUI()
        updateCoreStatus("INACTIVE")
        renderMetrics(null, null)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(HandoverMonitorService.ACTION_METRICS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(metricsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(metricsReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(metricsReceiver) } catch (_: Throwable) {}
    }

    private fun bindUI() {
        tvCoreStatus = findViewById(R.id.textViewCoreStatus)
        btnToggle = findViewById(R.id.buttonToggleConnection)
        btnBack = findViewById(R.id.buttonBack)
        btnClearLogs = findViewById(R.id.buttonClearLogs)
        spinnerService = findViewById(R.id.spinnerServiceType)
        tvLogs = findViewById(R.id.textViewLogs)

        // Cellular
        tvCellLatency = findViewById(R.id.textViewCellLatency)
        tvCellDownload = findViewById(R.id.textViewCellDownload)
        tvCellUpload = findViewById(R.id.textViewCellUpload)
        tvCellJitter = findViewById(R.id.textViewCellJitter)
        tvCellLoss = findViewById(R.id.textViewCellLoss)
        tvCellRsrp = findViewById(R.id.textViewCellRsrp)
        tvCellSinr = findViewById(R.id.textViewCellSinr)

        // Wi-Fi
        tvWifiLatency = findViewById(R.id.textViewWifiLatency)
        tvWifiDownload = findViewById(R.id.textViewWifiDownload)
        tvWifiUpload = findViewById(R.id.textViewWifiUpload)
        tvWifiJitter = findViewById(R.id.textViewWifiJitter)
        tvWifiLoss = findViewById(R.id.textViewWifiLoss)
        tvWifiRssi = findViewById(R.id.textViewWifiRssi)
        tvWifiSinr = findViewById(R.id.textViewWifiSinr)

        spinnerService.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                val svcName = parent?.getItemAtPosition(position)?.toString() ?: "web"

                val key = when (svcName.lowercase(Locale.US)) {
                    "gaming" -> "gaming"
                    "video"  -> "video"
                    "urllc"  -> "urllc"
                    else     -> "web"
                }

                log("Profile changed: $key")

                val i = Intent(this@CoreConnectionActivity, HandoverMonitorService::class.java)
                i.action = HandoverMonitorService.ACTION_SET_PROFILE
                i.putExtra(HandoverMonitorService.EXTRA_PROFILE, key)
                startService(i)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnToggle.setOnClickListener {
            if (!monitoring) startLiveMonitoring() else stopLiveMonitoring()
        }
        btnBack.setOnClickListener { finish() }
        btnClearLogs.setOnClickListener { tvLogs.text = "" }
    }

    private fun ensurePermissions() {
        val perms = arrayOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.READ_PHONE_STATE
        )
        val missing = perms.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty())
            requestPermissions(missing.toTypedArray(), 99)
    }

    // ========================== RECEIVER ======================================
    private val metricsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val str = intent?.getStringExtra("metrics") ?: return
            val o = JSONObject(str)

            val ts = o.optLong("timestamp")

            // WIFI
            val wRssi = o.optDouble("wifi_rssi", Double.NaN).takeIf { !it.isNaN() }
            val wLat  = o.optDouble("wifi_latency", Double.NaN).takeIf { !it.isNaN() }
            val wJit  = o.optDouble("wifi_jitter", Double.NaN).takeIf { !it.isNaN() }
            val wLoss = o.optDouble("wifi_loss", Double.NaN).takeIf { !it.isNaN() }
            val wDown = o.optDouble("wifi_down_mbps", Double.NaN).takeIf { !it.isNaN() }
            val wUp   = o.optDouble("wifi_up_mbps", Double.NaN).takeIf { !it.isNaN() }

            // CELL
            val cRsrp = o.optDouble("cell_rsrp", Double.NaN).takeIf { !it.isNaN() }
            val cSinr = o.optDouble("cell_sinr", Double.NaN).takeIf { !it.isNaN() }
            val cLat  = o.optDouble("cell_latency", Double.NaN).takeIf { !it.isNaN() }
            val cJit  = o.optDouble("cell_jitter", Double.NaN).takeIf { !it.isNaN() }
            val cLoss = o.optDouble("cell_loss", Double.NaN).takeIf { !it.isNaN() }
            val cDown = o.optDouble("cell_down_mbps", Double.NaN).takeIf { !it.isNaN() }
            val cUp   = o.optDouble("cell_up_mbps", Double.NaN).takeIf { !it.isNaN() }

            // HO Info
            val activePath = o.optString("active_path", "UNKNOWN")
            val hoEvent    = o.optString("ho_event", "NONE")
            val hScore     = o.optDouble("h_score", Double.NaN)
            val wScore     = o.optDouble("wifi_score", Double.NaN)
            val cScore     = o.optDouble("cell_score", Double.NaN)

            lastWifiRow = Row(ts, null, null, wRssi, wLat, wJit, wLoss, wDown, wUp)
            lastCellRow = Row(ts, cSinr, cRsrp, null, cLat, cJit, cLoss, cDown, cUp)

            renderMetrics(lastWifiRow, lastCellRow)
            updateCoreStatus(activePath)

            if (hoEvent != "NONE") {
                log(
                    "HO event: $hoEvent | H=${fmtD(hScore)} | wifi=${fmtD(wScore)} | cell=${fmtD(cScore)}"
                )
            }
        }
    }
    // ==========================================================================

    private fun startLiveMonitoring() {
        monitoring = true
        btnToggle.text = "Disconnect"
        updateCoreStatus("CELL")
        log("Monitoring started")

        val i = Intent(this, HandoverMonitorService::class.java)
        i.action = HandoverMonitorService.ACTION_START

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i)
        } else {
            startService(i)
        }
    }

    private fun stopLiveMonitoring() {
        monitoring = false
        btnToggle.text = "Connect"
        updateCoreStatus("INACTIVE")
        log("Monitoring stopped")

        stopService(Intent(this, HandoverMonitorService::class.java).apply {
            action = HandoverMonitorService.ACTION_STOP
        })
    }

    private fun updateCoreStatus(state: String?) {
        when (state?.uppercase(Locale.US)) {
            "INACTIVE" -> {
                tvCoreStatus.text = "Handover Engine: INACTIVE"
                tvCoreStatus.setTextColor(Color.RED)
            }
            "WIFI" -> {
                tvCoreStatus.text = "Handover Engine: Non-3GPP (Wi-Fi) selected"
                tvCoreStatus.setTextColor(Color.parseColor("#2ECC71"))
            }
            "CELL" -> {
                tvCoreStatus.text = "Handover Engine: 3GPP (NR/LTE) selected"
                tvCoreStatus.setTextColor(Color.parseColor("#007BFF"))
            }
            else -> {
                tvCoreStatus.text = "Handover Engine: UNKNOWN"
                tvCoreStatus.setTextColor(Color.DKGRAY)
            }
        }
    }

    private fun renderMetrics(w: Row?, c: Row?) {
        fun f(v: Double?, u: String) =
            v?.let { String.format(Locale.US, "%.2f %s", it, u) } ?: "—"

        // CELL (3GPP)
        tvCellLatency.text = "Latency: ${f(c?.rtt, "ms")}"
        tvCellDownload.text = "DL: ${f(c?.down, "Mbps")}"
        tvCellUpload.text   = "UL: ${f(c?.up, "Mbps")}"
        tvCellJitter.text   = "Jitter: ${f(c?.jitter, "ms")}"
        tvCellLoss.text     = "Loss: ${f(c?.loss, "%")}"
        tvCellRsrp.text     = "RSRP: ${f(c?.rsrp, "dBm")}"
        tvCellSinr.text     = "SINR: ${f(c?.sinr, "dB")}"

        // WIFI (NON-3GPP)
        tvWifiLatency.text = "Latency: ${f(w?.rtt, "ms")}"
        tvWifiDownload.text = "DL: ${f(w?.down, "Mbps")}"
        tvWifiUpload.text   = "UL: ${f(w?.up, "Mbps")}"
        tvWifiJitter.text   = "Jitter: ${f(w?.jitter, "ms")}"
        tvWifiLoss.text     = "Loss: ${f(w?.loss, "%")}"
        tvWifiRssi.text     = "RSSI: ${f(w?.rssi, "dBm")}"
        tvWifiSinr.text     = "SINR: —"
    }

    private fun fmtD(v: Double?) =
        if (v == null || v.isNaN()) "NA" else String.format(Locale.US, "%.3f", v)

    private fun log(m: String) {
        if (tvLogs.text.length > 8000) {
            tvLogs.text = ""
        }
        val prefix = if (tvLogs.text.isEmpty()) "" else "\n"
        tvLogs.append(prefix + m)
    }
}
