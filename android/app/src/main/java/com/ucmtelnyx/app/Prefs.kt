package com.ucmtelnyx.app

import android.content.Context

/**
 * Local, per-device settings. Only the server URL lives here now - SIP details
 * come from the server based on the extension an admin assigned to the account,
 * so there is nothing credential-shaped stored on the device.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("ucm_telnyx_prefs", Context.MODE_PRIVATE)

    var backendUrl: String
        get() = sp.getString("backendUrl", "") ?: ""
        set(value) = sp.edit().putString("backendUrl", value).apply()
}
