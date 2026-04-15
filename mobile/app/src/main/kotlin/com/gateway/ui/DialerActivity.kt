package com.gateway.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.gateway.util.GatewayLogger

/**
 * Minimal dialer activity required for the app to hold the ROLE_DIALER (default phone app) role.
 * Android requires a default dialer to have an activity with ACTION_DIAL intent filter.
 *
 * For ACTION_CALL (outgoing calls placed by the bridge), this finishes immediately
 * without showing any UI — the InCallService handles everything.
 * For ACTION_DIAL (user taps a phone number), this redirects to MainActivity.
 */
class DialerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DialerActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent?.action
        val data = intent?.data
        GatewayLogger.info(TAG, "DialerActivity launched: action=$action data=$data")

        // Detect USSD codes: tel:*123# or tel:%2A123%23
        val number = data?.schemeSpecificPart ?: ""
        val isUssd = number.contains("*") && number.contains("#")

        when {
            // USSD code — forward to MainActivity so the USSD panel handles it
            isUssd -> {
                GatewayLogger.info(TAG, "USSD code detected: $number — forwarding to MainActivity")
                val mainIntent = Intent(this, MainActivity::class.java).apply {
                    putExtra("ussd_code", number)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(mainIntent)
                finish()
            }
            // Outgoing call placed by our bridge or the system — absorb silently
            action == Intent.ACTION_CALL || action == "android.intent.action.CALL_PRIVILEGED" -> {
                GatewayLogger.info(TAG, "Outgoing call intent absorbed silently")
                finish()
            }
            // User tapped a phone number or opened the dialer — show main UI
            else -> {
                val mainIntent = Intent(this, MainActivity::class.java).apply {
                    this.data = intent?.data
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(mainIntent)
                finish()
            }
        }
    }
}
