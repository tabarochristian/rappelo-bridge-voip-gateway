package com.gateway.ui

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gateway.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class DialerPadActivity : AppCompatActivity() {

    private val viewModel: StatusViewModel by viewModels()

    private lateinit var numberText: TextView
    private lateinit var backspaceButton: ImageButton
    private lateinit var callActionButton: FloatingActionButton
    private lateinit var ussdActionButton: FloatingActionButton
    private lateinit var ussdResponseCard: MaterialCardView
    private lateinit var ussdResponseText: TextView
    private lateinit var ussdResponseLabel: TextView
    private lateinit var ussdProgress: ProgressBar
    private lateinit var ussdDismissButton: MaterialButton

    private val numberBuffer = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dialer_pad)

        initViews()
        setupKeypad()
        setupActions()
        observeUssd()

        // If launched with a USSD code, pre-fill it
        intent?.getStringExtra("ussd_code")?.let { code ->
            numberBuffer.append(code)
            updateDisplay()
            viewModel.sendUssd(code)
        }
    }

    private fun initViews() {
        numberText = findViewById(R.id.dialerNumberText)
        backspaceButton = findViewById(R.id.backspaceButton)
        callActionButton = findViewById(R.id.callActionButton)
        ussdActionButton = findViewById(R.id.ussdActionButton)
        ussdResponseCard = findViewById(R.id.ussdResponseCard)
        ussdResponseText = findViewById(R.id.ussdResponseText)
        ussdResponseLabel = findViewById(R.id.ussdResponseLabel)
        ussdProgress = findViewById(R.id.ussdProgress)
        ussdDismissButton = findViewById(R.id.ussdDismissButton)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
    }

    private fun setupKeypad() {
        val keys = mapOf(
            R.id.key0 to "0", R.id.key1 to "1", R.id.key2 to "2",
            R.id.key3 to "3", R.id.key4 to "4", R.id.key5 to "5",
            R.id.key6 to "6", R.id.key7 to "7", R.id.key8 to "8",
            R.id.key9 to "9", R.id.keyStar to "*", R.id.keyHash to "#"
        )

        for ((id, digit) in keys) {
            findViewById<MaterialButton>(id).setOnClickListener {
                numberBuffer.append(digit)
                updateDisplay()
            }
        }

        // Long press 0 for +
        findViewById<MaterialButton>(R.id.key0).setOnLongClickListener {
            if (numberBuffer.isEmpty()) {
                numberBuffer.append("+")
            } else {
                numberBuffer.append("+")
            }
            updateDisplay()
            true
        }

        backspaceButton.setOnClickListener {
            if (numberBuffer.isNotEmpty()) {
                numberBuffer.deleteCharAt(numberBuffer.length - 1)
                updateDisplay()
            }
        }

        backspaceButton.setOnLongClickListener {
            numberBuffer.clear()
            updateDisplay()
            true
        }
    }

    private fun setupActions() {
        callActionButton.setOnClickListener {
            val number = numberBuffer.toString()
            if (number.isBlank()) return@setOnClickListener

            if (isUssdCode(number)) {
                viewModel.sendUssd(number)
            } else {
                // Place a call through the bridge (handled by GsmCallManager)
                viewModel.placeCall(number)
                finish()
            }
        }

        ussdActionButton.setOnClickListener {
            val number = numberBuffer.toString()
            if (number.isNotBlank()) {
                viewModel.sendUssd(number)
            }
        }

        ussdDismissButton.setOnClickListener {
            viewModel.dismissUssd()
            ussdResponseCard.visibility = View.GONE
        }
    }

    private fun observeUssd() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    // Sending
                    if (state.ussdSending) {
                        ussdResponseCard.visibility = View.VISIBLE
                        ussdProgress.visibility = View.VISIBLE
                        ussdResponseLabel.text = "Sending USSD…"
                        ussdResponseText.text = ""
                        ussdDismissButton.visibility = View.GONE
                    }

                    // Response
                    if (state.ussdResponse != null) {
                        ussdResponseCard.visibility = View.VISIBLE
                        ussdProgress.visibility = View.GONE
                        ussdResponseLabel.text = "USSD Response"
                        ussdResponseLabel.setTextColor(getColor(R.color.primary))
                        ussdResponseText.text = state.ussdResponse
                        ussdResponseText.setTextColor(getColor(R.color.text_primary))
                        ussdDismissButton.visibility = View.VISIBLE
                    }

                    // Error
                    if (state.ussdError != null) {
                        ussdResponseCard.visibility = View.VISIBLE
                        ussdProgress.visibility = View.GONE
                        ussdResponseLabel.text = "USSD Error"
                        ussdResponseLabel.setTextColor(getColor(R.color.status_error))
                        ussdResponseText.text = state.ussdError
                        ussdResponseText.setTextColor(getColor(R.color.status_error))
                        ussdDismissButton.visibility = View.VISIBLE
                    }

                    // Idle — hide if nothing to show
                    if (!state.ussdSending && state.ussdResponse == null && state.ussdError == null) {
                        ussdResponseCard.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun updateDisplay() {
        val text = numberBuffer.toString()
        numberText.text = text
        backspaceButton.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE

        // Show/hide USSD button based on whether input looks like a USSD code
        ussdActionButton.visibility = if (isUssdCode(text) || text.startsWith("*")) View.VISIBLE else View.GONE
    }

    private fun isUssdCode(input: String): Boolean {
        return input.startsWith("*") && input.endsWith("#") && input.length >= 3
    }
}
