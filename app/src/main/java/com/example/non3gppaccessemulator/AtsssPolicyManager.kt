package com.example.non3gppaccessemulator

import android.util.Log

/**
 * ATSSS Policy Manager (UE-side approximation)
 *
 * IMPORTANT:
 * - This module DOES NOT perform handover.
 * - It only computes steering preferences conceptually aligned
 *   with ATSSS logic.
 * - Result is advisory only; HandoverDecisionEngine makes actual HO.
 *
 * Fully instrumented with LOGS for analysis.
 */
class AtsssPolicyManager {

    private val TAG_ATSSS = "ATSSS"
    private val TAG_ATSSS_SCORE = "ATSSS_SCORE"
    private val TAG_ATSSS_INPUT = "ATSSS_INPUT"

    // Traffic categories aligned with thesis
    enum class TrafficType {
        URLLC,
        EMBB,
        VIDEO,
        WEB,
        IOT
    }

    // Steering outcome
    enum class SteeringMode {
        STEER_TO_CELL,
        STEER_TO_WIFI,
        SPLIT_TRAFFIC,
        SWITCH_TO_BETTER
    }

    /**
     * Metrics coming from HandoverMonitorService
     */
    data class Metrics(
        val wifiRtt: Double? = null,
        val wifiJitter: Double? = null,
        val wifiRssi: Double? = null,
        val wifiThr: Double? = null,
        val cellRtt: Double? = null,
        val cellJitter: Double? = null,
        val cellRsrp: Double? = null,
        val cellSinr: Double? = null,
        val cellThr: Double? = null
    )

    /**
     * Main ATSSS-style (advisory) steering logic.
     */
    fun evaluate(
        type: TrafficType,
        m: Metrics
    ): SteeringMode {

        Log.d(
            TAG_ATSSS_INPUT,
            "traffic=$type " +
            "wifi(rtt=${m.wifiRtt}, jitter=${m.wifiJitter}, rssi=${m.wifiRssi}, thr=${m.wifiThr}) " +
            "cell(rtt=${m.cellRtt}, jitter=${m.cellJitter}, rsrp=${m.cellRsrp}, sinr=${m.cellSinr}, thr=${m.cellThr})"
        )

        // QUICK sanity guards (UE-side only)
        val wifiGood =
            (m.wifiRssi ?: -999.0) > -75 &&
            (m.wifiJitter ?: 999.0) < 40 &&
            (m.wifiRtt ?: 999.0) < 80

        val cellGood =
            (m.cellRsrp ?: -999.0) > -100 &&
            (m.cellSinr ?: -999.0) > 3 &&
            (m.cellRtt ?: 999.0) < 120

        Log.d(TAG_ATSSS, "wifiGood=$wifiGood cellGood=$cellGood")

        // TRAFFIC TYPE BRANCHES
        val result =
            when (type) {

                // ====================== URLLC ======================
                TrafficType.URLLC -> {
                    val wifiScore = scoreUrlcc(m.wifiRtt, m.wifiJitter)
                    val cellScore = scoreUrlcc(m.cellRtt, m.cellJitter)

                    Log.d(
                        TAG_ATSSS_SCORE,
                        "URLLC wifiScore=$wifiScore cellScore=$cellScore"
                    )

                    when {
                        cellScore > wifiScore * 1.1 -> SteeringMode.STEER_TO_CELL
                        wifiScore > cellScore * 1.1 -> SteeringMode.STEER_TO_WIFI
                        else -> SteeringMode.SPLIT_TRAFFIC
                    }
                }

                // ====================== eMBB ======================
                TrafficType.EMBB -> {
                    val wifiScore = scoreEmbb(m.wifiThr, m.wifiRssi)
                    val cellScore = scoreEmbb(m.cellThr, m.cellRsrp)

                    Log.d(
                        TAG_ATSSS_SCORE,
                        "EMBB wifiScore=$wifiScore cellScore=$cellScore"
                    )

                    when {
                        wifiScore > cellScore * 1.2 -> SteeringMode.STEER_TO_WIFI
                        cellScore > wifiScore * 1.2 -> SteeringMode.STEER_TO_CELL
                        else -> SteeringMode.SPLIT_TRAFFIC
                    }
                }

                // ====================== VIDEO ======================
                TrafficType.VIDEO -> {
                    val wifiScore = scoreVideo(m.wifiJitter, m.wifiThr)
                    val cellScore = scoreVideo(m.cellJitter, m.cellThr)

                    Log.d(
                        TAG_ATSSS_SCORE,
                        "VIDEO wifiScore=$wifiScore cellScore=$cellScore"
                    )

                    when {
                        wifiGood -> SteeringMode.STEER_TO_WIFI
                        cellGood -> SteeringMode.STEER_TO_CELL
                        wifiScore > cellScore -> SteeringMode.STEER_TO_WIFI
                        else -> SteeringMode.STEER_TO_CELL
                    }
                }

                // ====================== WEB ======================
                TrafficType.WEB -> {
                    Log.d(TAG_ATSSS, "WEB → SPLIT_TRAFFIC")
                    SteeringMode.SPLIT_TRAFFIC
                }

                // ====================== IoT ======================
                TrafficType.IOT -> {
                    val wifiLat = m.wifiRtt ?: 999.0
                    val cellLat = m.cellRtt ?: 999.0

                    Log.d(TAG_ATSSS_SCORE, "IOT wifiLat=$wifiLat cellLat=$cellLat")

                    when {
                        wifiLat < cellLat * 0.9 -> SteeringMode.STEER_TO_WIFI
                        cellLat < wifiLat * 0.9 -> SteeringMode.STEER_TO_CELL
                        else -> SteeringMode.SPLIT_TRAFFIC
                    }
                }
            }

        Log.i(TAG_ATSSS, "ATSSS_DECISION traffic=$type -> $result")

        return result
    }

    // ===================================================================
    // SCORING HELPERS
    // ===================================================================
    private fun scoreUrlcc(rtt: Double?, jitter: Double?): Double {
        if (rtt == null || jitter == null) return 0.0
        val rttScore = 1.0 - (rtt / 150.0).coerceIn(0.0, 1.0)
        val jitScore = 1.0 - (jitter / 80.0).coerceIn(0.0, 1.0)
        return 0.6 * rttScore + 0.4 * jitScore
    }

    private fun scoreEmbb(thr: Double?, quality: Double?): Double {
        if (thr == null) return 0.0
        val thrNorm = (thr / 80.0).coerceIn(0.0, 1.0)
        val qNorm = (((quality ?: -120.0) + 120.0) / 40.0).coerceIn(0.0, 1.0)
        return 0.7 * thrNorm + 0.3 * qNorm
    }

    private fun scoreVideo(jitter: Double?, thr: Double?): Double {
        val jitNorm = 1.0 - ((jitter ?: 200.0) / 120.0).coerceIn(0.0, 1.0)
        val thrNorm = ((thr ?: 0.0) / 40.0).coerceIn(0.0, 1.0)
        return 0.65 * jitNorm + 0.35 * thrNorm
    }

    private companion object {
        val STEER_TO_CELL = SteeringMode.STEER_TO_CELL
        val STEER_TO_WIFI = SteeringMode.STEER_TO_WIFI
        val SPLIT_TRAFFIC = SteeringMode.SPLIT_TRAFFIC
    }
}
