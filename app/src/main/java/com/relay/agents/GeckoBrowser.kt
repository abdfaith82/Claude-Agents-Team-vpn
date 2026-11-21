package com.relay.agents

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.webkit.URLUtil
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebResponse
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

private const val TAG = "GeckoBrowser"
private const val BRIDGE_URI = "resource://android/assets/bridge/"
// بعض المواقع (مثل claude.ai) تعدّ إصدار المحرك المضمّن قديماً؛ نعرّف المتصفح بإصدار فايرفوكس حديث.
private const val MODERN_UA = "Mozilla/5.0 (Android 14; Mobile; rv:145.0) Gecko/145.0 Firefox/145.0"
const val AI_STUDIO_CTX = "env_aistudio"
// التطبيق الذي حدّدته في AI Studio (بدون /u/N ليستخدم حسابك الافتراضي بعد تسجيل الدخول).
const val AI_STUDIO_URL = "https://aistudio.google.com/apps/97f6253e-b0fa-450d-8a98-0084dc261076?showPreview=true"
const val APKONLINE_CTX = "env_apkonline"
const val APKONLINE_URL = "https://www.apkonline.net/"
private const val BRIDGE_ID = "bridge@claudeagents.team"

/** تبويب واحد: جلسة Gecko خاصة به، وبيئة تخزين (ctxId) منفصلة للكوكيز وتسجيل الدخول. */
class BrowserTab(val id: String, val ctxId: String, startUrl: String, env0: String) {
    var env by mutableStateOf(env0)
    var url by mutableStateOf(startUrl)
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var canBack by mutableStateOf(false)
    var canFwd by mutableStateOf(false)

    @Volatile var port: WebExtension.Port? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

    val session: GeckoSession = GeckoSession(
        GeckoSessionSettings.Builder().contextId(ctxId).userAgentOverride(MODERN_UA).build()
    ).also { s ->
        s.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) { this@BrowserTab.title = title ?: "" }
            override fun onCloseRequest(session: GeckoSession) { if (!BrowserHub.isPinned(ctxId)) BrowserHub.close(this@BrowserTab) }
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) { BrowserHub.saveDownload(response) }
        }
        s.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession, url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean
            ) {
                if (url != null && BrowserHub.isWeb(url)) { this@BrowserTab.url = url; BrowserHub.persist() }
            }
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) { canBack = canGoBack }
            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) { canFwd = canGoForward }
            // نوافذ منبثقة (مثل زر «الدخول بحساب Google»): تبويب جديد بنفس البيئة.
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                val child = BrowserHub.openChild(this@BrowserTab, uri)
                return GeckoResult.fromValue(child.session)
            }
        }
        // نافذة اختيار الملفات (زر «رفع ملفات» في المواقع): تُمرَّر إلى واجهة الاختيار في التطبيق.
        s.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onFilePrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.FilePrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                BrowserHub.filePick = FilePickReq(prompt, result)
                return result
            }
        }
        s.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) { loading = true }
            override fun onPageStop(session: GeckoSession, success: Boolean) { loading = false; progress = 0 }
            override fun onProgressChange(session: GeckoSession, progress: Int) { this@BrowserTab.progress = progress }
        }
    }

    fun onPortMessage(message: Any) {
        val o = message as? JSONObject ?: return
        pending[o.optString("id")]?.complete(o)
    }

    /** يطلب من الصفحة لقطة (نص + حقول) عبر إضافة الجسر. null إن لم تكن الصفحة جاهزة أو لا تدعم الإضافة. */
    suspend fun snapshot(timeoutMs: Long = 6000): JSONObject? {
        val p = port ?: return null
        val id = UUID.randomUUID().toString()
        val d = CompletableDeferred<JSONObject>()
        pending[id] = d
        return try {
            p.postMessage(JSONObject().put("id", id).put("cmd", "snapshot"))
            val r = withTimeoutOrNull(timeoutMs) { d.await() }
            if (r != null && r.optBoolean("ok")) r.optJSONObject("data") else null
        } finally {
            pending.remove(id)
        }
    }
}

/** طلب اختيار ملف من صفحة ويب بانتظار نتيجة منتقي الملفات. */
class FilePickReq(val prompt: GeckoSession.PromptDelegate.FilePrompt, val result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>)

/** بيئة محفوظة: اسم + آخر صفحة + تثبيت. بياناتها (الكوكيز وتسجيل الدخول) تبقى في Gecko ما لم تُمسح. */
class SavedEnv(val ctxId: String, var name: String, var url: String, var pinned: Boolean)

/** سجل ما قام به الوكيل في المتصفح (قراءات وأسئلة وإجاباته) ليُصدَّر إلى الوكيل الرئيسي. */
class AgentAction(val time: Long, val env: String, val title: String, val url: String, val scope: String, val question: String, val answer: String)

/** مركز المتصفح: محرك Gecko واحد للعملية، وقائمة التبويبات، وحفظها بين مرات التشغيل. */
object BrowserHub {
    val tabs = mutableStateListOf<BrowserTab>()
    var selectedId by mutableStateOf("")

    private var appCtx: Context? = null
    private var runtime: GeckoRuntime? = null
    private var bridge: WebExtension? = null
    private var storage: Storage? = null
    private var counter = 0

    var filePick by mutableStateOf<FilePickReq?>(null)
    val envs = mutableStateListOf<SavedEnv>()
    val actions = mutableStateListOf<AgentAction>()

    /** عناوين الويب فقط تُحفظ؛ about:blank وغيرها تُهمل كي لا تُفسد عنوان البيئة المحفوظ. */
    fun isWeb(u: String?): Boolean = u != null && (u.startsWith("http://") || u.startsWith("https://"))

    fun savedOf(ctxId: String): SavedEnv? = envs.firstOrNull { it.ctxId == ctxId }
    fun isPinned(ctxId: String): Boolean = savedOf(ctxId)?.pinned == true

    private fun relabel(ctxId: String, name: String) { tabs.filter { it.ctxId == ctxId }.forEach { it.env = name } }

    /** تثبيت البيئة: لا يُغلق تبويبها ويُعاد فتحها دائماً مع كل جلسة، وتُحفظ تلقائياً. */
    fun togglePin(tab: BrowserTab) {
        val e = savedOf(tab.ctxId)
        if (e == null) envs.add(SavedEnv(tab.ctxId, tab.env, tab.url, true)) else e.pinned = !e.pinned
        persist()
    }

    /** حفظ البيئة باسم لاستعادتها لاحقاً (تبقى بياناتها حتى لو أُغلقت تبويباتها). */
    fun saveEnv(tab: BrowserTab, name: String) {
        val n = name.trim().ifEmpty { tab.env }
        val e = savedOf(tab.ctxId)
        if (e == null) envs.add(SavedEnv(tab.ctxId, n, tab.url, false)) else { e.name = n; e.url = tab.url }
        relabel(tab.ctxId, n)
        persist()
    }

    /** يفتح بيئة محفوظة: يعود لتبويبها المفتوح أو يفتح تبويباً جديداً بنفس البيئة وآخر صفحة. */
    fun openSaved(ctx: Context, e: SavedEnv) {
        val open = tabs.firstOrNull { it.ctxId == e.ctxId }
        if (open != null) select(open) else newTab(ctx, e.url, e.ctxId)
    }

    /** ينسى البيئة ويمسح بياناتها نهائياً ويغلق تبويباتها. */
    fun forget(e: SavedEnv) {
        e.pinned = false
        tabs.filter { it.ctxId == e.ctxId }.forEach { close(it) }
        runtime?.storageController?.clearDataForSessionContext(e.ctxId)
        envs.remove(e)
        persist()
    }

    /** تبويب Google AI Studio ببيئة مخصّصة محفوظة (تسجيل دخولك يبقى فيها). يعود للتبويب المفتوح إن وُجد. */
    fun openAiStudio(ctx: Context) {
        if (savedOf(AI_STUDIO_CTX) == null) envs.add(SavedEnv(AI_STUDIO_CTX, "AI Studio", AI_STUDIO_URL, false))
        val open = tabs.firstOrNull { it.ctxId == AI_STUDIO_CTX }
        if (open != null) select(open) else newTab(ctx, savedOf(AI_STUDIO_CTX)?.url?.takeIf { isWeb(it) } ?: AI_STUDIO_URL, AI_STUDIO_CTX)
        persist()
    }

    /** تبويب ApkOnline (محاكي أندرويد مستضاف) ببيئة مخصّصة. الخدمة طرف ثالث فلا نعدّل فيها شيئاً؛ نفتحها فقط. */
    fun openApkOnline(ctx: Context) {
        if (savedOf(APKONLINE_CTX) == null) envs.add(SavedEnv(APKONLINE_CTX, "ApkOnline", APKONLINE_URL, false))
        val open = tabs.firstOrNull { it.ctxId == APKONLINE_CTX }
        if (open != null) select(open) else newTab(ctx, savedOf(APKONLINE_CTX)?.url?.takeIf { isWeb(it) } ?: APKONLINE_URL, APKONLINE_CTX)
        persist()
    }

    fun logAction(tab: BrowserTab, scope: String, question: String, answer: String) {
        actions.add(AgentAction(System.currentTimeMillis(), tab.env, tab.title, tab.url, scope, question, answer))
        while (actions.size > 40) actions.removeAt(0)
    }

    /** نص التبويبات المفتوحة ولقطة كل واحد منها ليقرأها الوكيل الرئيسي. */
    suspend fun allTabsPrompt(): String {
        val sb = StringBuilder()
        tabs.toList().forEachIndexed { i, t ->
            sb.append("=== Tab ${i + 1} [env ${t.env}${if (isPinned(t.ctxId)) ", pinned" else ""}] ===\n")
            val snap = t.snapshot(4000)
            if (snap != null) sb.append(snapshotToPrompt(snap).take(2500)) else sb.append("URL: ${t.url}\nTitle: ${t.title}\n(content unavailable)")
            sb.append("\n\n")
        }
        return sb.toString()
    }

    /** ملخص جاهز للتصدير إلى الوكيل الرئيسي: التبويبات الحالية ثم سجل ما نفّذه الوكيل. */
    fun exportText(): String {
        val sb = StringBuilder("Browser state\nOpen tabs:\n")
        tabs.toList().forEachIndexed { i, t ->
            sb.append("${i + 1}. [env ${t.env}${if (isPinned(t.ctxId)) ", pinned" else ""}] ${t.title.ifBlank { "-" }} — ${t.url}\n")
        }
        sb.append("\nAgent activity in the browser (oldest first):\n")
        if (actions.isEmpty()) sb.append("(none yet)\n")
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        actions.toList().forEachIndexed { i, a ->
            sb.append("${i + 1}. ${fmt.format(java.util.Date(a.time))} [env ${a.env}] ${a.scope} — ${a.url}\n   Q: ${a.question.take(300)}\n   A: ${a.answer.take(900)}\n")
        }
        return sb.toString().take(12000)
    }

    fun runtime(ctx: Context): GeckoRuntime {
        appCtx = ctx.applicationContext
        runtime?.let { return it }
        val rt = GeckoRuntime.create(ctx.applicationContext)
        runtime = rt
        rt.webExtensionController.ensureBuiltIn(BRIDGE_URI, BRIDGE_ID).accept(
            { ext ->
                if (ext != null) {
                    bridge = ext
                    tabs.forEach { attachBridge(it) }
                }
            },
            { e -> Log.e(TAG, "bridge install failed", e) }
        )
        return rt
    }

    private fun attachBridge(tab: BrowserTab) {
        val ext = bridge ?: return
        val delegate = object : WebExtension.MessageDelegate {
            override fun onConnect(port: WebExtension.Port) {
                tab.port = port
                port.setDelegate(object : WebExtension.PortDelegate {
                    override fun onPortMessage(message: Any, port: WebExtension.Port) { tab.onPortMessage(message) }
                    override fun onDisconnect(port: WebExtension.Port) { if (tab.port == port) tab.port = null }
                })
            }
        }
        tab.session.webExtensionController.setMessageDelegate(ext, delegate, "browser")
    }

    private fun register(ctx: Context, tab: BrowserTab, open: Boolean) {
        tabs.add(tab)
        if (open) tab.session.open(runtime(ctx))
        attachBridge(tab)
    }

    /** يستعيد التبويبات المحفوظة أو يفتح واحداً جديداً. */
    fun init(ctx: Context, homeUrl: String) {
        if (tabs.isNotEmpty()) return
        val st = Storage(ctx.applicationContext).also { storage = it }
        runtime(ctx)
        runCatching {
            val ea = JSONArray(st.setting("gecko_envs", "[]"))
            for (i in 0 until ea.length()) {
                val o = ea.getJSONObject(i)
                envs.add(SavedEnv(o.getString("ctx"), o.optString("name", "A"), o.optString("url", homeUrl).takeIf { isWeb(it) } ?: when (o.getString("ctx")) { APKONLINE_CTX -> APKONLINE_URL; AI_STUDIO_CTX -> AI_STUDIO_URL; else -> homeUrl }, o.optBoolean("pinned")))
            }
        }
        runCatching {
            val arr = JSONArray(st.setting("gecko_tabs", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = BrowserTab(o.getString("id"), o.getString("ctx"), (savedOf(o.getString("ctx"))?.url ?: o.optString("url", homeUrl)).takeIf { isWeb(it) } ?: homeUrl, savedOf(o.getString("ctx"))?.name ?: o.optString("env", "A"))
                register(ctx, t, true)
                t.session.loadUri(t.url)
            }
        }
        // البيئات المثبّتة تُفتح دائماً حتى لو لم تكن بين تبويبات الجلسة السابقة.
        envs.filter { e -> e.pinned && tabs.none { it.ctxId == e.ctxId } }.forEach { e ->
            val t = BrowserTab(UUID.randomUUID().toString().take(8), e.ctxId, e.url, e.name)
            register(ctx, t, true)
            t.session.loadUri(e.url)
        }
        if (tabs.isEmpty()) newTab(ctx, homeUrl, null)
        selectedId = st.setting("gecko_selected", "").takeIf { id -> tabs.any { it.id == id } } ?: tabs.first().id
    }

    /** تبويب جديد. ctxId = null → بيئة جديدة منفصلة؛ وإلا يشارك بيئة تبويب موجود. */
    fun newTab(ctx: Context, url: String, ctxId: String?): BrowserTab {
        val id = UUID.randomUUID().toString().take(8)
        val envCtx = ctxId ?: ("env" + UUID.randomUUID().toString().take(8))
        val envName = savedOf(envCtx)?.name ?: tabs.firstOrNull { it.ctxId == envCtx }?.env ?: ('A' + (counter++ % 26)).toString() + (if (counter > 26) "${counter / 26}" else "")
        val t = BrowserTab(id, envCtx, url, envName)
        register(ctx, t, true)
        t.session.loadUri(url)
        select(t)
        persist()
        return t
    }

    internal fun openChild(parent: BrowserTab, uri: String): BrowserTab {
        val ctx = appCtx!!
        val t = BrowserTab(UUID.randomUUID().toString().take(8), parent.ctxId, uri, parent.env)
        register(ctx, t, false) // يفتحها Gecko بنفسه بعد إرجاعها
        selectedId = t.id
        persist()
        return t
    }

    fun select(tab: BrowserTab) {
        selectedId = tab.id
        tabs.forEach { if (it.session.isOpen) it.session.setActive(it === tab) }
        storage?.saveSetting("gecko_selected", tab.id)
    }

    fun close(tab: BrowserTab): Boolean {
        if (isPinned(tab.ctxId)) return false
        val idx = tabs.indexOf(tab)
        if (idx < 0) return false
        runCatching { tab.session.close() }
        tabs.remove(tab)
        if (tabs.isEmpty()) {
            appCtx?.let { newTab(it, "https://github.com", null) }
        } else if (selectedId == tab.id) {
            select(tabs[minOf(idx, tabs.size - 1)])
        }
        persist()
        return true
    }

    /** يمسح كوكيز وتخزين بيئة هذا التبويب فقط (فيخرج من الحسابات المسجّلة فيها). */
    fun clearEnvironment(tab: BrowserTab) {
        runtime?.storageController?.clearDataForSessionContext(tab.ctxId)
        tab.session.reload()
    }

    fun persist() {
        val st = storage ?: return
        runCatching {
            val arr = JSONArray()
            tabs.forEach { arr.put(JSONObject().put("id", it.id).put("ctx", it.ctxId).put("url", it.url).put("env", it.env)) }
            st.saveSetting("gecko_tabs", arr.toString())
            tabs.forEach { t -> if (isWeb(t.url)) savedOf(t.ctxId)?.url = t.url }
            val ea = JSONArray()
            envs.forEach { ea.put(JSONObject().put("ctx", it.ctxId).put("name", it.name).put("url", it.url).put("pinned", it.pinned)) }
            st.saveSetting("gecko_envs", ea.toString())
        }
    }

    // ---- التنزيلات ----
    fun saveDownload(r: WebResponse) {
        val ctx = appCtx ?: return
        val body = r.body ?: return
        val hdr = { k: String -> r.headers.entries.firstOrNull { it.key.equals(k, true) }?.value }
        val cd = hdr("content-disposition")
        val mime = hdr("content-type")?.substringBefore(';')?.trim()
        val name = URLUtil.guessFileName(r.uri, cd, mime)
        val ui = Handler(Looper.getMainLooper())
        ui.post { Toast.makeText(ctx, trf("بدأ التنزيل: %s", name), Toast.LENGTH_LONG).show() }
        thread {
            runCatching {
                body.use { input ->
                    if (Build.VERSION.SDK_INT >= 29) {
                        val v = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, name)
                            if (mime != null) put(MediaStore.Downloads.MIME_TYPE, mime)
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }
                        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: error("insert failed")
                        ctx.contentResolver.openOutputStream(uri)!!.use { out -> input.copyTo(out) }
                    } else {
                        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
                        java.io.File(dir, name).outputStream().use { out -> input.copyTo(out) }
                    }
                }
            }.onSuccess {
                ui.post { Toast.makeText(ctx, trf("اكتمل التنزيل: %s", name), Toast.LENGTH_LONG).show() }
            }.onFailure {
                Log.e(TAG, "download failed", it)
                ui.post { Toast.makeText(ctx, tr("تعذّر بدء التنزيل"), Toast.LENGTH_LONG).show() }
            }
        }
    }
}

/** يحوّل لقطة الصفحة إلى نص مضغوط للوكيل (كلمات المرور محجوبة أصلاً من الجسر). */
internal fun snapshotToPrompt(s: JSONObject): String {
    val sb = StringBuilder()
    sb.append("URL: ").append(s.optString("url")).append('\n')
    sb.append("Title: ").append(s.optString("title")).append('\n')
    if (s.optBoolean("hasPassword")) sb.append("(الصفحة فيها حقل كلمة مرور؛ قيمته غير مقروءة)\n")
    sb.append("\nFields:\n")
    val f = s.optJSONArray("fields") ?: JSONArray()
    for (i in 0 until f.length()) {
        val o = f.getJSONObject(i)
        sb.append("- ").append(o.optString("tag"))
        if (o.optString("type").isNotEmpty()) sb.append('[').append(o.optString("type")).append(']')
        val label = o.optString("label").ifEmpty { o.optString("name").ifEmpty { o.optString("id") } }
        if (label.isNotEmpty()) sb.append(" \"").append(label).append('"')
        if (o.has("text")) sb.append(" text=").append(o.optString("text"))
        if (o.has("value") && o.optString("value").isNotEmpty()) sb.append(" value=").append(o.optString("value"))
        if (o.optBoolean("secret")) sb.append(" (secret)")
        if (o.optBoolean("required")) sb.append(" required")
        if (o.has("checked")) sb.append(" checked=").append(o.optBoolean("checked"))
        sb.append('\n')
    }
    sb.append("\nPage text:\n").append(s.optString("text"))
    return sb.toString()
}

private val GH_RESERVED = setOf(
    "settings", "orgs", "marketplace", "notifications", "login", "logout", "sponsors", "features", "pricing", "explore", "topics",
    "trending", "search", "new", "apps", "users", "about", "pulls", "issues", "codespaces", "organizations", "enterprise",
    "collections", "events", "dashboard", "join", "sessions", "account", "contact", "security", "site", "stars", "watching"
)

/** يستخرج جذر المستودع من رابط الصفحة المفتوحة (https://github.com/owner/repo) أو null. */
internal fun repoBaseOf(url: String): String? {
    val m = Regex("""^https://github\.com/([^/?#]+)/([^/?#]+)""").find(url) ?: return null
    if (m.groupValues[1].lowercase() in GH_RESERVED) return null
    return "https://github.com/${m.groupValues[1]}/${m.groupValues[2]}"
}

@Composable
private fun NavBtn(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(38.dp).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.titleMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
    }
}

/** متصفح Gecko متعدد التبويبات: كل تبويب ببيئة تسجيل دخول منفصلة، ومربوط بالوكيل الرئيسي لقراءة الصفحة وحقولها. */
@Composable
fun GeckoBrowserTab(vm: RelayViewModel, gh: GhConfig, onOpenChat: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val base = "https://github.com/${gh.owner}/${gh.repo}"
    remember { BrowserHub.init(ctx, base); true }
    val tab = BrowserHub.tabs.firstOrNull { it.id == BrowserHub.selectedId } ?: BrowserHub.tabs.firstOrNull()

    var addr by remember(tab?.id) { mutableStateOf(tab?.url ?: "") }
    LaunchedEffect(tab?.url) { tab?.url?.let { addr = it } }
    var plusMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var agentOpen by remember { mutableStateOf(false) }
    var saveOpen by remember { mutableStateOf(false) }
    var listOpen by remember { mutableStateOf(false) }

    // رفع الملفات: منتقي النظام ثم تسليم النتيجة للصفحة.
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val req = BrowserHub.filePick
        BrowserHub.filePick = null
        if (req != null) {
            val data = res.data
            val uris = mutableListOf<Uri>()
            data?.clipData?.let { c -> for (i in 0 until c.itemCount) uris.add(c.getItemAt(i).uri) }
            if (uris.isEmpty()) data?.data?.let { uris.add(it) }
            val resp = if (res.resultCode != android.app.Activity.RESULT_OK || uris.isEmpty()) req.prompt.dismiss()
            else if (uris.size == 1) req.prompt.confirm(ctx, uris[0]) else req.prompt.confirm(ctx, uris.toTypedArray())
            req.result.complete(resp)
        }
    }
    val pickReq = BrowserHub.filePick
    LaunchedEffect(pickReq) {
        if (pickReq != null) {
            val multiple = pickReq.prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE
            val mimes = pickReq.prompt.mimeTypes?.filter { it.isNotBlank() }?.toTypedArray() ?: emptyArray()
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (mimes.size == 1) mimes[0] else "*/*"
                if (mimes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimes)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
            }
            val ok = runCatching { picker.launch(Intent.createChooser(intent, null)) }.isSuccess
            if (!ok) { BrowserHub.filePick = null; pickReq.result.complete(pickReq.prompt.dismiss()) }
        }
    }

    BackHandler(enabled = tab?.canBack == true) { tab?.session?.goBack() }

    fun go(raw: String) {
        val t = raw.trim()
        val u = when {
            t.isEmpty() -> return
            t.startsWith("http://") || t.startsWith("https://") || t.startsWith("about:") -> t
            t.contains(".") && !t.contains(" ") -> "https://$t"
            else -> "https://duckduckgo.com/?q=" + Uri.encode(t)
        }
        tab?.session?.loadUri(u)
    }

    Column(Modifier.fillMaxSize()) {
        // شريط التبويبات (مضغوط)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BrowserHub.tabs.toList().forEach { t ->
                val sel = t.id == tab?.id
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (sel) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.height(34.dp).widthIn(max = 150.dp).clickable { BrowserHub.select(t) }
                ) {
                    Row(Modifier.padding(start = 10.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text((if (BrowserHub.isPinned(t.ctxId)) "📌" else "") + t.env, style = MaterialTheme.typography.labelSmall, color = Brand.GreenDark)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            t.title.ifBlank { Uri.parse(t.url).host ?: tr("تبويب") },
                            style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.weight(1f, fill = false)
                        )
                        if (BrowserHub.isPinned(t.ctxId)) Spacer(Modifier.width(10.dp)) else
                        Box(Modifier.size(30.dp).clickable { BrowserHub.close(t) }, contentAlignment = Alignment.Center) {
                            Text("✕", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            Box {
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(34.dp).clickable { plusMenu = true }) {
                    Box(contentAlignment = Alignment.Center) { Text("＋") }
                }
                DropdownMenu(expanded = plusMenu, onDismissRequest = { plusMenu = false }) {
                    DropdownMenuItem(text = { Text(tr("تبويب جديد (بيئة منفصلة)")) },
                        onClick = { plusMenu = false; BrowserHub.newTab(ctx, "https://github.com", null) })
                    if (tab != null) DropdownMenuItem(text = { Text(trf("تبويب بنفس بيئة %s", tab.env)) },
                        onClick = { plusMenu = false; BrowserHub.newTab(ctx, "https://github.com", tab.ctxId) })
                }
            }
        }
        if (tab != null) {
            // شريط العنوان: خانة عريضة واحدة وأزرار صغيرة
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                NavBtn(if (I18n.isRtl) "→" else "←", tab.canBack) { tab.session.goBack() }
                NavBtn(if (I18n.isRtl) "←" else "→", tab.canFwd) { tab.session.goForward() }
                NavBtn(if (tab.loading) "✕" else "⟳", true) { if (tab.loading) tab.session.stop() else tab.session.reload() }
                Surface(
                    shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.weight(1f).height(40.dp).padding(horizontal = 4.dp)
                ) {
                    BasicTextField(
                        value = addr, onValueChange = { addr = it }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { go(addr) }),
                        modifier = Modifier.padding(horizontal = 14.dp).wrapContentHeight(Alignment.CenterVertically)
                    )
                }
                NavBtn("🤖", true) { agentOpen = true }
                Box {
                    NavBtn("⋮", true) { moreMenu = true }
                    DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                        DropdownMenuItem(text = { Text(tr("فتح في المتصفح الخارجي")) }, onClick = {
                            moreMenu = false
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(tab.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        })
                        DropdownMenuItem(
                            text = { Text(if (BrowserHub.isPinned(tab.ctxId)) trf("إلغاء تثبيت البيئة %s", tab.env) else trf("📌 تثبيت البيئة %s", tab.env)) },
                            onClick = { moreMenu = false; BrowserHub.togglePin(tab) })
                        DropdownMenuItem(text = { Text(tr("حفظ البيئة باسم…")) }, onClick = { moreMenu = false; saveOpen = true })
                        DropdownMenuItem(text = { Text(trf("البيئات المحفوظة (%s)", BrowserHub.envs.size)) }, onClick = { moreMenu = false; listOpen = true })
                        DropdownMenuItem(text = { Text(trf("مسح بيانات البيئة %s", tab.env)) }, onClick = {
                            moreMenu = false; BrowserHub.clearEnvironment(tab)
                        })
                    }
                }
            }
            // الأزرار تتبع المستودع المفتوح حالياً في هذا التبويب، وإلا مستودعك المربوط.
            val rb = repoBaseOf(tab.url) ?: base
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    tr("الكود") to rb, "Actions" to "$rb/actions", tr("الإصدارات") to "$rb/releases",
                    "Issues" to "$rb/issues", "Pull requests" to "$rb/pulls", tr("الإعدادات") to "$rb/settings",
                    "Google" to "https://accounts.google.com", "AI Studio" to "@aistudio", "ApkOnline" to "@apkonline"
                ).forEach { (label, url) ->
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.clickable { when (url) { "@aistudio" -> BrowserHub.openAiStudio(ctx); "@apkonline" -> BrowserHub.openApkOnline(ctx); else -> tab.session.loadUri(url) } }) {
                        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            }
            if (tab.loading && tab.progress in 1..99) LinearProgressIndicator(progress = { tab.progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))

            // لا نربط العرض إلا بعد فتح الجلسة (تبويبات النوافذ المنبثقة يفتحها Gecko بنفسه).
            var ready by remember(tab.id) { mutableStateOf(tab.session.isOpen) }
            LaunchedEffect(tab.id) {
                while (!tab.session.isOpen) delay(50)
                tab.session.setActive(true)
                ready = true
            }
            if (ready) {
                key(tab.id) {
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        factory = { c -> GeckoView(c).also { it.setSession(tab.session) } },
                        onRelease = { it.releaseSession() }
                    )
                }
            } else {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }

    if (agentOpen && tab != null) {
        var question by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var answer by remember { mutableStateOf("") }
        var allTabs by remember { mutableStateOf(false) }
        fun ask(q: String) {
            busy = true; answer = ""
            scope.launch {
                val prompt = if (allTabs) BrowserHub.allTabsPrompt() else tab.snapshot()?.let { snapshotToPrompt(it) }
                answer = if (prompt == null) tr("تعذّر قراءة الصفحة. انتظر اكتمال التحميل ثم أعد المحاولة (الجسر لا يعمل على صفحات about: وبعض الصفحات المحمية).")
                else vm.askAboutPage(q, prompt).also {
                    BrowserHub.logAction(tab, if (allTabs) "all tabs" else "this page", q, it)
                }
                busy = false
            }
        }
        AlertDialog(
            onDismissRequest = { agentOpen = false },
            title = { Text(tr("اسأل الوكيل عن هذه الصفحة")) },
            confirmButton = { TextButton(onClick = { agentOpen = false }) { Text(tr("تم")) } },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        tr("عند الضغط يُرسَل نص الصفحة وأسماء حقولها وقيمها (بلا كلمات مرور ولا بيانات بطاقات) إلى الوكيل الرئيسي المحدد في «سير العمل». يقرأ فقط ولا ينفّذ شيئاً في الصفحة."),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = !allTabs, onClick = { allTabs = false }, label = { Text(tr("هذه الصفحة")) })
                        FilterChip(selected = allTabs, onClick = { allTabs = true }, label = { Text(trf("كل التبويبات (%s)", BrowserHub.tabs.size)) })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip(onClick = { ask(tr("لخّص هذه الصفحة بإيجاز.")) }, label = { Text(tr("لخّص")) }, enabled = !busy)
                        AssistChip(onClick = { ask(tr("ما حقول الإدخال في هذه الصفحة وما المطلوب تعبئته؟")) }, label = { Text(tr("اقرأ الحقول")) }, enabled = !busy)
                    }
                    OutlinedTextField(question, { question = it }, label = { Text(tr("سؤالك عن الصفحة")) }, modifier = Modifier.fillMaxWidth())
                    Button(enabled = !busy && question.isNotBlank(), onClick = { ask(question) }) { Text(tr("اسأل")) }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (answer.isNotEmpty()) Text(answer, style = MaterialTheme.typography.bodyMedium)
                    HorizontalDivider()
                    Text(trf("نشاط الوكيل في المتصفح: %s", BrowserHub.actions.size), style = MaterialTheme.typography.labelLarge)
                    OutlinedButton(onClick = {
                        val text = tr("هذا ما نفّذه الوكيل في المتصفح وحالة التبويبات. اقرأه وكمّل منه:") + "\n\n" + BrowserHub.exportText()
                        if (vm.leadId.isNotEmpty()) vm.pickChatAgent(vm.leadId)
                        if (vm.sendChat(text)) { /* مهمة بدأت */ }
                        agentOpen = false
                        onOpenChat()
                    }) { Text(tr("تصدير إلى الوكيل الرئيسي")) }
                }
            }
        )
    }

    if (saveOpen && tab != null) {
        var name by remember { mutableStateOf(BrowserHub.savedOf(tab.ctxId)?.name ?: tab.env) }
        AlertDialog(
            onDismissRequest = { saveOpen = false },
            title = { Text(tr("حفظ البيئة")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("تُحفظ البيئة باسم لاستعادتها في أي جلسة جديدة مع تسجيل الدخول وبياناتها، حتى لو أغلقت تبويباتها."), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(tr("اسم البيئة")) })
                }
            },
            confirmButton = { TextButton(onClick = { BrowserHub.saveEnv(tab, name); saveOpen = false }) { Text(tr("حفظ")) } },
            dismissButton = { TextButton(onClick = { saveOpen = false }) { Text(tr("إلغاء")) } }
        )
    }

    if (listOpen) {
        var confirm by remember { mutableStateOf<SavedEnv?>(null) }
        AlertDialog(
            onDismissRequest = { listOpen = false },
            title = { Text(tr("البيئات المحفوظة")) },
            confirmButton = { TextButton(onClick = { listOpen = false }) { Text(tr("تم")) } },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (BrowserHub.envs.isEmpty()) Text(tr("لا توجد بيئات محفوظة. احفظ بيئة أو ثبّتها من القائمة ⋮."), style = MaterialTheme.typography.bodySmall)
                    BrowserHub.envs.toList().forEach { e ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text((if (e.pinned) "📌 " else "") + e.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                Text(Uri.parse(e.url).host ?: e.url, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                            TextButton(onClick = { BrowserHub.openSaved(ctx, e); listOpen = false }) { Text(tr("فتح")) }
                            TextButton(onClick = { confirm = e }) { Text(tr("حذف")) }
                        }
                    }
                }
            }
        )
        confirm?.let { e ->
            AlertDialog(
                onDismissRequest = { confirm = null },
                title = { Text(trf("حذف البيئة %s؟", e.name)) },
                text = { Text(tr("يمسح بياناتها نهائياً (الكوكيز وتسجيل الدخول) ويغلق تبويباتها.")) },
                confirmButton = { TextButton(onClick = { BrowserHub.forget(e); confirm = null }) { Text(tr("حذف")) } },
                dismissButton = { TextButton(onClick = { confirm = null }) { Text(tr("إلغاء")) } }
            )
        }
    }
}

/** «محاكي» Google AI Studio: تبويب Gecko ببيئة مستقلة تسجّل الدخول فيها بحساب Google مرة واحدة وتبقى محفوظة. */
@Composable
fun AiStudioTab(vm: RelayViewModel, gh: GhConfig, onOpenChat: () -> Unit) {
    val ctx = LocalContext.current
    remember { BrowserHub.init(ctx, "https://github.com/${gh.owner}/${gh.repo}"); BrowserHub.openAiStudio(ctx); true }
    GeckoBrowserTab(vm, gh, onOpenChat)
}

/** شاشة ApkOnline: محاكي أندرويد مستضاف في بيئة متصفح مستقلة؛ ارفع ملف APK من هاتفك عبر زر الرفع فيه. */
@Composable
fun ApkOnlineTab(vm: RelayViewModel, gh: GhConfig, onOpenChat: () -> Unit) {
    val ctx = LocalContext.current
    remember { BrowserHub.init(ctx, "https://github.com/${gh.owner}/${gh.repo}"); BrowserHub.openApkOnline(ctx); true }
    GeckoBrowserTab(vm, gh, onOpenChat)
}
