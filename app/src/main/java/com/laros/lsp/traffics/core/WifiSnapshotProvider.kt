package com.laros.lsp.traffics.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.laros.lsp.traffics.log.LogStore
import java.util.concurrent.TimeUnit

class WifiSnapshotProvider(private val context: Context) {
    private val conn by lazy { context.getSystemService(ConnectivityManager::class.java) }
    private val wifi by lazy { context.applicationContext.getSystemService(WifiManager::class.java) }
    private val logStore by lazy { LogStore(context) }
    private var lastRootProbeAtMs: Long = 0L
    private var lastRootSnapshot: WifiSnapshot? = null
    private var lastDiagAtMs: Long = 0L

    fun current(): WifiSnapshot? {
        val fromCaps = readFromNetworkCapabilities()
        val fromManager = readFromWifiManager()
        val merged = mergeSnapshot(fromCaps, fromManager)
        val fromScan = readFromScanResults(merged)
        val normal = mergeSnapshot(merged, fromScan)?.let {
            WifiSnapshot(ssid = it.first, bssid = it.second)
        }
        if (normal != null && (normal.ssid != null || normal.bssid != null)) {
            return normal
        }

        val now = System.currentTimeMillis()
        if (now - lastRootProbeAtMs >= 15_000L) {
            lastRootProbeAtMs = now
            lastRootSnapshot = readViaRoot()
        }
        val root = lastRootSnapshot
        if (root == null || (root.ssid == null && root.bssid == null)) {
            logFailureIfNeeded(fromCaps, fromManager, fromScan)
        }
        return root
    }

    private fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim().trim('"')
        return if (s.equals("<unknown ssid>", ignoreCase = true)) null else s
    }

    private fun normalizeBssid(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val normalized = raw.uppercase()
        return if (normalized == "02:00:00:00:00:00") null else normalized
    }

    private fun readFromNetworkCapabilities(): Pair<String?, String?>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null

        fun extract(caps: NetworkCapabilities?): Pair<String?, String?>? {
            val wifiCaps = caps?.takeIf { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } ?: return null
            val wifiInfo = wifiCaps.transportInfo as? WifiInfo ?: return null
            return normalize(wifiInfo.ssid) to normalizeBssid(wifiInfo.bssid)
        }

        val active = extract(conn?.getNetworkCapabilities(conn?.activeNetwork))
        if (active != null && (active.first != null || active.second != null)) {
            return active
        }

        val allNetworks = conn?.allNetworks ?: emptyArray()
        for (network in allNetworks) {
            val pair = extract(conn?.getNetworkCapabilities(network))
            if (pair != null && (pair.first != null || pair.second != null)) {
                return pair
            }
        }
        return active
    }

    private fun readFromWifiManager(): Pair<String?, String?>? {
        return runCatching {
            @Suppress("DEPRECATION")
            wifi?.connectionInfo?.let { normalize(it.ssid) to normalizeBssid(it.bssid) }
        }.getOrNull()
    }

    private fun readFromScanResults(hint: Pair<String?, String?>?): Pair<String?, String?>? {
        val bssidHint = hint?.second
        val ssidHint = hint?.first
        return runCatching {
            val results = wifi?.scanResults.orEmpty()
            if (results.isEmpty()) return@runCatching null
            val matched = when {
                !bssidHint.isNullOrBlank() ->
                    results.firstOrNull { it.BSSID.equals(bssidHint, ignoreCase = true) }
                !ssidHint.isNullOrBlank() ->
                    results.firstOrNull { it.SSID.equals(ssidHint, ignoreCase = true) }
                else -> null
            } ?: return@runCatching null
            normalize(matched.SSID) to normalizeBssid(matched.BSSID)
        }.getOrNull()
    }

    private fun mergeSnapshot(
        primary: Pair<String?, String?>?,
        secondary: Pair<String?, String?>?
    ): Pair<String?, String?>? {
        if (primary == null) return secondary
        if (secondary == null) return primary
        return (primary.first ?: secondary.first) to (primary.second ?: secondary.second)
    }

    private fun readViaRoot(): WifiSnapshot? {
        val dump = runAsRoot("dumpsys wifi")
        if (dump.isBlank()) return null

        val ssidCandidates = listOf(
            Regex("""current SSID\(s\):\s*\[(?:")?([^"\],]+)""", RegexOption.IGNORE_CASE),
            Regex("""\bssid\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE),
            Regex("""\bssid\s*[:=]\s*"([^"]+)"""", RegexOption.IGNORE_CASE),
            Regex("""\bssid\s*[:=]\s*([^\s,;]+)""", RegexOption.IGNORE_CASE)
        )
        val ssid = ssidCandidates.asSequence()
            .mapNotNull { it.find(dump)?.groupValues?.getOrNull(1) }
            .map { normalize(it) }
            .firstOrNull { !it.isNullOrBlank() }

        val bssid = Regex("""\bbssid\s*[:=]?\s*([0-9a-fA-F:]{17})\b""", RegexOption.IGNORE_CASE)
            .find(dump)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { normalizeBssid(it) }

        if (ssid == null && bssid == null) return null
        return WifiSnapshot(ssid = ssid, bssid = bssid)
    }

    private fun logFailureIfNeeded(
        fromCaps: Pair<String?, String?>?,
        fromManager: Pair<String?, String?>?,
        fromScan: Pair<String?, String?>?
    ) {
        val now = System.currentTimeMillis()
        if (now - lastDiagAtMs < 30_000L) return
        lastDiagAtMs = now

        val scanCount = runCatching { wifi?.scanResults?.size ?: 0 }.getOrDefault(0)
        val fine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        val nearby = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            true
        }
        val locationEnabled = isLocationEnabled()

        logStore.append(
            "wifi_snapshot: empty caps=${pairToLabel(fromCaps)} " +
                "manager=${pairToLabel(fromManager)} scan=${pairToLabel(fromScan)} " +
                "scanCount=$scanCount permFine=$fine permCoarse=$coarse " +
                "permNearbyWifi=$nearby locationEnabled=$locationEnabled"
        )
    }

    private fun pairToLabel(pair: Pair<String?, String?>?): String {
        if (pair == null) return "null"
        val ssid = pair.first ?: "null"
        val bssid = pair.second ?: "null"
        return "ssid=$ssid,bssid=$bssid"
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun isLocationEnabled(): Boolean {
        val lm = context.getSystemService(LocationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            true
        }
    }

    private fun runAsRoot(command: String): String {
        val suBins = listOf("su", "/system/bin/su", "/data/adb/ksu/bin/su")
        for (suBin in suBins) {
            val result = runCatching {
                val process = ProcessBuilder(suBin, "-c", command)
                    .redirectErrorStream(true)
                    .start()
                val finished = process.waitFor(5, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    ""
                } else {
                    val out = process.inputStream.bufferedReader().use { it.readText() }
                    if (process.exitValue() == 0) out else ""
                }
            }.getOrDefault("")
            if (result.isNotBlank()) return result
        }
        return ""
    }
}
