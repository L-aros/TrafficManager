package com.laros.lsp.traffics

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.laros.lsp.traffics.databinding.ActivityAboutBinding
import com.laros.lsp.traffics.log.LogStore

class AboutActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAboutBinding
    private lateinit var logStore: LogStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupSystemBars()

        logStore = LogStore(this)
        binding.aboutBackButton.setOnClickListener { finish() }
        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty()
        binding.aboutVersionText.text = getString(
            R.string.about_version_format,
            versionName.ifBlank { getString(R.string.label_unknown) }
        )
        binding.aboutGithubButton.setOnClickListener { openGithub() }
        binding.aboutExportLogsButton.setOnClickListener { exportLogs() }
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
            binding.aboutHeader.updatePadding(top = bars.top + 8.dp())
            binding.aboutScroll.updatePadding(bottom = bars.bottom + 12.dp())
            insets
        }
    }

    private fun openGithub() {
        val uri = Uri.parse(getString(R.string.github_url))
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
        startActivity(intent)
    }

    private fun exportLogs() {
        val file = logStore.exportAll()
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        shareUri(uri)
    }

    private fun shareUri(uri: Uri) {
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(android.content.Intent.createChooser(intent, getString(R.string.chooser_export_logs))) }
            .onFailure {
                val err = it.message ?: getString(R.string.label_unknown)
                Toast.makeText(this, getString(R.string.status_export_failed, err), Toast.LENGTH_LONG).show()
            }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}
