package bypass.whitelist.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import bypass.whitelist.R
import bypass.whitelist.tunnel.ConnectTarget
import bypass.whitelist.tunnel.PortGuard
import bypass.whitelist.tunnel.TunnelServiceState
import bypass.whitelist.tunnel.VpnStatus
import bypass.whitelist.util.ConnectionProber
import bypass.whitelist.util.Prefs
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import java.lang.ref.WeakReference

/**
 * Debug screen: active probes through the local SOCKS inbound plus an
 * automatic connect/probe/disconnect cycle over every whitelist service.
 * State lives in [DiagState] so it survives leaving/reopening the screen.
 */
class DiagnosticsFragment : Fragment(R.layout.fragment_diagnostics) {

    private val ui = Handler(Looper.getMainLooper())
    private var probeLog: TextView? = null
    private var autoLog: TextView? = null
    private var statusView: TextView? = null
    private var autoButton: MaterialButton? = null
    private var repeatSwitch: MaterialSwitch? = null

    private val tick = object : Runnable {
        override fun run() { render(); ui.postDelayed(this, 1000) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<ImageButton>(R.id.backButton).setOnClickListener { (activity as? MainActivityHost)?.popSubPage() }
        probeLog = view.findViewById(R.id.probeLog)
        autoLog = view.findViewById(R.id.autoLog)
        statusView = view.findViewById(R.id.diagStatus)
        autoButton = view.findViewById(R.id.buttonAutoTest)
        repeatSwitch = view.findViewById(R.id.switchRepeat)
        view.findViewById<MaterialButton>(R.id.buttonRunOnce).setOnClickListener { DiagState.runProbeAsync() }
        repeatSwitch?.isChecked = DiagState.repeat
        repeatSwitch?.setOnCheckedChangeListener { _, checked -> DiagState.setRepeat(checked) }
        autoButton?.setOnClickListener {
            if (DiagState.autoRunning) DiagState.stopAuto()
            else (activity as? MainFragment.Host)?.let { DiagState.startAuto(it) }
        }
        view.findViewById<MaterialButton>(R.id.buttonCopyReport).setOnClickListener {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("diagnostics", DiagState.report()))
            Toast.makeText(requireContext(), R.string.diag_copied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }

    private fun render() {
        val host = activity as? MainFragment.Host
        val st = host?.currentStatus()
        statusView?.text = getString(R.string.diag_status, st?.name ?: "-", DiagState.autoStage)
        probeLog?.text = synchronized(DiagState) { DiagState.probes.takeLast(30).reversed().joinToString("\n\n") { it.format() } }
            .ifEmpty { getString(R.string.diag_empty) }
        autoLog?.text = DiagState.autoTable().ifEmpty { getString(R.string.diag_empty) }
        autoButton?.setText(if (DiagState.autoRunning) R.string.diag_auto_stop else R.string.diag_auto_start)
    }
}

/** Process-wide diagnostics state and workers (plain threads, never the UI thread). */
object DiagState {
    val probes = mutableListOf<ConnectionProber.ProbeSet>()
    data class AutoRow(val service: String, var connectMs: Long = -1, var probe: ConnectionProber.ProbeSet? = null, var error: String = "")
    val autoRows = mutableListOf<AutoRow>()

    @Volatile var repeat = false; private set
    @Volatile var autoRunning = false; private set
    @Volatile var autoStage = ""
    @Volatile private var probeBusy = false
    private var repeatThread: Thread? = null
    private var autoThread: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    fun runProbeAsync() {
        if (probeBusy) return
        Thread { runProbeBlocking() }.apply { isDaemon = true }.start()
    }

    private fun runProbeBlocking(): ConnectionProber.ProbeSet {
        probeBusy = true
        try {
            val r = ConnectionProber.runAll()
            synchronized(this) { probes.add(r); if (probes.size > 200) probes.removeAt(0) }
            return r
        } finally { probeBusy = false }
    }

    fun setRepeat(on: Boolean) {
        repeat = on
        repeatThread?.interrupt()
        repeatThread = null
        if (!on) return
        repeatThread = Thread {
            try {
                while (repeat && !Thread.currentThread().isInterrupted) {
                    if (!probeBusy && !autoRunning) runProbeBlocking()
                    Thread.sleep(30_000)
                }
            } catch (_: InterruptedException) {}
        }.apply { isDaemon = true; start() }
    }

    private fun <T> onMain(block: () -> T): T? {
        var out: T? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        main.post { try { out = block() } finally { latch.countDown() } }
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        return out
    }

    fun startAuto(host: MainFragment.Host) {
        if (autoRunning) return
        autoRunning = true
        val hostRef = WeakReference(host)
        val ctx = (host as? Context)?.applicationContext
        synchronized(this) { autoRows.clear() }
        autoThread = Thread {
            try {
                val services = Prefs.corsServices.ifEmpty { listOf(cc.cors.connect.api.AppService(0, "default")) }
                for (svc in services) {
                    if (!autoRunning) break
                    val row = AutoRow("${svc.name} (#${svc.id})")
                    synchronized(this) { autoRows.add(row) }
                    autoStage = "${svc.name}: wait idle"
                    waitIdle(hostRef, ctx)
                    autoStage = "${svc.name}: connecting"
                    onMain {
                        Prefs.corsServiceId = svc.id
                        Prefs.activeDestinationId = ""
                        Prefs.connectionMode = bypass.whitelist.tunnel.ConnectionMode.INSTANCE
                        hostRef.get()?.onConnectPressed(ConnectTarget.WhitelistBypass(svc.id, svc.name))
                    }
                    val t0 = System.currentTimeMillis()
                    var captchaSince = 0L
                    var outcome = "timeout"
                    while (autoRunning) {
                        val st = onMain { hostRef.get()?.currentStatus() }
                        val now = System.currentTimeMillis()
                        if (st == VpnStatus.TUNNEL_ACTIVE) { outcome = "ok"; break }
                        if (st == VpnStatus.CALL_FAILED) { outcome = "call failed"; break }
                        if (st == VpnStatus.ACTION_REQUIRED_CAPTCHA) {
                            if (captchaSince == 0L) captchaSince = now
                            if (now - t0 > 60_000) { outcome = "captcha"; break }
                        }
                        if (now - t0 > 90_000) { outcome = if (captchaSince > 0) "captcha" else "timeout (${st?.name})"; break }
                        Thread.sleep(500)
                    }
                    if (outcome == "ok") {
                        row.connectMs = System.currentTimeMillis() - t0
                        Thread.sleep(1500)
                        autoStage = "${svc.name}: probing"
                        row.probe = runProbeBlocking()
                        if (row.probe?.allOk != true) row.error = "probe failed"
                    } else {
                        row.error = outcome
                    }
                    autoStage = "${svc.name}: disconnecting"
                    onMain { hostRef.get()?.onDisconnectPressed() }
                    Thread.sleep(2000)
                }
                waitIdle(hostRef, ctx)
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                autoStage = "error: ${e.message}"
            } finally {
                autoRunning = false
                if (!autoStage.startsWith("error")) autoStage = "done"
            }
        }.apply { isDaemon = true; start() }
    }

    fun stopAuto() {
        autoRunning = false
        autoThread?.interrupt()
        autoStage = "stopped"
    }

    /** Waits (max 30s) until no tunnel component runs and the SOCKS ports are free. */
    private fun waitIdle(hostRef: WeakReference<MainFragment.Host>, ctx: Context?) {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 30_000) {
            val running = ctx?.let { TunnelServiceState.isAnyTunnelComponentRunning(it) } ?: false
            val portsFree = PortGuard.isPortAvailable(Prefs.socksPort) && PortGuard.isPortAvailable(Prefs.xraySocksPort)
            val st = onMain { hostRef.get()?.currentStatus() }
            if (!running && portsFree && st != VpnStatus.STOPPING) return
            Thread.sleep(500)
        }
    }

    fun autoTable(): String = synchronized(this) {
        autoRows.joinToString("\n") { r ->
            val c = if (r.connectMs >= 0) "${r.connectMs / 1000}s" else "-"
            val p = r.probe?.short() ?: "-"
            "${r.service} | connect $c | $p | ${r.error.ifEmpty { "ok" }}"
        }
    }

    fun report(): String = synchronized(this) {
        val sb = StringBuilder("== Cors.Connect diagnostics ==\n")
        sb.append("status rows (service | connect | probes | error):\n").append(autoTable()).append("\n\n")
        for (r in autoRows) r.probe?.let { sb.append(r.service).append(":\n").append(it.format()).append("\n") }
        sb.append("\n-- probe history --\n")
        probes.forEach { sb.append(it.format()).append("\n") }
        sb.toString()
    }
}
