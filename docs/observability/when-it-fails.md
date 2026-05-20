---
hide:
  - toc
---

# When a Test Fails

A Stove test failure is not a stack trace. It's a guided trail from assertion to root cause. Console report, dashboard timeline, span tree, MCP query. Scroll to walk through what happens the moment a test goes red.

<div class="story" markdown="0">

  <div class="story-step">
    <div>
      <h3>1. Assertion fails</h3>
      <p>You wrote a normal Stove test. <code>shouldBePublished</code> times out. The expected Kafka event never showed up.</p>
      <p class="sw-hint">No special config. The assertion's failure carries everything that follows.</p>
    </div>
    <pre class="story-frame"><span class="dim">// OrderE2ETest.kt</span>
test(<span class="ok">"order is published after POST /orders"</span>) {
  stove {
    http { post&lt;OrderResponse&gt;(<span class="ok">"/orders"</span>, ...) }
    kafka {
      <span class="info">shouldBePublished</span>&lt;OrderCreated&gt; {
        actual.userId == userId
      }
    }
  }
}

<span class="err">FAILED</span> after 10.0s
  <span class="err">org.opentest4j.AssertionFailedError</span>:
  No matching OrderCreated event arrived
    within 10s on topic <span class="info">order.created.v1</span>.</pre>
  </div>

  <div class="story-step reverse">
    <div>
      <h3>2. Console report fills in context</h3>
      <p>Stove's reporter prints the timeline that led up to the assertion: every HTTP call, every DB row, every published event, every WireMock stub hit. All serialized side-by-side with the failure.</p>
      <p>No more guessing whether the bug was in your test, the app, or a downstream mock.</p>
    </div>
    <pre class="story-frame"><span class="dim">─── Stove Report ─────────────────────────────────</span>
<span class="ok">▶ http</span> POST  /orders            201   42ms
<span class="ok">▶ postgres</span> INSERT INTO orders   1 row 6ms
<span class="ok">▶ wiremock</span> GET /inventory/x-1  200   3ms
<span class="err">✘ kafka</span> shouldBePublished&lt;OrderCreated&gt;
        topic=order.created.v1   timeout=10s
        seen on bus: <span class="dim">(none)</span>
        most recent topics: order.failed.v1 (1)
<span class="dim">────────────────────────────────────────────────────</span></pre>
  </div>

  <div class="story-step">
    <div>
      <h3>3. Dashboard renders the full timeline</h3>
      <p>If the local dashboard is running, the same execution shows up as a clickable timeline at <code>http://localhost:4040</code>. System snapshots, message payloads, DB rows, all queryable.</p>
      <p>Drill into the Kafka panel: <strong>the app published to <code>order.failed.v1</code>, not <code>order.created.v1</code></strong>. Bug found.</p>
    </div>
    <pre class="story-frame"><span class="dim">┌─ Timeline ─────────────────────────────────────┐</span>
│ 00:00.012  http     POST /orders         <span class="ok">201</span>  │
│ 00:00.054  postgres INSERT orders         <span class="ok">1</span>   │
│ 00:00.061  wiremock GET  /inventory/x-1   <span class="ok">200</span> │
│ 00:00.080  kafka    published             <span class="err">!?</span>  │
│            └─ topic: <span class="err">order.failed.v1</span>        │
│               payload: {reason:"stock=0"}      │
│ 00:10.001  <span class="err">assertion timeout</span>                  │
<span class="dim">└────────────────────────────────────────────────┘</span></pre>
  </div>

  <div class="story-step reverse">
    <div>
      <h3>4. Trace tree shows the call chain inside the app</h3>
      <p>OpenTelemetry spans are captured automatically. The trace tree shows exactly where the decision got made. Which controller, which service, which Kafka producer. With timing on each hop.</p>
    </div>
    <pre class="story-frame"><span class="info">▾ POST /orders</span>                       42ms
  ├─ OrderController.create        2ms
  ├─ <span class="info">▾ OrderService.place</span>           35ms
  │    ├─ InventoryClient.fetch    7ms
  │    ├─ <span class="err">▾ StockGuard.check</span>         1ms
  │    │    └─ <span class="err">returned: INSUFFICIENT</span>
  │    └─ <span class="err">KafkaProducer.send</span>          3ms
  │         topic = <span class="err">order.failed.v1</span>
  └─ HTTP 201 returned</pre>
  </div>

  <div class="story-step">
    <div>
      <h3>5. AI agent triages via MCP</h3>
      <p>The same data is exposed over a loopback MCP endpoint. An agent (Claude Code, Cursor, etc.) can fetch failures and traces directly. No shell-out, no log scraping, no token waste on noisy stdout.</p>
      <p>It returns a focused, actionable diff suggestion in seconds.</p>
    </div>
    <pre class="story-frame">$ curl localhost:4040/mcp/tools/stove_failure_detail \
       -d '{"test_id":"OrderE2ETest#order-publish"}'

<span class="ok">{</span>
  failure: <span class="err">"kafka.shouldBePublished timeout"</span>,
  expected_topic: <span class="ok">"order.created.v1"</span>,
  observed_topics: [<span class="err">"order.failed.v1"</span>],
  span: <span class="info">"StockGuard.check returned INSUFFICIENT"</span>,
  suggestion: <span class="ok">"InventoryClient returns stock=0. Verify
    test fixture seeded inventory before order"</span>
<span class="ok">}</span></pre>
  </div>

</div>

## How to get this loop in your project

This experience requires three things, all opt-in:

<div class="grid cards" markdown>

-   :material-chart-timeline-variant: **Tracing**

    OpenTelemetry agent attached automatically by the Gradle plugin.

    [Configure tracing](../Components/15-tracing.md) · <a class="open-in-wizard" data-sys="kafka,postgresql" data-mk="wiremock">Try in wizard</a>

-   :material-monitor-dashboard: **Dashboard**

    Local SQLite-backed UI streaming live test runs.

    [Set up Dashboard](../Components/18-dashboard.md)

-   :material-robot-outline: **MCP**

    Loopback agent endpoint exposed by `stove-cli`.

    [Connect MCP](../Components/21-mcp.md)

</div>

<script>
(function () {
  function init() {
    const els = document.querySelectorAll(".story-step");
    if (!("IntersectionObserver" in window)) {
      els.forEach((e) => e.classList.add("in-view"));
      return;
    }
    const io = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) entry.target.classList.add("in-view");
      });
    }, { threshold: 0.25 });
    els.forEach((e) => io.observe(e));
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init);
  else init();
  if (window.document$ && typeof window.document$.subscribe === "function") {
    window.document$.subscribe(() => init());
  }
})();
</script>
