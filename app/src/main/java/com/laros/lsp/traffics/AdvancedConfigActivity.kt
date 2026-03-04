package com.laros.lsp.traffics

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.laros.lsp.traffics.config.ConfigStore
import com.laros.lsp.traffics.databinding.ActivityAdvancedConfigBinding
import com.laros.lsp.traffics.model.AppConfig
import com.laros.lsp.traffics.service.AutoSwitchService
import com.laros.lsp.traffics.service.PowerSaveScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import com.laros.lsp.traffics.core.SwitchRunner

class AdvancedConfigActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAdvancedConfigBinding
    private lateinit var configStore: ConfigStore
    private var updatingNoWifiSwitch = false
    private var updatingNoWifiSlot = false
    private var updatingPowerMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdvancedConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupSystemBars()

        configStore = ConfigStore(this)
        binding.advancedConfigBackButton.setOnClickListener { finish() }
        loadConfigToEditor()
        bindNoWifiImmediateSwitch()
        bindNoWifiSlotGroup()
        bindPowerModeGroup()

        binding.saveButton.setOnClickListener { saveRawConfig() }
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
            binding.advancedConfigHeader.updatePadding(top = bars.top + 8.dp())
            binding.advancedConfigScroll.updatePadding(bottom = bars.bottom + 12.dp())
            insets
        }
    }

    private fun loadConfigToEditor() {
        binding.configEditor.setText(configStore.loadRawJson())
        val cfg = configStore.load()
        syncNoWifiImmediateSwitch(cfg.noWifiImmediate)
        syncNoWifiSlot(cfg.noWifiSlot)
        syncPowerMode(cfg.powerSaveMode)
    }

    private fun saveRawConfig() {
        val raw = binding.configEditor.text?.toString().orEmpty()
        val result = configStore.saveRawJson(raw)
        if (result.isSuccess) {
            val cfg = configStore.load()
            syncNoWifiImmediateSwitch(cfg.noWifiImmediate)
            syncNoWifiSlot(cfg.noWifiSlot)
            syncPowerMode(cfg.powerSaveMode)
            Toast.makeText(this, R.string.status_config_saved, Toast.LENGTH_SHORT).show()
        } else {
            val err = result.exceptionOrNull()?.message ?: getString(R.string.label_unknown)
            Toast.makeText(this, getString(R.string.status_config_save_failed, err), Toast.LENGTH_LONG).show()
        }
    }

    private fun parseConfigFromEditor(): Result<AppConfig> {
        val raw = binding.configEditor.text?.toString().orEmpty()
        return runCatching { configStore.parse(raw) }
    }

    private fun persistConfig(config: AppConfig) {
        configStore.save(config)
        binding.configEditor.setText(configStore.toJson(config))
        syncNoWifiImmediateSwitch(config.noWifiImmediate)
        syncNoWifiSlot(config.noWifiSlot)
        syncPowerMode(config.powerSaveMode)
    }

    private fun bindNoWifiImmediateSwitch() {
        binding.noWifiImmediateSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingNoWifiSwitch) return@setOnCheckedChangeListener
            val config = parseConfigFromEditor().getOrElse {
                syncNoWifiImmediateSwitch(configStore.load().noWifiImmediate)
                return@setOnCheckedChangeListener
            }
            persistConfig(config.copy(noWifiImmediate = isChecked))
        }
    }

    private fun syncNoWifiImmediateSwitch(enabled: Boolean) {
        updatingNoWifiSwitch = true
        binding.noWifiImmediateSwitch.isChecked = enabled
        updatingNoWifiSwitch = false
    }

    private fun bindNoWifiSlotGroup() {
        binding.noWifiSlotGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || updatingNoWifiSlot) return@addOnButtonCheckedListener
            val target = when (checkedId) {
                R.id.noWifiSlotSim1 -> 0
                R.id.noWifiSlotSim2 -> 1
                else -> null
            }
            val config = parseConfigFromEditor().getOrElse {
                syncNoWifiSlot(configStore.load().noWifiSlot)
                return@addOnButtonCheckedListener
            }
            persistConfig(config.copy(noWifiSlot = target))
        }
    }

    private fun syncNoWifiSlot(target: Int?) {
        updatingNoWifiSlot = true
        val targetId = when (target) {
            0 -> R.id.noWifiSlotSim1
            1 -> R.id.noWifiSlotSim2
            else -> R.id.noWifiSlotOff
        }
        binding.noWifiSlotGroup.check(targetId)
        updateNoWifiSlotButtons(targetId)
        updatingNoWifiSlot = false
    }

    private fun updateNoWifiSlotButtons(selectedId: Int) {
        val activeBg = ColorStateList.valueOf(color(R.color.primary))
        val activeText = color(R.color.on_primary)
        val inactiveBg = ColorStateList.valueOf(color(R.color.card_stroke))
        val inactiveText = color(R.color.on_background)
        val inactiveStroke = ColorStateList.valueOf(color(R.color.card_stroke))

        val buttons = listOf(
            binding.noWifiSlotOff,
            binding.noWifiSlotSim1,
            binding.noWifiSlotSim2
        )
        for (btn in buttons) {
            val active = btn.id == selectedId
            btn.backgroundTintList = if (active) activeBg else inactiveBg
            btn.setTextColor(if (active) activeText else inactiveText)
            btn.strokeColor = inactiveStroke
        }
    }

    private fun bindPowerModeGroup() {
        binding.powerModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || updatingPowerMode) return@addOnButtonCheckedListener
            val powerSave = checkedId == R.id.powerModeSave
            if (powerSave) {
                showModeDialog(
                    title = getString(R.string.dialog_title_powersave),
                    message = getString(R.string.dialog_msg_powersave)
                )
            } else {
                showModeDialog(
                    title = getString(R.string.dialog_title_persistent),
                    message = getString(R.string.dialog_msg_persistent)
                )
            }

            val current = parseConfigFromEditor().getOrElse { configStore.load() }
            persistConfig(current.copy(powerSaveMode = powerSave))
            Toast.makeText(
                this,
                getString(if (powerSave) R.string.status_mode_powersave else R.string.status_mode_persistent),
                Toast.LENGTH_SHORT
            ).show()
            updatePowerModeButtons(checkedId)
            startModeWorker("mode_switch")
        }
    }

    private fun syncPowerMode(powerSave: Boolean) {
        updatingPowerMode = true
        val targetId = if (powerSave) R.id.powerModeSave else R.id.powerModePersistent
        binding.powerModeGroup.check(targetId)
        updatePowerModeButtons(targetId)
        updatingPowerMode = false
    }

    private fun updatePowerModeButtons(selectedId: Int) {
        val activeBg = ColorStateList.valueOf(color(R.color.primary))
        val activeText = color(R.color.on_primary)
        val inactiveBg = ColorStateList.valueOf(color(R.color.card_stroke))
        val inactiveText = color(R.color.on_background)
        val inactiveStroke = ColorStateList.valueOf(color(R.color.card_stroke))

        val buttons = listOf(
            binding.powerModeSave,
            binding.powerModePersistent
        )
        for (btn in buttons) {
            val active = btn.id == selectedId
            btn.backgroundTintList = if (active) activeBg else inactiveBg
            btn.setTextColor(if (active) activeText else inactiveText)
            btn.strokeColor = inactiveStroke
        }
    }

    private fun showModeDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(getString(R.string.dialog_btn_confirm), null)
            .show()
    }

    private fun startModeWorker(source: String) {
        val config = configStore.load()
        if (!config.enabled) return
        if (config.powerSaveMode) {
            PowerSaveScheduler.schedule(applicationContext)
            lifecycleScope.launch(Dispatchers.IO) {
                SwitchRunner(applicationContext).runOnce(source)
            }
            stopForegroundServiceIfNeeded()
        } else {
            PowerSaveScheduler.cancel(applicationContext)
            val svc = Intent(this, AutoSwitchService::class.java)
            ContextCompat.startForegroundService(this, svc)
        }
    }

    private fun stopForegroundServiceIfNeeded() {
        val svc = Intent(this, AutoSwitchService::class.java)
        stopService(svc)
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)
}
