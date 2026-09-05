/// <reference lib="webworker" />

import { calculateFlow, type FlowInput, type FlowResult } from "../utils/flow-work";

self.onmessage = (message: MessageEvent<FlowInput>) => {
  try {
    self.postMessage({ graph: calculateFlow(message.data) } satisfies FlowResult);
  } catch (error) {
    self.postMessage({
      error: error instanceof Error ? error.message : "Flow calculation failed",
    } satisfies FlowResult);
  }
};
