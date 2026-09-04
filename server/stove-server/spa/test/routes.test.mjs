import assert from "node:assert/strict";
import test from "node:test";
import createJiti from "jiti";

const jiti = createJiti(import.meta.url);
const { pathForRoute, routeForPath } = await jiti.import("../src/utils/routes.ts");

test("admin has a dedicated route", () => {
  assert.equal(routeForPath("/admin"), "admin");
  assert.equal(routeForPath("/admin/"), "admin");
  assert.equal(pathForRoute("admin"), "/admin");
});

test("other paths resolve to the dashboard", () => {
  assert.equal(routeForPath("/"), "dashboard");
  assert.equal(routeForPath("/runs/example"), "dashboard");
  assert.equal(pathForRoute("dashboard"), "/");
});
