package com.example.non3gppaccessemulator

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.*
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max

class HandoverMonitorService : Service() {

    private val TAG_MEAS = "MEAS"
    private val TAG_SCORE = "SCORE"
    private val TAG_PATH = "PATH"
    private val TAG_HO = "HO"
    private val TAG_PROFILE = "PROFILE"

    private lateinit var telephony: TelephonyManager
    private lateinit var wifi: WifiManager
    private lateinit var cm: ConnectivityManager

    private val serviceJob = SupervisorJob()
    private val ioScope = CoroutineScope(Dispatchers.IO + serviceJob)

    @Volatile private var started = false

    // ===== CELL METRICS =====
    @Volatile private var rsrp: Double? = null
    @Volatile private var sinr: Double? = null
    @Volatile private var cellLat: Double? = null
    @Volatile private var cellJitter: Double? = null

    // ===== WIFI METRICS =====
    @Volatile private var rssi: Double? = null
    @Volatile private var wifiLat: Double? = null
    @Volatile private var wifiJitter: Double? = null

    // ===== THROUGHPUT =====
    @Volatile private var wifiDownMbps: Double? = null
    @Volatile private var wifiUpMbps: Double? = null
    @Volatile private var cellDownMbps: Double? = null
    @Volatile private var cellUpMbps: Double? = null

    // ===== PACKET LOSS =====
    @Volatile private var wifiLossPct: Double? = null
    @Volatile private var cellLossPct: Double? = null

    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private lateinit var engine: HandoverDecisionEngine
    private var engineReady = false
    private var activeProfile = "web"

    private var currentPath: HandoverDecisionEngine.AccessPath? = null

    private var lastRootRead = 0L
    private val rootPollInterval = 5000L

    companion object {
        const val ACTION_METRICS_UPDATE = "com.example.non3gppaccessemulator.METRICS_UPDATE"
        const val ACTION_START = "com.example.non3gppaccessemulator.START"
        const val ACTION_STOP = "com.example.non3gppaccessemulator.STOP"
        const val ACTION_SET_PROFILE = "com.example.non3gppaccessemulator.SET_PROFILE"
        const val EXTRA_PROFILE = "profile"

        const val CHANNEL_ID = "handover_monitor"
        private const val TAG = "HandoverMonitor"

        private const val DL_URL = "https://speed.cloudflare.com/__down?bytes=50000000"
        private const val UL_URL_WIFI = "https://office.romars.tech/"
        private const val UL_URL_CELL = "https://www.google.com/"
        private const val DL_WINDOW_MS = 600L
        private const val DL_STREAMS = 3
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        trustAllSsl()

        telephony = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        createChannel()
        startForegroundSafe()

        initEngine("web")
        currentPath = getInitialPath()
        
        Log.i(TAG_PROFILE, "Service created. Initial profile=$activeProfile initialPath=$currentPath")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                Log.i(TAG, "START received")
                startMonitoring()
            }
            ACTION_STOP -> {
                Log.i(TAG, "STOP received")
                stopMonitoring()
            }
            ACTION_SET_PROFILE -> intent.getStringExtra(EXTRA_PROFILE)?.let { 
                Log.i(TAG_PROFILE, "SET_PROFILE received: $it")
                initEngine(it) 
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopMonitoring()
        serviceJob.cancel()
        Log.w(TAG, "Service destroyed")
        super.onDestroy()
    }

    // ============================================================
    // NOTIFICATION
    // ============================================================
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Handover Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun startForegroundSafe() {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Handover Monitor Running")
            .setContentText("Network metrics are being collected")
            .setSmallIcon(R.drawable.ic_network_check)
            .setOngoing(true)
            .build()

        startForeground(1, notif)
    }

    // ============================================================
    // ENGINE INIT
    // ============================================================
    private fun initEngine(profile: String) {
        try {
            val root = JSONObject(
                assets.open("ho_thresholds.json").bufferedReader().use { it.readText() }
            )

            val key = if (root.has(profile)) profile else "web"
            val p = root.getJSONObject(key).getJSONObject("handover_params")

            val cfg = HoProfileCfg(
                w1 = p.optDouble("w1", 0.2),
                w2 = p.optDouble("w2", 0.2),
                w3 = p.optDouble("w3", 0.4),
                w4 = p.optDouble("w4", 0.2),
                tttMs = p.optLong("tttMs", 250),
                hyst = p.optDouble("hyst", 0.05),
                dwellMs = p.optLong("dwellMs", 1200),
                minThrGainMbps = p.optDouble("minThrGainMbps", 0.0),
                theta = p.optDouble("theta", 0.15),
                hysteresisUp = p.optDouble("hysteresisUp", 0.03),
                hysteresisDown = p.optDouble("hysteresisDown", 0.02),
                minWifiRssi = p.optDouble("minWifiRssi", -75.0),
                maxWifiJitter = p.optDouble("maxWifiJitter", 60.0),
                maxWifiLoss = p.optDouble("maxWifiLoss", 6.0)
            )

            engine = HandoverDecisionEngine(cfg)
            engineReady = true
            activeProfile = key

            Log.i(TAG_PROFILE, "Loaded profile=$activeProfile cfg=$cfg")
        } catch (e: Exception) {
            engineReady = false
            Log.e(TAG_PROFILE, "Engine init fail: ${e.message}")
        }
    }

    // ============================================================
    // MONITOR LOOP
    // ============================================================
    private fun startMonitoring() {
        if (started) return
        started = true

        registerConnectivityCallback()

        ioScope.launch {
            Log.i(TAG, "Monitoring loop started")
            while (isActive && started) {
                try {
                    readCellSignal()
                    readWifiRssi()
                    measureRttAll()
                    measureThroughputDual()
                    measureLossAll()
                    evaluate()
                } catch (e: Exception) {
                    Log.e(TAG, "loop err: ${e.message}")
                }
                delay(2000)
            }
        }
    }

    private fun stopMonitoring() {
        started = false
        netCallback?.let { cm.unregisterNetworkCallback(it) }
        netCallback = null
        Log.w(TAG, "Monitoring stopped")
    }

    // ============================================================
    // INITIAL PATH
    // ============================================================
    private fun getInitialPath(): HandoverDecisionEngine.AccessPath {
        val net = cm.activeNetwork ?: return HandoverDecisionEngine.AccessPath.CELL
        val caps = cm.getNetworkCapabilities(net)
        return if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
            HandoverDecisionEngine.AccessPath.WIFI
        else HandoverDecisionEngine.AccessPath.CELL
    }

    // ============================================================
    // ROOT CELL SIGNAL
    // ============================================================
    private fun tryReadCellSignalRoot(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastRootRead < rootPollInterval) return true
        lastRootRead = now

        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "dumpsys telephony.registry"))
            val dump = proc.inputStream.bufferedReader().use { it.readText() }
            proc.waitFor()

            val rsrpRegex = Regex("(ssRsrp|rsrp)=(-?\\d+)")
            val sinrRegex = Regex("(ssSinr|sinr)=(-?\\d+)")
            val rssnrRegex = Regex("rssnr=(-?\\d+)")

            var found = false

            rsrpRegex.find(dump)?.groupValues?.get(2)?.toDoubleOrNull()?.let {
                rsrp = it
                found = true
            }

            sinrRegex.find(dump)?.groupValues?.get(2)?.toDoubleOrNull()?.let {
                sinr = it
                found = true
            } ?: run {
                rssnrRegex.find(dump)?.groupValues?.get(1)?.toDoubleOrNull()?.let {
                    sinr = if (it < 40) it else it / 10.0
                    found = true
                }
            }

            Log.d(TAG_MEAS, "CELL ROOT rsrp=$rsrp sinr=$sinr found=$found")

            found
        } catch (e: Exception) {
            Log.e(TAG_MEAS, "ROOT FAIL: ${e.message}")
            false
        }
    }

    private fun readCellSignal() {
        if (tryReadCellSignalRoot()) {
            Log.d(TAG_MEAS, "CELL METRICS (root): rsrp=$rsrp sinr=$sinr")
            return
        }

        val s = runCatching { telephony.signalStrength }.getOrNull() ?: return
        val lvl = s.level

        rsrp = when (lvl) {
            4 -> -80.0
            3 -> -95.0
            2 -> -105.0
            1 -> -115.0
            else -> null
        }

        sinr = when (lvl) {
            4 -> 20.0
            3 -> 10.0
            2 -> 3.0
            1 -> -3.0
            else -> null
        }

        Log.d(TAG_MEAS, "CELL FALLBACK METRICS: rsrp=$rsrp sinr=$sinr")
    }

    // ============================================================
    // WIFI RSSI
    // ============================================================
    private fun registerConnectivityCallback() {
        if (netCallback != null) return

        netCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                readWifiRssi()
                Log.d(TAG_MEAS, "WIFI RSSI updated: $rssi")
            }
        }

        cm.registerDefaultNetworkCallback(netCallback!!)
        readWifiRssi()
        Log.d(TAG_MEAS, "WIFI RSSI initial: $rssi")
    }

    private fun readWifiRssi() {
        rssi = try {
            wifi.connectionInfo?.let { if (it.networkId != -1) it.rssi.toDouble() else null }
        } catch (_: Exception) { null }
    }

    // ============================================================
    // RTT / JITTER
    // ============================================================
    private suspend fun measureRttAll() {
        val wifiNet = getNetwork(NetworkCapabilities.TRANSPORT_WIFI)
        val cellNet = getNetwork(NetworkCapabilities.TRANSPORT_CELLULAR)

        fun measure(n: Network?): Pair<Double?, Double?> {
            if (n == null) return Pair(null, null)
            val list = mutableListOf<Long>()
            repeat(3) {
                tcpRtt(n, "1.1.1.1", 443)?.let { list += it }
            }
            if (list.isEmpty()) return Pair(null, null)

            val avg = list.average()
            val jitter = if (list.size > 1) list.zipWithNext { a, b -> abs(a - b) }.average() else 0.0

            return Pair(avg, jitter)
        }

        val (wl, wj) = measure(wifiNet)
        val (cl, cj) = measure(cellNet)

        wifiLat = wl
        wifiJitter = wj
        cellLat = cl
        cellJitter = cj

        Log.d(TAG_MEAS, "RTT/JITTER wifi=($wifiLat,$wifiJitter) cell=($cellLat,$cellJitter)")
    }

    private suspend fun getNetwork(transport: Int): Network? =
        suspendCancellableCoroutine { cont ->
            val req = NetworkRequest.Builder()
                .addTransportType(transport)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) {
                    cm.unregisterNetworkCallback(this)
                    if (!cont.isCompleted) cont.resume(n)
                }

                override fun onUnavailable() {
                    cm.unregisterNetworkCallback(this)
                    if (!cont.isCompleted) cont.resume(null)
                }
            }

            try { cm.requestNetwork(req, cb, 1500) }
            catch (_: Exception) { cont.resume(null) }
        }

    private fun tcpRtt(net: Network, host: String, port: Int): Long? {
        return try {
            val start = System.currentTimeMillis()
            val s = Socket()
            net.bindSocket(s)
            s.connect(InetSocketAddress(host, port), 1000)
            val end = System.currentTimeMillis()
            s.close()
            end - start
        } catch (_: Exception) {
            null
        }
    }

    // ============================================================
    // PACKET LOSS
    // ============================================================
    private suspend fun measureLossAll() {
        val wifiNet = getNetwork(NetworkCapabilities.TRANSPORT_WIFI)
        val cellNet = getNetwork(NetworkCapabilities.TRANSPORT_CELLULAR)

        wifiLossPct = wifiNet?.let { pingLossOnNetwork(it, "1.1.1.1") }
        cellLossPct = cellNet?.let { pingLossOnNetwork(it, "1.1.1.1") }

        Log.d(TAG_MEAS, "LOSS wifi=$wifiLossPct cell=$cellLossPct")
    }

    private fun pingLossOnNetwork(
        net: Network,
        host: String,
        count: Int = 3,
        timeoutSec: Int = 1
    ): Double? {
        return try {
            cm.bindProcessToNetwork(net)

            val cmd = arrayOf("su", "-c", "ping -c $count -W $timeoutSec $host")
            val proc = Runtime.getRuntime().exec(cmd)
            val output = proc.inputStream.bufferedReader().use { it.readText() }
            proc.waitFor()

            val regex =
                Regex("(\\d+) packets transmitted, (\\d+) (?:packets )?received, (\\d+)% packet loss")
            val match = regex.find(output) ?: return null

            val tx = match.groupValues[1].toDoubleOrNull() ?: return null
            val rx = match.groupValues[2].toDoubleOrNull() ?: return null

            val loss = if (tx <= 0.0) null else ((tx - rx) / tx) * 100.0
            loss
        } catch (e: Exception) {
            Log.e(TAG_MEAS, "LOSS FAIL: ${e.message}")
            null
        } finally {
            try { cm.bindProcessToNetwork(null) } catch (_: Exception) {}
        }
    }

    // ============================================================
    // THROUGHPUT
    // ============================================================
    private suspend fun measureThroughputDual() {
        wifiDownMbps = null
        wifiUpMbps = null
        cellDownMbps = null
        cellUpMbps = null

        val wifiNet = getNetwork(NetworkCapabilities.TRANSPORT_WIFI)
        val cellNet = getNetwork(NetworkCapabilities.TRANSPORT_CELLULAR)

        wifiNet?.let {
            val (d, u) = measurePathThroughput(it, "WIFI")
            wifiDownMbps = d
            wifiUpMbps = u
        }

        cellNet?.let {
            val (d, u) = measurePathThroughput(it, "CELL")
            cellDownMbps = d
            cellUpMbps = u
        }

        Log.d(
            TAG_MEAS,
            "THR wifi=($wifiDownMbps,$wifiUpMbps) cell=($cellDownMbps,$cellUpMbps)"
        )
    }

    private fun measurePathThroughput(
        net: Network,
        label: String
    ): Pair<Double?, Double?> {

        var d: Double? = null
        var u: Double? = null

        try {
            val bytesPerStream = LongArray(DL_STREAMS)
            val latch = CountDownLatch(DL_STREAMS)

            repeat(DL_STREAMS) { idx ->
                Thread {
                    try {
                        val url = URL(DL_URL)
                        val conn = (net.openConnection(url) as HttpsURLConnection).apply {
                            connectTimeout = 3000
                            readTimeout = 3000
                        }

                        conn.inputStream.use { inp ->
                            val buf = ByteArray(8192)
                            val start = System.currentTimeMillis()
                            while (System.currentTimeMillis() - start < DL_WINDOW_MS) {
                                val r = inp.read(buf)
                                if (r <= 0) break
                                bytesPerStream[idx] += r
                            }
                        }

                        conn.disconnect()
                    } catch (e: Exception) {
                        Log.w(TAG_MEAS, "DL[$label] stream$idx failed: ${e.message}")
                    } finally {
                        latch.countDown()
                    }
                }.start()
            }

            latch.await(DL_WINDOW_MS + 500, TimeUnit.MILLISECONDS)
            val totalBytes = bytesPerStream.sum()
            if (totalBytes > 0) {
                val ms = max(1L, DL_WINDOW_MS)
                d = (totalBytes * 8.0) / ms / 1000.0
            }
        } catch (e: Exception) {
            Log.w(TAG_MEAS, "DL[$label] failed (global): ${e.message}")
        }

        try {
            val data = ByteArray(1024 * 1024) { 1 }
            val ulUrl = if (label == "CELL") UL_URL_CELL else UL_URL_WIFI
            val url = URL(ulUrl)

            val conn = (net.openConnection(url) as HttpsURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 3000
                readTimeout = 3000
            }

            val start = System.currentTimeMillis()
            BufferedOutputStream(conn.outputStream).use { it.write(data) }
            val ms = max(1, System.currentTimeMillis() - start)

            u = (data.size * 8.0) / ms / 1000.0
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG_MEAS, "UL[$label] failed: ${e.message}")
        }

        return Pair(d, u)
    }

    private fun trustAllSsl() {
        try {
            val trustAllCerts = arrayOf<TrustManager>(
                object : X509TrustManager {
                    override fun checkClientTrusted(
                        chain: Array<java.security.cert.X509Certificate>,
                        authType: String
                    ) { }
                    override fun checkServerTrusted(
                        chain: Array<java.security.cert.X509Certificate>,
                        authType: String
                    ) { }
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> =
                        arrayOf()
                }
            )

            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())

            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
            HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }

            Log.w(TAG, "SSL BYPASS ENABLED")
        } catch (e: Exception) {
            Log.e(TAG, "trustAllSsl failed: ${e.message}")
        }
    }

    // ============================================================
    // HANDOVER ENGINE
    // ============================================================
    private fun evaluate() {
        if (!engineReady) {
            send(null)
            return
        }

        val wifiM = HandoverDecisionEngine.Metrics(
            rttMs = wifiLat,
            jitterMs = wifiJitter,
            throughputMbps = wifiDownMbps,
            rssiDbm = rssi
        )

        val cellM = HandoverDecisionEngine.Metrics(
            rttMs = cellLat,
            jitterMs = cellJitter,
            throughputMbps = cellDownMbps,
            sinrDb = sinr,
            rsrpDbm = rsrp
        )

        val d = engine.update(wifiM, cellM, currentPath)

        Log.d(
            TAG_SCORE,
            "DECISION wifiScore=${d.wifiScore} cellScore=${d.cellScore} H=${d.hScore} event=${d.hoEvent}"
        )

        if (d.hoEvent != HandoverDecisionEngine.HandoverEvent.NONE) {
            Log.w(TAG_HO, "HANDOVER TRIGGERED event=${d.hoEvent} path=${d.activePath}")
            currentPath = d.activePath
            Log.w(TAG_PATH, "ACTIVE PATH CHANGED → $currentPath")
        } else if (currentPath == null) {
            currentPath = d.activePath
            Log.i(TAG_PATH, "INITIAL PATH SET → $currentPath")
        }

        send(d)
    }

    // ============================================================
    // BROADCAST
    // ============================================================
    private fun send(d: HandoverDecisionEngine.DecisionResult?) {

        val j = JSONObject()

        j.put("timestamp", System.currentTimeMillis())
        j.put("profile", activeProfile)

        wifiLat?.let { j.put("wifi_latency", it) }
        wifiJitter?.let { j.put("wifi_jitter", it) }
        rssi?.let { j.put("wifi_rssi", it) }
        wifiLossPct?.let { j.put("wifi_loss", it) }

        cellLat?.let { j.put("cell_latency", it) }
        cellJitter?.let { j.put("cell_jitter", it) }
        rsrp?.let { j.put("cell_rsrp", it) }
        sinr?.let { j.put("cell_sinr", it) }
        cellLossPct?.let { j.put("cell_loss", it) }

        wifiDownMbps?.let { j.put("wifi_down_mbps", it) }
        wifiUpMbps?.let { j.put("wifi_up_mbps", it) }
        cellDownMbps?.let { j.put("cell_down_mbps", it) }
        cellUpMbps?.let { j.put("cell_up_mbps", it) }

        if (d != null) {
            j.put("active_path", currentPath?.name ?: "UNKNOWN")
            j.put("ho_event", d.hoEvent.name)
            j.put("h_score", d.hScore)
            j.put("wifi_score", d.wifiScore)
            j.put("cell_score", d.cellScore)
        } else {
            j.put("active_path", "UNKNOWN")
            j.put("ho_event", "NONE")
        }

        val i = Intent(ACTION_METRICS_UPDATE).apply {
            setPackage(packageName)
            putExtra("metrics", j.toString())
        }

        sendBroadcast(i)
    }
}
