package com.laros.lsp.traffics

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.laros.lsp.traffics.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupSystemBars()

        binding.settingsToolbar.setNavigationOnClickListener { finish() }
        binding.itemSelfCheck.setOnClickListener {
            startActivity(Intent(this, SelfCheckActivity::class.java))
        }
        binding.itemAdvanced.setOnClickListener { openAdvanced() }
        binding.itemAbout.setOnClickListener { startActivity(Intent(this, AboutActivity::class.java)) }
        binding.itemDisclaimer.setOnClickListener { showDisclaimerDialog() }
        binding.itemPermissionGuide.setOnClickListener {
            startActivity(Intent(this, PermissionGuideActivity::class.java))
        }
    }

    private fun openAdvanced() {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_PAGE, MainActivity.PAGE_ADVANCED)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
    }

    private fun showDisclaimerDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_title_disclaimer))
            .setMessage(getString(R.string.dialog_msg_disclaimer))
            .setPositiveButton(getString(R.string.dialog_btn_confirm), null)
            .show()
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
            binding.settingsToolbar.updatePadding(top = bars.top + 8.dp())
            binding.settingsScroll.updatePadding(bottom = bars.bottom + 12.dp())
            insets
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}
