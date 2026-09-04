import type { Test } from "../api/types";
import type { FilterValue } from "../layout/sidebar/TestFilters";
import { isFailed, isPassed } from "./status";

function matchesFilter(test: Test, filter: FilterValue): boolean {
  if (filter === "pass") return isPassed(test.status);
  if (filter === "fail") return isFailed(test.status);
  return true;
}

function matchesSearch(test: Test, query: string): boolean {
  if (!query) return true;
  const q = query.toLowerCase();
  return (
    test.test_name.toLowerCase().includes(q) ||
    test.test_path.some((seg) => seg.toLowerCase().includes(q)) ||
    test.spec_name.toLowerCase().includes(q)
  );
}

export function filterTests(tests: Test[], filter: FilterValue, search: string): Test[] {
  return tests.filter((t) => matchesFilter(t, filter) && matchesSearch(t, search));
}
