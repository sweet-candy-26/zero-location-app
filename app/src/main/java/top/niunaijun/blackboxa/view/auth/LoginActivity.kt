package top.niunaijun.blackboxa.view.auth

import android.annotation.SuppressLint
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.data.SessionManager
import top.niunaijun.blackboxa.databinding.ActivityLoginBinding
import top.niunaijun.blackboxa.util.InjectionUtil
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.view.list.ListViewModel
import top.niunaijun.blackboxa.view.main.MainActivity
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * LoginActivity for ZERO LOCATION.
 * Validates client license key against live remote PHP API gateway.
 */
class LoginActivity : AppCompatActivity() {

    private val binding: ActivityLoginBinding by inflate()
    private lateinit var sessionManager: SessionManager

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    companion object {
        private const val TAG = "ZeroLocationAuth"
        // Live PHP authentication API endpoint (Cleartext HTTP)
        private const val VERIFY_KEY_URL = "http://zerolocation.lovestoblog.com/verify_key.php"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionManager = SessionManager(this)

        // If previously authenticated, skip login and enter dashboard directly
        if (sessionManager.isLoggedIn()) {
            launchHomeScreen()
            return
        }

        setContentView(binding.root)
        initViews()
    }

    private fun initViews() {
        binding.btnLogin.setOnClickListener {
            val key = binding.etLicenseKey.text?.toString()?.trim().orEmpty()
            if (key.isEmpty()) {
                showToast(getString(R.string.toast_empty_key))
                return@setOnClickListener
            }
            verifyLicenseKey(key)
        }
    }

    @SuppressLint("HardwareIds")
    private fun getDeviceId(): String {
        return try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN_DEVICE_ID"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read ANDROID_ID: ${e.message}")
            "UNKNOWN_DEVICE_ID"
        }
    }

    private fun verifyLicenseKey(licenseKey: String) {
        setLoadingState(true)

        val deviceId = getDeviceId()

        // JSON payload format: {"key": "ENTERED_KEY", "device_id": "ANDROID_ID"}
        val jsonPayload = JSONObject().apply {
            put("key", licenseKey)
            put("device_id", deviceId)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = jsonPayload.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(VERIFY_KEY_URL)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .build()

        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Network call failed (timeout/no-internet): ${e.message}", e)
                runOnUiThread {
                    setLoadingState(false)
                    showToast("Server Unreachable. Check your internet.")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val statusCode = response.code
                val responseBody = try {
                    response.body?.string().orEmpty()
                } catch (e: Exception) {
                    ""
                }

                runOnUiThread {
                    setLoadingState(false)
                    handleServerResponse(statusCode, response.isSuccessful, responseBody, licenseKey)
                }
            }
        })
    }

    private fun handleServerResponse(
        statusCode: Int,
        isHttpSuccess: Boolean,
        responseBody: String,
        licenseKey: String
    ) {
        // Handle HTTP failure codes (404, 500, etc.) or empty response
        if (!isHttpSuccess && (statusCode == 404 || statusCode >= 500 || responseBody.isEmpty())) {
            showToast("Server Unreachable. Check your internet.")
            return
        }

        try {
            val json = JSONObject(responseBody)
            val status = json.optString("status")

            if ("success".equals(status, ignoreCase = true)) {
                // {"status": "success", "token": "auth_token_12345"}
                val token = json.optString("token", "")
                sessionManager.saveLoginSession(licenseKey, token)
                showToast("Welcome to ZERO LOCATION")
                launchHomeScreen()
            } else if ("error".equals(status, ignoreCase = true)) {
                // {"status": "error", "message": "Invalid or expired key"}
                val errorMessage = json.optString("message", "Invalid or expired key")
                showToast(errorMessage)
            } else {
                val fallbackMsg = json.optString("message", "Invalid or expired key")
                showToast(fallbackMsg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing server JSON response: ${e.message}", e)
            showToast("Server Unreachable. Check your internet.")
        }
    }

    private fun setLoadingState(isLoading: Boolean) {
        if (isLoading) {
            binding.btnLogin.visibility = View.INVISIBLE
            binding.progressBar.visibility = View.VISIBLE
            binding.etLicenseKey.isEnabled = false
            binding.tvStatusMessage.visibility = View.VISIBLE
            binding.tvStatusMessage.text = getString(R.string.verifying_key)
        } else {
            binding.btnLogin.visibility = View.VISIBLE
            binding.progressBar.visibility = View.GONE
            binding.etLicenseKey.isEnabled = true
            binding.tvStatusMessage.visibility = View.GONE
        }
    }

    private fun launchHomeScreen() {
        try {
            val viewModel = ViewModelProvider(this, InjectionUtil.getListFactory()).get(ListViewModel::class.java)
            viewModel.previewInstalledList()
        } catch (e: Exception) {
            Log.w(TAG, "Could not preload installed list: ${e.message}")
        }
        MainActivity.start(this)
        finish()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
