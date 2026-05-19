/* Disable Prism's auto-highlight on DOM ready.
 * Must load BEFORE prism-core so the global flag is set during init.
 *
 * Reason: mkdocs-material's pygments already renders <code class="language-kotlin">
 * with <span class="k|s|c|...">. Prism would re-tokenize and destroy whitespace.
 * We only want Prism for dynamic Alpine-rendered code (wizard live preview,
 * cookbook cards). Those call Prism.highlight() explicitly via wizard.js's hl().
 */
window.Prism = window.Prism || {};
window.Prism.manual = true;
