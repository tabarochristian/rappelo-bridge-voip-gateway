package com.gateway.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.gateway.R
import com.gateway.data.prefs.EncryptedPrefsManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import android.widget.RadioGroup
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {

    @Inject
    lateinit var prefsManager: EncryptedPrefsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.apply {
            title = getString(R.string.settings_title)
            setDisplayHomeAsUpEnabled(true)
        }

        loadCurrentSettings()
        setupSaveButton()
    }

    private fun loadCurrentSettings() {
        findViewById<TextInputEditText>(R.id.sipServerInput).setText(prefsManager.getSipServer())
        findViewById<TextInputEditText>(R.id.sipUsernameInput).setText(prefsManager.getSipUsername())
        findViewById<TextInputEditText>(R.id.sipPasswordInput).setText(prefsManager.getSipPassword())
        findViewById<TextInputEditText>(R.id.sipDomainInput).setText(prefsManager.getSipDomain())
        findViewById<TextInputEditText>(R.id.sipDisplayNameInput).setText(prefsManager.getSipDisplayName())

        findViewById<TextInputEditText>(R.id.stunServerInput).setText(prefsManager.getStunServer())
        findViewById<TextInputEditText>(R.id.turnServerInput).setText(prefsManager.getTurnServer())
        findViewById<TextInputEditText>(R.id.turnUsernameInput).setText(prefsManager.getTurnUsername())
        findViewById<TextInputEditText>(R.id.turnPasswordInput).setText(prefsManager.getTurnPassword())
        findViewById<SwitchMaterial>(R.id.iceEnabledSwitch).isChecked = prefsManager.isIceEnabled()

        val transportGroup = findViewById<RadioGroup>(R.id.transportGroup)
        when (prefsManager.getSipTransport()) {
            "TCP" -> transportGroup.check(R.id.transportTcp)
            else -> transportGroup.check(R.id.transportUdp)
        }
    }

    private fun setupSaveButton() {
        findViewById<MaterialButton>(R.id.saveButton).setOnClickListener {
            saveSettings()
        }
    }

    private fun saveSettings() {
        val sipServer = findViewById<TextInputEditText>(R.id.sipServerInput).text.toString().trim()
        val sipUsername = findViewById<TextInputEditText>(R.id.sipUsernameInput).text.toString().trim()
        val sipPassword = findViewById<TextInputEditText>(R.id.sipPasswordInput).text.toString()
        val sipDomain = findViewById<TextInputEditText>(R.id.sipDomainInput).text.toString().trim()
        val sipDisplayName = findViewById<TextInputEditText>(R.id.sipDisplayNameInput).text.toString().trim()

        if (sipServer.isEmpty() || sipUsername.isEmpty() || sipPassword.isEmpty()) {
            Toast.makeText(this, R.string.settings_error_required, Toast.LENGTH_SHORT).show()
            return
        }

        prefsManager.setSipServer(sipServer)
        prefsManager.setSipUsername(sipUsername)
        prefsManager.setSipPassword(sipPassword)
        prefsManager.setSipDomain(sipDomain.ifEmpty { sipServer })
        prefsManager.setSipDisplayName(sipDisplayName.ifEmpty { "Gateway" })

        prefsManager.setStunServer(
            findViewById<TextInputEditText>(R.id.stunServerInput).text.toString().trim()
        )
        prefsManager.setTurnServer(
            findViewById<TextInputEditText>(R.id.turnServerInput).text.toString().trim()
        )
        prefsManager.setTurnUsername(
            findViewById<TextInputEditText>(R.id.turnUsernameInput).text.toString().trim()
        )
        prefsManager.setTurnPassword(
            findViewById<TextInputEditText>(R.id.turnPasswordInput).text.toString()
        )
        prefsManager.setIceEnabled(
            findViewById<SwitchMaterial>(R.id.iceEnabledSwitch).isChecked
        )

        val transportGroup = findViewById<RadioGroup>(R.id.transportGroup)
        val transport = if (transportGroup.checkedRadioButtonId == R.id.transportTcp) "TCP" else "UDP"
        prefsManager.setSipTransport(transport)

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
