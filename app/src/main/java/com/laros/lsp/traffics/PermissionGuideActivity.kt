package com.laros.lsp.traffics

import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.laros.lsp.traffics.databinding.ActivityPermissionGuideBinding

class PermissionGuideActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPermissionGuideBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPermissionGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupSystemBars()

        binding.permissionGuideBackButton.setOnClickListener { finish() }
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
            binding.permissionGuideHeader.updatePadding(top = bars.top + 8.dp())
            binding.permissionGuideScroll.updatePadding(bottom = bars.bottom + 12.dp())
            insets
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}
