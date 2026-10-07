# Dashboard frontend

This React app serves both the Stove server dashboard and the interactive documentation demo.
Use Node.js 24 and install dependencies with `npm ci` in this directory.

## Interactive demo

```sh
npm run dev:demo
```

Open <http://localhost:5173/stove/dashboard-demo/>. The demo uses the real dashboard components
and API validators with a private, in-memory SQLite database. All navigation, filters, evidence
inspectors, SQL queries, retention, and purge controls operate on fictional data in the page.
**Replay a test** emits the same live event types as the server. **Reset demo** or a page reload
restores the fixtures. There is no connection to a Stove server.

```sh
npm run build:demo
npm exec vite preview -- --outDir dist-demo --base /stove/dashboard-demo/
```

The Pages workflow builds this output and copies it into `site/dashboard-demo/` after MkDocs
builds the documentation. Hash routes keep copied evidence links and refreshes working on
GitHub Pages without server routing rules. `src/demo/fixtures.ts` defines the showcase, and
`src/demo/backend.ts` implements its browser-only API adapter.

## Server dashboard

`npm run dev` uses the regular dashboard and proxies `/api` to `localhost:4040`.
`npm run build` regenerates the API types and builds the server's assets. The production build
excludes the demo adapter, fixtures, and SQLite WASM.

## Checks

```sh
npm test
npm run typecheck
npm run check
npm run build:assets
npm run build:demo
```

Demo tests validate the REST/event contracts, evidence scope and context, SQL mutations,
retention, purge, reset, and replay lifecycle. After UI changes, also check the timeline,
Mocks, Trace, State, Flow, admin tools, and narrow layouts in the browser.
