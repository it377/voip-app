package com.ucmtelnyx.app

import android.content.Context

/** Local, per-device settings - mirrors what the web app keeps in localStorage. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("ucm_telnyx_prefs", Context.MODE_PRIVATE)

    var backendUrl: String
        get() = sp.getString("backendUrl", "") ?: ""
        set(value) = sp.edit().putString("backendUrl", value).apply()

    var sipDomain: String
        get() = sp.getString("sipDomain", "") ?: ""
        set(value) = sp.edit().putString("sipDomain", value).apply()

    var sipPort: Int
        get() = sp.getInt("sipPort", 5061)
        set(value) = sp.edit().putInt("sipPort", value).apply()

    var sipTransport: String
        get() = sp.getString("sipTransport", "tls") ?: "tls"
        set(value) = sp.edit().putString("sipTransport", value).apply()

    var sipExtension: String
        get() = sp.getString("sipExtension", "") ?: ""
        set(value) = sp.edit().putString("sipExtension", value).apply()

    var sipPassword: String
        get() = sp.getString("sipPassword", "") ?: ""
        set(value) = sp.edit().putString("sipPassword", value).apply()

    fun hasSipSettings() = sipDomain.isNotBlank() && sipExtension.isNotBlank() && sipPassword.isNotBlank()
}
