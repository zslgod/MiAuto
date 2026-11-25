package com.andrerinas.openheadunit.utils

import android.content.Context
import android.os.Build
import java.util.ArrayDeque

/** Small, shareable report. Never exports saved credentials or raw protocol payloads. */
object DiagnosticReport {
    fun build(context: Context): String {
        val settings = Settings(context)
        val ssid = settings.hotspotSsid
        val password = settings.hotspotPassword
        val lines = ArrayDeque<String>()
        fun accept(line: String) {
            DiagnosticReportRedactor.redact(line, ssid, password)?.let {
                if (lines.size == 6000) lines.removeFirst()
                lines.addLast(it)
            }
        }
        val history = DiagnosticJournal.snapshot()
        val source = if (settings.logSource == Settings.LogSource.APPLOG_FILE)
            AppLog.currentLogFile ?: AppLog.lastLogFile else null
        // Prefer logcat: it is the only place the ICCOA SDK's own lines appear ("codec configured
        // failed", "init: setOutputSurface failed", "onWfdSinkReady", "start cast failed"). The
        // journal below only ever holds *our* OPENHU lines, which is why a black picture looked
        // like "everything worked": every app-level step succeeded.
        val logcat = readLogcat()
        when {
            logcat != null -> logcat.forEach(::accept)
            history.isNotEmpty() -> history.forEach(::accept)
            source?.isFile == true -> source.useLines { it.forEach(::accept) }
        }
        check(lines.isNotEmpty()) { "Could not read the app logs" }
        return buildString {
            appendLine("DiAuto ${context.packageManager.getPackageInfo(context.packageName, 0).versionName} · diagnostic report")
            appendLine("Android=${Build.VERSION.RELEASE}; API=${Build.VERSION.SDK_INT}")
            appendLine("Head unit: ${Build.MANUFACTURER} ${Build.MODEL}; board=${Build.BOARD}; build=${Build.DISPLAY}")
            appendLine("Wireless mode=${settings.wifiConnectionMode}; native transport=${settings.nativeApTransport}")
            appendLine("Hotspot auto-enable=${settings.autoEnableHotspot}; hotspot state=${SoftApStateReader.read(context)}")
            appendLine("Hotspot name saved=${settings.hotspotSsid.isNotBlank()}; password saved=${settings.hotspotPassword.isNotEmpty()}")
            appendLine("Logging level=${settings.exporterLogLevel}; source=${settings.logSource}")
            appendLine("Saved credentials, network addresses and protocol payloads are omitted.")
            appendLine("--- Recent app logs (up to 6000 lines) ---")
            if (lines.isEmpty()) appendLine("No recent app logs available. Reproduce the issue and save a new report.")
            lines.forEach { appendLine(it) }
        }
    }

    /**
     * Everything this process logged, including the ICCOA SDK's and libovmsink's own tags.
     * `--pid` is preferred; the tag list covers ROMs whose logcat rejects `--pid`. Returns null
     * when logcat is unavailable (a non-privileged app often cannot read it) so the caller can
     * fall back to the in-app journal.
     */
    private fun readLogcat(): List<String>? {
        val pid = android.os.Process.myPid().toString()
        val commands = listOf(
            listOf("logcat", "-d", "-v", "threadtime", "--pid=$pid"),
            listOf(
                "logcat", "-d", "-v", "threadtime",
                "OPENHU:V", "UCarAdapter:V", "UCarConfig:V", "UCarSurfaceView:V",
                "UCarProtocol:V", "SinkNative:V", "CastManager:V", "VideoPlayerV2:V",
                "VideoSinkInputThread:V", "VideoQueueManager:V", "UCarAudioManager:V",
                "AndroidRuntime:E", "*:S",
            ),
        )
        for (args in commands) {
            val chunk = ArrayList<String>()
            val ok = try {
                val process = ProcessBuilder(args).redirectErrorStream(true).start()
                try {
                    process.inputStream.bufferedReader().useLines { seq ->
                        seq.forEach { chunk.add(it) }
                    }
                    process.waitFor() == 0 && chunk.isNotEmpty()
                } finally {
                    process.destroy()
                }
            } catch (t: Throwable) {
                false
            }
            if (ok) return chunk
        }
        return null
    }
}
