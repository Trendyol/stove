/* KotlinPlayground bootstrap + init.
 * Loads kotlin-playground from unpkg, manually inits `.kotlin-runnable` blocks,
 * re-runs on Material instant-nav swaps.
 */
(function () {
  var SELECTOR = ".kotlin-runnable";
  var SCRIPT_URL = "https://unpkg.com/kotlin-playground@1";

  function loadScript(cb) {
    if (window.KotlinPlayground) return cb();
    var existing = document.querySelector('script[data-kp-loader="1"]');
    if (existing) {
      existing.addEventListener("load", function () { waitForApi(cb); });
      return;
    }
    var s = document.createElement("script");
    s.src = SCRIPT_URL;
    s.async = true;
    s.dataset.kpLoader = "1";
    s.addEventListener("load", function () { waitForApi(cb); });
    s.addEventListener("error", function () {
      console.error("[kotlin-playground] script failed to load:", SCRIPT_URL);
    });
    document.head.appendChild(s);
  }

  function waitForApi(cb, tries) {
    tries = tries || 0;
    if (typeof window.KotlinPlayground === "function") return cb();
    if (tries > 80) {
      console.error("[kotlin-playground] global KotlinPlayground never appeared");
      return;
    }
    setTimeout(function () { waitForApi(cb, tries + 1); }, 100);
  }

  function init() {
    if (!document.querySelector(SELECTOR)) return;
    loadScript(function () {
      try {
        window.KotlinPlayground(SELECTOR);
      } catch (e) {
        console.error("[kotlin-playground] init threw:", e);
      }
    });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }

  if (window.document$ && typeof window.document$.subscribe === "function") {
    window.document$.subscribe(function () { init(); });
  }
})();
