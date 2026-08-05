package com.custom.astrion.config

import android.content.Context
import com.custom.astrion.BuildConfig

/**
 * Where the HA connection details live — deliberately NOT compiled into the APK.
 *
 * Modelled on the stock HaRemote app, which keeps its credentials in
 * app-private SharedPreferences (/data/data/com.aiks.HaRemote/shared_prefs/
 * credentials.xml → key `strAppData` = {"accessToken":…,"serverIP":…}) and
 * receives them from its pairing web server. We do the same: ConfigServer serves
 * a form on http://<remote-ip>:8099 and writes the result here.
 *
 * App-private storage matters — a long-lived token in /sdcard is readable by ANY
 * app holding the storage permission, so there is deliberately no config file
 * on shared storage.
 *
 * Resolution order: SharedPreferences, then BuildConfig (legacy fallback; left
 * blank in secrets.properties so no credential is baked into the binary).
 */
object ConnectionConfig {
    private const val TAG = "ConnectionConfig"
    private const val PREFS = "astrion_connection"
    private const val KEY_URL = "url"
    private const val KEY_TOKEN = "token"

    data class Connection(val url: String, val token: String) {
        val isComplete: Boolean get() = url.isNotBlank() && token.isNotBlank()
    }

    /** Resolve the stored connection, falling back to the (blank) build config. */
    fun load(context: Context): Connection {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = Connection(
            prefs.getString(KEY_URL, "").orEmpty(),
            prefs.getString(KEY_TOKEN, "").orEmpty(),
        )
        if (stored.isComplete) return stored
        return Connection(BuildConfig.HA_URL, BuildConfig.HA_TOKEN)
    }

    /** Persist credentials received from the setup server. */
    fun save(context: Context, conn: Connection) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_URL, conn.url)
            .putString(KEY_TOKEN, conn.token)
            .apply()
    }
}
