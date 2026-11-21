package com.relay.agents

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

// ───────────────────────── تتبع سير العمل والأدوار ─────────────────────────

/** شاشة سير العمل: تقدّم الخطوات، من نفّذ كل خطوة، تحديد الوكيل الرئيسي والمنفّذين، وتثبيت وكيل لخطوة معيّنة. */
@Composable
fun WorkflowTab(vm: RelayViewModel, onOpenRun: () -> Unit) {
    val cp = vm.checkpoint
    val usable = vm.agents.filter { it.enabled }
    fun nameOf(id: String): String = vm.agents.firstOrNull { it.id == id }?.name ?: tr("تلقائي")
    var openStep by remember { mutableStateOf(-1) }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SoftCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (cp == null || cp.goal.isBlank()) {
                        Text(tr("لا توجد مهمة حالية"), style = MaterialTheme.typography.titleMedium)
                        Text(tr("ابدأ مهمة من شاشة التشغيل وسيظهر هنا تقدّمها خطوة بخطوة."), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = onOpenRun) { Text(tr("فتح التشغيل")) }
                    } else {
                        Text(cp.goal, style = MaterialTheme.typography.titleMedium, maxLines = 3)
                        val total = cp.steps.size
                        val done = cp.steps.count { it.status == StepStatus.DONE }
                        if (total > 0) {
                            LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusDot(if (vm.running) Brand.Green else if (cp.finished) Brand.GreenDark else Brand.Amber)
                            Text(
                                when {
                                    vm.running -> trf("قيد التنفيذ • %s من %s", done, total)
                                    cp.finalAnswer.isNotEmpty() -> tr("اكتملت المهمة")
                                    total == 0 -> tr("بانتظار التخطيط")
                                    else -> trf("متوقفة • %s من %s", done, total)
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (vm.running) {
                                OutlinedButton(onClick = { vm.stop() }) { Text(tr("إيقاف")) }
                            } else if (cp.finalAnswer.isEmpty()) {
                                Button(onClick = { vm.resume() }) { Text(tr("استئناف")) }
                            }
                            OutlinedButton(onClick = onOpenRun) { Text(tr("فتح التشغيل")) }
                        }
                    }
                }
            }
        }

        // الأدوار
        item {
            SoftCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("توزيع الأدوار"), style = MaterialTheme.typography.titleMedium)
                    Text(tr("الوكيل الرئيسي يخطط المهمة ويجمّع النتيجة النهائية. المنفّذون يعملون الخطوات. يسري التغيير فوراً حتى أثناء التشغيل، وإن تعطّل الوكيل المحدد يكمل غيره."),
                        style = MaterialTheme.typography.bodySmall)
                    Text(tr("الوكيل الرئيسي"), style = MaterialTheme.typography.labelLarge)
                    if (usable.isEmpty()) {
                        Text(tr("لا يوجد وكلاء مفعّلون. أضفهم من تبويب الوكلاء."), style = MaterialTheme.typography.bodySmall)
                    } else {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = vm.leadId.isEmpty(), onClick = { vm.setLead("") }, label = { Text(tr("تلقائي")) })
                            usable.forEach { a ->
                                FilterChip(selected = vm.leadId == a.id, onClick = { vm.setLead(a.id) },
                                    label = { Text("⭐ " + a.name, maxLines = 1) })
                            }
                        }
                        Text(tr("وكلاء تنفيذ الخطوات"), style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = vm.workerIds.isEmpty(), onClick = { vm.clearWorkers() }, label = { Text(tr("الكل")) })
                            usable.forEach { a ->
                                FilterChip(selected = a.id in vm.workerIds, onClick = { vm.toggleWorker(a.id) },
                                    label = { Text(a.name, maxLines = 1) })
                            }
                        }
                    }
                }
            }
        }

        // الخطوات
        if (cp != null && cp.steps.isNotEmpty()) {
            item { Text(tr("الخطوات"), style = MaterialTheme.typography.titleMedium) }
            itemsIndexed(cp.steps) { i, s ->
                val isCurrent = vm.running && i == cp.nextIndex
                var menu by remember { mutableStateOf(false) }
                SoftCard(Modifier.clickable { openStep = if (openStep == i) -1 else i }) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                when {
                                    s.status == StepStatus.DONE -> "✅"
                                    isCurrent -> "⏳"
                                    else -> "○"
                                }
                            )
                            Text("${i + 1}. ${s.title}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        }
                        if (s.status == StepStatus.DONE) {
                            Text(trf("نفّذها: %s", s.doneBy), style = MaterialTheme.typography.labelMedium, color = Brand.GreenDark)
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(tr("الوكيل:"), style = MaterialTheme.typography.labelMedium)
                                Box {
                                    TextButton(onClick = { menu = true }) {
                                        Text(if (s.assignee.isEmpty()) tr("تلقائي") else nameOf(s.assignee), maxLines = 1)
                                    }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(text = { Text(tr("تلقائي")) }, onClick = { vm.assignStep(i, ""); menu = false })
                                        usable.forEach { a ->
                                            DropdownMenuItem(text = { Text(a.name) }, onClick = { vm.assignStep(i, a.id); menu = false })
                                        }
                                    }
                                }
                            }
                        }
                        if (openStep == i && s.result.isNotBlank()) {
                            HorizontalDivider()
                            Text(s.result.take(3000), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        // حالة الوكلاء الحية
        if (vm.states.isNotEmpty()) {
            item { Text(tr("حالة الوكلاء"), style = MaterialTheme.typography.titleMedium) }
            items(vm.states, key = { "st" + it.agent.id }) { st ->
                val now = System.currentTimeMillis()
                val label = when {
                    st.disabled -> tr("معطّل")
                    st.cooldownUntil > now -> trf("راحة %s ث", (st.cooldownUntil - now) / 1000)
                    else -> tr("جاهز")
                }
                val color = when {
                    st.disabled -> Brand.Red
                    st.cooldownUntil > now -> Brand.Amber
                    else -> Brand.Green
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusDot(color)
                    Text(
                        (if (st.agent.id == vm.leadId) "⭐ " else "") + st.agent.name,
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1
                    )
                    Text(label + if (st.note.isNotEmpty()) " • ${st.note.take(30)}" else "", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        // السجل
        if (cp != null && cp.log.isNotEmpty()) {
            item { Text(tr("السجل"), style = MaterialTheme.typography.titleMedium) }
            items(cp.log.takeLast(40).reversed()) { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ───────────────────────── المتصفح الداخلي المرتبط بـ GitHub ─────────────────────────

private fun enqueueDownload(ctx: Context, url: String, ua: String?, cd: String?, mime: String?) {
    runCatching {
        val name = URLUtil.guessFileName(url, cd, mime)
        val req = DownloadManager.Request(Uri.parse(url))
            .setMimeType(mime)
            .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
            .addRequestHeader("User-Agent", ua ?: "")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
        Toast.makeText(ctx, trf("بدأ التنزيل: %s", name), Toast.LENGTH_LONG).show()
    }.onFailure { Toast.makeText(ctx, tr("تعذّر بدء التنزيل"), Toast.LENGTH_LONG).show() }
}

/** متصفح داخل التطبيق يفتح مستودع GitHub المربوط (الكود، Actions، الإصدارات…) مع بقاء تسجيل دخولك. زر الرجوع يعود صفحة قبل الخروج منه. */
@Composable
fun BrowserTab(gh: GhConfig) {
    val ctx = LocalContext.current
    val storage = remember { Storage(ctx) }
    val base = "https://github.com/${gh.owner}/${gh.repo}"
    var web by remember { mutableStateOf<WebView?>(null) }
    var canBack by remember { mutableStateOf(false) }
    var canFwd by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var addr by remember { mutableStateOf(storage.setting("browser_url", base)) }
    val start = remember { storage.setting("browser_url", base).ifBlank { base } }

    BackHandler(enabled = canBack) { web?.goBack() }
    DisposableEffect(Unit) {
        onDispose { web?.stopLoading(); web?.destroy(); web = null }
    }

    fun go(raw: String) {
        val t = raw.trim()
        val u = when {
            t.isEmpty() -> return
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(".") && !t.contains(" ") -> "https://$t"
            else -> "https://github.com/search?q=" + Uri.encode(t)
        }
        web?.loadUrl(u)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = canBack, onClick = { web?.goBack() }) { Text(if (I18n.isRtl) "→" else "←") }
            TextButton(enabled = canFwd, onClick = { web?.goForward() }) { Text(if (I18n.isRtl) "←" else "→") }
            TextButton(onClick = { web?.reload() }) { Text("⟳") }
            OutlinedTextField(
                addr, { addr = it }, singleLine = true, modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.large,
                textStyle = MaterialTheme.typography.bodySmall,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go(addr) })
            )
            TextButton(onClick = {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web?.url ?: base))) }
            }) { Text("↗") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                tr("الكود") to base,
                "Actions" to "$base/actions",
                tr("الإصدارات") to "$base/releases",
                "Issues" to "$base/issues",
                "Pull requests" to "$base/pulls",
                tr("الإعدادات") to "$base/settings",
                tr("مستودعاتي") to "https://github.com/${gh.owner}?tab=repositories"
            ).forEach { (label, url) ->
                AssistChip(onClick = { web?.loadUrl(url) }, label = { Text(label) })
            }
        }
        if (progress in 0.01f..0.99f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { c ->
                CookieManager.getInstance().setAcceptCookie(true)
                WebView(c).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val s = request.url.scheme ?: return true
                            return s != "http" && s != "https"
                        }

                        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                            canBack = view.canGoBack(); canFwd = view.canGoForward()
                            if (url != null) { addr = url; storage.saveSetting("browser_url", url) }
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            CookieManager.getInstance().flush()
                            canBack = view.canGoBack(); canFwd = view.canGoForward()
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress / 100f }
                    }
                    setDownloadListener { url, ua, cd, mime, _ -> enqueueDownload(c, url, ua, cd, mime) }
                    loadUrl(start)
                    web = this
                }
            }
        )
    }
}
