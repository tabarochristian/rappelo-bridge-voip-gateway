package com.gateway.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gateway.R
import com.gateway.service.GatewayForegroundService
import com.gateway.util.GatewayLogger
import com.gateway.util.PermissionHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 100
    }

    private val viewModel: StatusViewModel by viewModels()

    @Inject
    lateinit var permissionHelper: PermissionHelper

    private lateinit var statusText: TextView
    private lateinit var sipStatusText: TextView
    private lateinit var gsmStatusText: TextView
    private lateinit var queueStatusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var permissionButton: Button
    private lateinit var logsRecyclerView: RecyclerView
    private lateinit var logAdapter: LogAdapter
    private lateinit var activeCallCard: MaterialCardView
    private lateinit var activeCallNumberText: TextView
    private lateinit var activeCallStateText: TextView
    private lateinit var activeCallDurationText: TextView
    private lateinit var hangupButton: MaterialButton
    private lateinit var dialerFab: FloatingActionButton

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            GatewayLogger.i(TAG, "All permissions granted")
            updatePermissionUI()
        } else {
            GatewayLogger.w(TAG, "Some permissions denied")
            updatePermissionUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupClickListeners()
        observeViewModel()
        checkPermissions()
        handleUssdIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUssdIntent(intent)
    }

    private fun handleUssdIntent(intent: Intent?) {
        val ussdCode = intent?.getStringExtra("ussd_code")
        if (!ussdCode.isNullOrEmpty()) {
            val dialerIntent = Intent(this, DialerPadActivity::class.java).apply {
                putExtra("ussd_code", ussdCode)
            }
            startActivity(dialerIntent)
            intent?.removeExtra("ussd_code")
        }
    }

    private fun initViews() {
        statusText = findViewById(R.id.statusText)
        sipStatusText = findViewById(R.id.sipStatusText)
        gsmStatusText = findViewById(R.id.gsmStatusText)
        queueStatusText = findViewById(R.id.queueStatusText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        permissionButton = findViewById(R.id.permissionButton)
        logsRecyclerView = findViewById(R.id.logsRecyclerView)
        activeCallCard = findViewById(R.id.activeCallCard)
        activeCallNumberText = findViewById(R.id.activeCallNumberText)
        activeCallStateText = findViewById(R.id.activeCallStateText)
        activeCallDurationText = findViewById(R.id.activeCallDurationText)
        hangupButton = findViewById(R.id.hangupButton)
        dialerFab = findViewById(R.id.ussdFab)

        logAdapter = LogAdapter()
        logsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                reverseLayout = true
                stackFromEnd = true
            }
            adapter = logAdapter
        }
    }

    private fun setupClickListeners() {
        startButton.setOnClickListener {
            startGatewayService()
        }

        stopButton.setOnClickListener {
            stopGatewayService()
        }

        hangupButton.setOnClickListener {
            viewModel.hangupActiveCall()
        }

        dialerFab.setOnClickListener {
            startActivity(Intent(this, DialerPadActivity::class.java))
        }

        permissionButton.setOnClickListener {
            requestMissingPermissions()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        updateUI(state)
                    }
                }

                launch {
                    viewModel.logs.collect { logs ->
                        logAdapter.submitList(logs)
                        if (logs.isNotEmpty()) {
                            logsRecyclerView.smoothScrollToPosition(logs.size - 1)
                        }
                    }
                }
            }
        }
    }

    private fun updateUI(state: GatewayUiState) {
        statusText.text = when {
            state.isServiceRunning -> "Service: Running"
            else -> "Service: Stopped"
        }
        statusText.setTextColor(
            getColor(if (state.isServiceRunning) R.color.status_ok else R.color.status_error)
        )

        sipStatusText.text = "SIP: ${state.sipStatus}"
        sipStatusText.setTextColor(
            getColor(if (state.sipRegistered) R.color.status_ok else R.color.status_warning)
        )

        gsmStatusText.text = "GSM: ${state.gsmStatus}"

        queueStatusText.text = "Calls: ${state.queuedCalls}  SMS: ${state.pendingSms}"

        startButton.isEnabled = !state.isServiceRunning && state.hasAllPermissions
        stopButton.isEnabled = state.isServiceRunning

        // Active call card
        if (state.activeCallNumber != null && state.activeCallState != null) {
            activeCallCard.visibility = View.VISIBLE
            activeCallNumberText.text = state.activeCallNumber
            activeCallStateText.text = state.activeCallState
            if (state.activeCallState == "Active") {
                val m = state.activeCallDurationSec / 60
                val s = state.activeCallDurationSec % 60
                activeCallDurationText.text = String.format("%02d:%02d", m, s)
                activeCallDurationText.visibility = View.VISIBLE
            } else {
                activeCallDurationText.visibility = View.GONE
            }
        } else {
            activeCallCard.visibility = View.GONE
        }
    }

    private fun checkPermissions() {
        val missing = permissionHelper.getMissingPermissions()
        updatePermissionUI()
        
        if (missing.isNotEmpty()) {
            GatewayLogger.w(TAG, "Missing permissions: $missing")
        }
    }

    private fun updatePermissionUI() {
        val hasAll = permissionHelper.hasAllRequiredPermissions()
        permissionButton.visibility = if (hasAll) View.GONE else View.VISIBLE
        startButton.isEnabled = hasAll && !viewModel.uiState.value.isServiceRunning

        if (!hasAll) {
            val missing = permissionHelper.getMissingPermissions()
            permissionButton.text = "Grant ${missing.size} Permission(s)"
        }
    }

    private fun requestMissingPermissions() {
        val missing = permissionHelper.getMissingPermissions()
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }

        // Check battery optimization separately
        if (!permissionHelper.isIgnoringBatteryOptimizations()) {
            startActivity(permissionHelper.createBatteryOptimizationIntent())
        }
    }

    private fun startGatewayService() {
        GatewayLogger.i(TAG, "Starting gateway service")
        val intent = Intent(this, GatewayForegroundService::class.java).apply {
            action = GatewayForegroundService.ACTION_START
        }
        startForegroundService(intent)
    }

    private fun stopGatewayService() {
        GatewayLogger.i(TAG, "Stopping gateway service")
        val intent = Intent(this, GatewayForegroundService::class.java).apply {
            action = GatewayForegroundService.ACTION_STOP
        }
        startService(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
        updatePermissionUI()
    }
}
