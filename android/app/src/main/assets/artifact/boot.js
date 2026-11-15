/*
 * Artifact reader bootstrap. External (not inline) so the strict CSP can stay
 * `script-src 'self' 'unsafe-eval'` — no 'unsafe-inline'. Loaded after the
 * vendored libs; exposes window.__renderArtifact(markdown, theme) for the host.
 *
 * Pipeline: protect math -> marked(md) -> DOMPurify -> inject -> restore math
 * (KaTeX) -> render mermaid -> report height.
 */
(function () {
  "use strict";

  var root = document.getElementById("root");

  // ---- height bridge (one-way, number only) --------------------------------
  function reportHeight() {
    try {
      var h = Math.ceil(Math.max(document.body.scrollHeight, document.body.offsetHeight));
      if (window.__artifactHost && typeof window.__artifactHost.postHeight === "function") {
        window.__artifactHost.postHeight(h);
      }
    } catch (e) { /* never throw from measurement */ }
  }
  var _ro = null;
  function watchHeight() {
    reportHeight();
    try {
      if (!_ro && typeof ResizeObserver !== "undefined") {
        _ro = new ResizeObserver(function () { reportHeight(); });
        _ro.observe(document.documentElement);
      }
    } catch (e) {}
    [80, 300, 800, 1600].forEach(function (ms) { setTimeout(reportHeight, ms); });
  }

  function showError(title, detail) {
    root.innerHTML = '<div class="artifact-error"><b></b></div>';
    root.querySelector("b").textContent = title;
    if (detail) {
      var p = document.createElement("div");
      p.style.marginTop = "6px";
      p.textContent = String(detail);
      root.querySelector(".artifact-error").appendChild(p);
    }
    watchHeight();
  }

  // ---- math protection ------------------------------------------------------
  // Pull $$…$$, \[…\], \(…\) and $…$ out of the markdown BEFORE marked runs, so
  // marked can't turn `a_b` / `*x*` inside math into <em>/<strong>. Each span is
  // replaced by an alphanumeric sentinel that survives marked + sanitization,
  // then swapped back as rendered KaTeX.
  //
  // Research documents are full of currency ("OCF $1.52B on $4.9B revenue"),
  // and models almost never escape it, so inline `$…$` is recognised only when
  // it really looks like math (pandoc's rules plus a few currency guards):
  //   - never inside fenced code or `inline code` (shell `$HOME`, `$1`…);
  //   - the opening `$` is followed by a non-space, the closing `$` is preceded
  //     by a non-space and NOT followed by a digit ("$5 and $9" isn't math);
  //   - the span doesn't start like an amount ("$3.2B", "$450") unless it has
  //     TeX in it, isn't prose ("$100B opportunity by 2030 …"), doesn't cross
  //     a table cell `|` or a `**` bold marker.
  var SENTINEL_RE = /KXMATHSENTINEL(\d+)ENDKX/;
  var SENTINEL_RE_G = /KXMATHSENTINEL(\d+)ENDKX/g;
  var CODE_TOKEN_RE_G = /KXCODESPAN(\d+)ENDKX/g;
  var TEXISH = /[\\^_{}=<>]/;

  function looksLikeMath(tex, inTableRow) {
    if (!tex.trim()) return false;
    if (inTableRow && tex.indexOf("|") >= 0) return false;
    if (tex.indexOf("**") >= 0) return false;
    if (TEXISH.test(tex)) return true;
    // An amount: "$3.2B", "$450", "$1,200M", "$2.3 billion" …
    if (/^[-+~]?\d[\d.,]*/.test(tex)) return false;
    // Prose between two stray dollar signs: several plain words, no TeX.
    var words = tex.match(/[A-Za-z]{3,}/g) || [];
    if (words.length >= 3) return false;
    return true;
  }

  // Inline $…$ on one line of prose (code spans already masked out).
  function inlineDollars(line, stash) {
    var inTable = /^\s*\|/.test(line);
    var out = "", i = 0, n = line.length;
    while (i < n) {
      var c = line.charAt(i);
      if (c === "\\" && i + 1 < n) { out += c + line.charAt(i + 1); i += 2; continue; }
      if (c === "$") {
        if (line.charAt(i + 1) === "$") { out += "$$"; i += 2; continue; }  // stray $$
        var next = line.charAt(i + 1);
        if (next && !/\s/.test(next)) {
          var j = i + 1, close = -1;
          while (j < n) {
            var d = line.charAt(j);
            if (d === "\\") { j += 2; continue; }
            if (d === "$") {
              if (line.charAt(j + 1) === "$") break;
              if (!/\s/.test(line.charAt(j - 1)) && !/\d/.test(line.charAt(j + 1) || "")) { close = j; break; }
            }
            j++;
          }
          if (close > 0) {
            var tex = line.slice(i + 1, close);
            if (looksLikeMath(tex, inTable)) { out += stash(tex, false); i = close + 1; continue; }
          }
        }
      }
      out += c; i++;
    }
    return out;
  }

  // Math in a run of prose lines (no fenced code inside).
  function protectProse(text, stash) {
    // Mask inline code spans so nothing inside them is touched.
    var spans = [];
    text = text.replace(/(`+)([\s\S]*?[^`])\1(?!`)/g, function (m) {
      spans.push(m);
      return "KXCODESPAN" + (spans.length - 1) + "ENDKX";
    });
    text = text.replace(/\$\$([\s\S]+?)\$\$/g, function (_, t) { return stash(t, true); });
    // \[…\] only when it holds math — "\[3\]" is an escaped citation marker.
    text = text.replace(/\\\[([\s\S]+?)\\\]/g, function (m, t) {
      return /^[\s\d,]*$/.test(t) ? m : stash(t, true);
    });
    text = text.replace(/\\\(([\s\S]+?)\\\)/g, function (_, t) { return stash(t, false); });
    text = text.split("\n").map(function (l) { return inlineDollars(l, stash); }).join("\n");
    return text.replace(CODE_TOKEN_RE_G, function (_, k) { return spans[+k]; });
  }

  function protectMath(md) {
    var store = [];
    function stash(tex, display) {
      var i = store.length;
      store.push({ tex: tex, display: display });
      return "KXMATHSENTINEL" + i + "ENDKX";
    }
    // Walk the document, leaving fenced code blocks verbatim (an unclosed
    // fence runs to the end, as marked treats it).
    var lines = md.split("\n"), out = [], prose = [], fence = null;
    function flush() {
      if (prose.length) { out.push(protectProse(prose.join("\n"), stash)); prose = []; }
    }
    for (var k = 0; k < lines.length; k++) {
      var line = lines[k];
      var m = /^ {0,3}(`{3,}|~{3,})/.exec(line);
      if (fence) {
        out.push(line);
        if (m && m[1].charAt(0) === fence.charAt(0) && m[1].length >= fence.length &&
            /^ {0,3}[`~]+\s*$/.test(line)) fence = null;
      } else if (m) {
        flush(); fence = m[1]; out.push(line);
      } else {
        prose.push(line);
      }
    }
    flush();
    return { md: out.join("\n"), store: store };
  }

  function restoreMath(container, store) {
    if (!store.length || typeof katex === "undefined") return;
    var walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT, null);
    var nodes = [], n;
    while ((n = walker.nextNode())) { if (SENTINEL_RE.test(n.nodeValue)) nodes.push(n); }
    nodes.forEach(function (node) {
      var parts = node.nodeValue.split(SENTINEL_RE_G);
      var frag = document.createDocumentFragment();
      for (var i = 0; i < parts.length; i++) {
        if (i % 2 === 0) {
          if (parts[i]) frag.appendChild(document.createTextNode(parts[i]));
        } else {
          var item = store[parseInt(parts[i], 10)];
          var span = document.createElement("span");
          try {
            span.innerHTML = katex.renderToString(item.tex, {
              displayMode: item.display, throwOnError: true,
            });
          } catch (e) {
            // Not valid TeX after all (most often stray dollar signs): show
            // the original text plainly rather than a red error.
            span.textContent = (item.display ? "$$" : "$") + item.tex + (item.display ? "$$" : "$");
          }
          frag.appendChild(span);
        }
      }
      node.parentNode.replaceChild(frag, node);
    });
  }

  // ---- mermaid --------------------------------------------------------------
  function monoTheme(theme) {
    return theme === "light"
      ? { primaryColor: "#f5f5f5", primaryTextColor: "#1a1a1a", primaryBorderColor: "#cfcfcf",
          lineColor: "#6b6b6b", secondaryColor: "#ffffff", tertiaryColor: "#ffffff",
          background: "#ffffff", mainBkg: "#f5f5f5", textColor: "#1a1a1a",
          edgeLabelBackground: "#ffffff", clusterBkg: "#f6f6f7", fontSize: "15px" }
      : { primaryColor: "#1f1f22", primaryTextColor: "#ededed", primaryBorderColor: "#3a3a3a",
          lineColor: "#8a8a8f", secondaryColor: "#161618", tertiaryColor: "#161618",
          background: "#161618", mainBkg: "#1f1f22", textColor: "#ededed",
          edgeLabelBackground: "#161618", clusterBkg: "#1f1f22", fontSize: "15px" };
  }

  // Phones are narrow: a left-to-right flowchart of more than a few nodes gets
  // scaled down until its labels are unreadable. Lay it out top-to-bottom
  // instead (subgraph-local `direction` lines are left alone).
  var NARROW_PX = 640;
  var MIN_SCALE = 0.75;   // below this the text is too small to read

  function phoneLayout(src) {
    if ((root.clientWidth || window.innerWidth) >= NARROW_PX) return src;
    var lines = src.split("\n");
    for (var i = 0; i < lines.length; i++) {
      var t = lines[i].trim();
      if (!t || t.indexOf("%%") === 0) continue;   // blank / comment / directive
      var m = /^(flowchart|graph)\s+(LR|RL)\b(.*)$/.exec(t);
      if (m) lines[i] = lines[i].replace(t, m[1] + " " + (m[2] === "LR" ? "TD" : "BT") + m[3]);
      break;   // only the header line decides the layout
    }
    return lines.join("\n");
  }

  // After layout: anything still too wide keeps a readable size and scrolls.
  function fitDiagrams() {
    root.querySelectorAll(".mermaid").forEach(function (box) {
      var svg = box.querySelector("svg");
      if (!svg || !svg.viewBox || !svg.viewBox.baseVal || !svg.viewBox.baseVal.width) return;
      var natural = svg.viewBox.baseVal.width;
      var avail = box.clientWidth || root.clientWidth;
      if (avail / natural >= MIN_SCALE) return;
      box.classList.add("wide");
      svg.style.maxWidth = "none";
      svg.style.width = Math.round(natural * MIN_SCALE) + "px";
      svg.style.height = "auto";
      if (!(box.nextSibling && box.nextSibling.className === "mermaid-hint")) {
        var hint = document.createElement("div");
        hint.className = "mermaid-hint";
        hint.textContent = "scroll sideways →";
        box.parentNode.insertBefore(hint, box.nextSibling);
      }
    });
    watchHeight();
  }

  function renderMermaid(theme) {
    var codes = root.querySelectorAll("code.language-mermaid, code.lang-mermaid");
    if (!codes.length || typeof mermaid === "undefined") return;
    codes.forEach(function (code) {
      var pre = code.parentNode;
      var div = document.createElement("div");
      div.className = "mermaid";
      // Keep a change highlight the code block carried.
      ["chg-add", "chg-mod"].forEach(function (c) {
        if (pre && pre.classList && pre.classList.contains(c)) div.classList.add(c);
      });
      div.textContent = phoneLayout(code.textContent);
      if (pre) pre.__mermaidBox = div;
      if (pre && pre.parentNode) pre.parentNode.replaceChild(div, pre);
    });
    try {
      mermaid.initialize({
        startOnLoad: false, securityLevel: "strict",
        theme: "base", themeVariables: monoTheme(theme),
      });
      var p = mermaid.run({ querySelector: ".mermaid", suppressErrors: true });
      if (p && p.then) p.then(fitDiagrams, fitDiagrams);
    } catch (e) { /* leave the raw text if mermaid fails */ }
  }

  // ---- tables: wrap for horizontal scroll on narrow screens -----------------
  function wrapTables() {
    root.querySelectorAll("table").forEach(function (t) {
      if (t.parentNode && t.parentNode.classList && t.parentNode.classList.contains("table-wrap")) return;
      var w = document.createElement("div");
      w.className = "table-wrap";
      t.parentNode.insertBefore(w, t);
      w.appendChild(t);
    });
  }

  // ---- sources (structured citations) ---------------------------------------
  // The host passes the document's sources (cited first). Inline [n] markers
  // become superscript links to a numbered Sources section appended at the end;
  // tapping a source link leaves the page, which the host intercepts and opens
  // in the user's browser. Titles/snippets are web text: textContent only.
  function parseSources(json) {
    try {
      var arr = JSON.parse(json || "[]");
      if (!Array.isArray(arr)) return [];
      return arr.filter(function (s) {
        return s && typeof s.url === "string" && /^https?:\/\//i.test(s.url) && isFinite(+s.n);
      });
    } catch (e) { return []; }
  }

  // [3] or [3, 7] — but not a markdown link label "[3](…)".
  var CITE_RE = /\[(\d{1,3}(?:\s*,\s*\d{1,3})*)\](?!\()/g;

  function insideSkipped(node, container) {
    for (var p = node.parentNode; p && p !== container; p = p.parentNode) {
      var tag = p.nodeName;
      if (tag === "CODE" || tag === "PRE" || tag === "A") return true;
      if (p.classList && (p.classList.contains("katex") || p.classList.contains("mermaid"))) return true;
    }
    return false;
  }

  function linkCitations(container, known) {
    var walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT, null);
    var nodes = [], n;
    while ((n = walker.nextNode())) {
      CITE_RE.lastIndex = 0;
      if (CITE_RE.test(n.nodeValue) && !insideSkipped(n, container)) nodes.push(n);
    }
    nodes.forEach(function (node) {
      var text = node.nodeValue, frag = document.createDocumentFragment(), last = 0, m;
      CITE_RE.lastIndex = 0;
      while ((m = CITE_RE.exec(text))) {
        var nums = m[1].split(/\s*,\s*/).map(Number).filter(function (x) { return known[x]; });
        if (!nums.length) continue;   // a bracketed number that isn't a source: leave it
        frag.appendChild(document.createTextNode(text.slice(last, m.index)));
        var sup = document.createElement("sup");
        sup.className = "cite";
        nums.forEach(function (x, i) {
          if (i) sup.appendChild(document.createTextNode(","));
          var a = document.createElement("a");
          a.href = "#src-" + x;
          a.textContent = String(x);
          sup.appendChild(a);
        });
        frag.appendChild(sup);
        last = m.index + m[0].length;
      }
      if (last === 0) return;
      frag.appendChild(document.createTextNode(text.slice(last)));
      node.parentNode.replaceChild(frag, node);
    });
  }

  function sourceItem(s) {
    var li = document.createElement("li");
    li.id = "src-" + s.n;
    li.value = +s.n;
    var a = document.createElement("a");
    a.href = s.url;
    a.textContent = s.title || s.domain || s.url;
    li.appendChild(a);
    var meta = document.createElement("div");
    meta.className = "src-meta";
    // Pages the agent opened itself in the sandbox are marked as such.
    meta.textContent = (s.domain || "") + (s.via === "browse" ? " · browsed" : "") + "  ↗";
    li.appendChild(meta);
    if (s.snippet) {
      var sn = document.createElement("div");
      sn.className = "src-snippet";
      sn.textContent = s.snippet;
      li.appendChild(sn);
    }
    return li;
  }

  function appendSources(container, sources) {
    var cited = sources.filter(function (s) { return s.cited; });
    var rest = sources.filter(function (s) { return !s.cited; });
    var sec = document.createElement("section");
    sec.className = "sources";
    var h = document.createElement("h2");
    h.textContent = "Sources";
    sec.appendChild(h);
    if (cited.length) {
      var ol = document.createElement("ol");
      cited.forEach(function (s) { ol.appendChild(sourceItem(s)); });
      sec.appendChild(ol);
    }
    if (rest.length) {
      var det = document.createElement("details");
      if (!cited.length) det.open = true;
      var sum = document.createElement("summary");
      sum.textContent = (cited.length ? "Also consulted" : "Consulted") + " (" + rest.length + ")";
      det.appendChild(sum);
      var ol2 = document.createElement("ol");
      rest.forEach(function (s) { ol2.appendChild(sourceItem(s)); });
      det.appendChild(ol2);
      sec.appendChild(det);
    }
    container.appendChild(sec);
  }

  // ---- change highlights: what the latest revision changed ------------------
  // The host passes the previous version's markdown. Both versions are
  // rendered (off-screen) through the same pipeline and diffed block by block:
  // each list item, table row and other top-level block is a unit. Units new
  // in this version are marked NEW; units that replaced a similar one are
  // marked EDITED (inserted words highlighted, tap the tag for the old
  // wording); removed units leave a collapsed "Removed" marker. A floating
  // pill steps through the changes. Citation markers are ignored when
  // comparing, so a revision that only renumbered [n]s doesn't light up.
  var CITES_G = /\[\d{1,3}(?:\s*,\s*\d{1,3})*\]/g;
  var CHG = { items: [], idx: -1, shown: false };
  var LAST = null;   // last render args, for toggling highlights without the host

  function normText(s) { return String(s || "").replace(/\s+/g, " ").trim(); }
  function unitKey(el) { return normText((el.textContent || "").replace(CITES_G, "")); }

  function unitsOf(container) {
    var out = [];
    Array.prototype.forEach.call(container.children, function (el) {
      var tag = el.nodeName;
      if (tag === "UL" || tag === "OL") {
        Array.prototype.forEach.call(el.children, function (li) { if (li.nodeName === "LI") out.push(li); });
      } else if (tag === "TABLE" || (el.classList && el.classList.contains("table-wrap"))) {
        el.querySelectorAll("tr").forEach(function (tr) { out.push(tr); });
      } else if (!(el.classList && (el.classList.contains("sources") || el.id === "chg-nav"))) {
        out.push(el);
      }
    });
    return out;
  }

  // The markdown rendered into an inert document (nothing loads or runs);
  // math is put back as its TeX source so keys compare like with like.
  function renderDetached(md) {
    var p = protectMath(String(md || ""));
    var html = marked.parse(p.md, { gfm: true, breaks: false });
    if (typeof DOMPurify !== "undefined") html = DOMPurify.sanitize(html, { USE_PROFILES: { html: true } });
    html = html.replace(SENTINEL_RE_G, function (_, i) {
      return String(p.store[+i].tex).replace(/&/g, "&amp;").replace(/</g, "&lt;");
    });
    var doc = document.implementation.createHTMLDocument("");
    var div = doc.createElement("div");
    div.innerHTML = html;
    return div;
  }

  // Longest common subsequence of two arrays -> matched index pairs.
  function lcsPairs(a, b) {
    var n = a.length, m = b.length, i, j;
    if (n * m > 4000000) return null;
    var dp = new Array(n + 1);
    for (i = 0; i <= n; i++) dp[i] = new Uint16Array(m + 1);
    for (i = n - 1; i >= 0; i--) {
      for (j = m - 1; j >= 0; j--) {
        dp[i][j] = a[i] === b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
      }
    }
    var pairs = [];
    i = 0; j = 0;
    while (i < n && j < m) {
      if (a[i] === b[j]) { pairs.push([i, j]); i++; j++; }
      else if (dp[i + 1][j] >= dp[i][j + 1]) i++;
      else j++;
    }
    return pairs;
  }

  function similarity(a, b) {
    var wa = {}, wb = {}, inter = 0, total = 0, w;
    a.toLowerCase().split(" ").forEach(function (x) { if (x) wa[x] = 1; });
    b.toLowerCase().split(" ").forEach(function (x) { if (x) wb[x] = 1; });
    for (w in wa) { total++; if (wb[w]) inter++; }
    for (w in wb) { if (!wa[w]) total++; }
    return total ? inter / total : 0;
  }

  function noWordMarks(node, el) {
    for (var p = node.parentNode; p && p !== el; p = p.parentNode) {
      if (p.nodeName === "SUP") return true;
      if (p.classList && (p.classList.contains("katex") || p.classList.contains("chg-tag"))) return true;
    }
    return false;
  }

  // Word identity for diffing: case, edge punctuation and citation markers
  // don't count ("inventory," == "Inventory"; "[5]." is no word at all).
  function wordKey(w) {
    return w.replace(CITES_G, "").toLowerCase().replace(/^[^\w$€£¥%]+|[^\w%]+$/g, "");
  }

  // Highlight the words this version inserted into an edited block.
  function markWords(el, oldText) {
    var oldW = normText(String(oldText).replace(CITES_G, "")).split(" ").map(wordKey).filter(Boolean);
    var nodes = [], n, walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, null);
    while ((n = walker.nextNode())) { if (!noWordMarks(n, el)) nodes.push(n); }
    var parts = nodes.map(function (nd) { return nd.nodeValue.split(/(\s+)/); });
    var toks = [];
    parts.forEach(function (ps, ni) {
      ps.forEach(function (t, ti) {
        var key = t && !/^\s+$/.test(t) ? wordKey(t) : "";
        if (key) toks.push({ k: ni + ":" + ti, w: key });
      });
    });
    if (!toks.length || toks.length > 900 || oldW.length > 900) return;
    var pairs = lcsPairs(oldW, toks.map(function (t) { return t.w; }));
    if (!pairs) return;
    var kept = {};
    pairs.forEach(function (p) { kept[p[1]] = true; });
    var ins = {}, count = 0;
    toks.forEach(function (t, k) { if (!kept[k]) { ins[t.k] = true; count++; } });
    if (!count || count / toks.length > 0.6) return;   // mostly rewritten: the block bar says enough
    nodes.forEach(function (nd, ni) {
      var ps = parts[ni];
      if (!ps.some(function (t, ti) { return ins[ni + ":" + ti]; })) return;
      var frag = document.createDocumentFragment(), mark = null;
      ps.forEach(function (t, ti) {
        if (!t) return;
        var isIns = ins[ni + ":" + ti];
        var bridge = /^\s+$/.test(t) && mark && ins[ni + ":" + (ti + 1)];
        if (isIns || bridge) {
          if (!mark) { mark = document.createElement("mark"); mark.className = "chg-ins"; }
          mark.appendChild(document.createTextNode(t));
        } else {
          if (mark) { frag.appendChild(mark); mark = null; }
          frag.appendChild(document.createTextNode(t));
        }
      });
      if (mark) frag.appendChild(mark);
      nd.parentNode.replaceChild(frag, nd);
    });
  }

  function tagHost(el) {
    // Where a NEW / EDITED tag goes: inside the block, or its last cell.
    if (el.nodeName === "TR") return el.lastElementChild;
    if (el.nodeName === "PRE" || el.nodeName === "HR" || el.nodeName === "TABLE") return null;
    return el;
  }

  function addTag(el, label, oldText) {
    var host = tagHost(el);
    if (!host) return;
    var tag = document.createElement(oldText != null ? "button" : "span");
    tag.className = "chg-tag";
    tag.textContent = label;
    host.appendChild(tag);
    if (oldText == null) return;
    tag.setAttribute("aria-label", "Show the previous wording");
    var was = document.createElement("div");
    was.className = "chg-was";
    was.hidden = true;
    var lab = document.createElement("span");
    lab.className = "chg-was-label";
    lab.textContent = "Before";
    was.appendChild(lab);
    was.appendChild(document.createTextNode(" " + normText(oldText)));
    host.appendChild(was);
    tag.addEventListener("click", function (e) {
      e.preventDefault(); e.stopPropagation();
      was.hidden = !was.hidden;
      tag.classList.toggle("open", !was.hidden);
    });
  }

  function removedMarker(anchor, oldEl) {
    var tag = anchor ? anchor.nodeName : "DIV";
    var box, holder;
    if (tag === "LI") { box = document.createElement("li"); holder = box; }
    else if (tag === "TR") {
      box = document.createElement("tr");
      holder = document.createElement("td");
      holder.colSpan = Math.max(1, anchor.children.length);
      box.appendChild(holder);
    } else { box = document.createElement("div"); holder = box; }
    box.className = "chg-removed";
    var det = document.createElement("details");
    var sum = document.createElement("summary");
    var text = normText(oldEl.textContent);
    sum.textContent = "Removed · " + (text.length > 90 ? text.slice(0, 88) + "…" : text || "(empty)");
    det.appendChild(sum);
    if (text.length > 90) {
      var full = document.createElement("div");
      full.className = "chg-removed-text";
      full.textContent = text;
      det.appendChild(full);
    }
    holder.appendChild(det);
    return box;
  }

  function markChanges(container, markdown, prevMarkdown) {
    var oldU = unitsOf(renderDetached(prevMarkdown));
    var newU = unitsOf(renderDetached(markdown));
    var live = unitsOf(container);
    if (newU.length !== live.length) return [];   // structure mismatch: show nothing rather than mislead
    var ok = oldU.map(unitKey), nk = newU.map(unitKey);
    var pairs = lcsPairs(ok, nk);
    if (!pairs) return [];
    var items = [];
    pairs = [[-1, -1]].concat(pairs, [[ok.length, nk.length]]);
    for (var g = 1; g < pairs.length; g++) {
      var p0 = pairs[g - 1], p1 = pairs[g];
      var removed = [], added = [], r;
      for (r = p0[0] + 1; r < p1[0]; r++) removed.push(r);
      for (r = p0[1] + 1; r < p1[1]; r++) added.push(r);
      if (!removed.length && !added.length) continue;
      var used = {};
      added.forEach(function (ai, k) {
        var el = live[ai];
        var ri = removed[k];
        if (ri != null && similarity(ok[ri], nk[ai]) >= 0.35) {
          used[ri] = true;
          el.classList.add("chg-mod");
          if (el.nodeName !== "PRE") markWords(el, oldU[ri].textContent);
          addTag(el, "EDITED", oldU[ri].textContent);
        } else {
          el.classList.add("chg-add");
          addTag(el, "NEW", null);
        }
        items.push(el);
      });
      var anchorNext = p1[1] < live.length ? live[p1[1]] : null;
      var anchorPrev = !anchorNext && p0[1] >= 0 ? live[p0[1]] : null;
      var lastInserted = anchorPrev;
      removed.forEach(function (ri) {
        if (used[ri]) return;
        var marker = removedMarker(anchorNext || anchorPrev, oldU[ri]);
        if (anchorNext) anchorNext.parentNode.insertBefore(marker, anchorNext);
        else if (lastInserted) { lastInserted.parentNode.insertBefore(marker, lastInserted.nextSibling); lastInserted = marker; }
        else container.appendChild(marker);
        items.push(marker);
      });
    }
    return items;
  }

  function showNav(items) {
    var old = document.getElementById("chg-nav");
    if (old) old.parentNode.removeChild(old);
    CHG.items = items; CHG.idx = -1; CHG.shown = items.length > 0;
    document.body.classList.toggle("has-chg", items.length > 0);
    if (!items.length) return;
    var nav = document.createElement("div");
    nav.id = "chg-nav";
    nav.setAttribute("role", "toolbar");
    function btn(label, act, aria) {
      var b = document.createElement("button");
      b.textContent = label; b.setAttribute("data-a", act); b.setAttribute("aria-label", aria);
      nav.appendChild(b);
    }
    btn("‹", "prev", "Previous change");
    var count = document.createElement("span");
    count.className = "chg-count";
    count.textContent = items.length + (items.length === 1 ? " change" : " changes");
    nav.appendChild(count);
    btn("›", "next", "Next change");
    btn("Done", "done", "Hide change highlights");
    nav.addEventListener("click", function (e) {
      var b = e.target.closest ? e.target.closest("button") : null;
      if (!b) return;
      var a = b.getAttribute("data-a");
      if (a === "done") { window.__setChanges(false); return; }
      gotoChange(a === "prev" ? -1 : 1);
    });
    document.body.appendChild(nav);
  }

  function gotoChange(dir) {
    var n = CHG.items.length;
    if (!n) return;
    CHG.idx = CHG.idx < 0 ? (dir > 0 ? 0 : n - 1) : (CHG.idx + dir + n) % n;
    var el = CHG.items[CHG.idx];
    el.scrollIntoView({ behavior: "smooth", block: "center" });
    // Smooth scrolling can stall (backgrounded/older WebViews): make sure we
    // actually arrive.
    setTimeout(function () {
      var r = el.getBoundingClientRect();
      if (r.bottom < 0 || r.top > window.innerHeight) el.scrollIntoView({ block: "center" });
    }, 700);
    el.classList.remove("flash"); void el.offsetWidth; el.classList.add("flash");
    var c = document.querySelector("#chg-nav .chg-count");
    if (c) c.textContent = (CHG.idx + 1) + " / " + n;
  }

  /** Host + in-page toggle: re-render with or without highlights, keeping the
   *  reading position. Returns the new state. */
  window.__setChanges = function (on) {
    if (!LAST || !LAST.prev) return false;
    var y = window.scrollY, h = Math.max(1, document.body.scrollHeight);
    render(LAST.markdown, LAST.theme, LAST.mode, LAST.sourcesJson, on ? LAST.prev : "", false);
    setTimeout(function () { window.scrollTo(0, Math.round(y / h * document.body.scrollHeight)); }, 60);
    return !!(on && CHG.shown);
  };
  window.__changesShown = function () { return !!CHG.shown; };

  /** The rendered body without any change markup — for share/export. */
  window.__cleanHtml = function () {
    var c = root.cloneNode(true);
    c.querySelectorAll(".chg-removed, .chg-tag, .chg-was, #chg-nav").forEach(function (x) { x.parentNode.removeChild(x); });
    c.querySelectorAll("mark.chg-ins").forEach(function (m) {
      while (m.firstChild) m.parentNode.insertBefore(m.firstChild, m);
      m.parentNode.removeChild(m);
    });
    c.querySelectorAll(".chg-add, .chg-mod, .flash").forEach(function (x) {
      x.classList.remove("chg-add", "chg-mod", "flash");
    });
    return c.innerHTML;
  };

  /** The document as simple, email-safe HTML: only the tags a rich-text email
   *  body keeps (headings, paragraphs, bold/italic, links, lists, monospace).
   *  Tables become labeled rows, math its TeX source, citations plain [n],
   *  numbered lists keep their numbers, and diagrams point to the full page
   *  sent along as an attachment. */
  window.__emailHtml = function () {
    var c = document.createElement("div");
    c.innerHTML = window.__cleanHtml();
    function esc(s) { return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;"); }
    function swap(el, html) {
      var t = document.createElement("div");
      t.innerHTML = html;
      var frag = document.createDocumentFragment();
      while (t.firstChild) frag.appendChild(t.firstChild);
      el.parentNode.replaceChild(frag, el);
    }
    c.querySelectorAll(".mermaid-hint").forEach(function (x) { x.parentNode.removeChild(x); });
    c.querySelectorAll(".mermaid").forEach(function (m) {
      swap(m, "<p><i>[Diagram — see the attached full version]</i></p>");
    });
    c.querySelectorAll(".katex").forEach(function (k) {
      var ann = k.querySelector('annotation[encoding="application/x-tex"]');
      var tex = ann ? ann.textContent : k.textContent;
      var disp = k.closest(".katex-display");
      if (disp) swap(disp, "<p><tt>" + esc(tex) + "</tt></p>");
      else swap(k, "<tt>" + esc(tex) + "</tt>");
    });
    c.querySelectorAll("sup.cite").forEach(function (s) { swap(s, "[" + esc(s.textContent) + "]"); });
    // Sources: "5. Title — domain" on one line; snippets are reader-only.
    c.querySelectorAll(".src-snippet").forEach(function (x) { x.parentNode.removeChild(x); });
    c.querySelectorAll(".src-meta").forEach(function (m) {
      swap(m, " <i>— " + esc(m.textContent.replace(/\s*↗\s*$/, "").trim()) + "</i>");
    });
    c.querySelectorAll("table").forEach(function (t) {
      var rows = Array.prototype.slice.call(t.rows);
      var head = rows.length && rows[0].querySelector("th")
        ? Array.prototype.map.call(rows[0].cells, function (x) { return x.textContent.trim(); }) : null;
      var out = "";
      rows.slice(head ? 1 : 0).forEach(function (r) {
        var cells = Array.prototype.slice.call(r.cells);
        if (!cells.length) return;
        if (cells.length === 2) {
          out += "<p><b>" + cells[0].innerHTML + "</b> — " + cells[1].innerHTML + "</p>";
        } else {
          out += "<p><b>" + cells[0].innerHTML + "</b><br>" + cells.slice(1).map(function (x, i) {
            var h = head && head[i + 1] ? "<i>" + esc(head[i + 1]) + ":</i> " : "";
            return h + x.innerHTML;
          }).join(" · ") + "</p>";
        }
      });
      swap(t.closest(".table-wrap") || t, out);
    });
    c.querySelectorAll("pre").forEach(function (p) {
      swap(p, "<p><tt>" + esc(p.textContent).replace(/\n/g, "<br>") + "</tt></p>");
    });
    c.querySelectorAll("code").forEach(function (k) { swap(k, "<tt>" + esc(k.textContent) + "</tt>"); });
    // Rich-text email bodies drop list numbers: write them in.
    c.querySelectorAll("ol").forEach(function (ol) {
      var n = parseInt(ol.getAttribute("start") || "1", 10);
      var html = "";
      Array.prototype.forEach.call(ol.children, function (li) {
        var v = li.getAttribute("value");
        if (v) n = parseInt(v, 10);
        html += "<p>" + n + ". " + li.innerHTML + "</p>";
        n++;
      });
      swap(ol, html);
    });
    c.querySelectorAll("details").forEach(function (d) {
      var sum = d.querySelector("summary");
      var html = sum ? "<p><b>" + esc(sum.textContent) + "</b></p>" : "";
      if (sum) sum.parentNode.removeChild(sum);
      swap(d, html + d.innerHTML);
    });
    c.querySelectorAll("svg, img, button, style, script").forEach(function (x) { x.parentNode && x.parentNode.removeChild(x); });
    c.querySelectorAll("*").forEach(function (x) {
      ["class", "style", "id", "aria-hidden", "role"].forEach(function (a) { x.removeAttribute(a); });
    });
    return c.innerHTML;
  };

  // In-page jumps ([n] -> its source) scroll + highlight; http(s) links fall
  // through to a navigation the host turns into "open in browser".
  document.addEventListener("click", function (e) {
    var a = e.target && e.target.closest ? e.target.closest("a") : null;
    if (!a) return;
    var href = a.getAttribute("href") || "";
    if (href.charAt(0) !== "#") return;
    e.preventDefault();
    var t = document.getElementById(href.slice(1));
    if (!t) return;
    t.scrollIntoView({ behavior: "smooth", block: "center" });
    t.classList.remove("flash");
    void t.offsetWidth;   // restart the highlight animation
    t.classList.add("flash");
  }, true);

  // ---- host entry point -----------------------------------------------------
  // mode: "reader" (full-screen, padded) | anything else (inline card).
  // sourcesJson: the document's citations (JSON array), or "[]".
  // prevMarkdown: the previous version, to highlight what changed ("" = none).
  // focusChanges: scroll to the first change once rendered.
  // showChanges: false = keep the previous version on hand (for __setChanges)
  // but start with the highlights off.
  window.__renderArtifact = function (markdown, theme, mode, sourcesJson, prevMarkdown, focusChanges, showChanges) {
    prevMarkdown = prevMarkdown || "";
    if (prevMarkdown && showChanges === false) {
      LAST = { markdown: markdown, prev: prevMarkdown };
      render(markdown, theme, mode, sourcesJson, "", false);
    } else {
      render(markdown, theme, mode, sourcesJson, prevMarkdown, !!focusChanges);
    }
  };

  function render(markdown, theme, mode, sourcesJson, prevMarkdown, focusChanges) {
    // Remember the previous version across a highlights-off re-render of the
    // same document, so they can be switched back on.
    var keepPrev = LAST && LAST.markdown === markdown ? LAST.prev : "";
    LAST = { markdown: markdown, theme: theme, mode: mode, sourcesJson: sourcesJson,
             prev: prevMarkdown || keepPrev };
    var oldNav = document.getElementById("chg-nav");
    if (oldNav) oldNav.parentNode.removeChild(oldNav);
    CHG.items = []; CHG.idx = -1; CHG.shown = false;
    try {
      theme = (theme === "light") ? "light" : "dark";
      document.documentElement.setAttribute("data-theme", theme);
      document.body.classList.toggle("reader", mode === "reader");
      if (typeof marked === "undefined") { showError("Renderer not ready"); return; }
      var src = String(markdown || "");
      if (!src.trim()) { showError("Empty document"); return; }

      var p = protectMath(src);
      var html;
      try {
        html = marked.parse(p.md, { gfm: true, breaks: false });
      } catch (e) {
        showError("Couldn't render this document", e && e.message ? e.message : e);
        return;
      }
      var clean = (typeof DOMPurify !== "undefined")
        ? DOMPurify.sanitize(html, { USE_PROFILES: { html: true } })
        : html;
      root.innerHTML = clean;

      restoreMath(root, p.store);
      wrapTables();
      var sources = parseSources(sourcesJson);
      var known = {};
      sources.forEach(function (s) { known[+s.n] = true; });
      if (sources.length) linkCitations(root, known);
      // Change highlights go on before the Sources section exists and before
      // mermaid swaps code blocks for diagrams (which carry the marks over).
      var items = [];
      if (prevMarkdown) {
        try { items = markChanges(root, src, prevMarkdown); } catch (e) { items = []; }
      }
      if (sources.length) appendSources(root, sources);
      renderMermaid(theme);
      // A changed diagram's code block was swapped for its mermaid box.
      items = items.map(function (el) { return el.__mermaidBox || el; });
      showNav(items);
      if (focusChanges && items.length) setTimeout(function () { gotoChange(1); }, 450);
      watchHeight();
    } catch (e) {
      showError("Unexpected renderer error", e && e.message ? e.message : e);
    }
  }

  // Signal readiness (empty-root height) before the host injects content.
  watchHeight();
})();
