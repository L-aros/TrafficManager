package com.laros.lsp.traffics.core

import android.content.Context
import com.laros.lsp.traffics.model.AppConfig
import java.util.concurrent.TimeUnit

class SwitchTransportChain(private val transports: List<SwitchTransport>) {
    fun switchDataSlot(
        context: Context,
        targetSlot: Int,
        targetSubId: Int?,
        reason: String,
        config: AppConfig
    ): SwitchResult {
        var last = SwitchResult(false, "none", "No transport configured")
        val trace = mutableListOf<String>()
        for (transport in transports) {
            last = transport.switchDataSlot(context, targetSlot, targetSubId, reason, config)
            val verify = if (last.success) verifySlotApplied(context, targetSlot) else VerifyOutcome(VerifyStatus.UNKNOWN, "none", null)
            val verifyLabel = verify.status.name.lowercase()
            val resultLabel = if (last.success) "ok" else "fail"
            val verifyDetail = "source=${verify.source} observed=${verify.observed ?: "null"}"
            trace += "${transport.name}:$resultLabel:verify=$verifyLabel:$verifyDetail:${last.message}"
            if (last.success) {
                if (verify.status == VerifyStatus.NOT_VERIFIED) {
                    last = last.copy(
                        success = false,
                        message = "verify_failed transport=${transport.name} $verifyDetail ${last.message}"
                    )
                    continue
                }
                return last.copy(
                    message = "${last.message} | verify=$verifyLabel $verifyDetail | trace=${trace.joinToString(" || ")}"
                )
            }
        }
        return last.copy(message = "all_failed trace=${trace.joinToString(" || ")}")
    }

    private enum class VerifyStatus {
        VERIFIED,
        NOT_VERIFIED,
        UNKNOWN
    }

    private data class VerifyOutcome(
        val status: VerifyStatus,
        val source: String,
        val observed: Int?
    )

    private fun verifySlotApplied(context: Context, targetSlot: Int, timeoutMs: Long = 2000L): VerifyOutcome {
        val resolver = DataSlotResolver(context)
        val start = System.currentTimeMillis()
        var lastSeen: Int? = null
        while (System.currentTimeMillis() - start < timeoutMs) {
            val current = resolver.currentDataSlot()
            if (current != null) {
                lastSeen = current
                if (current == targetSlot) return VerifyOutcome(VerifyStatus.VERIFIED, "subscription_manager", current)
            }
            TimeUnit.MILLISECONDS.sleep(250)
        }
        if (lastSeen != null) {
            return VerifyOutcome(VerifyStatus.NOT_VERIFIED, "subscription_manager", lastSeen)
        }
        val rootSlot = readGlobalDataSlotByRoot()
        return when {
            rootSlot == null -> VerifyOutcome(VerifyStatus.UNKNOWN, "settings_global", null)
            rootSlot == targetSlot -> VerifyOutcome(VerifyStatus.VERIFIED, "settings_global", rootSlot)
            else -> VerifyOutcome(VerifyStatus.NOT_VERIFIED, "settings_global", rootSlot)
        }
    }

    private fun readGlobalDataSlotByRoot(): Int? {
        val output = runAsRoot("settings get global multi_sim_data_call")
        if (!output.startsWith("ok:")) return null
        val value = output.removePrefix("ok:").trim()
        return value.toIntOrNull()?.takeIf { it in 0..1 }
    }

    private fun runAsRoot(command: String): String {
        val suBins = listOf(
            "su",
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su",
            "/debug_ramdisk/su"
        )
        val traces = mutableListOf<String>()
        for (suBin in suBins) {
            val output = runSingleSu(suBin, command)
            traces += "$suBin=>$output"
            if (output.startsWith("ok:")) return output
        }
        return "err: ${traces.joinToString(" | ")}"
    }

    private fun runSingleSu(suBin: String, command: String): String {
        return runCatching {
            val process = ProcessBuilder(suBin, "-c", command)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(8, TimeUnit.SECONDS)
            val out = process.inputStream.bufferedReader().readText().trim()
            if (!finished) {
                process.destroyForcibly()
                return "err: timeout"
            }
            val code = process.exitValue()
            if (code == 0) "ok: $out" else "err($code): $out"
        }.getOrElse { "err: ${it.javaClass.simpleName}: ${it.message}" }
    }
}
