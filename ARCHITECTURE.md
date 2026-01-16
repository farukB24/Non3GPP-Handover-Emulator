# Architecture & Data Flow

This document explains the  runtime architecture ,  data flow , and  control logic  of the Non-3GPP Access Emulator application.

The goal is to provide a clear mental model of how the system operates without requiring a deep dive into the source code.



 # High-Level Architecture

The application is composed of  three logical layers :

```
┌──────────────────────────────┐
│        UI / Control Layer    │
│  (MainActivity, CoreConn)    │
└──────────────┬───────────────┘
               │
┌──────────────▼───────────────┐
│   Monitoring & Measurement   │
│    (HandoverMonitorService)  │
└──────────────┬───────────────┘
               │
┌──────────────▼───────────────┐
│     Decision & Policy Layer  │
│  (HandoverDecisionEngine,    │
│   AtsssPolicyManager)        │
└──────────────────────────────┘
```

Each layer is isolated by responsibility to keep the system modular and extensible.



 # 1. UI / Control Layer

 Relevant files 

* `MainActivity.kt`
* `CoreConnectionActivity.kt`

  # Responsibilities

* Start and stop the monitoring service
* Display real-time metrics
* Show current active access path (Wi-Fi / Cellular)
* Visualize handover decisions

  # Key Characteristics

* Contains  no decision logic 
* Does not directly access network APIs
* Communicates with the service via  broadcasts 

This separation ensures that UI changes do not affect measurement or decision behavior.



 # 2. Monitoring & Measurement Layer

 Relevant file 

* `HandoverMonitorService.kt`

This is the  core runtime component  of the application.

  # Why a Foreground Service?

* Continuous monitoring is required
* Android background execution limits apply
* Foreground service guarantees stable execution under load



  # Metric Collection Pipeline

Each monitoring cycle performs the following steps:

```
1. Read signal quality
2. Measure RTT & jitter
3. Measure UL/DL throughput
4. Feed metrics into decision engine
5. Broadcast results
```

This loop runs periodically with a configurable interval.



  # Network Separation (Critical Design Choice)

Wi-Fi and Cellular measurements are  explicitly separated  using:

* `ConnectivityManager`
* `NetworkRequest`
* `Network.bindSocket()` / `Network.openConnection()`

This ensures that:

* Wi-Fi metrics are measured over Wi-Fi
* Cellular metrics are measured over Cellular
* No implicit Android routing decisions affect results



  # Measured Metrics

| Metric              | Wi-Fi | Cellular |
| - | -- | -- |
| RTT                 | ✓     | ✓        |
| Jitter              | ✓     | ✓        |
| Downlink Throughput | ✓     | ✓        |
| Uplink Throughput   | ✓     | ✓        |
| RSSI                | ✓     | –        |
| RSRP / SINR         | –     | ✓        |

> Root access enables more accurate cellular signal readings but is optional.



 # 3. Decision & Policy Layer

 Relevant files 

* `HandoverDecisionEngine.kt`
* `AtsssPolicyManager.kt`

  # HandoverDecisionEngine

This component performs  pure decision logic .

It receives:

* Wi-Fi metrics
* Cellular metrics
* Current active access path

It outputs:

* Active path decision
* Handover event (if any)
* Internal scores for both paths



  # Scoring Model

Each access path is evaluated using a  weighted multi-metric score :

```
Score = w1·Latency + w2·Jitter + w3·Throughput + w4·Signal
```

Weights and thresholds are  profile-dependent  and externally configurable.



  # Stability Mechanisms

To avoid oscillations and false handovers, the engine applies:

* Time-To-Trigger (TTT)
* Hysteresis margins
* Minimum dwell time
* Minimum throughput gain constraints

A handover is triggered  only if all conditions are satisfied .



  # AtsssPolicyManager

This module provides an abstraction layer inspired by  3GPP ATSSS concepts .

Current role:

* Policy evaluation
* Future extensibility for traffic steering or splitting

It does  not  perform low-level packet routing.



 # Data Flow Summary

```
[Network Interfaces]
        │
        ▼
[HandoverMonitorService]
        │
        ▼
[Metric Aggregation]
        │
        ▼
[HandoverDecisionEngine]
        │
        ▼
[Decision Result]
        │
        ▼
[Broadcast to UI]
```

No component directly bypasses this pipeline.



 # Design Philosophy

* Measurement and decision logic are  decoupled 
* All decisions are  data-driven 
* Parameters are  externally configurable 
* The system is designed for  experimentation and analysis , not enforcement



 # What This System Does NOT Do

* It does not force system-level handovers
* It does not modify modem behavior
* It does not claim absolute throughput values

All outputs are  observational and comparative .



 # Intended Usage

* Research experimentation
* Algorithm evaluation
* Handover logic analysis
* Multi-access performance comparison



 # Extensibility Points

* Add new profiles via JSON
* Add new metrics without changing UI
* Replace scoring logic without touching measurement layer
* Integrate traffic steering in future iterations



 # Final Note

This architecture intentionally mirrors how  real multi-access decision systems  are structured, while remaining fully implementable at the Android application layer.

It is designed to be understandable, modifiable, and reproducible.
