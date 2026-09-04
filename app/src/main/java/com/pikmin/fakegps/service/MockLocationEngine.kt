package com.pikmin.fakegps.service

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.pikmin.fakegps.data.model.LocationPoint
import com.pikmin.fakegps.utils.GeoUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MockLocationEngine(private val context: Context) {

    companion object {
        private const val TAG = "MockLocationEngine"
        val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    }

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var mockJob: Job? = null

    private val _currentLocation = MutableStateFlow<LocationPoint?>(null)
    val currentLocation: StateFlow<LocationPoint?> = _currentLocation.asStateFlow()

    private val _isMocking = MutableStateFlow(false)
    val isMocking: StateFlow<Boolean> = _isMocking.asStateFlow()

    var enableJitter: Boolean = true

    /**
     * 啟動模擬定位
     */
    fun startMocking(initialLocation: LocationPoint) {
        if (_isMocking.value) {
            updateLocation(initialLocation)
            return
        }

        try {
            setupTestProviders()
            _currentLocation.value = initialLocation
            _isMocking.value = true

            // 定期廣播座標 (例如每秒 1 次)，確保系統與其他 App 持續收到定位訊號
            mockJob?.cancel()
            mockJob = coroutineScope.launch {
                while (isActive && _isMocking.value) {
                    val point = _currentLocation.value
                    if (point != null) {
                        pushLocationToSystem(point)
                    }
                    delay(1000L)
                }
            }
            Log.i(TAG, "Mocking started at: ${initialLocation.latitude}, ${initialLocation.longitude}")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: 尚未在開發人員選項中設定此 App 為模擬位置程式", e)
            _isMocking.value = false
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start mock location", e)
            _isMocking.value = false
            throw e
        }
    }

    /**
     * 即時更新座標（由地圖點擊或搖桿移動觸發）
     */
    fun updateLocation(newPoint: LocationPoint) {
        _currentLocation.value = newPoint
        if (_isMocking.value) {
            coroutineScope.launch {
                pushLocationToSystem(newPoint)
            }
        }
    }

    /**
     * 停止模擬定位並清理測試提供者
     */
    fun stopMocking() {
        mockJob?.cancel()
        mockJob = null
        _isMocking.value = false
        removeTestProviders()
        Log.i(TAG, "Mocking stopped")
    }

    private fun setupTestProviders() {
        for (provider in PROVIDERS) {
            try {
                // 如果之前已經存在先移除，避免已存在的狀態例外
                try {
                    locationManager.removeTestProvider(provider)
                } catch (ignored: Exception) {}

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val properties = ProviderProperties.Builder()
                        .setHasNetworkRequirement(false)
                        .setHasSatelliteRequirement(provider == LocationManager.GPS_PROVIDER)
                        .setHasCellRequirement(false)
                        .setHasMonetaryCost(false)
                        .setHasAltitudeSupport(true)
                        .setHasSpeedSupport(true)
                        .setHasBearingSupport(true)
                        .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                        .setAccuracy(ProviderProperties.ACCURACY_FINE)
                        .build()
                    locationManager.addTestProvider(provider, properties)
                } else {
                    @Suppress("DEPRECATION")
                    locationManager.addTestProvider(
                        provider,
                        false, // requiresNetwork
                        provider == LocationManager.GPS_PROVIDER, // requiresSatellite
                        false, // requiresCell
                        false, // hasMonetaryCost
                        true,  // supportsAltitude
                        true,  // supportsSpeed
                        true,  // supportsBearing
                        Criteria.POWER_LOW,
                        Criteria.ACCURACY_FINE
                    )
                }
                locationManager.setTestProviderEnabled(provider, true)
            } catch (e: Exception) {
                Log.w(TAG, "Error adding test provider $provider: ${e.message}")
                throw e
            }
        }
    }

    private fun removeTestProviders() {
        for (provider in PROVIDERS) {
            try {
                locationManager.setTestProviderEnabled(provider, false)
                locationManager.removeTestProvider(provider)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing test provider $provider: ${e.message}")
            }
        }
    }

    private fun pushLocationToSystem(point: LocationPoint) {
        // 若開啟擬真雜訊且速度為 0 時，加入極微小浮動
        val (finalLat, finalLng) = if (enableJitter && point.speed == 0f) {
            GeoUtils.applyRealisticJitter(point.latitude, point.longitude, 0.4)
        } else {
            Pair(point.latitude, point.longitude)
        }

        val currentTime = System.currentTimeMillis()
        val elapsedNanos = SystemClock.elapsedRealtimeNanos()

        for (provider in PROVIDERS) {
            try {
                val mockLocation = Location(provider).apply {
                    latitude = finalLat
                    longitude = finalLng
                    altitude = point.altitude
                    accuracy = point.accuracy
                    bearing = point.bearing
                    speed = point.speed
                    time = currentTime
                    elapsedRealtimeNanos = elapsedNanos

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        bearingAccuracyDegrees = 0.1f
                        verticalAccuracyMeters = 0.5f
                        speedAccuracyMetersPerSecond = 0.1f
                    }
                }
                locationManager.setTestProviderLocation(provider, mockLocation)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set test provider location for $provider: ${e.message}")
            }
        }
    }
}
