package com.laros.lsp.traffics

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.laros.lsp.traffics.config.ConfigStore
import com.laros.lsp.traffics.core.DataSlotResolver
import com.laros.lsp.traffics.core.WifiSnapshotProvider
import com.laros.lsp.traffics.databinding.ActivitySelfCheckBinding
import com.laros.lsp.traffics.config.SwitchStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class SelfCheckActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySelfCheckBinding
    private lateinit var configStore: ConfigStore
    private lateinit var wifiSnapshotProvider: WifiSnapshotProvider
    private lateinit var slotResolver: DataSlotResolver
    private var updatingSelfCheck = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySelfCheckBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupSystemBars()

        configStore = ConfigStore(this)
        wifiSnapshotProvider = WifiSnapshotProvider(this)
        slotResolver = DataSlotResolver(this)

        binding.selfCheckBackButton.setOnClickListener { finish() }
        binding.selfCheckRefreshButton.setOnClickListener { refreshSelfCheck() }
        refreshSelfCheck()
    }

    private fun setupSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.selfCheckHeader.updatePadding(top = bars.top + 8.dp())
            binding.selfCheckScroll.updatePadding(bottom = bars.bottom + 12.dp())
            insets
        }
    }

    private fun refreshSelfCheck() {
        if (updatingSelfCheck) return
        updatingSelfCheck = true
        binding.selfCheckRefreshButton.isEnabled = false
        lifecycleScope.launch {
            try {
                val data = withContext(Dispatchers.IO) { collectSelfCheckData() }
                val permissionText = if (data.missingPermissions.isEmpty()) {
                    getString(R.string.label_permissions_ok)
                } else {
                    val labels = data.missingPermissions.joinToString(", ")
                    getString(R.string.label_permissions_missing, labels)
                }
                val locationPermText = getString(
                    if (data.locationPermissionGranted) {
                        R.string.label_permission_granted
                    } else {
                        R.string.label_permission_missing_short
                    }
                )
                val nearbyPermText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    getString(
                        if (data.nearbyPermissionGranted) {
                            R.string.label_permission_granted
                        } else {
                            R.string.label_permission_missing_short
                        }
                    )
                } else {
                    getString(R.string.label_permission_not_required)
                }
                val locationText = getString(
                    if (data.locationEnabled) R.string.label_location_on else R.string.label_location_off
                )
                val locationHintText = if (
                    data.locationEnabled &&
                    data.locationPermissionGranted &&
                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || data.nearbyPermissionGranted)
                ) {
                    getString(R.string.self_check_location_hint_ok)
                } else {
                    getString(R.string.self_check_location_hint_bad)
                }

                val wifiParts = buildList {
                    data.wifiSsid?.let { add("SSID=$it") }
                    data.wifiBssid?.let { add("BSSID=$it") }
                }
                val wifiText = if (wifiParts.isEmpty()) {
                    getString(R.string.label_no_wifi)
                } else {
                    wifiParts.joinToString(" | ")
                }

                val slotText = when (data.slot) {
                    0 -> "SIM1"
                    1 -> "SIM2"
                    else -> getString(R.string.label_unknown)
                }
                val enabledText = getString(
                    if (data.enabled) R.string.label_enabled else R.string.label_disabled
                )
                val powerText = getString(
                    if (data.powerSaveMode) R.string.label_powersave else R.string.label_persistent
                )
                val rootText = getString(
                    if (data.rootAvailable) R.string.label_root_available else R.string.label_root_unavailable
                )
                val lastSwitchText = if (data.lastSwitchAtMs <= 0L) {
                    getString(R.string.label_last_switch_none)
                } else {
                    val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
                    fmt.format(Date(data.lastSwitchAtMs))
                }

                binding.selfCheckPermissions.text =
                    getString(R.string.self_check_permissions, permissionText)
                binding.selfCheckLocationPermission.text =
                    getString(R.string.self_check_location_permission, locationPermText)
                binding.selfCheckNearbyPermission.text =
                    getString(R.string.self_check_nearby_permission, nearbyPermText)
                binding.selfCheckLocation.text =
                    getString(R.string.self_check_location, locationText)
                binding.selfCheckLocationHint.text = locationHintText
                binding.selfCheckWifi.text =
                    getString(R.string.self_check_wifi, wifiText)
                binding.selfCheckSlot.text =
                    getString(R.string.self_check_slot, slotText)
                binding.selfCheckEnabled.text =
                    getString(R.string.self_check_enabled, enabledText)
                binding.selfCheckPowerMode.text =
                    getString(R.string.self_check_power_mode, powerText)
                binding.selfCheckRules.text =
                    getString(R.string.self_check_rules, data.rulesCount)
                binding.selfCheckRoot.text =
                    getString(R.string.self_check_root, rootText)
                binding.selfCheckLastSwitch.text =
                    getString(R.string.self_check_last_switch, lastSwitchText)
            } finally {
                binding.selfCheckRefreshButton.isEnabled = true
                updatingSelfCheck = false
            }
        }
    }

    private data class SelfCheckData(
        val missingPermissions: List<String>,
        val locationPermissionGranted: Boolean,
        val nearbyPermissionGranted: Boolean,
        val locationEnabled: Boolean,
        val wifiSsid: String?,
        val wifiBssid: String?,
        val slot: Int?,
        val enabled: Boolean,
        val powerSaveMode: Boolean,
        val rulesCount: Int,
        val rootAvailable: Boolean,
        val lastSwitchAtMs: Long
    )

    private fun collectSelfCheckData(): SelfCheckData {
        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val nearbyGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.map { permissionLabel(it) }
        val snapshot = wifiSnapshotProvider.current()
        val config = configStore.load()
        val state = SwitchStateStore(this).getLastSwitchAtMs()
        return SelfCheckData(
            missingPermissions = missing,
            locationPermissionGranted = fineGranted || coarseGranted,
            nearbyPermissionGranted = nearbyGranted,
            locationEnabled = isLocationEnabled(),
            wifiSsid = snapshot?.ssid,
            wifiBssid = snapshot?.bssid,
            slot = slotResolver.currentDataSlot(),
            enabled = config.enabled,
            powerSaveMode = config.powerSaveMode,
            rulesCount = config.rules.size,
            rootAvailable = isRootAvailable(),
            lastSwitchAtMs = state
        )
    }

    private fun requiredPermissions(): List<String> {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.NEARBY_WIFI_DEVICES
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            permissions += Manifest.permission.ACCESS_BACKGROUND_LOCATION
        }
        return permissions
    }

    private fun permissionLabel(permission: String): String {
        return when (permission) {
            Manifest.permission.ACCESS_FINE_LOCATION -> getString(R.string.perm_fine_location)
            Manifest.permission.ACCESS_COARSE_LOCATION -> getString(R.string.perm_coarse_location)
            Manifest.permission.ACCESS_BACKGROUND_LOCATION -> getString(R.string.perm_background_location)
            Manifest.permission.NEARBY_WIFI_DEVICES -> getString(R.string.perm_nearby_wifi)
            Manifest.permission.READ_PHONE_STATE -> getString(R.string.perm_read_phone_state)
            Manifest.permission.POST_NOTIFICATIONS -> getString(R.string.perm_post_notifications)
            else -> permission.substringAfterLast('.')
        }
    }

    private fun isRootAvailable(): Boolean {
        val suBins = listOf(
            "su",
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su",
            "/debug_ramdisk/su"
        )
        for (suBin in suBins) {
            val ok = runCatching {
                val process = ProcessBuilder(suBin, "-c", "id")
                    .redirectErrorStream(true)
                    .start()
                val finished = process.waitFor(2, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    false
                } else {
                    process.exitValue() == 0
                }
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(LocationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            true
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}
