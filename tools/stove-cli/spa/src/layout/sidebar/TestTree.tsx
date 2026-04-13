import { useCallback, useMemo, useState } from "react";
import type { Test } from "../../api/types";
import { aggregateStatus, type Status } from "../../utils/status";
import { TestListItem } from "./TestListItem";

interface TestTreeProps {
  tests: Test[];
  selectedTestId: string | null;
  onSelectTest: (testId: string) => void;
}

interface TreeNode {
  label: string;
  tests: Test[];
  children: Map<string, TreeNode>;
}

export function TestTree({ tests, selectedTestId, onSelectTest }: TestTreeProps) {
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());

  const tree = useMemo(() => buildTree(tests), [tests]);

  const toggle = useCallback((key: string) => {
    setCollapsed((prev) =>
      prev.has(key) ? new Set([...prev].filter((k) => k !== key)) : new Set([...prev, key]),
    );
  }, []);

  return <>{renderNodes(tree, collapsed, toggle, selectedTestId, onSelectTest, 0, "")}</>;
}

function buildTree(tests: Test[]): Map<string, TreeNode> {
  const root = new Map<string, TreeNode>();

  for (const test of tests) {
    const specName = test.spec_name || "(no spec)";
    const path = test.test_path.length > 0 ? test.test_path : [test.test_name];

    if (!root.has(specName)) {
      root.set(specName, { label: specName, tests: [], children: new Map() });
    }
    const specNode = root.get(specName)!;

    if (path.length <= 1) {
      specNode.tests.push(test);
      continue;
    }

    let current = specNode;
    for (let i = 0; i < path.length - 1; i++) {
      const segment = path[i];
      if (!current.children.has(segment)) {
        current.children.set(segment, { label: segment, tests: [], children: new Map() });
      }
      current = current.children.get(segment)!;
    }
    current.tests.push(test);
  }

  return root;
}

function renderNodes(
  nodes: Map<string, TreeNode>,
  collapsed: Set<string>,
  toggle: (key: string) => void,
  selectedTestId: string | null,
  onSelectTest: (testId: string) => void,
  depth: number,
  parentKey: string,
): React.ReactNode[] {
  const result: React.ReactNode[] = [];

  for (const [key, node] of nodes) {
    const nodeKey = parentKey ? `${parentKey}/${key}` : key;
    const isCollapsed = collapsed.has(nodeKey);
    const hasChildren = node.children.size > 0 || node.tests.length > 0;
    const status = getNodeAggregateStatus(node);

    result.push(
      <button
        type="button"
        key={`group-${nodeKey}`}
        className="w-full text-left flex items-center gap-1 hover:bg-[var(--stove-hover)] cursor-pointer"
        style={{ paddingLeft: `${depth * 12 + 8}px`, paddingTop: "4px", paddingBottom: "4px" }}
        onClick={() => toggle(nodeKey)}
      >
        {hasChildren && (
          <svg
            aria-hidden="true"
            className={`w-3 h-3 shrink-0 text-[var(--stove-text-muted)] transition-transform ${isCollapsed ? "" : "rotate-90"}`}
            viewBox="0 0 16 16"
            fill="currentColor"
          >
            <path d="M6 4l4 4-4 4z" />
          </svg>
        )}
        <span className="text-xs font-medium text-[var(--stove-text-secondary)] truncate flex-1">
          {node.label}
        </span>
        <StatusDot status={status} />
      </button>,
    );

    if (!isCollapsed) {
      if (node.children.size > 0) {
        result.push(
          ...renderNodes(
            node.children,
            collapsed,
            toggle,
            selectedTestId,
            onSelectTest,
            depth + 1,
            nodeKey,
          ),
        );
      }
      for (const test of node.tests) {
        result.push(
          <div key={test.id} style={{ paddingLeft: `${depth * 12}px` }}>
            <TestListItem
              test={test}
              selected={selectedTestId === test.id}
              onSelect={() => onSelectTest(test.id)}
              hideSpec
            />
          </div>,
        );
      }
    }
  }

  return result;
}

function getNodeAggregateStatus(node: TreeNode): Status {
  const statuses: Status[] = [];
  collectNodeStatuses(node, statuses);
  return aggregateStatus(statuses);
}

function collectNodeStatuses(node: TreeNode, out: Status[]): void {
  for (const test of node.tests) {
    out.push(test.status);
  }
  for (const child of node.children.values()) {
    collectNodeStatuses(child, out);
  }
}

function StatusDot({ status }: { status: Status }) {
  const color =
    status === "FAILED" || status === "ERROR"
      ? "bg-red-400"
      : status === "PASSED"
        ? "bg-emerald-400"
        : "bg-blue-400 animate-pulse-dot";

  return <span className={`w-1.5 h-1.5 rounded-full shrink-0 mr-2 ${color}`} />;
}
