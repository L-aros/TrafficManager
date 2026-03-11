package com.laros.lsp.traffics

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.laros.lsp.traffics.config.ConfigStore
import com.laros.lsp.traffics.config.SwitchStateStore
import com.laros.lsp.traffics.core.DataSlotResolver
import com.laros.lsp.traffics.core.SwitchRunner
import com.laros.lsp.traffics.core.WifiSnapshot
import com.laros.lsp.traffics.core.WifiSnapshotProvider
import com.laros.lsp.traffics.databinding.ActivityMainBinding
import com.laros.lsp.traffics.databinding.PageAdvancedContentBinding
import com.laros.lsp.traffics.databinding.PageHomeContentBinding
import com.laros.lsp.traffics.databinding.PageRulesContentBinding
import com.laros.lsp.traffics.model.AppConfig
import com.laros.lsp.traffics.model.SwitchRule
import com.laros.lsp.traffics.service.RunModeController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var homeBinding: PageHomeContentBinding
    private lateinit var rulesBinding: PageRulesContentBinding
    private lateinit var advancedBinding: PageAdvancedContentBinding
    private lateinit var configStore: ConfigStore
    private lateinit var wifiSnapshotProvider: WifiSnapshotProvider
    private lateinit var slotResolver: DataSlotResolver
    private var updatingLiveStatus = false
    private var liveStatusJob: Job? = null
    private var statusRevertJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetCallbackAtMs: Long = 0L
    private val selectedRuleIds = mutableSetOf<String>()

    private enum class Page {
        HOME, RULES, ADVANCED
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val denied = result.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            showStatus(getString(
                R.string.status_missing_permissions,
                denied.joinToString()
            ))
        } else {
            showStatus(getString(R.string.status_permissions_granted))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        homeBinding = PageHomeContentBinding.bind(binding.pageHome)
        rulesBinding = PageRulesContentBinding.bind(binding.pageRules)
        advancedBinding = PageAdvancedContentBinding.bind(binding.pageAdvanced)
        setupSystemBars()
        configStore = ConfigStore(this)
        wifiSnapshotProvider = WifiSnapshotProvider(this)
        slotResolver = DataSlotResolver(this)

        requestRuntimePermissions()
        bindDelayConfig()
        bindBottomNav()
        applyInitialPageSelection()
        bindClicks()
        updateLiveStatus()
        refreshRuleList()
        startAutoSwitchOnLaunch()
    }

    override fun onStart() {
        super.onStart()
        registerPowerSaveNetworkCallback()
    }

    override fun onStop() {
        unregisterPowerSaveNetworkCallback()
        super.onStop()
    }

    private fun bindClicks() {
        advancedBinding.settingsListSelfCheck.setOnClickListener {
            startActivity(Intent(this, SelfCheckActivity::class.java))
        }
        advancedBinding.settingsListAdvanced.setOnClickListener {
            startActivity(Intent(this, AdvancedConfigActivity::class.java))
        }
        advancedBinding.settingsListAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        advancedBinding.settingsListDisclaimer.setOnClickListener {
            showDisclaimerDialog()
        }
        advancedBinding.settingsListPermissionGuide.setOnClickListener {
            startActivity(Intent(this, PermissionGuideActivity::class.java))
        }

        homeBinding.startButton.setOnClickListener {
            val current = configStore.load()
            val next = current.copy(enabled = true)
            persistConfig(next)
            showStatus(getString(R.string.status_auto_switch_enabled_with_mode, modeDetail(next.powerSaveMode)))
            startModeWorker("manual_start")
        }

        homeBinding.stopButton.setOnClickListener {
            val current = configStore.load()
            persistConfig(current.copy(enabled = false))
            RunModeController.disable(this)
            unregisterPowerSaveNetworkCallback()
            showStatus(getString(R.string.status_auto_switch_disabled))
        }

        homeBinding.refreshStatusButton.setOnClickListener { updateLiveStatus() }
        homeBinding.learnSim1Button.setOnClickListener { learnCurrentWifiRule(targetSlot = 0) }
        homeBinding.learnSim2Button.setOnClickListener { learnCurrentWifiRule(targetSlot = 1) }
        rulesBinding.addRuleToSim1Button.setOnClickListener { addManualRule(targetSlot = 0) }
        rulesBinding.addRuleToSim2Button.setOnClickListener { addManualRule(targetSlot = 1) }
        rulesBinding.refreshRulesButton.setOnClickListener { refreshRuleList() }
        rulesBinding.deleteSelectedRulesButton.setOnClickListener { deleteSelectedRules() }
        homeBinding.applyDelayConfigButton.setOnClickListener { applyDelayConfig() }
    }

    private fun bindBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    showPage(Page.HOME)
                    true
                }

                R.id.nav_rules -> {
                    showPage(Page.RULES)
                    true
                }

                R.id.nav_advanced -> {
                    showPage(Page.ADVANCED)
                    true
                }

                else -> false
            }
        }
    }

    private fun applyInitialPageSelection() {
        val target = intent.getStringExtra(EXTRA_OPEN_PAGE)
        val itemId = when (target) {
            PAGE_RULES -> R.id.nav_rules
            PAGE_ADVANCED -> R.id.nav_advanced
            else -> R.id.nav_home
        }
        binding.bottomNav.selectedItemId = itemId
    }

    private fun showPage(page: Page) {
        binding.pageHome.visibility = if (page == Page.HOME) View.VISIBLE else View.GONE
        binding.pageRules.visibility = if (page == Page.RULES) View.VISIBLE else View.GONE
        binding.pageAdvanced.visibility = if (page == Page.ADVANCED) View.VISIBLE else View.GONE

        when (page) {
            Page.HOME -> {
                binding.headerTitle.text = getString(R.string.page_home_title)
                binding.headerSubtitle.text = getString(R.string.page_home_subtitle)
                updateLiveStatus()
            }

            Page.RULES -> {
                binding.headerTitle.text = getString(R.string.page_rules_title)
                binding.headerSubtitle.text = getString(R.string.page_rules_subtitle)
                refreshRuleList()
            }

            Page.ADVANCED -> {
                binding.headerTitle.text = getString(R.string.page_advanced_title)
                binding.headerSubtitle.text = getString(R.string.page_advanced_subtitle)
            }
        }
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
            binding.headerContainer.updatePadding(top = bars.top + 12.dp())
            binding.bottomNav.updatePadding(bottom = bars.bottom + 6.dp())
            insets
        }
    }

    private fun refreshRuleList() {
        val config = configStore.load()
        renderRules(config.rules.sortedByDescending { it.priority })
    }

    private fun updateLiveStatus() {
        if (updatingLiveStatus) return
        updatingLiveStatus = true
        homeBinding.refreshStatusButton.isEnabled = false
        liveStatusJob?.cancel()
        liveStatusJob = lifecycleScope.launch {
            try {
                val (snapshot, slot) = withContext(Dispatchers.IO) {
                    val snap = wifiSnapshotProvider.current()
                    val dataSlot = slotResolver.currentDataSlot()
                    snap to dataSlot
                }
                val slotLabel = slot?.let { "SIM${it + 1}" } ?: getString(R.string.label_unknown)
                val wifiLabel = snapshot?.ssid ?: snapshot?.bssid
                val wifiText = if (wifiLabel.isNullOrBlank()) {
                    getString(R.string.status_live_wifi_disconnected)
                } else {
                    getString(R.string.status_live_wifi_connected, wifiLabel)
                }
                homeBinding.statusText.text = getString(R.string.status_live, slotLabel, wifiText)
                updateLastSwitchSummary()
            } finally {
                homeBinding.refreshStatusButton.isEnabled = true
                updatingLiveStatus = false
            }
        }
    }

    private fun learnCurrentWifiRule(targetSlot: Int) {
        val snapshot = wifiSnapshotProvider.current()
        if (snapshot?.ssid == null && snapshot?.bssid == null) {
            showStatus(getString(R.string.status_wifi_not_detected))
            return
        }

        val config = configStore.load()
        val updated = appendOrUpdateRule(config.rules, snapshot, targetSlot)
        val next = config.copy(rules = updated.sortedByDescending { it.priority })
        persistConfig(next)

        val wifiLabel = snapshot.ssid ?: snapshot.bssid ?: getString(R.string.label_unknown)
        showStatus(getString(R.string.status_learned_rule, wifiLabel, targetSlot + 1))
        refreshRuleList()
    }

    private fun appendOrUpdateRule(
        rules: List<SwitchRule>,
        snapshot: WifiSnapshot,
        targetSlot: Int
    ): List<SwitchRule> {
        val normalizedBssid = snapshot.bssid?.uppercase(Locale.US)
        val maxPriority = (rules.maxOfOrNull { it.priority } ?: 0) + 10
        val index = rules.indexOfFirst { sameWifi(it, snapshot.ssid, normalizedBssid) }

        val mutable = rules.toMutableList()
        if (index >= 0) {
            val old = mutable[index]
            mutable[index] = old.copy(
                targetSlot = targetSlot,
                priority = maxOf(old.priority, maxPriority),
                ssid = snapshot.ssid,
                bssid = normalizedBssid
            )
            return mutable
        }

        val baseId = buildRuleId(snapshot.ssid, normalizedBssid, targetSlot)
        val uniqueId = if (rules.none { it.id == baseId }) {
            baseId
        } else {
            "${baseId}_${System.currentTimeMillis() % 100000}"
        }
        mutable += SwitchRule(
            id = uniqueId,
            priority = maxPriority,
            ssid = snapshot.ssid,
            bssid = normalizedBssid,
            targetSlot = targetSlot
        )
        return mutable
    }

    private fun addManualRule(targetSlot: Int) {
        val ssid = rulesBinding.manualSsidInput.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
        val bssid = rulesBinding.manualBssidInput.text?.toString()?.trim()?.uppercase(Locale.US)?.takeIf { it.isNotBlank() }
        if (ssid == null && bssid == null) {
            showStatus(getString(R.string.status_input_ssid_or_bssid))
            return
        }

        val priorityInput = rulesBinding.manualPriorityInput.text?.toString()?.trim()
        val priorityManual = priorityInput?.toIntOrNull()
        val status = getString(R.string.status_manual_rule_saved, targetSlot + 1)

        applyConfigChange(status) { cfg ->
            val mutable = cfg.rules.toMutableList()
            val nextPriority = priorityManual ?: (mutable.maxOfOrNull { it.priority } ?: 0) + 10
            val idx = mutable.indexOfFirst { sameWifi(it, ssid, bssid) }
            if (idx >= 0) {
                val old = mutable[idx]
                mutable[idx] = old.copy(
                    ssid = ssid ?: old.ssid,
                    bssid = bssid ?: old.bssid,
                    targetSlot = targetSlot,
                    priority = nextPriority
                )
            } else {
                val idBase = buildRuleId(ssid, bssid, targetSlot)
                val uniqueId = if (mutable.none { it.id == idBase }) idBase else "${idBase}_${System.currentTimeMillis() % 100000}"
                mutable += SwitchRule(
                    id = uniqueId,
                    priority = nextPriority,
                    ssid = ssid,
                    bssid = bssid,
                    targetSlot = targetSlot
                )
            }
            cfg.copy(rules = mutable.sortedByDescending { it.priority })
        }
    }

    private fun renderRules(rules: List<SwitchRule>) {
        rulesBinding.rulesContainer.removeAllViews()
        if (rules.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.label_no_rules)
                textSize = 14f
                setTextColor(color(R.color.muted))
            }
            rulesBinding.rulesContainer.addView(empty)
            return
        }

        rules.forEach { rule ->
            val card = MaterialCardView(this).apply {
                radius = 22f
                cardElevation = 0f
                strokeWidth = 1.dp()
                strokeColor = color(R.color.card_stroke)
                setCardBackgroundColor(color(R.color.card_bg_alt))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 8.dp() }
            }
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
            }
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val check = MaterialCheckBox(this).apply {
                isChecked = selectedRuleIds.contains(rule.id)
                text = ""
                buttonTintList = ColorStateList.valueOf(color(R.color.primary))
                minimumHeight = 0
                minHeight = 0
                minimumWidth = 0
                minWidth = 0
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    20.dp(),
                    20.dp()
                ).apply { marginStart = 6.dp() }
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedRuleIds.add(rule.id) else selectedRuleIds.remove(rule.id)
                }
            }
            val title = TextView(this).apply {
                val label = rule.ssid ?: rule.bssid ?: getString(R.string.label_null)
                text = getString(R.string.label_rule_title_natural, rule.priority, label, rule.targetSlot + 1)
                textSize = 15f
                setTextColor(color(R.color.on_background))
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
            val detail = TextView(this).apply {
                val bssid = rule.bssid ?: getString(R.string.label_null)
                text = getString(R.string.label_rule_detail_natural, bssid)
                textSize = 13f
                setTextColor(color(R.color.muted))
            }
            if (rule.bssid.isNullOrBlank()) {
                detail.visibility = View.GONE
            }
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isBaselineAligned = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 8.dp() }
            }

            val toSim1 = buildActionButton(
                text = getString(R.string.btn_to_sim1),
                background = color(R.color.primary),
                textColor = color(R.color.on_primary)
            ).apply {
                setOnClickListener {
                    applyConfigChange(getString(R.string.status_rule_to_sim, rule.id, 1)) { cfg ->
                        val mutable = cfg.rules.toMutableList()
                        val idx = mutable.indexOfFirst { it.id == rule.id }
                        if (idx >= 0) mutable[idx] = mutable[idx].copy(targetSlot = 0)
                        cfg.copy(rules = mutable.sortedByDescending { it.priority })
                    }
                }
            }

            val toSim2 = buildActionButton(
                text = getString(R.string.btn_to_sim2),
                background = color(R.color.accent),
                textColor = color(R.color.on_primary),
                marginStartDp = 6
            ).apply {
                setOnClickListener {
                    applyConfigChange(getString(R.string.status_rule_to_sim, rule.id, 2)) { cfg ->
                        val mutable = cfg.rules.toMutableList()
                        val idx = mutable.indexOfFirst { it.id == rule.id }
                        if (idx >= 0) mutable[idx] = mutable[idx].copy(targetSlot = 1)
                        cfg.copy(rules = mutable.sortedByDescending { it.priority })
                    }
                }
            }

            actions.addView(toSim1)
            actions.addView(toSim2)
            header.addView(title)
            header.addView(check)
            body.addView(header)
            body.addView(detail)
            body.addView(actions)
            card.addView(body)
            header.setOnClickListener { check.isChecked = !check.isChecked }
            rulesBinding.rulesContainer.addView(card)
        }
    }

    private fun buildActionButton(
        text: String,
        background: Int,
        textColor: Int,
        marginStartDp: Int = 0
    ): MaterialButton {
        return MaterialButton(this).apply {
            this.text = text
            isAllCaps = false
            cornerRadius = 16.dp()
            insetTop = 2.dp()
            insetBottom = 2.dp()
            minHeight = 48.dp()
            minimumHeight = 48.dp()
            setPadding(8.dp(), 6.dp(), 8.dp(), 6.dp())
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            maxLines = 2
            setTextColor(textColor)
            backgroundTintList = ColorStateList.valueOf(background)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                if (marginStartDp > 0) {
                    marginStart = marginStartDp.dp()
                }
            }
        }
    }

    private fun sameWifi(rule: SwitchRule, ssid: String?, bssid: String?): Boolean {
        if (!bssid.isNullOrBlank() && !rule.bssid.isNullOrBlank()) {
            return rule.bssid.equals(bssid, ignoreCase = true)
        }
        if (!ssid.isNullOrBlank() && !rule.ssid.isNullOrBlank()) {
            return rule.ssid.equals(ssid, ignoreCase = true)
        }
        return false
    }

    private fun buildRuleId(ssid: String?, bssid: String?, targetSlot: Int): String {
        val raw = (ssid ?: bssid ?: "wifi").lowercase(Locale.US)
        val clean = raw.replace(Regex("[^a-z0-9]+"), "_").trim('_')
        val base = if (clean.isBlank()) "wifi" else clean
        return "${base}_to_sim${targetSlot + 1}"
    }

    private fun applyConfigChange(statusMessage: String, transform: (AppConfig) -> AppConfig) {
        val current = configStore.load()
        val next = transform(current)
        persistConfig(next)
        refreshRuleList()
        showStatus(statusMessage)
    }

    private fun persistConfig(config: AppConfig) {
        configStore.save(config)
        syncDelayConfig(config)
    }

    private fun deleteSelectedRules() {
        if (selectedRuleIds.isEmpty()) {
            showStatus(getString(R.string.status_no_rules_selected))
            return
        }
        val ids = selectedRuleIds.toSet()
        applyConfigChange(getString(R.string.status_rules_deleted, ids.joinToString(","))) { cfg ->
            cfg.copy(rules = cfg.rules.filterNot { ids.contains(it.id) })
        }
        selectedRuleIds.clear()
        refreshRuleList()
    }

    private fun bindDelayConfig() {
        syncDelayConfig(configStore.load())
    }

    private fun syncDelayConfig(config: AppConfig) {
        homeBinding.cooldownSecInput.setText(config.cooldownSec.toString())
        homeBinding.leaveDelaySecInput.setText(config.leaveDelaySec.toString())
        homeBinding.leaveMissThresholdInput.setText(config.leaveMissThreshold.toString())
    }

    private fun applyDelayConfig() {
        val cooldown = homeBinding.cooldownSecInput.text?.toString()?.trim()?.toIntOrNull()
        val leaveDelay = homeBinding.leaveDelaySecInput.text?.toString()?.trim()?.toIntOrNull()
        val leaveMiss = homeBinding.leaveMissThresholdInput.text?.toString()?.trim()?.toIntOrNull()
        if (cooldown == null || leaveDelay == null || leaveMiss == null) {
            showStatus(getString(R.string.status_invalid_json_modify))
            return
        }

        val config = configStore.load()
        val next = config.copy(
            cooldownSec = cooldown,
            leaveDelaySec = leaveDelay,
            leaveMissThreshold = leaveMiss
        )
        persistConfig(next)
        showStatus(getString(R.string.status_delay_config_saved))
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            permissions += Manifest.permission.ACCESS_BACKGROUND_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }

        val need = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (need.isNotEmpty()) {
            permissionLauncher.launch(need.toTypedArray())
        }
    }

    private fun showStatus(message: String) {
        homeBinding.statusText.text = message
        statusRevertJob?.cancel()
        statusRevertJob = lifecycleScope.launch {
            delay(5_000L)
            updateLiveStatus()
        }
    }

    private fun startAutoSwitchOnLaunch() {
        val current = configStore.load()
        if (!current.enabled) {
            showStatus(getString(R.string.status_auto_switch_disabled))
            return
        }
        showStatus(getString(R.string.status_auto_switch_enabled_with_mode, modeDetail(current.powerSaveMode)))
        startModeWorker("app_launch")
    }

    private fun registerPowerSaveNetworkCallback() {
        val config = configStore.load()
        if (!config.enabled || !config.powerSaveMode) {
            unregisterPowerSaveNetworkCallback()
            return
        }
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i("TrafficManager", "net callback available")
                triggerPowerSaveOnce("net_available")
            }

            override fun onLost(network: Network) {
                Log.i("TrafficManager", "net callback lost")
                triggerPowerSaveOnce("net_lost")
            }
        }
        networkCallback = callback
        cm.registerDefaultNetworkCallback(callback)
    }

    private fun unregisterPowerSaveNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = networkCallback ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
        networkCallback = null
    }

    private fun triggerPowerSaveOnce(source: String) {
        val now = System.currentTimeMillis()
        if (now - lastNetCallbackAtMs < 1500L) return
        lastNetCallbackAtMs = now
        lifecycleScope.launch(Dispatchers.IO) {
            SwitchRunner(applicationContext).runOnce(source)
        }
    }

    private fun startModeWorker(source: String) {
        val config = configStore.load()
        RunModeController.apply(
            context = this,
            config = config,
            source = source
        ) {
            lifecycleScope.launch(Dispatchers.IO) {
                SwitchRunner(applicationContext).runOnce(it)
            }
        }
    }

    private fun modeDetail(powerSave: Boolean): String {
        return if (powerSave) {
            getString(R.string.label_mode_powersave_detail)
        } else {
            getString(R.string.label_mode_persistent_detail)
        }
    }

    private fun showDisclaimerDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_title_disclaimer))
            .setMessage(getString(R.string.dialog_msg_disclaimer))
            .setPositiveButton(getString(R.string.dialog_btn_confirm), null)
            .show()
    }

    private fun updateLastSwitchSummary() {
        val event = SwitchStateStore(this).getLastSwitchEvent()
        if (event == null) {
            homeBinding.lastSwitchText.text = getString(R.string.status_last_switch_none)
            return
        }
        val timeText = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(event.atMs))
        val resultText = when {
            event.success -> getString(R.string.label_switch_success)
            event.message.contains("verify_failed", ignoreCase = true) ->
                getString(R.string.label_switch_verify_failed)
            else -> getString(R.string.label_switch_failed)
        }
        val simLabel = "SIM${event.targetSlot + 1}"
        val reason = event.reason.ifBlank { getString(R.string.label_unknown) }
        val transport = event.transport.ifBlank { getString(R.string.label_unknown) }
        val summary = "$timeText $resultText $simLabel · $transport · $reason"
        homeBinding.lastSwitchText.text = getString(R.string.status_last_switch, summary)
    }

    companion object {
        const val EXTRA_OPEN_PAGE = "open_page"
        const val PAGE_ADVANCED = "advanced"
        const val PAGE_RULES = "rules"
    }
}
