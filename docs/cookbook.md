---
hide:
  - navigation
  - toc
---

# Cookbook

Every Stove system, its Gradle dep, its configure block, its test DSL. Searchable. Copy-pastable. Powered by the same `setup.json` the wizard reads.

<div class="stove-tldr" markdown>
<span class="stove-tldr-title">Quick lookup</span>
Filter by name, family (database, mock, observability), or substring. Each card has copy buttons and a one-click jump into the wizard with that system preselected.
</div>

<div id="stove-cookbook" markdown="0">
  <noscript>
    <div class="admonition warning">
      <p class="admonition-title">JavaScript required</p>
      <p>The cookbook is a live, searchable view. Browse the static <a href="Components/">Systems reference</a> instead.</p>
    </div>
  </noscript>
</div>

<script>
(function () {
  const DATA_URL = "assets/data/setup.json";

  function dataUrl() {
    const meta = document.querySelector('meta[name="stove-home"]')?.content;
    if (meta) return meta.replace(/\/?$/, "/") + DATA_URL;
    const logo = document.querySelector("a.md-header__button.md-logo");
    if (logo?.href) return logo.href.replace(/\/?$/, "/") + DATA_URL;
    return "/" + DATA_URL;
  }

  function siteHomeUrl() {
    const meta = document.querySelector('meta[name="stove-home"]')?.content;
    if (meta) return meta.replace(/\/?$/, "/");
    const logo = document.querySelector("a.md-header__button.md-logo");
    if (logo?.href) return logo.href.replace(/\/?$/, "/");
    return "/";
  }

  function esc(s) {
    return String(s).replace(/[&<>]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;" }[c]));
  }

  function dedent(s) {
    if (s == null) return s;
    const lines = String(s).split("\n");
    const nonEmpty = lines.filter((l) => l.trim().length > 0);
    if (!nonEmpty.length) return s;
    const min = nonEmpty.reduce((m, l) => {
      const w = (l.match(/^[ \t]*/) || [""])[0].length;
      return Math.min(m, w);
    }, Infinity);
    if (!isFinite(min) || min === 0) return s;
    return lines.map((l) => l.slice(min)).join("\n");
  }
  function indent(s, pad) {
    return String(s).split("\n").map((l) => (l.length ? pad + l : l)).join("\n");
  }

  function flatten(data) {
    const items = [];
    Object.entries(data.systems || {}).forEach(([id, e]) =>
      items.push({ id, ...e, bucket: "system" }));
    Object.entries(data.observability || {}).forEach(([id, e]) =>
      items.push({ id, ...e, bucket: "obs" }));
    Object.entries(data.frameworks || {}).forEach(([id, e]) =>
      items.push({ id: "framework." + id, ...e, bucket: "framework", family: "framework" }));
    Object.entries(data.runtimes || {}).forEach(([id, e]) =>
      items.push({ id: "runtime." + id, ...e, bucket: "runtime", family: "runtime" }));
    return items;
  }

  function hl(code, lang) {
    if (code == null) return "";
    const target = (lang || "kotlin").toLowerCase();
    if (window.Prism && Prism.languages && Prism.languages[target]) {
      return Prism.highlight(String(code), Prism.languages[target], target);
    }
    return esc(code);
  }

  function copyToClipboard(text, btn) {
    navigator.clipboard.writeText(text).then(() => {
      const orig = btn.textContent;
      btn.textContent = "copied!";
      setTimeout(() => (btn.textContent = orig), 1200);
    });
  }

  function render(items, filter, family) {
    const q = filter.trim().toLowerCase();
    const filtered = items.filter((it) => {
      if (family && family !== "all" && (it.family || "") !== family) return false;
      if (!q) return true;
      const hay = (it.id + " " + (it.label || "") + " " + (it.family || "")).toLowerCase();
      return hay.includes(q);
    });

    if (!filtered.length) {
      return `<p class="sw-hint">No matches for "${esc(q)}".</p>`;
    }

    return filtered.map((it) => {
      const gradleBlock = (it.gradle || []).length
        ? `<pre><code class="language-kotlin">${hl((it.gradle || []).map((d) => `testImplementation("${d}")`).join("\n"), "kotlin")}</code></pre>`
        : `<p class="sw-hint">No extra dependency (built into core).</p>`;

      const configBlock = it.configure
        ? `<pre><code class="language-kotlin">${hl(dedent(it.configure), "kotlin")}</code></pre>`
        : it.runner
          ? `<pre><code class="language-kotlin">${hl(dedent(it.runner.replace(/\{pkg\}/g, "com.yourcompany.yourapp")), "kotlin")}</code></pre>`
          : `<p class="sw-hint">No configuration block.</p>`;

      const testBlock = it.testDsl
        ? `<pre><code class="language-kotlin">${hl("stove {\n" + indent(dedent(it.testDsl), "  ") + "\n}", "kotlin")}</code></pre>`
        : "";

      const pluginBlock = (it.pluginLine || it.pluginBlock)
        ? `<pre><code class="language-kotlin">${hl(["plugins {\n  " + (it.pluginLine || "") + "\n}", dedent(it.pluginBlock || "")].filter(Boolean).join("\n\n"), "kotlin")}</code></pre>`
        : "";

      const sysParam = it.id.startsWith("sys.")
        ? `data-sys="${it.id.slice(4)}"`
        : it.bucket === "framework"
          ? `data-fw="${it.id.replace(/^framework\./, "")}"`
          : "";

      const wizardLink = sysParam
        ? `<a class="open-in-wizard" ${sysParam}>open in wizard</a>`
        : "";

      const familyTag = it.family
        ? `<span class="cb-family-tag">${esc(it.family)}</span>`
        : "";

      return `
        <article class="cb-card">
          <header class="cb-card-head">
            <strong>${esc(it.label || it.id)}</strong>
            ${familyTag}
            <span class="cb-card-id"><code>${esc(it.id)}</code></span>
          </header>
          <div class="cb-card-body">
            ${gradleBlock ? `<div class="cb-section"><div class="cb-section-head"><span>Gradle</span></div>${gradleBlock}</div>` : ""}
            ${configBlock ? `<div class="cb-section"><div class="cb-section-head"><span>Configure</span></div>${configBlock}</div>` : ""}
            ${pluginBlock ? `<div class="cb-section"><div class="cb-section-head"><span>Gradle plugin</span></div>${pluginBlock}</div>` : ""}
            ${testBlock ? `<div class="cb-section"><div class="cb-section-head"><span>Test DSL</span></div>${testBlock}</div>` : ""}
          </div>
          <footer class="cb-card-foot">
            ${wizardLink}
          </footer>
        </article>`;
    }).join("");
  }

  function mount() {
    const host = document.getElementById("stove-cookbook");
    if (!host) return;
    if (host.dataset.cbMounted === "1") return;
    host.dataset.cbMounted = "1";

    host.innerHTML = `
      <div class="cb-controls">
        <input id="cb-filter" type="search" placeholder="filter by name (postgres, kafka, mock, dashboard, ...)" />
        <div class="cb-family-chips" id="cb-families"></div>
      </div>
      <div id="cb-list" class="cb-list"></div>
    `;

    fetch(dataUrl(), { cache: "no-cache" })
      .then((r) => r.json())
      .then((data) => {
        const items = flatten(data);
        const families = Array.from(new Set(items.map((i) => i.family).filter(Boolean))).sort();
        const chipBar = document.getElementById("cb-families");
        let activeFamily = "all";
        let filterQ = "";

        const renderAll = () => {
          document.getElementById("cb-list").innerHTML = render(items, filterQ, activeFamily);
          // Decorate any open-in-wizard anchors freshly inserted
          if (window.__stoveDecorate) window.__stoveDecorate();
        };

        const renderChips = () => {
          chipBar.innerHTML = ["all", ...families].map((f) =>
            `<button class="cb-chip${activeFamily === f ? ' active' : ''}" data-f="${esc(f)}">${esc(f)}</button>`
          ).join("");
          chipBar.querySelectorAll("button").forEach((b) => {
            b.addEventListener("click", () => {
              activeFamily = b.dataset.f;
              renderChips();
              renderAll();
            });
          });
        };

        document.getElementById("cb-filter").addEventListener("input", (e) => {
          filterQ = e.target.value;
          renderAll();
        });

        renderChips();
        renderAll();
      })
      .catch((err) => {
        host.innerHTML = `<div class="sw-error">Failed to load cookbook data: ${esc(err.message)}</div>`;
      });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", mount);
  } else {
    mount();
  }
  if (window.document$ && typeof window.document$.subscribe === "function") {
    window.document$.subscribe(() => {
      document.querySelectorAll("#stove-cookbook").forEach((el) => (el.dataset.cbMounted = ""));
      mount();
    });
  }
})();
</script>
