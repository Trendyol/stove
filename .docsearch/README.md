# Algolia DocSearch Setup

Free for open-source docs. Adds typo-tolerant instant search across the entire site, replacing MkDocs' built-in client-side search.

## One-time apply

1. Submit the Stove docs URL at <https://docsearch.algolia.com/apply/>.
2. Once approved, Algolia emails three values:
   - `appId`
   - `searchApiKey` (public, for the site)
   - `indexName` (likely `stove`)
3. Crawler config (`.docsearch/config.json`) is already prepared. Algolia uses it as the starting point.

## Wire up

After receiving keys, do **all** of:

### 1. Add the DocSearch JS + CSS

Append to `mkdocs.yml`:

```yaml
extra_javascript:
  - https://cdn.jsdelivr.net/npm/@docsearch/js@3
  - js/docsearch-init.js

extra_css:
  - https://cdn.jsdelivr.net/npm/@docsearch/css@3
```

### 2. Create `docs/js/docsearch-init.js`

```js
(function () {
  function init() {
    if (typeof docsearch !== "function") return;
    const host = document.querySelector(".md-search");
    if (!host || host.dataset.dsMounted === "1") return;
    host.dataset.dsMounted = "1";
    host.innerHTML = '<div id="docsearch"></div>';
    docsearch({
      container: "#docsearch",
      appId: "<APP_ID>",
      apiKey: "<SEARCH_API_KEY>",
      indexName: "stove",
    });
  }
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else { init(); }
  if (window.document$ && typeof window.document$.subscribe === "function") {
    window.document$.subscribe(() => init());
  }
})();
```

### 3. Disable the built-in search (optional)

To use only Algolia, remove `- search` from `plugins:` in `mkdocs.yml`. Leaving both is fine; users see Algolia's UI replacing Material's box.

## Verify

```bash
mkdocs serve
# Open http://127.0.0.1:8000, hit Cmd/Ctrl+K, you should see the Algolia widget.
```

## Crawler reruns

Algolia recrawls on a schedule (usually weekly). Force a recrawl via the dashboard after major doc changes.
