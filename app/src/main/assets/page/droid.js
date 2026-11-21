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
  const call = (op, args) => new Promise((resolve, reject) => {
    if (!native) return reject(new Error("Not in Droid"));
    const id = ++next;
    waiting.set(id, { resolve, reject });
    post(Object.assign({ op, id }, args));
  });
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
  new ResizeObserver(report).observe(document.documentElement);
  addEventListener("load", report);
  addEventListener("DOMContentLoaded", () => new MutationObserver(footer).observe(document.body, { childList: true, subtree: true }));
})();
