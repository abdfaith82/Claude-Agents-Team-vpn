// جسر القراءة فقط: يرسل للتطبيق نص الصفحة وحقول الإدخال عند الطلب. لا يقرأ كلمات المرور ولا يعدّل الصفحة.
(() => {
  if (window.top !== window) return;
  let port;
  try { port = browser.runtime.connectNative("browser"); } catch (e) { return; }

  const visible = el => !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length);
  const SENSITIVE = /(cc-|password|one-time|otp)/i;

  function labelOf(el) {
    let t = el.getAttribute("aria-label") || "";
    if (!t && el.labels && el.labels.length) t = el.labels[0].innerText;
    if (!t && el.id) {
      try {
        const l = document.querySelector('label[for="' + CSS.escape(el.id) + '"]');
        if (l) t = l.innerText;
      } catch (e) {}
    }
    return (t || el.placeholder || el.title || "").trim().slice(0, 80);
  }

  function snapshot() {
    const fields = [];
    const nodes = document.querySelectorAll("input,textarea,select,button,[role=button],[contenteditable=true]");
    for (const el of nodes) {
      if (fields.length >= 80) break;
      const tag = el.tagName.toLowerCase();
      const type = (el.getAttribute("type") || "").toLowerCase();
      if (type === "hidden" || !visible(el)) continue;
      const secret = type === "password" || SENSITIVE.test(el.getAttribute("autocomplete") || "");
      const f = { tag: tag, type: type, name: el.name || "", id: el.id || "", label: labelOf(el), required: !!el.required };
      if (tag === "button" || el.getAttribute("role") === "button") {
        f.text = (el.innerText || el.value || "").trim().slice(0, 60);
      } else if (tag === "select") {
        f.options = Array.from(el.options).slice(0, 15).map(o => o.text.trim().slice(0, 40));
        f.value = secret ? "" : (el.value || "").slice(0, 100);
      } else if (type === "checkbox" || type === "radio") {
        f.checked = !!el.checked;
      } else {
        f.secret = secret;
        f.value = secret ? "" : (el.value || el.innerText || "").slice(0, 200);
      }
      fields.push(f);
    }
    return {
      url: location.href,
      title: document.title,
      hasPassword: !!document.querySelector("input[type=password]"),
      fields: fields,
      text: ((document.body && document.body.innerText) || "").slice(0, 6000)
    };
  }

  port.onMessage.addListener(m => {
    if (!m || !m.id) return;
    try {
      if (m.cmd === "snapshot") port.postMessage({ id: m.id, ok: true, data: snapshot() });
      else port.postMessage({ id: m.id, ok: false, error: "unknown command" });
    } catch (e) {
      port.postMessage({ id: m.id, ok: false, error: String(e) });
    }
  });
})();
