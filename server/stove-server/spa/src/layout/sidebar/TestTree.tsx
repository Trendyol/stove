import { useCallback, useMemo, useState } from "react";
import type { Test } from "../../api/types";
import { VirtualList } from "../../components/VirtualList";
import { aggregateStatus, type Status } from "../../utils/status";
import { TestListItem } from "./TestListItem";

interface TestTreeProps {
  tests: Test[];
  selectedTestId: string | undefined;
  onSelectTest: (testId: string) => void;
}

interface TreeNode {
  label: string;
  tests: Test[];
  children: Map<string, TreeNode>;
  status: Status;
}

type TreeRow =
  | {
      kind: "group";
      key: string;
      collapseKey: string;
      label: string;
      depth: number;
      collapsed: boolean;
      expandable: boolean;
      status: Status;
    }
  | { kind: "test"; key: string; test: Test; depth: number };

export function TestTree({ tests, selectedTestId, onSelectTest }: TestTreeProps) {
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const tree = useMemo(() => buildTree(tests), [tests]);
  const rows = useMemo(() => flattenTree(tree, collapsed), [collapsed, tree]);

  const toggle = useCallback((key: string) => {
    setCollapsed((current) => {
      const next = new Set(current);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }, []);

  return (
    <VirtualList
      className="stove-test-tree-scroll"
      ariaLabel="Run navigator"
      items={rows}
      getKey={(row) => row.key}
      getItemSize={(row) => (row.kind === "group" ? 32 : 50)}
      windowThreshold={120}
      renderItem={(row) =>
        row.kind === "group" ? (
          <TreeGroup row={row} onToggle={() => toggle(row.collapseKey)} />
        ) : (
          <div style={{ paddingLeft: `${row.depth * 12}px` }}>
            <TestListItem
              test={row.test}
              selected={selectedTestId === row.test.id}
              onSelect={() => onSelectTest(row.test.id)}
              hideSpec
            />
          </div>
        )
      }
    />
  );
}

function TreeGroup({
  row,
  onToggle,
}: {
  row: Extract<TreeRow, { kind: "group" }>;
  onToggle: () => void;
}) {
  return (
    <button
      type="button"
      className="stove-tree-group"
      style={{ paddingLeft: `${row.depth * 12 + 8}px`, paddingTop: "4px", paddingBottom: "4px" }}
      onClick={onToggle}
    >
      {row.expandable && (
        <svg
          aria-hidden="true"
          className={`stove-tree-chevron ${row.collapsed ? "" : "is-open"}`}
          viewBox="0 0 16 16"
          fill="currentColor"
        >
          <path d="M6 4l4 4-4 4z" />
        </svg>
      )}
      <span className="stove-tree-label">{row.label}</span>
      <StatusDot status={row.status} />
    </button>
  );
}

function buildTree(tests: Test[]): Map<string, TreeNode> {
  const root = new Map<string, TreeNode>();
  for (const test of tests) {
    const specName = test.spec_name || "(no spec)";
    const path = test.test_path.length > 0 ? test.test_path : [test.test_name];
    const specNode = getOrCreateNode(root, specName);

    if (path.length <= 1) {
      specNode.tests.push(test);
      continue;
    }

    let current = specNode;
    for (const segment of path.slice(0, -1)) {
      current = getOrCreateNode(current.children, segment);
    }
    current.tests.push(test);
  }

  for (const node of root.values()) calculateStatus(node);
  return root;
}

function getOrCreateNode(nodes: Map<string, TreeNode>, label: string): TreeNode {
  const existing = nodes.get(label);
  if (existing) return existing;
  const node = { label, tests: [], children: new Map(), status: "RUNNING" as const };
  nodes.set(label, node);
  return node;
}

function calculateStatus(node: TreeNode): Status {
  const statuses = node.tests.map((test) => test.status);
  for (const child of node.children.values()) statuses.push(calculateStatus(child));
  node.status = aggregateStatus(statuses);
  return node.status;
}

function flattenTree(nodes: Map<string, TreeNode>, collapsed: Set<string>): TreeRow[] {
  const rows: TreeRow[] = [];
  appendRows(nodes, collapsed, rows, 0, "");
  return rows;
}

function appendRows(
  nodes: Map<string, TreeNode>,
  collapsed: Set<string>,
  rows: TreeRow[],
  depth: number,
  parentKey: string,
) {
  for (const [key, node] of nodes) {
    const nodeKey = parentKey ? `${parentKey}/${key}` : key;
    const isCollapsed = collapsed.has(nodeKey);
    rows.push({
      kind: "group",
      key: `group:${nodeKey}`,
      collapseKey: nodeKey,
      label: node.label,
      depth,
      collapsed: isCollapsed,
      expandable: node.children.size > 0 || node.tests.length > 0,
      status: node.status,
    });
    if (isCollapsed) continue;

    appendRows(node.children, collapsed, rows, depth + 1, nodeKey);
    for (const test of node.tests) {
      rows.push({ kind: "test", key: `test:${test.run_id}:${test.id}`, test, depth });
    }
  }
}

function StatusDot({ status }: { status: Status }) {
  const tone =
    status === "FAILED" || status === "ERROR"
      ? "failed"
      : status === "PASSED"
        ? "passed"
        : "running";
  return <span className={`stove-tree-dot is-${tone}`} />;
}
