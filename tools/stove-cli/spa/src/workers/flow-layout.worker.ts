/// <reference lib="webworker" />

import type { Span } from "../api/types";
import { applyDagreLayout, spansToTraceDag } from "../utils/flow";

self.onmessage = (message: MessageEvent<Span[]>) => {
  const graph = spansToTraceDag(message.data);
  self.postMessage({ nodes: applyDagreLayout(graph.nodes, graph.edges), edges: graph.edges });
};
