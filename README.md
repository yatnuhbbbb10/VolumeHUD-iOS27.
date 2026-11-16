# VolumeHUD iOS 27 Beta 4 — LSPosed source (0.1.6-dev)

Target: TECNO LI7 / Android 15 / HiOS 15.1.2.170.

This is a development source project. It observes initial physical volume-key DOWN events in system_server, relays them to SystemUI, and displays a vertical Liquid Glass-style volume capsule. Dragging the capsule requests volume changes through the Transsion VolumeDialogController API.

The module does not modify brightness, Control Center, or Quick Settings. The native Transsion volume dialog is deliberately retained as a fallback. It is not considered device-verified until the hook, relay, overlay attachment, and live volume drag are tested on the target firmware.

## Build

Requires JDK 17+, Gradle 8.9+, Android SDK Platform 35, Build Tools 35.0.0, and Xposed API 82. GitHub Actions builds a debug APK and checks the APK signature, Xposed entry-point asset, manifest metadata, absence of bundled Xposed API classes, and SHA-256 checksum.

## LSPosed scope and testing

Enable the module for System (system_server) and `com.android.systemui`. Do not scope it to all apps. Verify that the native volume dialog still works, first physical press shows the long capsule, second press switches to compact, subsequent presses alternate, and vertical drag changes actual volume. If the overlay is rejected or SystemUI becomes unstable, disable the module. No target-device test is claimed by this source upload.
