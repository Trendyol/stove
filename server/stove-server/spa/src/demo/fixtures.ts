import type { Entry, MockInteraction, MockWarning, Run, Snapshot, Span, Test } from "../api/types";

export interface DemoData {
  runs: Run[];
  tests: Test[];
  entries: Entry[];
  spans: Span[];
  snapshots: Snapshot[];
  mock_interactions: MockInteraction[];
  mock_warnings: MockWarning[];
}

const orderTests = [
  "confirms an order after payment",
  "reserves stock before charging",
  "accepts a valid access token",
  "publishes OrderCreated once",
  "stores the confirmed order",
  "updates the order cache",
];
const applications = [
  { name: "checkout-api", spec: "CheckoutFlowTest", tests: orderTests },
  { name: "mobile-checkout-api", spec: "MobileOrderTest", tests: orderTests },
  { name: "partner-orders-api", spec: "PartnerOrderTest", tests: orderTests },
];

/** Fictional, deterministic evidence. Each run is a complete, internally linked test suite. */
export function createDemoData(version: string, now = Date.now()): DemoData {
  const data: DemoData = {
    runs: [],
    tests: [],
    entries: [],
    spans: [],
    snapshots: [],
    mock_interactions: [],
    mock_warnings: [],
  };
  let id = 1;
  for (const [appIndex, app] of applications.entries()) {
    for (let revision = 0; revision < 3; revision++) {
      const start = now - (revision * 86_400_000 + appIndex * 3_600_000 + 120_000);
      const runId = `${app.name}-ci-${1842 - revision}`;
      const failedRun = revision === 0 && appIndex === 0;
      const systems = ["HTTP", "PostgreSQL", "Kafka", "WireMock", "OIDC", "Redis"];
      data.runs.push({
        id: runId,
        app_name: app.name,
        started_at: new Date(start).toISOString(),
        ended_at: new Date(start + 22_000).toISOString(),
        status: failedRun ? "FAILED" : "PASSED",
        total_tests: app.tests.length,
        passed: app.tests.length - (failedRun ? 1 : 0),
        failed: failedRun ? 1 : 0,
        duration_ms: 22_000,
        stove_version: version,
        systems,
        metadata: {
          branch: revision === 0 ? "feature/payment-retry" : "main",
          commit: ["8e7c9a1", "2b1f6d4", "9a0d3e2"][revision],
          pipeline: String(1842 - revision),
          environment: "ci",
          team: appIndex === 2 ? "partners" : "commerce",
        },
      });
      for (const [testIndex, name] of app.tests.entries()) {
        const failed = failedRun && testIndex === 0;
        const testId = `${app.spec}::${name}`;
        const at = (offset: number) => new Date(start + testIndex * 3000 + offset).toISOString();
        const traceId = (appIndex * 1000 + revision * 100 + testIndex + 1)
          .toString(16)
          .padStart(32, "0");
        const orderId = `ord-${1842 - revision}-${testIndex + 1}`;
        const error = failed
          ? `org.opentest4j.AssertionFailedError: Order status did not become CONFIRMED\nexpected: <CONFIRMED> but was: <PAYMENT_PENDING>\n\tat demo.checkout.CheckoutFlowTest.confirmOrder(CheckoutFlowTest.kt:84)\nCaused by: PaymentTimeoutException: Payment provider exceeded 800ms deadline\n\tat demo.checkout.PaymentClient.charge(PaymentClient.kt:57)`
          : null;
        data.tests.push({
          id: testId,
          run_id: runId,
          test_name: name,
          spec_name: app.spec,
          test_path: [testIndex < 3 ? "Checkout" : "Side effects", name],
          started_at: at(0),
          ended_at: at(2400),
          status: failed ? "FAILED" : "PASSED",
          duration_ms: 2400,
          error,
        });
        const endpoint =
          appIndex === 0 ? "/orders" : appIndex === 1 ? "/mobile/orders" : "/partners/orders";
        const actions = [
          {
            system: "PostgreSQL",
            action: "Seed customer and stock",
            input: { customerId: "customer-42", sku: "STOVE-MUG", stock: 10 },
            output: { rowsAffected: 2 },
          },
          {
            system: "OIDC",
            action: "Issue access token",
            input: {
              issuer: "https://identity.demo.invalid",
              audience: app.name,
              scope: "orders:write",
            },
            output: { token_type: "Bearer", access_token: "[redacted]", expires_in: 300 },
          },
          {
            system: "WireMock",
            action: "Register payment response",
            input: { method: "POST", path: "/payments/charge", delayMs: failed ? 1200 : 32 },
            output: { stubId: "payment-charge" },
          },
          {
            system: "HTTP",
            action: `POST ${endpoint}`,
            input: { orderId, sku: "STOVE-MUG", quantity: 1 },
            output: {
              status: failed ? 202 : 201,
              body: { id: orderId, status: failed ? "PAYMENT_PENDING" : "CONFIRMED" },
            },
          },
          {
            system: "Kafka",
            action: "shouldBePublished<OrderCreated>",
            input: { topic: "orders.created", orderId },
            output: { orderId, customerId: "customer-42", total: 24.9, currency: "EUR" },
          },
          {
            system: "PostgreSQL",
            action: "Order should be confirmed",
            input: { query: "SELECT status FROM orders WHERE id = :orderId", orderId },
            output: { status: failed ? "PAYMENT_PENDING" : "CONFIRMED" },
          },
        ];
        for (const [index, action] of actions.entries()) {
          const assertionFailed = failed && index === actions.length - 1;
          data.entries.push({
            id: id++,
            run_id: runId,
            test_id: testId,
            timestamp: at([0, 60, 100, 200, 2240, 2320][index]),
            ...action,
            input: JSON.stringify(action.input),
            output: JSON.stringify(action.output),
            result: assertionFailed ? "FAILED" : "PASSED",
            metadata: JSON.stringify({
              duration_ms: index === 3 ? 820 : 18,
              timeout_ms: 2000,
              testData: "fictional",
            }),
            expected: assertionFailed ? JSON.stringify({ status: "CONFIRMED" }) : null,
            actual: assertionFailed ? JSON.stringify({ status: "PAYMENT_PENDING" }) : null,
            error: assertionFailed ? error : null,
            trace_id: index >= 3 ? traceId : null,
            assertion_id: `${testId}-${index}`,
            attempt_count: assertionFailed ? 4 : 1,
            failure_count: assertionFailed ? 4 : 0,
          });
        }
        const spans = [
          {
            name: `POST ${endpoint}`,
            service: "stove-tests",
            parent: null,
            offset: 200,
            duration: 2000,
            attrs: {
              "http.request.method": "POST",
              "url.path": endpoint,
              "http.response.status_code": failed ? 202 : 201,
            },
          },
          {
            name: `POST ${endpoint}`,
            service: app.name,
            parent: 0,
            offset: 220,
            duration: 1850,
            attrs: { "http.route": endpoint, "http.request.method": "POST" },
          },
          {
            name: "OrderService.placeOrder",
            service: app.name,
            parent: 1,
            offset: 240,
            duration: 1700,
            attrs: { "code.namespace": "demo.checkout.OrderService", "order.id": orderId },
          },
          {
            name: "SELECT inventory",
            service: app.name,
            parent: 2,
            offset: 260,
            duration: 42,
            attrs: {
              "db.system": "postgresql",
              "db.name": "checkout",
              "db.statement": "SELECT stock FROM inventory WHERE sku = ?",
              "server.address": "postgres",
            },
          },
          {
            name: "POST /payments/charge",
            service: app.name,
            parent: 2,
            offset: 320,
            duration: failed ? 800 : 32,
            attrs: {
              "http.request.method": "POST",
              "url.full": "http://payment-provider/payments/charge",
              "server.address": "payment-provider",
            },
          },
          {
            name: "orders.created publish",
            service: app.name,
            parent: 2,
            offset: 1150,
            duration: 28,
            attrs: {
              "messaging.system": "kafka",
              "messaging.destination.name": "orders.created",
              "messaging.operation": "publish",
            },
          },
          {
            name: "orders.created process",
            service: "notification-worker",
            parent: 5,
            offset: 1190,
            duration: 165,
            attrs: {
              "messaging.system": "kafka",
              "messaging.destination.name": "orders.created",
              "messaging.operation": "process",
            },
          },
          {
            name: "SET order cache",
            service: app.name,
            parent: 2,
            offset: 1400,
            duration: 8,
            attrs: {
              "db.system": "redis",
              "db.statement": `SET order:${orderId}`,
              "server.address": "redis",
            },
          },
        ];
        const spanIds = spans.map(() => (id++).toString(16).padStart(16, "0"));
        for (const [index, span] of spans.entries()) {
          const exception = failed && index === 4;
          data.spans.push({
            id: id++,
            run_id: runId,
            trace_id: traceId,
            span_id: spanIds[index],
            parent_span_id: span.parent === null ? null : spanIds[span.parent],
            operation_name: span.name,
            service_name: span.service,
            start_time_nanos: (start + testIndex * 3000 + span.offset) * 1_000_000,
            end_time_nanos: (start + testIndex * 3000 + span.offset + span.duration) * 1_000_000,
            status: exception ? "ERROR" : index === 2 ? "UNSET" : "OK",
            attributes: JSON.stringify({ ...span.attrs, "stove.test.id": testId }),
            exception_type: exception ? "PaymentTimeoutException" : null,
            exception_message: exception ? "Payment provider exceeded 800ms deadline" : null,
            exception_stack_trace: exception ? error : null,
          });
        }
        for (const [system, state] of Object.entries({
          PostgreSQL: {
            orders: [
              {
                id: orderId,
                customer_id: "customer-42",
                status: failed ? "PAYMENT_PENDING" : "CONFIRMED",
                total: 24.9,
                currency: "EUR",
              },
            ],
            inventory: [{ sku: "STOVE-MUG", stock: 9, reserved: 1 }],
          },
          Kafka: {
            published: [
              {
                topic: "orders.created",
                key: orderId,
                headers: { "content-type": "application/json" },
                message: { orderId, total: 24.9 },
              },
            ],
            consumed: [{ topic: "orders.created", group: "notifications", offset: 142 }],
            committed: [{ topic: "orders.created", offset: 143 }],
          },
          WireMock: {
            stubs: [
              {
                id: "payment-charge",
                method: "POST",
                url: "/payments/charge",
                status: 200,
                fixedDelayMilliseconds: failed ? 1200 : 32,
              },
            ],
            unmatchedRequests: [],
            servedRequests: 1,
          },
          OIDC: {
            issuer: "https://identity.demo.invalid",
            provider: "NAV",
            grants: [
              {
                clientId: "checkout-bff",
                grantType: "client_credentials",
                outcome: "SUCCESS",
                accessToken: "[redacted]",
              },
            ],
            discovery: { jwks_uri: "https://identity.demo.invalid/jwks" },
          },
          Redis: {
            keys: [`order:${orderId}`],
            values: { [orderId]: { status: failed ? "PAYMENT_PENDING" : "CONFIRMED", ttl: 300 } },
          },
        }))
          data.snapshots.push({
            id: id++,
            run_id: runId,
            test_id: testId,
            system,
            state_json: JSON.stringify(state),
            summary: `${system} state after ${name}`,
            captured_at: at(2380),
            trigger: failed ? "TEST_FAILED" : "TEST_FINISHED",
          });
        for (let index = 0; index < 3; index++) {
          const unmatched = failed && index === 2;
          data.mock_interactions.push({
            id: id++,
            run_id: runId,
            test_id: testId,
            timestamp: at(320 + index * 450),
            system: index === 0 ? "OIDC" : "WireMock",
            protocol: "HTTP",
            method: "POST",
            target:
              index === 0
                ? "/oauth/token"
                : index === 1
                  ? "/payments/charge"
                  : "/notifications/order",
            matched: !unmatched,
            stub_id: unmatched ? null : index === 0 ? "oidc-client-credentials" : "payment-charge",
            attribution: "PROVEN_HEADER",
            request_body: JSON.stringify(
              index === 0
                ? { client_id: "checkout-bff", client_secret: "[redacted]" }
                : { orderId, total: 24.9 },
            ),
            request_body_truncated: false,
            response_body: JSON.stringify(
              unmatched ? { error: "No matching stub" } : { status: "accepted", id: orderId },
            ),
            response_body_truncated: false,
            status: unmatched ? "404" : "200",
            latency_ms: failed && index === 1 ? 1200 : 32,
            near_misses: unmatched
              ? ["Expected POST /notifications/orders, received /notifications/order"]
              : [],
            trace_id: traceId,
            scenario_name: index === 1 ? "Payment retry" : null,
            scenario_state: index === 1 ? "Started" : null,
            next_scenario_state: index === 1 ? "Charged" : null,
            configured_delay_ms: index === 1 ? (failed ? 1200 : 32) : null,
            fault: null,
            client_deadline_ms: index === 1 ? 800 : null,
          });
        }
        if (failed)
          data.mock_warnings.push({
            id: id++,
            run_id: runId,
            test_id: testId,
            timestamp: at(1800),
            system: "WireMock",
            kind: "UNMATCHED_REQUEST",
            message:
              "POST /notifications/order did not match a stub. The configured path is /notifications/orders.",
            stub_id: null,
            target: "/notifications/order",
          });
      }
      data.mock_interactions.push({
        ...data.mock_interactions[data.mock_interactions.length - 1],
        id: id++,
        run_id: runId,
        test_id: null,
        attribution: "UNATTRIBUTED",
        method: "GET",
        target: "/health",
        status: "200",
        matched: true,
        stub_id: "health-check",
        request_body: null,
        response_body: '{"status":"UP"}',
        near_misses: [],
        scenario_name: null,
        scenario_state: null,
        next_scenario_state: null,
      });
    }
  }
  return data;
}
