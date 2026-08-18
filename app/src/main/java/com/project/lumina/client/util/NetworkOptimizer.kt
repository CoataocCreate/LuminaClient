package com.project.lumina.client.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Process
import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Utility for optimizing network usage:
 * - Prefer Wi-Fi when available, fall back to cellular
 * - Bind the process to the preferred network
 * - Apply common TCP socket optimizations
 * - Optional DNS-related system properties
 *
 * Call [init] once (e.g. in Application.onCreate or a service).
 * Call [cleanup] when the component that owns the optimizer is destroyed.
 */
object NetworkOptimizer {

    private const val TAG = "NetworkOptimizer"

    private lateinit var connectivityManager: ConnectivityManager
    private val isInitialized = AtomicBoolean(false)
    private val optimizedSockets = CopyOnWriteArrayList<Socket>()

    // Keep a strong reference so the callback is not GC'd
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Preferred network we are currently bound to
    @Volatile
    private var currentBoundNetwork: Network? = null

    /**
     * Initialize the optimizer. Safe to call multiple times.
     * @return true if initialization succeeded
     */
    fun init(context: Context): Boolean {
        if (isInitialized.get()) {
            Log.d(TAG, "Already initialized")
            return true
        }

        return try {
            connectivityManager = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            requestBestNetwork()
            isInitialized.set(true)
            Log.d(TAG, "Initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Initialization failed", e)
            false
        }
    }

    /**
     * Prefer unmetered Wi-Fi. When lost, fall back to cellular.
     */
    private fun requestBestNetwork() {
        // Clean up any previous callback
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: Exception) { /* ignore */ }
        }

        val wifiRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Preferred network available: $network")
                bindProcessToNetworkSafely(network)
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Preferred network lost: $network")
                if (currentBoundNetwork == network) {
                    currentBoundNetwork = null
                    // Fall back to any available network (usually cellular)
                    bindToAnyAvailableNetwork()
                }
            }

            override fun onUnavailable() {
                Log.w(TAG, "Preferred network unavailable, falling back")
                bindToAnyAvailableNetwork()
            }
        }

        try {
            // requestNetwork will trigger onAvailable / onUnavailable
            connectivityManager.requestNetwork(wifiRequest, networkCallback!!)
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing permission for requestNetwork", e)
            // Fallback: just bind to whatever is currently active
            bindToAnyAvailableNetwork()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request preferred network", e)
        }
    }

    private fun bindToAnyAvailableNetwork() {
        val active = connectivityManager.activeNetwork
        if (active != null) {
            bindProcessToNetworkSafely(active)
        } else {
            Log.w(TAG, "No active network available to bind")
        }
    }

    private fun bindProcessToNetworkSafely(network: Network) {
        try {
            val success = connectivityManager.bindProcessToNetwork(network)
            if (success) {
                currentBoundNetwork = network
                Log.d(TAG, "Process bound to network: $network")
            } else {
                Log.w(TAG, "bindProcessToNetwork returned false for $network")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException while binding process", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error binding process", e)
        }
    }

    /**
     * Apply common TCP optimizations to an existing socket.
     * Safe to call multiple times on the same socket.
     */
    fun optimizeSocket(
        socket: Socket,
        soTimeoutMs: Int = 30_000,
        receiveBufferSize: Int = 64 * 1024,
        sendBufferSize: Int = 64 * 1024
    ) {
        try {
            if (socket.isClosed) {
                Log.w(TAG, "Cannot optimize a closed socket")
                return
            }

            socket.keepAlive = true
            socket.tcpNoDelay = true          // Disable Nagle – better for interactive / game traffic
            socket.soTimeout = soTimeoutMs
            socket.receiveBufferSize = receiveBufferSize
            socket.sendBufferSize = sendBufferSize

            // Optional: set traffic class (best-effort, may be ignored by OS)
            // socket.trafficClass = 0x10 // IPTOS_LOWDELAY

            if (!optimizedSockets.contains(socket)) {
                optimizedSockets.add(socket)
            }

            Log.d(TAG, "Socket optimized: keepAlive=${socket.keepAlive}, " +
                    "tcpNoDelay=${socket.tcpNoDelay}, soTimeout=${socket.soTimeout}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to optimize socket", e)
        }
    }

    /**
     * Create a new socket, apply optimizations, and optionally connect with a timeout.
     */
    fun createOptimizedSocket(
        host: String? = null,
        port: Int = -1,
        connectTimeoutMs: Int = 10_000
    ): Socket {
        val socket = Socket()
        optimizeSocket(socket)

        if (host != null && port > 0) {
            try {
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect optimized socket to $host:$port", e)
                try {
                    socket.close()
                } catch (_: Exception) { }
                throw e
            }
        }
        return socket
    }

    /**
     * Raise the priority of the current thread.
     * Useful for network I/O threads that should not be delayed.
     */
    fun setThreadPriority(priority: Int = Process.THREAD_PRIORITY_FOREGROUND) {
        try {
            Process.setThreadPriority(priority)
            Thread.currentThread().priority = Thread.MAX_PRIORITY
            Log.d(TAG, "Thread priority set to $priority " +
                    "(current thread priority=${Thread.currentThread().priority})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set thread priority", e)
        }
    }

    /**
     * Apply a few DNS-related system properties that can help
     * (prefer IPv4, shorter positive/negative cache TTLs).
     * Note: These only affect the current process and may be ignored
     * on some Android versions / OEMs.
     */
    fun useFastDNS(): Boolean {
        return try {
            System.setProperty("java.net.preferIPv4Stack", "true")
            System.setProperty("networkaddress.cache.ttl", "60")
            System.setProperty("networkaddress.cache.negative.ttl", "10")
            Log.d(TAG, "DNS-related system properties applied")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set DNS properties", e)
            false
        }
    }

    /**
     * Well-known public DNS servers (for reference / custom resolvers).
     * Does not change the system DNS.
     */
    fun getPublicDnsServers(): List<String> = listOf(
        "8.8.8.8", "8.8.4.4",           // Google
        "1.1.1.1", "1.0.0.1",           // Cloudflare
        "9.9.9.9", "149.112.112.112"    // Quad9
    )

    /**
     * Release resources. Call when the owning component is destroyed.
     */
    fun cleanup() {
        try {
            networkCallback?.let {
                try {
                    connectivityManager.unregisterNetworkCallback(it)
                } catch (_: Exception) { /* already unregistered or not registered */ }
            }
            networkCallback = null

            // Unbind process from any specific network
            try {
                connectivityManager.bindProcessToNetwork(null)
            } catch (_: Exception) { }

            currentBoundNetwork = null

            // Close any sockets we still track
            optimizedSockets.forEach { socket ->
                try {
                    if (!socket.isClosed) socket.close()
                } catch (_: Exception) { }
            }
            optimizedSockets.clear()

            isInitialized.set(false)
            Log.d(TAG, "Cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }

    fun getStatus(): String {
        return if (isInitialized.get()) {
            "Initialized | boundNetwork=$currentBoundNetwork | trackedSockets=${optimizedSockets.size}"
        } else {
            "Not initialized"
        }
    }

    /** Whether the process is currently bound to a specific network */
    fun isBound(): Boolean = currentBoundNetwork != null
}