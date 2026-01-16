package com.example.non3gppaccessemulator

import android.util.Log

/**
 * STATELESS SERVICE-AWARE HANDOVER ENGINE
 * FULLY LOG-INSTRUMENTED FOR EXPERIMENTAL ANALYSIS
 *
 * - No internal state (stateless)
 * - No timers inside
 * - Service handles dwell/TTT
 * - Engine only computes scores + HO recommendation
 */

data class HoProfileCfg(
    val w1: Double,
    val w2: Double,
    val w3: Double,
    val w4: Double,
    val tttMs: Long,
    val hyst: Double,
    val dwellMs: Long,
    val minThrGainMbps: Double,
    val theta: Double,
    val hysteresisUp: Double,
    val hysteresisDown: Double,
    val minWifiRssi: Double,
    val maxWifiJitter: Double,
    val maxWifiLoss: Double
)

class HandoverDecisionEngine(
    private val cfg: HoProfileCfg,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {

    private val TAG_SCORE = "SCORE"
    private val TAG_ENGINE = "ENGINE"
    private val TAG_PRECHECK = "PRECHECK"

    data class Metrics(
        val rttMs: Double? = null,
        val jitterMs: Double? = null,
        val throughputMbps: Double? = null,
        val sinrDb: Double? = null,
        val rsrpDbm: Double? = null,
        val rssiDbm: Double? = null,
        val lossPct: Double? = null
    )

    enum class AccessPath { WIFI, CELL }
    enum class HandoverEvent { NONE, CELL_TO_WIFI, WIFI_TO_CELL }

    data class DecisionResult(
        val activePath: AccessPath,
        val hoEvent: HandoverEvent,
        val hScore: Double,
        val wifiScore: Double,
        val cellScore: Double
    )

    // ------------------ NORMALIZATION RANGES ----------------------
    private val sinrRange = -5.0 to 30.0
    private val rttRange = 10.0 to 300.0
    private val thrRange = 0.0 to 200.0
    private val rsrpRange = -120.0 to -80.0

    private fun normalize(x: Double?, r: Pair<Double, Double>, higherBetter: Boolean): Double {
        if (x == null) return 0.0
        val (a, b) = r
        val clamped = x.coerceIn(a, b)
        val raw = (clamped - a) / (b - a)
        val out = if (higherBetter) raw else 1 - raw

        Log.d(TAG_ENGINE, "normalize: in=$x range=[$a,$b] higher=$higherBetter -> $out")

        return out
    }

    // ====================================================================
    //                           UPDATE()
    // ====================================================================
    fun update(
        wifi: Metrics?,
        cell: Metrics?,
        lastPath: AccessPath?
    ): DecisionResult {

        val now = nowMs()

        // ---------- NORMALIZED WIFI SCORES ---------
        val wLat = normalize(wifi?.rttMs, rttRange, false)
        val wThr = normalize(wifi?.throughputMbps, thrRange, true)
        val wSinr = normalize(wifi?.sinrDb, sinrRange, true)
        val wRssi = normalize(wifi?.rssiDbm, rsrpRange, true)

        // ---------- NORMALIZED CELL SCORES ---------
        val cLat = normalize(cell?.rttMs, rttRange, false)
        val cThr = normalize(cell?.throughputMbps, thrRange, true)
        val cSinr = normalize(cell?.sinrDb, sinrRange, true)
        val cRsrp = normalize(cell?.rsrpDbm, rsrpRange, true)

        // ----------- WEIGHTED SCORES ----------------
        val wifiScore =
            cfg.w1 * wSinr +
            cfg.w2 * wLat +
            cfg.w3 * wThr +
            cfg.w4 * wRssi

        val cellScore =
            cfg.w1 * cSinr +
            cfg.w2 * cLat +
            cfg.w3 * cThr +
            cfg.w4 * cRsrp

        val H = wifiScore - cellScore

        Log.d(
            TAG_SCORE,
            "SCORES wifi=$wifiScore cell=$cellScore H=$H " +
                    "raw: wifi(rtt=${wifi?.rttMs}, thr=${wifi?.throughputMbps}, sinr=${wifi?.sinrDb}, rssi=${wifi?.rssiDbm}) " +
                    "cell(rtt=${cell?.rttMs}, thr=${cell?.throughputMbps}, sinr=${cell?.sinrDb}, rsrp=${cell?.rsrpDbm})"
        )

        // -----------------------------------------------------------
        // WIFI PRECHECK
        // -----------------------------------------------------------
        val wifiOk = if (wifi != null) {
            val rssiOk = wifi.rssiDbm?.let { it >= cfg.minWifiRssi } ?: false
            val jitOk = wifi.jitterMs?.let { it <= cfg.maxWifiJitter } ?: true
            val lossOk = wifi.lossPct?.let { it <= cfg.maxWifiLoss } ?: true

            Log.d(
                TAG_PRECHECK,
                "wifiPreCheck: rssiOk=$rssiOk jitOk=$jitOk lossOk=$lossOk " +
                        "(rssi=${wifi.rssiDbm}, jitter=${wifi.jitterMs}, loss=${wifi.lossPct})"
            )

            rssiOk && jitOk && lossOk
        } else false

        // -----------------------------------------------------------
        // DECISION LOGIC
        // -----------------------------------------------------------
        val result =
            when (lastPath) {

                AccessPath.CELL -> {
                    val threshold = cfg.theta + cfg.hysteresisUp
                    Log.d(TAG_ENGINE, "CELL->? threshold=$threshold H=$H wifiOk=$wifiOk")

                    if (wifiOk && H > threshold) {
                        Log.w(TAG_ENGINE, "DECISION: CELL_TO_WIFI triggered")
                        DecisionResult(
                            activePath = AccessPath.WIFI,
                            hoEvent = HandoverEvent.CELL_TO_WIFI,
                            hScore = H,
                            wifiScore = wifiScore,
                            cellScore = cellScore
                        )
                    } else {
                        DecisionResult(
                            activePath = AccessPath.CELL,
                            hoEvent = HandoverEvent.NONE,
                            hScore = H,
                            wifiScore = wifiScore,
                            cellScore = cellScore
                        )
                    }
                }

                AccessPath.WIFI -> {
                    val threshold = cfg.theta + cfg.hysteresisDown
                    Log.d(TAG_ENGINE, "WIFI->? threshold=$threshold H=$H")

                    if (H < -threshold) {
                        Log.w(TAG_ENGINE, "DECISION: WIFI_TO_CELL triggered")
                        DecisionResult(
                            activePath = AccessPath.CELL,
                            hoEvent = HandoverEvent.WIFI_TO_CELL,
                            hScore = H,
                            wifiScore = wifiScore,
                            cellScore = cellScore
                        )
                    } else {
                        DecisionResult(
                            activePath = AccessPath.WIFI,
                            hoEvent = HandoverEvent.NONE,
                            hScore = H,
                            wifiScore = wifiScore,
                            cellScore = cellScore
                        )
                    }
                }

                else -> {
                    Log.i(TAG_ENGINE, "INITIAL STATE → fallback CELL")
                    DecisionResult(
                        activePath = AccessPath.CELL,
                        hoEvent = HandoverEvent.NONE,
                        hScore = H,
                        wifiScore = wifiScore,
                        cellScore = cellScore
                    )
                }
            }

        Log.d(
            TAG_ENGINE,
            "RESULT: active=${result.activePath} ho=${result.hoEvent} H=${result.hScore}"
        )

        return result
    }
}
