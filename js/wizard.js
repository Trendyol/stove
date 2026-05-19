/* Stove Setup Wizard
 * Vanilla + Alpine.js. Mounts into #stove-wizard.
 * Data source: docs/assets/data/setup.json (single source of truth).
 */
(function () {
  const DATA_URL = "assets/data/setup.json";

  /** Substitute {key} placeholders in a template string. */
  function fill(tpl, vars) {
    if (tpl == null) return tpl;
    return String(tpl).replace(/\{(\w+)\}/g, (_, k) => (k in vars ? vars[k] : `{${k}}`));
  }

  /** Strip the common leading whitespace from every non-blank line. */
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

  /** Prefix every line of `s` with `pad` spaces. */
  function indent(s, pad) {
    return String(s).split("\n").map((l) => (l.length ? pad + l : l)).join("\n");
  }

  /** Resolve setup.json relative to site root (works on any sub-page). */
  function dataUrl() {
    const meta = document.querySelector('meta[name="stove-home"]')?.content;
    if (meta) return meta.replace(/\/?$/, "/") + DATA_URL;
    const logo = document.querySelector("a.md-header__button.md-logo");
    if (logo?.href) return logo.href.replace(/\/?$/, "/") + DATA_URL;
    return "/" + DATA_URL;
  }

  // ---------------- Wizard state model ----------------
  function wizardApp() {
    return {
      ready: false,
      loadError: null,
      data: null,
      step: 0,
      steps: [
        "Runtime",
        "Framework / language",
        "Test framework",
        "Systems",
        "Mocks",
        "Observability",
        "Output",
      ],
      state: {
        runtime: "jvm",
        framework: "spring-boot",
        language: "go",
        test: "kotest",
        systems: ["sys.http", "sys.postgresql", "sys.kafka"],
        mocks: ["sys.wiremock"],
        obs: ["obs.reporting", "obs.tracing", "obs.dashboard"],
        bridge: true,
        keyed: false,
        pkg: "com.yourcompany.yourapp",
        appClass: "MyApp",
        stoveVersion: "$stoveVersion",
      },

      async init() {
        try {
          const res = await fetch(dataUrl(), { cache: "no-cache" });
          if (!res.ok) throw new Error("HTTP " + res.status);
          this.data = await res.json();
        } catch (err) {
          this.loadError = err.message;
          // Try absolute /assets/data/setup.json as final fallback
          try {
            const res2 = await fetch("/" + DATA_URL);
            if (res2.ok) {
              this.data = await res2.json();
              this.loadError = null;
            }
          } catch (_) { /* keep error */ }
        }
        this.loadFromUrl();
        this.$watch("state", () => this.syncUrl(), { deep: true });
        this.$watch("step", () => this.syncUrl());
        // Canonicalize URL immediately so shareUrl() reflects defaults,
        // not the bare /wizard/ a first-time visitor lands on.
        this.syncUrl();
        this.ready = true;
      },

      // -------- URL sync --------
      loadFromUrl() {
        const p = new URLSearchParams(location.search);
        const s = this.state;
        if (p.get("rt")) s.runtime = p.get("rt");
        if (p.get("fw")) s.framework = p.get("fw");
        if (p.get("lang")) s.language = p.get("lang");
        if (p.get("test")) s.test = p.get("test");
        if (p.get("sys")) s.systems = p.get("sys").split(",").filter(Boolean);
        if (p.get("mk")) s.mocks = p.get("mk").split(",").filter(Boolean);
        if (p.get("obs")) s.obs = p.get("obs").split(",").filter(Boolean);
        if (p.get("br")) s.bridge = p.get("br") === "1";
        if (p.get("kd")) s.keyed = p.get("kd") === "1";
        if (p.get("pkg")) s.pkg = p.get("pkg");
        if (p.get("cls")) s.appClass = p.get("cls");
        if (p.get("step")) this.step = Math.min(parseInt(p.get("step"), 10) || 0, this.steps.length - 1);

        // Deep-link preset: ?preset=postgres,kafka or ?add=sys.redis
        const preset = p.get("preset");
        if (preset) {
          const ids = preset.split(",").map((x) => x.trim()).filter(Boolean);
          ids.forEach((id) => {
            const fullId = id.startsWith("sys.") ? id : "sys." + id;
            if (this.data?.systems?.[fullId] && !s.systems.includes(fullId) && !s.mocks.includes(fullId)) {
              const fam = this.data.systems[fullId].family;
              (fam === "mock" ? s.mocks : s.systems).push(fullId);
            }
          });
          // Jump to systems step so user sees what was added
          this.step = 3;
        }
      },
      syncUrl() {
        const s = this.state;
        const p = new URLSearchParams({
          rt: s.runtime, fw: s.framework, lang: s.language, test: s.test,
          sys: s.systems.join(","), mk: s.mocks.join(","), obs: s.obs.join(","),
          br: s.bridge ? "1" : "0", kd: s.keyed ? "1" : "0",
          pkg: s.pkg, cls: s.appClass, step: String(this.step),
        });
        history.replaceState(null, "", "?" + p.toString());
      },

      // -------- step navigation --------
      next() { if (this.step < this.steps.length - 1) this.step++; },
      prev() { if (this.step > 0) this.step--; },
      goto(i) { this.step = i; },

      toggle(arr, id) {
        const i = arr.indexOf(id);
        if (i >= 0) arr.splice(i, 1); else arr.push(id);
      },

      // -------- data accessors --------
      labelOf(id) {
        if (!this.data) return id;
        return (
          this.data.systems?.[id]?.label ||
          this.data.observability?.[id]?.label ||
          this.data.frameworks?.[id]?.label ||
          this.data.runtimes?.[id]?.label ||
          id
        );
      },

      runnerEntry() {
        if (!this.data) return null;
        const s = this.state;
        if (s.runtime === "jvm") return this.data.frameworks?.[s.framework];
        if (s.runtime === "process") return s.language === "go" ? this.data.runtimes.go : this.data.runtimes.process;
        if (s.runtime === "container") return this.data.runtimes.container;
        if (s.runtime === "provided") return this.data.runtimes.provided;
        return null;
      },

      activeEntries() {
        if (!this.data) return [];
        const s = this.state;
        const out = [];
        s.systems.forEach((id) => this.data.systems?.[id] && out.push({ id, kind: "system", ...this.data.systems[id] }));
        s.mocks.forEach((id) => this.data.systems?.[id] && out.push({ id, kind: "mock", ...this.data.systems[id] }));
        s.obs.forEach((id) => this.data.observability?.[id] && out.push({ id, kind: "obs", ...this.data.observability[id] }));
        if (s.bridge && this.bridgeSupportedInJvm()) {
          const b = this.data.systems["sys.bridge"];
          if (b) out.push({ id: "sys.bridge", kind: "system", ...b });
        }
        return out;
      },

      bridgeSupportedInJvm() {
        return this.state.runtime === "jvm" && this.state.framework !== "quarkus";
      },

      // -------- rendering --------
      renderGradle() {
        if (!this.data) return "";
        const s = this.state;
        const entries = this.activeEntries();
        const runner = this.runnerEntry();
        const test = this.data.tests[s.test];

        const deps = new Set(["com.trendyol:stove"]);
        entries.forEach((e) => (e.gradle || []).forEach((d) => deps.add(d)));
        (runner?.gradle || []).forEach((d) => deps.add(d));
        (test?.gradle || []).forEach((d) => deps.add(d));
        const depLines = Array.from(deps).map((d) => `    testImplementation("${d}")`).join("\n");

        const tracing = entries.find((e) => e.id === "obs.tracing");
        const pluginsBlock = tracing
          ? `plugins {\n    ${tracing.pluginLine}\n}\n\n${tracing.pluginBlock}\n\n`
          : "";

        return `${pluginsBlock}dependencies {
    testImplementation(platform("com.trendyol:stove-bom:${s.stoveVersion}"))

${depLines}
}`;
      },

      renderStoveConfig() {
        if (!this.data) return "";
        const s = this.state;
        const entries = this.activeEntries();
        const runner = this.runnerEntry();
        const test = this.data.tests[s.test];

        const imports = new Set([
          "com.trendyol.stove.testing.e2e.Stove",
          "com.trendyol.stove.testing.e2e.standalone.kotest.AbstractProjectConfig",
          "io.kotest.core.extensions.Extension",
        ]);
        entries.forEach((e) => (e.imports || []).forEach((i) => imports.add(i)));
        (runner?.imports || []).forEach((i) => imports.add(i));
        if (test.extensionLine) imports.add("com.trendyol.stove.testing.e2e.standalone.kotest.StoveKotestExtension");
        const importBlock = Array.from(imports).sort().map((i) => `import ${i}`).join("\n");

        // Provided mode: prefer e.configureProvided over e.configure when available
        const useProvided = s.runtime === "provided";
        const systemBlocks = entries
          .map((e) => {
            const tpl = useProvided && e.configureProvided ? e.configureProvided : e.configure;
            return fill(tpl, { pkg: s.pkg });
          })
          .filter(Boolean)
          .map((c) => indent(dedent(c), "      "))
          .join("\n\n");

        const runnerBlock = runner
          ? indent(dedent(fill(runner.runner, { pkg: s.pkg })), "      ")
          : "      // configure your application runner here";

        const extensionsLine = test.extensionLine
          ? `  override val extensions: List<Extension> = listOf(${test.extensionLine})`
          : "  // JUnit: extend BaseE2ETest in your test classes";

        return `package ${s.pkg}.e2e.setup

${importBlock}

class StoveConfig : AbstractProjectConfig() {
${extensionsLine}

  override suspend fun beforeProject() {
    Stove().with {
${systemBlocks}

${runnerBlock}
    }.run()
  }

  override suspend fun afterProject() = Stove.stop()
}`;
      },

      renderSampleTest() {
        if (!this.data) return "";
        const s = this.state;
        const entries = this.activeEntries();
        const test = this.data.tests[s.test];

        const body = entries
          .filter((e) => e.kind === "system" || e.kind === "mock")
          .map((e) => fill(e.testDsl, {}))
          .filter(Boolean)
          .map((c) => indent(dedent(c), "      "))
          .join("\n\n");

        const classImports = new Set([
          "com.trendyol.stove.testing.e2e.standalone.kotest.stove",
          "io.kotest.matchers.shouldBe",
          "io.kotest.matchers.string.shouldContain",
        ]);
        if (test.classImport) classImports.add(test.classImport);
        const imp = Array.from(classImports).sort().map((i) => `import ${i}`).join("\n");

        return `package ${s.pkg}.e2e.tests

${imp}

${fill(test.sample, { body })}`;
      },

      renderKotestProps() {
        if (this.state.test !== "kotest") return null;
        return `kotest.framework.config.fqn=${this.state.pkg}.e2e.setup.StoveConfig`;
      },

      // Stats for sticky preview
      stats() {
        const entries = this.activeEntries();
        const deps = new Set(["stove"]);
        entries.forEach((e) => (e.gradle || []).forEach((d) => deps.add(d)));
        return {
          systems: this.state.systems.length,
          mocks: this.state.mocks.length,
          obs: this.state.obs.length,
          deps: deps.size,
        };
      },

      copy(text, btn) {
        navigator.clipboard.writeText(text).then(() => {
          const orig = btn.textContent;
          btn.textContent = "copied!";
          setTimeout(() => (btn.textContent = orig), 1200);
        });
      },

      /** Syntax-highlight `code` for `lang` via Prism.js if available;
       *  otherwise return HTML-escaped plain text. Always safe to set via x-html. */
      hl(code, lang) {
        if (code == null) return "";
        const target = (lang || "kotlin").toLowerCase();
        if (window.Prism && Prism.languages && Prism.languages[target]) {
          return Prism.highlight(String(code), Prism.languages[target], target);
        }
        return String(code).replace(/[&<>]/g, (c) =>
          ({ "&": "&amp;", "<": "&lt;", ">": "&gt;" }[c])
        );
      },

      shareUrl() {
        // Build from state so the link is always complete, even before the
        // browser's history.replaceState round-trip has updated location.href.
        const s = this.state;
        const p = new URLSearchParams({
          rt: s.runtime, fw: s.framework, lang: s.language, test: s.test,
          sys: s.systems.join(","), mk: s.mocks.join(","), obs: s.obs.join(","),
          br: s.bridge ? "1" : "0", kd: s.keyed ? "1" : "0",
          pkg: s.pkg, cls: s.appClass, step: String(this.step),
        });
        const base = location.origin + location.pathname.replace(/\?.*$/, "");
        return base + "?" + p.toString() + "#stove-wizard";
      },

      // Catalog accessors for templates
      sysIds() { return this.data ? Object.keys(this.data.systems).filter((id) => this.data.systems[id].family !== "mock" && id !== "sys.bridge") : []; },
      mockIds() { return this.data ? Object.keys(this.data.systems).filter((id) => this.data.systems[id].family === "mock") : []; },
      obsIds() { return this.data ? Object.keys(this.data.observability) : []; },
      frameworkIds() { return this.data ? Object.keys(this.data.frameworks) : []; },
    };
  }

  // ---------------- Template ----------------
  const TEMPLATE = `
    <div class="sw-root" x-data="wizardApp()" x-init="init()" x-cloak>
      <template x-if="!ready && !loadError">
        <p class="sw-hint">Loading wizard…</p>
      </template>
      <template x-if="loadError">
        <div class="sw-error">
          Failed to load setup data (<span x-text="loadError"></span>).
          Wizard requires the docs site to be served (try <code>mkdocs serve</code>).
        </div>
      </template>

      <div x-show="ready" class="sw-layout">

        <!-- LEFT: stepper + panels -->
        <div class="sw-main">
          <ol class="sw-steps">
            <template x-for="(name, i) in steps" :key="i">
              <li :class="{ active: step === i, done: step > i }" @click="goto(i)">
                <span class="sw-step-num" x-text="i+1"></span>
                <span class="sw-step-label" x-text="name"></span>
              </li>
            </template>
          </ol>

          <!-- Step 0: Runtime -->
          <section class="sw-panel" x-show="step === 0">
            <h3>What are you testing?</h3>
            <div class="sw-grid">
              <label class="sw-card" :class="{ selected: state.runtime === 'jvm' }">
                <input type="radio" name="rt" value="jvm" x-model="state.runtime">
                <strong>JVM application</strong>
                <p>Spring Boot, Ktor, Micronaut, Quarkus.</p>
              </label>
              <label class="sw-card" :class="{ selected: state.runtime === 'process' }">
                <input type="radio" name="rt" value="process" x-model="state.runtime">
                <strong>Host binary (process)</strong>
                <p>Go, Python, Rust, Node. Run a local binary.</p>
              </label>
              <label class="sw-card" :class="{ selected: state.runtime === 'container' }">
                <input type="radio" name="rt" value="container" x-model="state.runtime">
                <strong>Docker image (container)</strong>
                <p>Any language. CI parity.</p>
              </label>
              <label class="sw-card" :class="{ selected: state.runtime === 'provided' }">
                <input type="radio" name="rt" value="provided" x-model="state.runtime">
                <strong>Already-deployed app</strong>
                <p>Smoke test a remote service. Black-box.</p>
              </label>
            </div>
            <div class="sw-callout" x-show="state.runtime === 'provided'">
              <strong>🛰️ This switches Stove into Provided Instances mode.</strong>
              Every database, broker, and cache you pick will use <code>SystemOptions.provided(...)</code> instead of spinning a Testcontainer. You'll supply real connection URLs (staging Postgres, staging Kafka, ...). <a href="../Components/11-provided-instances/" target="_blank">Read the provided-instances guide ↗</a> for isolation patterns on shared infra.
              <br><br>
              Bridge (<code>using&lt;T&gt;</code>) won't be available either. Verify side effects through HTTP/DB/Kafka assertions only.
            </div>
          </section>

          <!-- Step 1 -->
          <section class="sw-panel" x-show="step === 1">
            <template x-if="state.runtime === 'jvm'">
              <div>
                <h3>Pick your JVM framework</h3>
                <div class="sw-grid">
                  <template x-for="fwId in frameworkIds()" :key="fwId">
                    <label class="sw-card" :class="{ selected: state.framework === fwId }">
                      <input type="radio" name="fw" :value="fwId" x-model="state.framework">
                      <strong x-text="data.frameworks[fwId].label"></strong>
                    </label>
                  </template>
                </div>
                <div class="sw-row">
                  <label>Application package
                    <input type="text" x-model="state.pkg">
                  </label>
                  <label>Application class
                    <input type="text" x-model="state.appClass">
                  </label>
                </div>
              </div>
            </template>
            <template x-if="state.runtime === 'process' || state.runtime === 'container'">
              <div>
                <h3>Language of your app</h3>
                <div class="sw-grid">
                  <template x-for="lang in ['go','python','node','rust','other']" :key="lang">
                    <label class="sw-card" :class="{ selected: state.language === lang }">
                      <input type="radio" name="lang" :value="lang" x-model="state.language">
                      <strong x-text="lang"></strong>
                    </label>
                  </template>
                </div>
                <p class="sw-hint" x-show="state.runtime === 'process' && state.language === 'go'">
                  Go gets a dedicated <code>goApp()</code> runner with build helpers.
                </p>
              </div>
            </template>
            <template x-if="state.runtime === 'provided'">
              <div>
                <h3>Already-deployed app</h3>
                <p>No framework needed — Stove only needs the URL and an optional readiness probe.</p>
              </div>
            </template>
          </section>

          <!-- Step 2 -->
          <section class="sw-panel" x-show="step === 2">
            <h3>Test framework</h3>
            <div class="sw-grid">
              <label class="sw-card" :class="{ selected: state.test === 'kotest' }">
                <input type="radio" name="test" value="kotest" x-model="state.test">
                <strong>Kotest (recommended)</strong>
                <p>First-class Stove integration via <code>AbstractProjectConfig</code>.</p>
              </label>
              <label class="sw-card" :class="{ selected: state.test === 'junit' }">
                <input type="radio" name="test" value="junit" x-model="state.test">
                <strong>JUnit 5</strong>
                <p>Uses a <code>BaseE2ETest</code> pattern.</p>
              </label>
            </div>
          </section>

          <!-- Step 3 -->
          <section class="sw-panel" x-show="step === 3">
            <h3>Physical dependencies</h3>
            <p class="sw-hint">Pick the databases, brokers, and clients your app actually uses.</p>
            <div class="sw-callout" x-show="state.runtime === 'provided'">
              <strong>Provided mode: connection details required per system.</strong>
              For staging / remote tests, point each system at the real infrastructure (e.g. <code>jdbc:postgresql://staging-db:5432/myapp</code>). Add a unique prefix per run (schema name, topic prefix, key prefix) so parallel CI jobs don't collide.
            </div>
            <div class="sw-grid sw-grid-3">
              <template x-for="id in sysIds()" :key="id">
                <label class="sw-card" :class="{ selected: state.systems.includes(id) }">
                  <input type="checkbox" :checked="state.systems.includes(id)" @change="toggle(state.systems, id)">
                  <strong x-text="data.systems[id].label"></strong>
                </label>
              </template>
            </div>
            <label class="sw-toggle" x-show="state.runtime === 'jvm'">
              <input type="checkbox" x-model="state.bridge" :disabled="!bridgeSupportedInJvm()">
              Add <code>bridge()</code> (DI access for setup + verification)
              <span class="sw-hint" x-show="state.framework === 'quarkus'">Bridge is not supported on Quarkus yet.</span>
            </label>
            <p class="sw-hint" x-show="state.runtime === 'provided' || state.runtime === 'process' || state.runtime === 'container'">
              Bridge is JVM-in-process only. Unavailable for <span x-text="state.runtime"></span> runtime.
            </p>
            <label class="sw-toggle">
              <input type="checkbox" x-model="state.keyed">
              Register multiple instances of the same system (keyed systems)
            </label>
          </section>

          <!-- Step 4 -->
          <section class="sw-panel" x-show="step === 4">
            <h3>External surfaces to mock</h3>
            <p class="sw-hint">Mock third-party HTTP and gRPC services your app calls out to.</p>
            <div class="sw-grid">
              <template x-for="id in mockIds()" :key="id">
                <label class="sw-card" :class="{ selected: state.mocks.includes(id) }">
                  <input type="checkbox" :checked="state.mocks.includes(id)" @change="toggle(state.mocks, id)">
                  <strong x-text="data.systems[id].label"></strong>
                </label>
              </template>
            </div>
          </section>

          <!-- Step 5 -->
          <section class="sw-panel" x-show="step === 5">
            <h3>Observability (recommended)</h3>
            <p class="sw-hint">Failure reports become call chains, timelines, and span trees. AI agents can read them via MCP.</p>
            <div class="sw-grid">
              <template x-for="id in obsIds()" :key="id">
                <label class="sw-card" :class="{ selected: state.obs.includes(id) }">
                  <input type="checkbox" :checked="state.obs.includes(id)" @change="toggle(state.obs, id)">
                  <strong x-text="data.observability[id].label"></strong>
                </label>
              </template>
            </div>
            <div class="sw-callout" x-show="state.obs.includes('obs.tracing')">
              <strong>🐘 Gradle required for tracing.</strong>
              The <code>stoveTracing</code> plugin attaches the OpenTelemetry Java agent, runs the OTLP receiver, allocates a per-task port, and exposes the endpoint to your AUT via env vars. <strong>Zero app-code changes.</strong>
              Maven users would need to wire all of that manually. If you're on Maven, this is the moment to consider switching. <code>gradle init --type pom</code> gets you most of the way there.
            </div>
          </section>

          <!-- Step 6 -->
          <section class="sw-panel" x-show="step === 6">
            <h3>Your Stove setup</h3>

            <div class="sw-provided-banner" x-show="state.runtime === 'provided'">
              <div class="sw-gradle-banner-head">
                <span class="sw-gradle-icon">🛰️</span>
                <strong>Provided Instances mode</strong>
              </div>
              <p>
                The output below uses container-mode <code>SystemOptions(...)</code> for readability. <strong>For staging / remote testing, swap each to <code>SystemOptions.provided(...)</code></strong> and pass real connection details (jdbcUrl, bootstrapServers, etc.). Add a per-run prefix for safety on shared infra.
              </p>
              <p>
                Full per-system <code>.provided(...)</code> signatures and an isolation pattern: <a href="../Components/11-provided-instances/" target="_blank">Provided Instances guide ↗</a>.
              </p>
            </div>

            <div class="sw-gradle-banner">
              <div class="sw-gradle-banner-head">
                <span class="sw-gradle-icon">🐘</span>
                <strong>Built for Gradle (Kotlin DSL)</strong>
              </div>
              <p>
                The <code>stoveTracing</code> plugin (OpenTelemetry agent attach + OTLP receiver + per-task port allocation) is <strong>Gradle-only</strong>.
                Stove dependencies work on Maven, but tracing, dashboard auto-wiring, and the polyglot tooling assume the Gradle path.
                On Maven? Converting is worth it. Use Gradle's <code>init</code> task as a starting point:
              </p>
              <pre><code>gradle init --type pom</code></pre>
            </div>

            <div class="sw-share">
              <p>Paste these into your project.</p>
              <button class="sw-btn primary sw-share-btn"
                      @click="copy(shareUrl(), $event.currentTarget)"
                      :title="shareUrl()">
                🔗 Copy share link
              </button>
              <a class="sw-share-open" :href="shareUrl()" target="_blank" rel="noopener">open ↗</a>
            </div>

            <div class="sw-output">
              <div class="sw-output-head">
                <h4><span class="sw-output-tag">Gradle Kotlin DSL</span> build.gradle.kts</h4>
                <button class="sw-copy" @click="copy(renderGradle(), $event.target)">copy</button>
              </div>
              <pre><code class="language-kotlin" x-html="hl(renderGradle(), 'kotlin')"></code></pre>
            </div>

            <div class="sw-output">
              <div class="sw-output-head">
                <h4>StoveConfig.kt</h4>
                <button class="sw-copy" @click="copy(renderStoveConfig(), $event.target)">copy</button>
              </div>
              <pre><code class="language-kotlin" x-html="hl(renderStoveConfig(), 'kotlin')"></code></pre>
            </div>

            <div class="sw-output" x-show="renderKotestProps()">
              <div class="sw-output-head">
                <h4>src/test-e2e/resources/kotest.properties</h4>
                <button class="sw-copy" @click="copy(renderKotestProps(), $event.target)">copy</button>
              </div>
              <pre><code class="language-properties" x-html="hl(renderKotestProps(), 'properties')"></code></pre>
            </div>

            <div class="sw-output">
              <div class="sw-output-head">
                <h4>SampleE2ETest.kt</h4>
                <button class="sw-copy" @click="copy(renderSampleTest(), $event.target)">copy</button>
              </div>
              <pre><code class="language-kotlin" x-html="hl(renderSampleTest(), 'kotlin')"></code></pre>
            </div>
          </section>

          <nav class="sw-nav">
            <button class="sw-btn" @click="prev()" :disabled="step === 0">← Back</button>
            <span class="sw-step-counter" x-text="(step+1) + ' / ' + steps.length"></span>
            <button class="sw-btn primary" @click="next()" :disabled="step === steps.length - 1">Next →</button>
          </nav>
        </div>

        <!-- RIGHT: sticky live preview -->
        <aside class="sw-preview">
          <div class="sw-preview-sticky">
            <div class="sw-preview-stats">
              <span><strong x-text="stats().deps"></strong> deps</span>
              <span><strong x-text="stats().systems"></strong> systems</span>
              <span><strong x-text="stats().mocks"></strong> mocks</span>
              <span><strong x-text="stats().obs"></strong> obs</span>
            </div>
            <div class="sw-preview-head">
              <h4>Live preview</h4>
              <button class="sw-copy" @click="copy(renderStoveConfig(), $event.target)">copy</button>
            </div>
            <pre class="sw-preview-code"><code class="language-kotlin" x-html="hl(renderStoveConfig(), 'kotlin')"></code></pre>
            <a class="sw-btn primary sw-preview-go" @click.prevent="goto(6)" x-show="step !== 6">Jump to full output →</a>
          </div>
        </aside>
      </div>
    </div>
  `;

  // ---------------- Bootstrap ----------------
  function mount() {
    const host = document.getElementById("stove-wizard");
    if (!host) return;
    if (host.dataset.swMounted === "1") return;
    host.innerHTML = TEMPLATE;
    host.dataset.swMounted = "1";
    window.SNIPPETS_REF = {}; // legacy
    window.wizardApp = wizardApp;
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", mount);
  } else {
    mount();
  }
  if (window.document$ && typeof window.document$.subscribe === "function") {
    window.document$.subscribe(() => {
      // Reset mount flag for instant-nav re-mount on different page
      document.querySelectorAll("#stove-wizard").forEach((el) => (el.dataset.swMounted = ""));
      mount();
    });
  }

  // ---------------- Open-in-wizard widget ----------------
  // Any anchor with class="open-in-wizard" data-sys="postgresql,kafka" data-mk="wiremock"
  // becomes a link to the home wizard with those preselected.
  function siteHomeUrl() {
    // Override via <meta name="stove-home">
    const meta = document.querySelector('meta[name="stove-home"]')?.content;
    if (meta) return meta;
    // Material theme logo always points to site root; works under sub-paths
    const logo = document.querySelector("a.md-header__button.md-logo");
    if (logo?.href) return logo.href;
    return "/";
  }
  function decorateDeepLinks() {
    document.querySelectorAll("a.open-in-wizard").forEach((a) => {
      if (a.dataset.swDecorated === "1") return;
      const sys = (a.dataset.sys || "").split(",").map((s) => s.trim()).filter(Boolean);
      const mk = (a.dataset.mk || "").split(",").map((s) => s.trim()).filter(Boolean);
      const fw = a.dataset.fw;
      const params = new URLSearchParams();
      if (sys.length) params.set("sys", sys.map((s) => (s.startsWith("sys.") ? s : "sys." + s)).join(","));
      if (mk.length) params.set("mk", mk.map((s) => (s.startsWith("sys.") ? s : "sys." + s)).join(","));
      if (fw) params.set("fw", fw);
      params.set("step", "6");
      const home = siteHomeUrl().replace(/\/$/, "/") || "/";
      a.href = home + "?" + params.toString() + "#stove-wizard";
      a.dataset.swDecorated = "1";
    });
  }
  // Expose so other widgets (e.g. cookbook) can re-decorate after dynamic inserts
  window.__stoveDecorate = decorateDeepLinks;

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", decorateDeepLinks);
  } else {
    decorateDeepLinks();
  }
  if (window.document$ && typeof window.document$.subscribe === "function") {
    window.document$.subscribe(() => decorateDeepLinks());
  }
})();
