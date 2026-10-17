package com.chun.lingzhu

/**
 * Display identity for this fork.
 *
 * The original internal build embedded preset endpoints and API keys at build time.
 * This open-source fork carries none of them: every user configures their own
 * endpoint, key and model in Settings. The preset/profile plumbing was removed
 * together with the build-time injection; only the display constants remain.
 */
object Preset {
    val channel: String get() = BuildConfig.CHANNEL
    val appName: String get() = BuildConfig.APP_NAME
}
