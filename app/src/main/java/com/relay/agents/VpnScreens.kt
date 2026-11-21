package com.relay.agents

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import com.wireguard.crypto.KeyPair

/** نواة VPN: WireGuard الرسمية (GoBackend). لا تتضمن خوادم؛ تستورد أنت ملف إعداد من مزوّد تثق به. */
object VpnHub {
    var state by mutableStateOf(Tunnel.State.DOWN)
        private set
    var busy by mutableStateOf(false)
    var error by mutableStateOf("")
    var exitIp by mutableStateOf("")

    private var backend: GoBackend? = null
    private val tunnel = object : Tunnel {
        override fun getName(): String = "claude_agents"
        override fun onStateChange(newState: Tunnel.State) { state = newState }
    }

    private fun backend(ctx: Context): GoBackend = backend ?: GoBackend(ctx.applicationContext).also { backend = it }

    /** يضبط نطاق النفق داخل ملف الإعداد: هذا التطبيق فقط (IncludedApplications) أو كل الجهاز. */
    internal fun withScope(text: String, appOnly: Boolean, pkg: String): String {
        val out = mutableListOf<String>()
        var inserted = false
        for (line in text.lines()) {
            val t = line.trim()
            if (t.startsWith("IncludedApplications", true) || t.startsWith("ExcludedApplications", true)) continue
            out.add(line)
            if (appOnly && !inserted && t.equals("[Interface]", true)) { out.add("IncludedApplications = $pkg"); inserted = true }
        }
        return out.joinToString("\n")
    }

    suspend fun connect(ctx: Context, configText: String, appOnly: Boolean) {
        busy = true; error = ""
        withContext(Dispatchers.IO) {
            runCatching {
                val cfg = Config.parse(BufferedReader(StringReader(withScope(configText, appOnly, ctx.packageName))))
                backend(ctx).setState(tunnel, Tunnel.State.UP, cfg)
            }.onFailure { error = (it.message ?: it.javaClass.simpleName).take(200) }
        }
        busy = false
    }

    suspend fun disconnect(ctx: Context) {
        busy = true
        withContext(Dispatchers.IO) {
            runCatching { backend(ctx).setState(tunnel, Tunnel.State.DOWN, null) }
                .onFailure { error = (it.message ?: it.javaClass.simpleName).take(200) }
        }
        exitIp = ""
        busy = false
    }


    /** يسجّل جهازاً مجانياً في Cloudflare WARP (واجهة غير رسمية) ويبني ملف WireGuard. المفتاح الخاص يُولَّد محلياً. */
    suspend fun fetchWarp(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val kp = KeyPair()
            val body = JSONObject().put("key", kp.publicKey.toBase64()).put("install_id", "").put("fcm_token", "")
                .put("tos", java.time.Instant.now().toString()).put("model", "PC").put("serial_number", "").put("locale", "en_US").toString()
            val c = URL("https://api.cloudflareclient.com/v0a2158/reg").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 12000; c.readTimeout = 15000; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("User-Agent", "okhttp/3.12.1")
            c.setRequestProperty("CF-Client-Version", "a-6.3-2158")
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode !in 200..299) error("HTTP " + c.responseCode)
            val cfg = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONObject("config")
            val peerKey = cfg.getJSONArray("peers").getJSONObject(0).getString("public_key")
            val addr = cfg.getJSONObject("interface").getJSONObject("addresses")
            "[Interface]\nPrivateKey = ${kp.privateKey.toBase64()}\nAddress = ${addr.getString("v4")}/32, ${addr.getString("v6")}/128\nDNS = 1.1.1.1, 1.0.0.1\nMTU = 1280\n\n" +
                "[Peer]\nPublicKey = $peerKey\nAllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = engage.cloudflareclient.com:2408\n"
        }
    }

    /** عنوان IP الذي يراه العالم لهذا التطبيق (يستعلم من api.ipify.org عند الضغط فقط). */
    suspend fun checkIp(): String = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL("https://api.ipify.org").openConnection()
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.getInputStream().bufferedReader().use { it.readText().trim() }
        }.getOrElse { "—" }
    }
}

class VpnProfile(val name: String, val config: String)

@Composable
fun VpnTab(vm: RelayViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val storage = remember { Storage(ctx) }
    val profiles = remember {
        mutableStateListOf<VpnProfile>().apply {
            runCatching {
                val arr = org.json.JSONArray(SecretStore.decrypt(storage.setting("vpn_profiles", "")))
                for (i in 0 until arr.length()) arr.getJSONObject(i).let { add(VpnProfile(it.getString("n"), it.getString("c"))) }
            }
            if (isEmpty()) runCatching { SecretStore.decrypt(storage.setting("vpn_config", "")) }.getOrDefault("")
                .takeIf { it.isNotBlank() }?.let { add(VpnProfile("WireGuard", it)) }
        }
    }
    var sel by remember { mutableIntStateOf(storage.setting("vpn_sel", "0").toIntOrNull() ?: 0) }
    if (sel !in profiles.indices) sel = 0
    var appOnly by remember { mutableStateOf(storage.setting("vpn_app_only", "1") == "1") }
    var adding by remember { mutableStateOf(profiles.isEmpty()) }
    var draft by remember { mutableStateOf("") }
    var draftName by remember { mutableStateOf("") }
    var warpAsk by remember { mutableStateOf(false) }
    var warpBusy by remember { mutableStateOf(false) }
    val up = VpnHub.state == Tunnel.State.UP
    val config = profiles.getOrNull(sel)?.config ?: ""

    fun persistProfiles() {
        val arr = org.json.JSONArray()
        profiles.forEach { arr.put(JSONObject().put("n", it.name).put("c", it.config)) }
        storage.saveSetting("vpn_profiles", if (profiles.isEmpty()) "" else SecretStore.encrypt(arr.toString()))
        storage.saveSetting("vpn_sel", sel.toString())
    }
    fun addProfile(name: String, text: String) {
        profiles.add(VpnProfile(name.trim().ifEmpty { "WireGuard " + (profiles.size + 1) }, text.trim()))
        sel = profiles.size - 1; adding = false; draft = ""; draftName = ""; persistProfiles()
    }

    fun doConnect() { scope.launch { VpnHub.connect(ctx, config, appOnly) } }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) doConnect() else VpnHub.error = tr("لم يُمنح إذن VPN")
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) runCatching {
            draft = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }.take(20000)
        }
    }

    if (warpAsk) AlertDialog(
        onDismissRequest = { warpAsk = false },
        title = { Text(tr("جلب خادم مجاني")) },
        text = { Text(tr("سيتصل التطبيق بخدمة Cloudflare WARP لتسجيل جهاز مجاني (واجهة غير رسمية قد تتوقف). يخفي نشاطك عن مزوّد الإنترنت لكنه لا يتيح اختيار دولة. المفتاح الخاص يُولَّد على جهازك ويُحفظ مشفّراً.")) },
        confirmButton = { TextButton(onClick = {
            warpAsk = false; warpBusy = true; VpnHub.error = ""
            scope.launch {
                VpnHub.fetchWarp().onSuccess { addProfile("Cloudflare WARP", it) }
                    .onFailure { VpnHub.error = tr("تعذّر الجلب: ") + (it.message ?: it.javaClass.simpleName).take(120) }
                warpBusy = false
            }
        }) { Text(tr("متابعة")) } },
        dismissButton = { TextButton(onClick = { warpAsk = false }) { Text(tr("إلغاء")) } }
    )

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SoftCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusDot(if (up) Brand.Green else MaterialTheme.colorScheme.outline, 12)
                    Text(if (up) tr("متصل") else tr("غير متصل"), style = MaterialTheme.typography.titleMedium)
                    if (profiles.isNotEmpty()) Text(profiles[sel].name, style = MaterialTheme.typography.bodyMedium)
                }
                if (profiles.isEmpty()) {
                    Text(tr("لم يُستورد ملف إعداد بعد."), style = MaterialTheme.typography.bodySmall)
                } else {
                    Button(enabled = !VpnHub.busy, onClick = {
                        if (up) scope.launch { VpnHub.disconnect(ctx) }
                        else {
                            val i = VpnService.prepare(ctx)
                            if (i != null) permission.launch(i) else doConnect()
                        }
                    }) { Text(if (up) tr("قطع الاتصال") else tr("اتصال")) }
                }
                if (VpnHub.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (VpnHub.error.isNotEmpty()) Text(VpnHub.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (up) {
                    OutlinedButton(onClick = { scope.launch { VpnHub.exitIp = VpnHub.checkIp() } }) { Text(tr("فحص عنوان IP الظاهر")) }
                    if (VpnHub.exitIp.isNotEmpty()) SelectionContainer { Text("IP: " + VpnHub.exitIp, fontFamily = FontFamily.Monospace) }
                }
            }
        }

        SoftCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("الخوادم المحفوظة (اختر الدولة)"), style = MaterialTheme.typography.titleMedium)
                profiles.forEachIndexed { i, p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = i == sel, enabled = !up, onClick = { sel = i; persistProfiles() })
                        Text(p.name, modifier = Modifier.weight(1f), maxLines = 1)
                        TextButton(enabled = !up, onClick = { profiles.removeAt(i); if (sel >= profiles.size) sel = 0; persistProfiles() }) { Text(tr("حذف")) }
                    }
                }
                if (up) Text(tr("اقطع الاتصال أولاً لتغيير الخادم."), style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !warpBusy, onClick = { warpAsk = true }) { Text(if (warpBusy) tr("⏳ جارٍ الجلب…") else tr("جلب خادم مجاني (Cloudflare WARP)")) }
                }
                OutlinedButton(onClick = { adding = !adding }) { Text(tr("إضافة خادم بدولة محددة")) }
            }
        }

        VpnGateCard()

        if (adding) SoftCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(draftName, { draftName = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(tr("الاسم أو الدولة (مثل: 🇳🇱 هولندا)")) })
                OutlinedTextField(draft, { draft = it }, label = { Text(tr("الصق محتوى ملف .conf")) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { filePicker.launch("*/*") }) { Text(tr("اختيار ملف")) }
                    Button(enabled = draft.contains("[Interface]") && draft.contains("[Peer]"), onClick = { addProfile(draftName, draft) }) { Text(tr("حفظ")) }
                }
            }
        }

        SoftCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("نطاق النفق"), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = appOnly, onClick = { appOnly = true; storage.saveSetting("vpn_app_only", "1") }, label = { Text(tr("هذا التطبيق فقط")) })
                    FilterChip(selected = !appOnly, onClick = { appOnly = false; storage.saveSetting("vpn_app_only", "0") }, label = { Text(tr("كل الجهاز")) })
                }
                Text(
                    if (appOnly) tr("يمرّ عبر النفق طلبات الوكلاء والبوابة والمتصفح داخل التطبيق فقط، وباقي هاتفك لا يتأثر.")
                    else tr("يمرّ كل اتصال الهاتف عبر النفق."),
                    style = MaterialTheme.typography.bodySmall
                )
                if (up) Text(tr("غيّر النطاق ثم اقطع الاتصال وأعد الاتصال لتطبيقه."), style = MaterialTheme.typography.labelSmall)
            }
        }

        SoftCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("خوادم مجانية بدولة تختارها"), style = MaterialTheme.typography.titleMedium)
                Text(tr("Proton VPN المجاني (حساب مجاني بلا حد بيانات): ولّد ملف WireGuard لكل دولة متاحة من حسابك (مثل الولايات المتحدة وهولندا واليابان) وأضفه هنا باسم الدولة، ثم بدّل بينها بنقرة. الدول المجانية يحددها المزوّد وقد تتغير."),
                    style = MaterialTheme.typography.bodySmall)
                Text(tr("احذر من خوادم VPN مجانية مجهولة؛ قد تسجّل وتبيع بياناتك. الـ VPN ينقل ثقتك من مزوّد الإنترنت إلى مزوّد الـ VPN."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
