import { useMemo, useState } from "react";
import type { ProfileSeries } from "../api/types";
import { formatProfileValue, frameKey, type ProfileFrame, shortMethod } from "../utils/profiles";

const FRAME_HEIGHT = 26;
const MAX_DEPTH = 128;
const MIN_WIDTH_PERCENT = 0.15;
type FlameNode = {
  name: string;
  frame?: ProfileFrame;
  path: string[];
  value: number;
  children: Map<string, FlameNode>;
};
type FlameCell = { node: FlameNode; x: number; width: number; depth: number };

export function ProfileFlameGraph({
  series,
  search = "",
  selectedFrame,
}: {
  series: ProfileSeries;
  search?: string;
  selectedFrame?: ProfileFrame;
}) {
  const root = useMemo(() => buildTree(series), [series]);
  const [path, setPath] = useState<string[]>([]);
  const [hover, setHover] = useState<{ root: FlameNode; node: FlameNode }>();
  const ancestors = [root];
  for (const key of path) {
    const child = ancestors[ancestors.length - 1].children.get(key);
    if (!child) {
      ancestors.splice(1);
      break;
    }
    ancestors.push(child);
  }
  const active = ancestors[ancestors.length - 1];
  const cells = layoutTree(active);
  const height = (Math.max(0, ...cells.map((c) => c.depth)) + 1) * FRAME_HEIGHT;
  const needle = search.trim().toLowerCase();
  const matches = (node: FlameNode) =>
    selectedFrame
      ? !!node.frame && frameKey(node.frame) === frameKey(selectedFrame)
      : `${node.name} ${node.frame?.file ?? ""}`.toLowerCase().includes(needle);
  const filtering = !!needle || !!selectedFrame;
  const matchCount = cells.filter((c) => matches(c.node) && c.node !== root).length;
  const inspected = hover?.root === root ? hover.node : active;
  const zoomTo = (node: FlameNode) => {
    setPath(node.path);
    setHover(undefined);
  };
  return (
    <>
      <div className="profile-graph-toolbar">
        <nav className="profile-zoom-trail" aria-label="Zoom path">
          <button type="button" onClick={() => zoomTo(root)} disabled={active === root}>
            All stacks
          </button>
          {active !== root && <span title={active.name}>/ {shortMethod(active.name)}</span>}
        </nav>
        <div>
          <button
            type="button"
            className="profile-button"
            disabled={active === root}
            onClick={() => zoomTo(ancestors[ancestors.length - 2])}
          >
            Zoom out
          </button>
          <button
            type="button"
            className="profile-button"
            disabled={active === root}
            onClick={() => zoomTo(root)}
          >
            Reset zoom
          </button>
        </div>
      </div>
      {filtering && (
        <p className="profile-match-status" role="status">
          {matchCount} matching frames in this view
          {selectedFrame && <> · {shortMethod(selectedFrame.name)}</>}
          {active !== root && ". Reset zoom to search all displayed stacks."}
        </p>
      )}
      <section
        className="profile-flame-scroll"
        aria-label="Flame graph. Callers at the top; select a frame to zoom."
      >
        <div className="profile-flame" style={{ height }}>
          {cells.map((c) => (
            <button
              type="button"
              key={JSON.stringify(c.node.path)}
              aria-label={c.node.name}
              onClick={() => zoomTo(c.node)}
              onMouseEnter={() => setHover({ root, node: c.node })}
              onMouseLeave={() => setHover(undefined)}
              onFocus={() => setHover({ root, node: c.node })}
              onBlur={() => setHover(undefined)}
              data-match={filtering && matches(c.node)}
              data-dimmed={filtering && !matches(c.node)}
              style={{
                left: `${c.x}%`,
                width: `${c.width}%`,
                top: c.depth * FRAME_HEIGHT,
                background: frameColor(c.node.name),
              }}
              title={`${c.node.name}: ${c.node.value.toLocaleString()} ${series.unit}`}
            >
              {shortMethod(c.node.name)}
            </button>
          ))}
        </div>
      </section>
      <div className="profile-frame-inspector" aria-live="polite">
        <strong title={inspected.name}>{inspected.name}</strong>
        <span>
          {formatProfileValue(inspected.value, series.unit)}{" "}
          <b>
            · {series.total > 0 ? ((inspected.value / series.total) * 100).toFixed(1) : "0"}% of
            total
          </b>
        </span>
        {inspected.frame?.file && (
          <small>
            {inspected.frame.file}
            {inspected.frame.line > 0 && `:${inspected.frame.line}`}
          </small>
        )}
      </div>
      <div className="profile-graph-legend">
        <span>
          <i /> Width = sampled cost · color distinguishes methods
        </span>
        <span>Callers at top · scroll for deeper stacks</span>
      </div>
      {cells.some((c) => c.depth === MAX_DEPTH && c.node.children.size > 0) && (
        <p className="profile-graph-note">
          Deeper frames are hidden at this zoom. Select a frame to explore further.
        </p>
      )}
    </>
  );
}

function frameColor(name: string): string {
  let hash = 0;
  for (const char of name) hash = (hash * 31 + char.charCodeAt(0)) | 0;
  return `hsl(${18 + Math.abs(hash % 32)} 78% ${64 + Math.abs(hash % 12)}%)`;
}

function buildTree(series: ProfileSeries): FlameNode {
  const root: FlameNode = { name: "All displayed stacks", path: [], value: 0, children: new Map() };
  for (const stack of series.stacks) {
    if (stack.value <= 0) continue;
    let node = root;
    node.value += stack.value;
    for (const frame of stack.frames) {
      const key = frameKey(frame);
      let child = node.children.get(key);
      if (!child) {
        child = {
          name: frame.name,
          frame,
          path: [...node.path, key],
          value: 0,
          children: new Map(),
        };
        node.children.set(key, child);
      }
      child.value += stack.value;
      node = child;
    }
  }
  return root;
}

function layoutTree(root: FlameNode): FlameCell[] {
  const cells: FlameCell[] = [];
  function layout(node: FlameNode, x: number, width: number, depth: number) {
    if (depth > MAX_DEPTH || width < MIN_WIDTH_PERCENT || node.value <= 0) return;
    cells.push({ node, x, width, depth });
    let offset = x;
    for (const child of [...node.children.values()].sort((a, b) => a.name.localeCompare(b.name))) {
      const w = (width * child.value) / node.value;
      layout(child, offset, w, depth + 1);
      offset += w;
    }
  }
  layout(root, 0, 100, 0);
  return cells;
}
