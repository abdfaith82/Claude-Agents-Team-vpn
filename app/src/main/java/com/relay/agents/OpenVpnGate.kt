package com.relay.agents

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.blinkt.openvpn.api.IOpenVPNAPIService
import de.blinkt.openvpn.api.IOpenVPNStatusCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class GateServer(val country: String, val code: String, val ip: String, val score: Long, val speedMbps: Double, val config: String)

/** نواة OpenVPN (ics-openvpn) مدمجة داخل التطبيق: نتحكم بها عبر واجهتها AIDL داخل نفس الحزمة، ونجلب خوادم VPN Gate حسب الدولة. */
object OvpnHub {
    var service by mutableStateOf<IOpenVPNAPIService?>(null)
        private set
    var status by mutableStateOf("")
    var error by mutableStateOf("")
    var servers by mutableStateOf<List<GateServer>>(emptyList())
    var loading by mutableStateOf(false)
    var current by mutableStateOf("")
    private var bound = false

    private val cb = object : IOpenVPNStatusCallback.Stub() {
        override fun newStatus(uuid: String?, state: String?, message: String?, level: String?) { status = state ?: "" }
    }
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
            service = IOpenVPNAPIService.Stub.asInterface(b)
            runCatching { service?.registerStatusCallback(cb) }
        }
        override fun onServiceDisconnected(n: ComponentName?) { service = null; bound = false }
    }

    fun installed(@Suppress("UNUSED_PARAMETER") ctx: Context): Boolean = true

    fun bind(ctx: Context) {
        if (bound || !installed(ctx)) return
        val i = Intent(IOpenVPNAPIService::class.java.name).setPackage(ctx.packageName)
        bound = runCatching { ctx.applicationContext.bindService(i, conn, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
    }

    fun flag(code: String): String =
        if (code.length != 2) "🌐" else code.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")

    /** قائمة VPN Gate العامة (متطوعون). تُرتَّب بالنقاط الأعلى أولاً. */
    suspend fun fetch() {
        loading = true; error = ""
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val c = URL("https://www.vpngate.net/api/iphone/").openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 40000
                val out = mutableListOf<GateServer>()
                c.inputStream.bufferedReader().useLines { lines ->
                    for (l in lines) {
                        if (l.startsWith("*") || l.startsWith("#")) continue
                        val f = l.split(",")
                        if (f.size < 15) continue
                        val cfg = runCatching { String(Base64.decode(f.last().trim(), Base64.DEFAULT)).replace("\r", "") }.getOrNull() ?: continue
                        if (!cfg.contains("remote ")) continue
                        out += GateServer(f[5], f[6], f[1], f[2].toLongOrNull() ?: 0, (f[4].toLongOrNull() ?: 0) / 1_000_000.0, cfg)
                    }
                }
                out.sortedByDescending { it.score }
            }
        }
        r.onSuccess { servers = it; if (it.isEmpty()) error = tr("القائمة فارغة حالياً، حاول لاحقاً.") }
            .onFailure { error = tr("تعذّر جلب القائمة: ") + (it.message ?: it.javaClass.simpleName).take(120) }
        loading = false
    }
}

@Composable
fun VpnGateCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val hub = OvpnHub
    var installed by remember { mutableStateOf(hub.installed(ctx)) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var skip by remember { mutableIntStateOf(0) }
    var chosen by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { installed = hub.installed(ctx); hub.bind(ctx) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) pending?.invoke() else hub.error = tr("لم يُمنح الإذن")
    }

    fun withPermission(then: () -> Unit) {
        val s = hub.service ?: run { hub.error = tr("نواة OpenVPN غير جاهزة بعد، أعد المحاولة بعد ثوانٍ."); return }
        runCatching {
            val i1: Intent? = s.prepare(ctx.packageName)
            if (i1 != null) { pending = { withPermission(then) }; launcher.launch(i1); return }
            val i2: Intent? = s.prepareVPNService()
            if (i2 != null) { pending = then; launcher.launch(i2); return }
            then()
        }.onFailure { hub.error = (it.message ?: it.javaClass.simpleName).take(160) }
    }

    fun connect(country: String, n: Int) {
        val list = hub.servers.filter { it.country == country }
        if (list.isEmpty()) return
        val srv = list[n % list.size]
        withPermission {
            runCatching { hub.service?.startVPN(srv.config); hub.current = country + " · " + srv.ip; hub.error = "" }
                .onFailure { hub.error = (it.message ?: it.javaClass.simpleName).take(160) }
        }
    }

    SoftCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("اختيار دولة (OpenVPN · VPN Gate)"), style = MaterialTheme.typography.titleMedium)
            Text(tr("خوادم عامة مجانية يديرها متطوعون، وتغطي دولاً كثيرة. تعمل بنواة OpenVPN المدمجة في التطبيق، والنفق هنا لكل الجهاز. لا تستخدمها للحسابات الحساسة."),
                style = MaterialTheme.typography.bodySmall)
            run {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = !hub.loading, onClick = { scope.launch { hub.fetch() } }) {
                        Text(if (hub.loading) tr("⏳ جارٍ الجلب…") else tr("جلب الخوادم"))
                    }
                    OutlinedButton(onClick = { runCatching { hub.service?.disconnect(); hub.current = "" } }) { Text(tr("قطع الاتصال")) }
                }
                if (hub.status.isNotEmpty()) Text(trf("الحالة: %s", hub.status) + (if (hub.current.isNotEmpty()) " — " + hub.current else ""),
                    style = MaterialTheme.typography.bodySmall)
                val groups = hub.servers.groupBy { it.country }.entries.sortedByDescending { e -> e.value.maxOf { it.score } }
                if (groups.isNotEmpty()) {
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        groups.forEach { (country, list) ->
                            ListItem(
                                headlineContent = { Text(hub.flag(list.first().code) + "  " + country) },
                                supportingContent = { Text(trf("%s خادم · الأسرع ~%s Mbps", list.size, "%.0f".format(java.util.Locale.US, list.maxOf { it.speedMbps }))) },
                                trailingContent = { if (chosen == country) Text("✓") },
                                modifier = Modifier.clickable { chosen = country; skip = 0; connect(country, 0) }
                            )
                        }
                    }
                    if (chosen.isNotEmpty()) OutlinedButton(onClick = { skip++; connect(chosen, skip) }) { Text(tr("جرّب خادماً آخر في نفس الدولة")) }
                }
            }
            if (hub.error.isNotEmpty()) Text(hub.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

