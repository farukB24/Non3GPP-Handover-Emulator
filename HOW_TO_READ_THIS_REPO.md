# How to Read This Repository

This document is a reading guide for the repository.

It explains where to start, which files matter first, and how the pieces fit together, without requiring prior knowledge of the codebase.



# Recommended Reading Order

Please follow this order for the fastest understanding of the system.



# 1️⃣ Start Here: Conceptual Overview

Read first

* `ARCHITECTURE.md`

This file explains:

* Overall system design
* Data flow
* Decision logic separation
* Role of each major component

📌 *No code reading required at this stage.*



# 2️⃣ Entry Point & UI Control

Files

* `MainActivity.kt`
* `CoreConnectionActivity.kt`

Purpose:

* Application entry point
* Start / stop monitoring
* Display metrics and decisions

What to look for:

* How the foreground service is started
* How metrics are received via broadcasts
* How the UI reflects active access path

⚠️ These files do not contain handover logic.



# 3️⃣ Core Runtime Logic (Most Important)

File

* `HandoverMonitorService.kt`

This is the heart of the system.

Focus on:

* Periodic monitoring loop
* Network-specific measurements
* Separation of Wi-Fi and Cellular paths
* Metric aggregation

Key concepts to understand:

* Foreground service lifecycle
* `ConnectivityManager` usage
* Explicit network binding
* Measurement intervals and timing

📌 If only one file is read in detail, it should be this one.



# 4️⃣ Decision-Making Logic

File

* `HandoverDecisionEngine.kt`

Purpose:

* Convert raw metrics into a decision
* Apply scoring, hysteresis, and stability rules

Important notes:

* No Android-specific APIs here
* Logic is deterministic and testable
* Input: metrics
* Output: decision + scores

This file can be read independently of the rest of the app.



# 5️⃣ Policy Abstraction Layer

File

* `AtsssPolicyManager.kt`

Purpose:

* Abstract policy evaluation
* Provide ATSSS-inspired structure

Current role:

* Policy scaffolding
* Future extensibility

This file is not required to understand the current handover behavior but shows how policies can be integrated.



# Configuration Files

* `assets/ho_thresholds.json`

This file defines:

* Profiles
* Metric weights
* Thresholds
* Hysteresis parameters

📌 Changing this file alters system behavior without code changes.



# What Can Be Skipped on First Read

* UI layout files
* Resource files (icons, styles)
* Manifest permissions (unless debugging)



# How to Validate Understanding

After reading the files in this order, you should be able to answer:

* How are Wi-Fi and Cellular measured independently?
* Where is the handover decision made?
* What prevents oscillations?
* How can behavior be tuned without changing code?

If the answer to these is clear, the repository has been understood correctly.



# Suggested Workflow for Reviewers

1. Read `ARCHITECTURE.md`
2. Skim UI files
3. Read `HandoverMonitorService.kt`
4. Read `HandoverDecisionEngine.kt`
5. Inspect configuration JSON

Total estimated time: ~20–30 minutes



# Final Note

This repository is structured for clarity over cleverness.

Each file has a single responsibility, and the reading order above reflects how the system was designed to be understood.
