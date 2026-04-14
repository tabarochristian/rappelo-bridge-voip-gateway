package com.gateway.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed class NetworkStatus {
    data object Available : NetworkStatus()
    data object Unavailable : NetworkStatus()
    data object Losing : NetworkStatus()
    data object Lost : NetworkStatus()
}

data class NetworkInfo(
    val isConnected: Boolean,
    val isWifi: Boolean,
    val isCellular: Boolean,
    val isVpn: Boolean,
    val hasInternet: Boolean
)

@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "NetworkMonitor"
    }

    private val connectivityManager: ConnectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val _networkStatus = MutableStateFlow<NetworkStatus>(NetworkStatus.Unavailable)
    val networkStatus: StateFlow<NetworkStatus> = _networkStatus.asStateFlow()

    private val _networkInfo = MutableStateFlow(NetworkInfo(
        isConnected = false,
        isWifi = false,
        isCellular = false,
        isVpn = false,
        hasInternet = false
    ))
    val networkInfo: StateFlow<NetworkInfo> = _networkInfo.asStateFlow()

    private val _isNetworkAvailable = MutableStateFlow(false)
    /** Whether the network is currently available and validated. */
    val isNetworkAvailable: StateFlow<Boolean> = _isNetworkAvailable.asStateFlow()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    val isConnected: Boolean
        get() = _networkInfo.value.isConnected

    val hasInternet: Boolean
        get() = _networkInfo.value.hasInternet

    fun startMonitoring() {
        if (networkCallback != null) {
            GatewayLogger.d(TAG, "Already monitoring network")
            return
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                GatewayLogger.d(TAG, "Network available")
                _networkStatus.value = NetworkStatus.Available
                updateNetworkInfo(network)
            }

            override fun onLosing(network: Network, maxMsToLive: Int) {
                GatewayLogger.d(TAG, "Network losing, max ms to live: $maxMsToLive")
                _networkStatus.value = NetworkStatus.Losing
            }

            override fun onLost(network: Network) {
                GatewayLogger.d(TAG, "Network lost")
                _networkStatus.value = NetworkStatus.Lost
                _networkInfo.value = NetworkInfo(
                    isConnected = false,
                    isWifi = false,
                    isCellular = false,
                    isVpn = false,
                    hasInternet = false
                )
                _isNetworkAvailable.value = false
            }

            override fun onUnavailable() {
                GatewayLogger.d(TAG, "Network unavailable")
                _networkStatus.value = NetworkStatus.Unavailable
                _networkInfo.value = NetworkInfo(
                    isConnected = false,
                    isWifi = false,
                    isCellular = false,
                    isVpn = false,
                    hasInternet = false
                )
                _isNetworkAvailable.value = false
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                updateNetworkInfo(network, capabilities)
            }
        }

        try {
            connectivityManager.registerNetworkCallback(request, networkCallback!!)
            GatewayLogger.i(TAG, "Network monitoring started")
            
            // Initial check
            checkCurrentNetwork()
        } catch (e: Exception) {
            GatewayLogger.e(TAG, "Failed to register network callback: ${e.message}", e)
        }
    }

    fun stopMonitoring() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
                GatewayLogger.i(TAG, "Network monitoring stopped")
            } catch (e: Exception) {
                GatewayLogger.e(TAG, "Error unregistering network callback: ${e.message}", e)
            }
        }
        networkCallback = null
    }

    private fun checkCurrentNetwork() {
        val network = connectivityManager.activeNetwork
        if (network != null) {
            updateNetworkInfo(network)
            _networkStatus.value = NetworkStatus.Available
        } else {
            _networkStatus.value = NetworkStatus.Unavailable
        }
    }

    private fun updateNetworkInfo(network: Network, capabilities: NetworkCapabilities? = null) {
        val caps = capabilities ?: connectivityManager.getNetworkCapabilities(network)
        
        val info = NetworkInfo(
            isConnected = true,
            isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
            isCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
            isVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
            hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        )
        _networkInfo.value = info
        _isNetworkAvailable.value = info.hasInternet
    }

    fun observeNetworkStatus(): Flow<NetworkStatus> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(NetworkStatus.Available)
            }

            override fun onLosing(network: Network, maxMsToLive: Int) {
                trySend(NetworkStatus.Losing)
            }

            override fun onLost(network: Network) {
                trySend(NetworkStatus.Lost)
            }

            override fun onUnavailable() {
                trySend(NetworkStatus.Unavailable)
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, callback)

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }
}
