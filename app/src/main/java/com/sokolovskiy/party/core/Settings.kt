package com.sokolovskiy.party.core

import android.content.Context

/** Host-side preferences that survive restarts. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("party", Context.MODE_PRIVATE)

    /** Players connect through the Render relay instead of (in addition to) local Wi-Fi. */
    var relayEnabled: Boolean
        get() = prefs.getBoolean("relayEnabled", false)
        set(v) = prefs.edit().putBoolean("relayEnabled", v).apply()

    var relayUrl: String
        get() = prefs.getString("relayUrl", null) ?: DEFAULT_RELAY
        set(v) = prefs.edit().putString("relayUrl", v).apply()

    /** "CODE:key" of the last relay room, so the QR stays valid across app restarts. */
    var relayRoom: String?
        get() = prefs.getString("relayRoom", null)
        set(v) = prefs.edit().putString("relayRoom", v).apply()

    companion object {
        const val DEFAULT_RELAY = "https://party-games-relay.onrender.com"
    }
}
