import { useState } from "react";

interface JsonTreeProps {
  value: unknown;
  defaultExpandedDepth?: number;
  searchQuery?: string;
}

export function JsonTree({ value, defaultExpandedDepth = 1, searchQuery = "" }: JsonTreeProps) {
  return (
    <div className="rounded-lg border border-stove-border bg-stove-base p-3 font-mono text-xs">
      <JsonTreeNode
        value={value}
        depth={0}
        label="state"
        defaultExpandedDepth={defaultExpandedDepth}
        searchQuery={searchQuery}
      />
    </div>
  );
}

interface JsonTreeNodeProps {
  value: unknown;
  depth: number;
  label: string;
  defaultExpandedDepth: number;
  searchQuery: string;
}

function JsonTreeNode({
  value,
  depth,
  label,
  defaultExpandedDepth,
  searchQuery,
}: JsonTreeNodeProps) {
  const expandable = isExpandable(value);
  const [expanded, setExpanded] = useState(depth < defaultExpandedDepth);
  const hasActiveSearch = searchQuery.trim().length > 0;
  const effectiveExpanded = hasActiveSearch || expanded;

  if (!expandable) {
    return (
      <div className="leading-6" style={{ marginLeft: depth * 14 }}>
        <span className="text-[var(--stove-text-secondary)]">
          <HighlightedText text={label} query={searchQuery} />
          {": "}
        </span>
        <JsonPrimitive value={value} searchQuery={searchQuery} />
      </div>
    );
  }

  const children = getChildren(value);
  const opening = Array.isArray(value) ? "[" : "{";
  const closing = Array.isArray(value) ? "]" : "}";
  const summary = Array.isArray(value)
    ? `${children.length} item${children.length === 1 ? "" : "s"}`
    : `${children.length} key${children.length === 1 ? "" : "s"}`;

  return (
    <div style={{ marginLeft: depth * 14 }}>
      <button
        type="button"
        className="flex w-full items-center gap-2 rounded px-1 py-1 text-left leading-6 text-[var(--stove-text)] hover:bg-[var(--stove-hover)]"
        onClick={() => setExpanded(!expanded)}
      >
        <span className="w-3 text-[var(--stove-text-secondary)]">
          {effectiveExpanded ? "v" : ">"}
        </span>
        <span className="text-[var(--stove-text-secondary)]">
          <HighlightedText text={label} query={searchQuery} />
          {":"}
        </span>
        <span className="text-[var(--stove-text)]">{opening}</span>
        <span className="text-[var(--stove-text-muted)]">{summary}</span>
        {!effectiveExpanded && (
          <span className="text-[var(--stove-text)]">
            {renderCollapsedPreview(children, Array.isArray(value))}
          </span>
        )}
        <span className="text-[var(--stove-text)]">{!effectiveExpanded ? closing : ""}</span>
      </button>

      {effectiveExpanded && (
        <>
          {children.length === 0 ? (
            <div
              className="leading-6 text-[var(--stove-text-muted)]"
              style={{ marginLeft: (depth + 1) * 14 }}
            >
              empty
            </div>
          ) : (
            children.map(([childLabel, childValue]) => (
              <JsonTreeNode
                key={`${label}-${childLabel}`}
                value={childValue}
                depth={depth + 1}
                label={childLabel}
                defaultExpandedDepth={defaultExpandedDepth}
                searchQuery={searchQuery}
              />
            ))
          )}
          <div
            className="leading-6 text-[var(--stove-text)]"
            style={{ marginLeft: depth * 14 + 20 }}
          >
            {closing}
          </div>
        </>
      )}
    </div>
  );
}

function JsonPrimitive({ value, searchQuery }: { value: unknown; searchQuery: string }) {
  if (typeof value === "string") {
    return (
      <span className="break-words text-[var(--stove-green)]">
        <HighlightedText text={JSON.stringify(value)} query={searchQuery} />
      </span>
    );
  }

  if (typeof value === "number") {
    return (
      <span className="text-[var(--stove-amber)]">
        <HighlightedText text={String(value)} query={searchQuery} />
      </span>
    );
  }

  if (typeof value === "boolean") {
    return (
      <span className="text-[var(--stove-blue)]">
        <HighlightedText text={String(value)} query={searchQuery} />
      </span>
    );
  }

  if (value === null) {
    return (
      <span className="italic text-[var(--stove-text-muted)]">
        <HighlightedText text="null" query={searchQuery} />
      </span>
    );
  }

  return (
    <span className="text-[var(--stove-text)]">
      <HighlightedText text={String(value)} query={searchQuery} />
    </span>
  );
}

function isExpandable(value: unknown): value is Record<string, unknown> | unknown[] {
  return Array.isArray(value) || (typeof value === "object" && value !== null);
}

function getChildren(value: Record<string, unknown> | unknown[]): Array<[string, unknown]> {
  if (Array.isArray(value)) {
    return value.map((item, index) => [`[${index}]`, item]);
  }

  return Object.entries(value);
}

function renderCollapsedPreview(children: Array<[string, unknown]>, isArray: boolean): string {
  if (children.length === 0) {
    return "";
  }

  const preview = children
    .slice(0, 3)
    .map(([label]) => label)
    .join(", ");
  const suffix = children.length > 3 ? ", ..." : "";
  return isArray ? `${preview}${suffix}` : `${preview}${suffix}`;
}

function HighlightedText({ text, query }: { text: string; query: string }) {
  const normalizedQuery = query.trim();
  if (!normalizedQuery) {
    return text;
  }

  const parts = splitByQuery(text, normalizedQuery);
  return parts.map((part) =>
    part.match ? (
      <mark
        key={`${part.start}-${part.match}`}
        className="rounded bg-[rgba(250,204,21,0.18)] px-0.5 text-inherit"
      >
        {part.text}
      </mark>
    ) : (
      <span key={`${part.start}-${part.match}`}>{part.text}</span>
    ),
  );
}

function splitByQuery(
  text: string,
  query: string,
): Array<{ text: string; match: boolean; start: number }> {
  const escapedQuery = escapeRegExp(query);
  const regex = new RegExp(`(${escapedQuery})`, "gi");
  let offset = 0;

  return text
    .split(regex)
    .filter((part) => part.length > 0)
    .map((part) => {
      const segment = {
        text: part,
        match: part.toLowerCase() === query.toLowerCase(),
        start: offset,
      };
      offset += part.length;
      return segment;
    });
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
