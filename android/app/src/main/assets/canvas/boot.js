/*
 * Canvas harness bootstrap. Kept as an external file (not inline) so the
 * strict CSP can stay `script-src 'self' 'unsafe-eval'` — no 'unsafe-inline'.
 * Loaded after the vendored libs; exposes window.__renderCanvas for the host.
 */
(function () {
  "use strict";

  // The monochrome ramp exposed to widget code as `MONO` (ordered most- to
  // least-prominent). Kept in sync with the CSS tokens per theme.
  var RAMP = {
    dark:  ["#f5f5f5", "#c7c7c7", "#9a9a9a", "#6e6e6e", "#3a3a3a"],
    light: ["#111111", "#444444", "#777777", "#aaaaaa", "#d4d4d4"]
  };

  var root = document.getElementById("root");
  var reactRoot = null;

  // The one, one-way, number-only channel to the host: report content height
  // so the native card can size itself. It conveys a single integer and reads
  // nothing from the app. Absent in a plain browser preview — guarded.
  function reportHeight() {
    try {
      // Measure the content (body), not documentElement — the latter floors at
      // the WebView's own viewport height, which would keep the card from
      // shrinking. body wraps #root, which is the actual widget.
      var h = Math.ceil(
        Math.max(document.body.scrollHeight, document.body.offsetHeight)
      );
      if (window.__canvasHost && typeof window.__canvasHost.postHeight === "function") {
        window.__canvasHost.postHeight(h);
      }
    } catch (e) { /* never let measurement throw */ }
  }

  var ro = null;
  function watchHeight() {
    reportHeight();
    // Re-measure as charts animate in and interactivity changes layout.
    try {
      if (!ro && typeof ResizeObserver !== "undefined") {
        ro = new ResizeObserver(function () { reportHeight(); });
        ro.observe(document.documentElement);
      }
    } catch (e) {}
    // Belt-and-suspenders ticks for late async layout (chart mount, fonts).
    [60, 200, 500, 1000].forEach(function (ms) { setTimeout(reportHeight, ms); });
  }

  function showError(title, detail) {
    try {
      if (reactRoot) { reactRoot.unmount(); reactRoot = null; }
    } catch (e) {}
    root.innerHTML =
      '<div class="canvas-error"><b>' + title + '</b>' +
      (detail ? '<pre></pre>' : '') + '</div>';
    if (detail) {
      root.querySelector("pre").textContent = String(detail);
    }
    watchHeight();
  }

  // Host entry point. Called by the native side after page load with the
  // JSX source and theme. JSX arrives as a value (JSON-encoded on the native
  // side), never concatenated into the HTML, so it can't break out of markup.
  window.__renderCanvas = function (jsxSource, theme) {
    try {
      theme = (theme === "light") ? "light" : "dark";
      document.documentElement.setAttribute("data-theme", theme);
      var MONO = RAMP[theme];

      if (typeof Babel === "undefined") { showError("Renderer not ready"); return; }
      if (!jsxSource || !String(jsxSource).trim()) { showError("Empty widget"); return; }

      // Transpile JSX -> JS (classic runtime: calls React.createElement, and
      // React is in scope below).
      var compiled;
      try {
        compiled = Babel.transform(String(jsxSource), {
          presets: [["react", { runtime: "classic" }]]
        }).code;
      } catch (e) {
        showError("Couldn't compile this widget", e && e.message ? e.message : e);
        return;
      }

      // Evaluate in a tightly-scoped function. Only React, ReactDOM, Recharts
      // and MONO are handed in — no access to app internals. Must define a
      // top-level `App`. 'unsafe-eval' in the CSP permits the Function ctor.
      var factory;
      try {
        factory = new Function(
          "React", "ReactDOM", "Recharts", "MONO",
          compiled + "\n;return (typeof App !== 'undefined') ? App : null;"
        );
      } catch (e) {
        showError("Couldn't load this widget", e && e.message ? e.message : e);
        return;
      }

      var App;
      try {
        App = factory(React, ReactDOM, Recharts, MONO);
      } catch (e) {
        showError("This widget failed to run", e && e.message ? e.message : e);
        return;
      }
      if (!App) { showError("Widget defined no App component"); return; }

      try {
        if (!reactRoot) reactRoot = ReactDOM.createRoot(root);
        reactRoot.render(React.createElement(App));
      } catch (e) {
        showError("This widget failed to render", e && e.message ? e.message : e);
        return;
      }
      watchHeight();
    } catch (e) {
      showError("Unexpected renderer error", e && e.message ? e.message : e);
    }
  };

  // Let the host know the harness is parsed and ready (height of the empty
  // root), so the card can show a sensible initial size before render.
  watchHeight();
})();
