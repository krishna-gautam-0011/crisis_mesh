package com.example.p2pchat.access

/**
 * ============================================================================
 *  DISTRIBUTION CONTROL — this is the ONLY file you should need to edit to
 *  decide which devices are allowed to open the app.
 * ============================================================================
 *
 * How it works:
 *  - Every Android device exposes a per-app-install "ANDROID_ID".
 *  - On launch, the app hashes that ID with SHA-256 (see [DeviceAccess]) and
 *    compares it against [AUTHORIZED_DEVICE_HASHES] below.
 *  - If the hash isn't in the set, the app shows a "device not authorized"
 *    screen that displays that device's own hash so its owner can send it to
 *    you — you add it to the set below, rebuild, and redistribute the APK.
 *
 * Onboarding a new device:
 *  1. Set [ALLOW_ALL_DEVICES_DEBUG_ONLY] = true temporarily, OR just install
 *     the current build on the new device — it will show its own hash on the
 *     blocked screen even while locked out.
 *  2. Copy that hash string into [AUTHORIZED_DEVICE_HASHES] below.
 *  3. Rebuild and hand them the new APK.
 *
 * Caveats worth knowing (so this doesn't surprise you later):
 *  - ANDROID_ID resets if the device is factory-reset, and can change if you
 *    ever re-sign the app with a different signing key. It is a convenience
 *    gate for controlling who you hand APKs to — not tamper-proof DRM. Anyone
 *    with the APK and some Android knowledge could patch the check out.
 *  - This only gates the app's own UI; it doesn't stop someone from reading
 *    the APK's code, so don't treat [AUTHORIZED_DEVICE_HASHES] as a secret.
 */
object AccessConfig {

    /** Shown on the blocked screen (e.g. "Ask <CONTROLLER_NAME> to add your device"). */
    const val CONTROLLER_NAME: String = "krishna"

    /**
     * SHA-256 hashes (lowercase hex) of every ANDROID_ID that is allowed to open
     * the app. Add one line per authorized device. Leave empty and every device
     * will be blocked until you add its hash (or flip the debug switch below).
     */
    val AUTHORIZED_DEVICE_HASHES: Set<String> = setOf(
        "4c0d2e6cac1b79ae737427a1b7404de86783de298fa1c5e4a5852e44d4fc0989"
    )

    /**
     * Safety valve for testing only — when true, EVERY device is let in
     * regardless of [AUTHORIZED_DEVICE_HASHES]. Must be false for any build
     * you actually hand out if you care about controlling distribution.
     */
    const val ALLOW_ALL_DEVICES_DEBUG_ONLY: Boolean = false
}
