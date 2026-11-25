/*
 * `droid`, what a page the agent makes can do in Droid (see PageView.kt, which answers these calls):
 *   droid.send(text, data)  a message from the owner to the agent: the chat shows text, the agent also gets data (any
 *                           JSON). For a page the agent is waiting on (`droid show --wait`), its answer instead.
 *   droid.run(command)      runs a shell command in the agent's Linux: resolves to { out, code }. No agent turn.
 *   droid.dash(feel, words) how Dash looks and what he says: a feeling's name ("celebrate") or a face ({ smile: .5 }).
 *   droid.screen()          hands the owner the agent's hidden screen.
 *   droid.open(url)         opens a link or app (https:, tel:, geo:, intent:...).
 *   droid.close()           closes a page shown full screen or floating.
 * send, run, screen and open work from the owner's taps (a page can't act by itself when it loads); each returns a
 * promise.
 */
(() => {
  const waiting = new Map();
  let next = 0;
  const native = window.DroidNative;
  const post = (o) => native && native.postMessage(JSON.stringify(o));
  if (native) native.onmessage = (e) => {
    const m = JSON.parse(e.data);
    const w = waiting.get(m.id);
    if (!w) return;
    waiting.delete(m.id);
    if (m.error) w.reject(new Error(m.error)); else w.resolve(m.result);
  };
  // A control's words as the owner reads them (an icon's name isn't a word).
  const words = (el) => { const c = el.cloneNode(true); c.querySelectorAll(".icon").forEach((i) => i.remove()); return c.textContent.replace(/\s+/g, " ").trim().slice(0, 60); };
  // The button the owner just pressed, which shows what its call is doing (droid.css .droid-busy, .droid-done).
  let pressed = null, pressedAt = 0;
  const press = (e) => { const b = e.target.closest && e.target.closest("button, .button"); if (b) { pressed = b; pressedAt = Date.now(); } };
  addEventListener("pointerdown", press, true);
  addEventListener("click", press, true);
  const call = (op, args) => {
    const btn = ["send", "run", "open", "screen"].includes(op) && pressed && Date.now() - pressedAt < 1500 ? pressed : null;
    if (btn) btn.classList.add("droid-busy");
    // what the owner tapped, for the agent to hear about a tap that acted at once (no turn of its own)
    if (btn && (op === "run" || op === "open")) args = Object.assign({ label: words(btn) }, args);
    const p = new Promise((resolve, reject) => {
      if (!native) return reject(new Error("Not in Droid"));
      const id = ++next;
      waiting.set(id, { resolve, reject });
      post(Object.assign({ op, id }, args));
    });
    // a message keeps its button going until the agent's next version replaces the page; the rest show they're done
    if (btn && op !== "send") p.then((r) => {
      btn.classList.remove("droid-busy");
      if (r && typeof r.code === "number" && r.code !== 0) return;
      btn.classList.add("droid-done");
      // shown for a moment once the owner is looking: after a link opened another app, when they come back
      const fade = () => setTimeout(() => btn.classList.remove("droid-done"), 2500);
      if (op !== "open") return fade();
      const back = () => { if (document.visibilityState !== "visible") return; removeEventListener("visibilitychange", back); fade(); };
      addEventListener("visibilitychange", back);
      setTimeout(back, 1500);  // nothing opened over Droid (or it came back at once)
    }, () => btn.classList.remove("droid-busy"));
    else if (btn) p.catch(() => btn.classList.remove("droid-busy"));
    return p;
  };
  const json = (v) => v === undefined ? null : JSON.stringify(v);

  window.droid = {
    send: (text, data) => call("send", { text: String(text ?? ""), data: json(data) }),
    run: (command) => call("run", { command: String(command) }),
    dash: (feel, words) => call("dash", { feel: typeof feel === "object" && feel ? JSON.stringify(feel) : String(feel ?? ""), words: words == null ? "" : String(words) }),
    screen: () => call("screen", {}),
    open: (url) => call("open", { url: String(url) }),
    close: () => call("close", {}),
    get dark() { return document.documentElement.classList.contains("dark"); },

    /**
     * Where the owner is on the page, for its next version (Droid reloads it in place: PageView's reload): how far down
     * they are, and what they typed or picked themselves, each with what it said before they changed it.
     */
    /** The turn its message started is over with no new version (stopped, failed, answered in words): nothing's going. */
    _settle() { document.querySelectorAll(".droid-busy").forEach((b) => b.classList.remove("droid-busy")); },

    _keep() {
      const fields = [];
      fieldList().forEach((el, i) => {
        if (orig.has(el) && valueOf(el) !== orig.get(el)) fields.push([fieldKey(el, i), valueOf(el), orig.get(el)]);
      });
      const sc = document.scrollingElement;
      return { y: sc ? sc.scrollTop : 0, fields };
    },

    /**
     * Puts back what [_keep] kept: the scroll, and each thing the owner changed, unless the new version changed that
     * same field itself (the agent's edit wins: "make it shorter" isn't undone by the old draft).
     */
    _restore(k) {
      if (typeof k === "string") try { k = JSON.parse(k); } catch (_) { return; }
      if (!k) return;
      const els = new Map(fieldList().map((el, i) => [fieldKey(el, i), el]));
      for (const [key, value, before] of k.fields || []) {
        const el = els.get(key);
        if (!el || valueOf(el) !== before) continue;
        orig.set(el, before);
        setValue(el, value);
      }
      const scroll = () => { const sc = document.scrollingElement; if (sc) sc.scrollTop = k.y || 0; };
      requestAnimationFrame(() => requestAnimationFrame(scroll));
      setTimeout(scroll, 300);  // once pictures and fonts have their size
    },

    /**
     * Layout problems on the page, in words, for `droid look`: what goes past the screen's edge, is cut off, overlaps or
     * touches, is too small to tap or read, and icon names that aren't icons.
     */
    _lint() {
      const out = new Set(), small = [], vw = document.documentElement.clientWidth;
      const label = (el) => '"' + ((el.classList.contains("icon") ? el.textContent : words(el)) || el.getAttribute("aria-label") || el.getAttribute("alt") || el.tagName.toLowerCase()).slice(0, 40) + '"';
      const scrolls = (el) => { for (let p = el.parentElement; p; p = p.parentElement) { const o = getComputedStyle(p).overflowX; if (o === "auto" || o === "scroll") return true; } return false; };
      const all = Array.from(document.querySelectorAll("body *")).filter((el) => {
        if (el.closest("script, style, template, head, svg *")) return false;
        const r = el.getBoundingClientRect(), cs = getComputedStyle(el);
        return r.width > 0 && r.height > 0 && cs.visibility !== "hidden" && cs.opacity !== "0";
      });
      for (const el of all) {
        const r = el.getBoundingClientRect(), cs = getComputedStyle(el);
        if (r.right > vw + 1 && !scrolls(el) && cs.position !== "fixed") out.add(`${label(el)} goes past the right edge by ${Math.round(r.right - vw)}px`);
        if (r.left < -1 && !scrolls(el)) out.add(`${label(el)} goes past the left edge`);
        if (el.classList.contains("icon") && el.scrollWidth > el.clientWidth + 2) out.add(`"${el.textContent.trim()}" isn't an icon name (Material Symbols names: call, event, flight, restaurant…)`);
        else if (!el.classList.contains("icon") && !el.children.length && el.textContent.trim() && cs.overflow !== "visible" && (el.scrollWidth > el.clientWidth + 1 || el.scrollHeight > el.clientHeight + 2) && cs.textOverflow !== "ellipsis" && !cs.webkitLineClamp?.match(/\d/))
          out.add(`${label(el)} is cut off`);
        if (Array.from(el.childNodes).some((n) => n.nodeType === 3 && n.textContent.trim()) && parseFloat(cs.fontSize) < 12) small.push(`"${Array.from(el.childNodes).filter((n) => n.nodeType === 3).map((n) => n.textContent).join(" ").replace(/\s+/g, " ").trim().slice(0, 24)}"`);
      }
      const controls = all.filter((el) => el.matches("button, .button, a[href], input:not([type=hidden]), select, textarea, label.option, [contenteditable]") && !el.closest("[contenteditable] *"));
      for (const el of controls) {
        const r = el.getBoundingClientRect();
        if (r.height < 34 && !el.matches("a[href]:not(.button), input[type=checkbox], input[type=radio]")) out.add(`${label(el)} is small to tap (${Math.round(r.height)}px tall)`);
      }
      const sep = (a, b) => Math.max(b.left - a.right, a.left - b.right, b.top - a.bottom, a.top - b.bottom);
      for (let i = 0; i < controls.length; i++) for (let j = i + 1; j < controls.length; j++) {
        const a = controls[i], b = controls[j];
        if (a.contains(b) || b.contains(a) || (a.matches("[contenteditable]") && b.matches("[contenteditable]"))) continue;
        const d = sep(a.getBoundingClientRect(), b.getBoundingClientRect());
        if (d < 0) out.add(`${label(a)} and ${label(b)} overlap`); else if (d < 4) out.add(`${label(a)} and ${label(b)} touch`);
      }
      // words over words: text blocks whose boxes cross (absolute or negative-margin mistakes)
      const texts = all.filter((el) => Array.from(el.childNodes).some((n) => n.nodeType === 3 && n.textContent.trim()) && !el.closest("button, .button"));
      for (let i = 0; i < texts.length && i < 400; i++) for (let j = i + 1; j < texts.length && j < 400; j++) {
        const a = texts[i], b = texts[j];
        if (a.contains(b) || b.contains(a)) continue;
        const ra = a.getBoundingClientRect(), rb = b.getBoundingClientRect();
        const ox = Math.min(ra.right, rb.right) - Math.max(ra.left, rb.left), oy = Math.min(ra.bottom, rb.bottom) - Math.max(ra.top, rb.top);
        if (ox > 4 && oy > 4) out.add(`${label(a)} and ${label(b)} overlap`);
      }
      if (small.length) out.add(`text under 12px is small to read: ${[...new Set(small)].slice(0, 4).join(", ")}${small.length > 4 ? ` and ${small.length - 4} more` : ""}`);
      return Array.from(out).slice(0, 12);
    },

    /**
     * What's picked and typed on the page now, in words, for what the owner says or types while looking at it (Droid
     * sends it along): picked "Fern check-in", "how's the fern?" in Message. Empty if nothing is.
     */
    _state() {
      const words = (s) => (s || "").replace(/\s+/g, " ").trim();
      // what the owner sees it called: its label's words (but its own value), or its hint
      const name = (el) => {
        const l = el.closest("label") || (el.id && document.querySelector(`label[for="${CSS.escape(el.id)}"]`));
        const mine = l ? words(l.innerText) : "";
        const own = mine && el.value && !["checkbox", "radio"].includes(el.type) ? words(mine.replace(words(el.value), "")) : mine;
        return own || words(el.getAttribute("aria-label") || el.placeholder || "");
      };
      const out = [];
      for (const el of document.querySelectorAll("[contenteditable]")) { const v = words(el.innerText); if (v) out.push(`"${v.slice(0, 1500)}" in a draft`); }
      for (const el of document.querySelectorAll("input, textarea, select")) {
        const type = (el.type || "").toLowerCase();
        if (["hidden", "button", "submit", "reset", "file", "password"].includes(type)) continue;
        if (type === "checkbox" || type === "radio") { if (el.checked) out.push(`picked "${(name(el) || words(el.value)).slice(0, 120)}"`); continue; }
        const v = el.tagName === "SELECT" ? words(el.selectedOptions[0]?.text) : words(el.value);
        if (v) out.push(`"${v.slice(0, 1500)}" in ` + (name(el) ? `"${name(el).slice(0, 60)}"` : el.tagName === "SELECT" ? "a list" : "a text box"));
      }
      return out.join(", ").slice(0, 3000);
    },

    /**
     * Renders an answer: its Markdown (a line break is a line break, as in Droid's own answers) and HTML segments, then
     * runs its scripts in order, as a page load would. They run once the document is parsed, so each gets
     * DOMContentLoaded once, from here; and load from here only if the real one came first.
     */
    async _render(segs) {
      const root = document.getElementById("droid");
      root.innerHTML = segs.map(([html, t]) => html ? t : window.marked.parse(t, { gfm: true, breaks: true })).join("");
      tidy();
      rise();
      if (document.readyState === "loading") {
        await new Promise((parsed) => document.addEventListener("DOMContentLoaded", parsed, { once: true }));
      }
      const loaded = document.readyState === "complete";
      for (const old of Array.from(root.querySelectorAll("script"))) {
        const s = document.createElement("script");
        for (const a of Array.from(old.attributes)) s.setAttribute(a.name, a.value);
        if (old.src) await new Promise((done) => { s.onload = s.onerror = done; old.replaceWith(s); });
        else { s.textContent = old.textContent; old.replaceWith(s); }
      }
      document.dispatchEvent(new Event("DOMContentLoaded", { bubbles: true }));
      if (loaded) window.dispatchEvent(new Event("load"));
    },
  };

  // The page's own fields (and drafts), for keeping the owner's place across versions (_keep, _restore): what each
  // said before the owner first touched it.
  const fieldList = () => Array.from(document.querySelectorAll("input, textarea, select, [contenteditable]"))
    .filter((el) => !["hidden", "file", "password", "button", "submit", "reset"].includes((el.type || "").toLowerCase()));
  const valueOf = (el) => el.isContentEditable ? el.innerHTML : (el.type === "checkbox" || el.type === "radio") ? String(el.checked) : el.value;
  const setValue = (el, v) => { if (el.isContentEditable) el.innerHTML = v; else if (el.type === "checkbox" || el.type === "radio") el.checked = v === "true"; else el.value = v; };
  const fieldKey = (el, i) => el.id ? "#" + el.id : el.name ? `${el.tagName}[${el.name}]${el.type === "radio" || el.type === "checkbox" ? "=" + el.value : ""}` : `${el.tagName}:${i}`;
  const orig = new WeakMap();
  const touch = (e) => { const el = e.target && e.target.closest && e.target.closest("input, textarea, select, [contenteditable]"); if (el && !orig.has(el)) orig.set(el, valueOf(el)); };
  addEventListener("focusin", touch, true);
  addEventListener("pointerdown", touch, true);

  // Its height, for the chat to size it (in the chat it never scrolls itself).
  let last = -1;
  const report = () => {
    const h = Math.ceil(document.documentElement.getBoundingClientRect().height);
    if (h !== last) { last = h; post({ op: "height", h }); }
    footer();
  };
  // The next step, a <footer> of buttons at the end: on the owner's screen it stays at the bottom (droid.css), with room
  // kept for it so nothing hides under it; Droid is told whether there is one, and makes way for it.
  const onScreen = document.documentElement.classList.contains("screen");
  let had = null, room = "";
  const footer = () => {
    if (!document.body) return;
    // the page's last element but for scripts and styles after it
    const f = document.querySelector(":is(body, #droid) > footer:not(:has(~ :not(script, style, template)))");
    const has = !!(f && f.querySelector("button, .button, a, input, select, textarea, label"));
    const pad = onScreen && f ? (f.offsetHeight + 16) + "px" : "";
    if (pad !== room) { room = pad; document.body.style.paddingBottom = pad; }
    if (has !== had) { had = has; post({ op: "footer", on: has }); }
  };
  // Buttons written one after another (as `<button>A</button><button>B</button>`, or one per line) go in a row that
  // spaces them (droid.css .row), so they never touch, however the page lays them out around them.
  const isButton = (n) => n && n.nodeType === 1 && (n.tagName === "BUTTON" || n.classList.contains("button"));
  const blank = (n) => n && n.nodeType === 3 && !n.textContent.trim();
  const tidy = () => {
    for (const b of document.querySelectorAll("button, .button")) {
      const p = b.parentElement;
      if (!p || p.classList.contains("droid-buttons")) continue;
      const shown = getComputedStyle(p).display;
      if (shown.includes("flex") || shown.includes("grid")) continue;  // the page spaces them itself
      let before = b.previousSibling;
      while (blank(before)) before = before.previousSibling;
      if (isButton(before)) continue;  // not the first of its run
      const run = [b];
      for (let n = b.nextSibling; n; n = n.nextSibling) { if (blank(n)) continue; if (!isButton(n)) break; run.push(n); }
      if (run.length < 2) continue;
      const row = document.createElement("div");
      row.className = "row droid-buttons";
      p.insertBefore(row, b);
      run.forEach((x) => row.appendChild(x));
    }
  };
  // A page arriving rises into place, block by block (droid.css .droid-rise), once: its own blocks, not the footer.
  let rose = false;
  const rise = () => {
    if (rose) return;
    const root = document.getElementById("droid") || document.body;
    const blocks = root ? Array.from(root.children).filter((el) => !el.matches("script, style, template, footer, link, meta, title")) : [];
    if (!blocks.length) return;
    rose = true;
    blocks.slice(0, 6).forEach((el, i) => { el.style.setProperty("--droid-i", i); el.classList.add("droid-rise"); });
    setTimeout(() => blocks.forEach((el) => { el.classList.remove("droid-rise"); el.style.removeProperty("--droid-i"); }), 1000);
  };
  new ResizeObserver(report).observe(document.documentElement);
  addEventListener("load", report);
  addEventListener("DOMContentLoaded", () => {
    tidy();
    if (!document.getElementById("droid")) rise();
    let queued = false;  // (a burst of changes is tidied once a frame: tidy and footer force layout)
    new MutationObserver(() => { if (queued) return; queued = true; requestAnimationFrame(() => { queued = false; tidy(); footer(); }); }).observe(document.body, { childList: true, subtree: true });
  });
})();
