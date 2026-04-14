package com.gateway.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
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

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
        updatePermissionUI()
    }
}
