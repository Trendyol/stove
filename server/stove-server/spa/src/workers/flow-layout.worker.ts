/// <reference lib="webworker" />

import { calculateFlow, type FlowInput } from "../utils/flow-work";

self.onmessage = (message: MessageEvent<FlowInput>) => {
  try {
    self.postMessage({ graph: calculateFlow(message.data) });
  } catch (error) {
    self.postMessage({ error: error instanceof Error ? error.message : "Flow calculation failed" });
  }
};
