# Non-3GPP Access Emulator & Handover Decision Framework (Android)

# Overview

This repository contains an Android-based experimental framework developed to  monitor, evaluate, and compare Wi-Fi (Non-3GPP) and Cellular (3GPP) access paths in real time, and to make handover decisions based on multi-metric network conditions.

The application is designed as a research and experimentation tool, not as a production-ready handover implementation.
Its primary goal is to observe realistic network metrics, feed them into a configurable decision engine, and analyze handover behavior under different conditions.


# Key Features

* Real-time monitoring of Wi-Fi and Cellular links
* Separate measurement of:

  * RTT (latency)
  * Jitter
  * Signal quality (RSSI, RSRP, SINR)
  * Uplink and Downlink throughput (per access path)
* Dual-path throughput testing using Network binding
* Profile-based handover decision engine
* Fully configurable handover parameters via JSON
* Designed for rooted devices (optional but recommended)


# Project Structure

```
app/
 └── src/main/java/com/example/non3gppaccessemulator/
     ├── MainActivity.kt
     ├── CoreConnectionActivity.kt
     ├── HandoverMonitorService.kt
     ├── HandoverDecisionEngine.kt
     ├── AtsssPolicyManager.kt
```

# Main Components

# `HandoverMonitorService`

Foreground service responsible for:

* Collecting Wi-Fi and Cellular metrics
* Measuring RTT, jitter, UL/DL throughput
* Broadcasting metrics periodically
* Feeding data into the handover decision engine

# `HandoverDecisionEngine`

Core logic implementing:

* Metric normalization
* Weighted scoring of access paths
* Hysteresis, dwell time, and TTT handling
* Handover event generation

All decision behavior is controlled via configuration parameters.

# `AtsssPolicyManager`

Experimental policy abstraction inspired by ATSSS (3GPP) concepts.
Currently used for rule evaluation and future extensibility.

# `MainActivity / CoreConnectionActivity`

UI and control layer:

* Starts/stops monitoring
* Displays live metrics
* Shows active access path and handover events


# Handover Logic Summary

The handover decision is based on a multi-metric weighted score, considering:

* Latency
* Jitter
* Throughput
* Signal quality

To avoid ping-pong effects:

* Time-To-Trigger (TTT)
* Hysteresis margins
* Minimum dwell time

are applied before any handover decision is finalized.


# Configuration

Handover parameters are defined in:

```
app/src/main/assets/ho_thresholds.json
```

Each profile (e.g. `web`) specifies:

* Metric weights
* Thresholds
* Timing constraints
* Minimum performance gains required for handover

No code changes are required to adjust these parameters.


# Device & Environment Requirements

* Android device (tested on Pixel series)
* Android 13+
* Root access recommended (for enhanced cellular signal metrics)
* Active Wi-Fi and Cellular connectivity
* Internet access to a reachable HTTPS endpoint

> Emulator support is limited and not recommended for realistic measurements.

# How to Run

1. Open the project in Android Studio
2. Grant required permissions
3. Deploy to a real device
4. Start the monitoring service from the app UI
5. Observe metrics and handover decisions in real time

# Scope & Limitations

* This framework does not modify system-level handover behavior
* Measurements are application-level observations
* Throughput tests are short and lightweight by design
* Results should be interpreted comparatively, not as absolute link capacity

# Purpose

This project was developed as part of a research study focusing on:

* Non-3GPP access evaluation
* Multi-access decision logic
* Handover behavior analysis

The repository is shared to provide code transparency and reproducibility, not as a commercial or production-ready solution.

Developed for academic and research purposes.
