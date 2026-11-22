package top.niunaijun.blackboxa.data

import android.content.Context
import android.content.SharedPreferences

/**
 * SessionManager handles persistent authentication state and license key data
 * using SharedPreferences for client-side access control.
 */
class SessionManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREF_NAME = "blackbox_auth_session"
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_LICENSE_KEY = "license_key"
        private const val KEY_AUTH_TOKEN = "auth_token"
    }

    /**
     * Check if a valid session exists.
     */
    fun isLoggedIn(): Boolean {
        return prefs.getBoolean(KEY_IS_LOGGED_IN, false)
    }

    /**
     * Save key and token upon successful remote validation.
     */
    fun saveLoginSession(key: String, token: String? = null) {
        prefs.edit().apply {
            putBoolean(KEY_IS_LOGGED_IN, true)
            putString(KEY_LICENSE_KEY, key)
            putString(KEY_AUTH_TOKEN, token ?: "")
            apply()
        }
    }

    /**
     * Get the stored license key.
     */
    fun getLicenseKey(): String? {
        return prefs.getString(KEY_LICENSE_KEY, null)
    }

    /**
     * Get the stored auth token if returned by API.
     */
    fun getAuthToken(): String? {
        return prefs.getString(KEY_AUTH_TOKEN, null)
    }

    /**
     * Clear the current session (e.g., on logout or key expiration).
     */
    fun clearSession() {
        prefs.edit().apply {
            clear()
            apply()
        }
    }
}
