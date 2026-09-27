package com.hermes.agent.data.tools.browser

import kotlinx.serialization.json.JsonPrimitive

/**
 * JavaScript the browser tool runs in the page. Each returns a JSON string.
 * Elements the model may act on are tagged with a `data-hermes-ref` attribute,
 * so a click or type finds the same element the snapshot described.
 */
internal object BrowserScripts {

    const val REF_ATTR = "data-hermes-ref"
    const val MAX_ELEMENTS = 60

    /** Tags visible interactive elements and returns title, url, elements and the rendered HTML. */
    val SNAPSHOT = """
        (function () {
          var old = document.querySelectorAll('[$REF_ATTR]');
          for (var k = 0; k < old.length; k++) old[k].removeAttribute('$REF_ATTR');
          var sel = 'a[href],button,input,textarea,select,summary,[role=button],[role=link],[role=tab],[role=menuitem],[onclick]';
          var els = document.querySelectorAll(sel), out = [], n = 0;
          for (var i = 0; i < els.length && out.length < $MAX_ELEMENTS; i++) {
            var e = els[i];
            if (e.disabled) continue;
            var t = (e.getAttribute('type') || '').toLowerCase();
            if (t === 'hidden') continue;
            var r = e.getBoundingClientRect(), st = window.getComputedStyle(e);
            if (st.visibility === 'hidden' || st.display === 'none' || (r.width === 0 && r.height === 0)) continue;
            var tag = e.tagName.toLowerCase(), kind;
            if (tag === 'a') kind = 'link';
            else if (tag === 'textarea') kind = 'input';
            else if (tag === 'select') kind = 'select';
            else if (tag === 'input') kind = (t === 'checkbox' || t === 'radio') ? t :
              (['submit', 'button', 'image', 'reset'].indexOf(t) >= 0 ? 'button' : 'input');
            else kind = 'button';
            var label = (e.innerText || e.value || e.getAttribute('aria-label') || e.getAttribute('placeholder') ||
              e.getAttribute('title') || e.getAttribute('name') || e.getAttribute('alt') || '').replace(/\s+/g, ' ').trim();
            var ref = 'e' + (++n);
            e.setAttribute('$REF_ATTR', ref);
            out.push({ ref: ref, kind: kind, label: label.slice(0, 80), href: tag === 'a' ? e.href : '',
              placeholder: kind === 'input' ? (e.getAttribute('placeholder') || '') : '' });
          }
          return JSON.stringify({ title: document.title, url: location.href, elements: out,
            html: document.documentElement.outerHTML.slice(0, 1500000) });
        })()
    """.trimIndent()

    fun click(ref: String): String = """
        (function () {
          var e = document.querySelector('[$REF_ATTR="' + ${js(ref)} + '"]');
          if (!e) return JSON.stringify({ ok: false, error: 'missing' });
          e.scrollIntoView({ block: 'center' });
          if (e.tagName.toLowerCase() === 'a') e.removeAttribute('target');
          e.click();
          return JSON.stringify({ ok: true });
        })()
    """.trimIndent()

    /** Sets the value the way a user would (native setter plus input/change events), then optionally submits. */
    fun type(ref: String, text: String, submit: Boolean): String = """
        (function () {
          var e = document.querySelector('[$REF_ATTR="' + ${js(ref)} + '"]');
          if (!e) return JSON.stringify({ ok: false, error: 'missing' });
          e.scrollIntoView({ block: 'center' });
          e.focus();
          var proto = e.tagName.toLowerCase() === 'textarea' ? HTMLTextAreaElement.prototype :
            e.tagName.toLowerCase() === 'select' ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
          var setter = Object.getOwnPropertyDescriptor(proto, 'value');
          if (setter && setter.set) setter.set.call(e, ${js(text)}); else e.value = ${js(text)};
          e.dispatchEvent(new Event('input', { bubbles: true }));
          e.dispatchEvent(new Event('change', { bubbles: true }));
          if (${submit}) {
            var ev = { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true };
            e.dispatchEvent(new KeyboardEvent('keydown', ev));
            e.dispatchEvent(new KeyboardEvent('keyup', ev));
            if (e.form) { if (e.form.requestSubmit) e.form.requestSubmit(); else e.form.submit(); }
          }
          return JSON.stringify({ ok: true });
        })()
    """.trimIndent()

    const val READY_STATE = "JSON.stringify(document.readyState)"

    /** A JavaScript string literal for [value]; JSON string encoding is valid JavaScript. */
    private fun js(value: String): String = JsonPrimitive(value).toString()
}
