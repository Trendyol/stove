/// <reference lib="webworker" />

import { describeJsonValue, filterJsonByQuery, parseJsonDeep } from "../utils/json";
import { getKafkaSnapshotMetrics, hasDetailedSnapshotState } from "../utils/snapshot-state";
import type { SnapshotWorkerRequest, SnapshotWorkerResponse } from "./snapshot-state.protocol";

let parsedState: unknown | null = null;

self.onmessage = (message: MessageEvent<SnapshotWorkerRequest>) => {
  const request = message.data;
  if (request.kind === "load") {
    parsedState = parseJsonDeep(request.stateJson);
    const detailed = hasDetailedSnapshotState({ state_json: request.stateJson }, parsedState);
    const metrics =
      request.system === "Kafka"
        ? getKafkaSnapshotMetrics({ state_json: request.stateJson }, parsedState)
        : [];
    const response: SnapshotWorkerResponse =
      parsedState === null
        ? { kind: "raw", value: request.stateJson, detailed, metrics }
        : {
            kind: "structured",
            value: parsedState,
            description: describeJsonValue(parsedState),
            detailed,
            metrics,
          };
    self.postMessage(response);
    return;
  }

  const result =
    parsedState === null
      ? { filteredValue: null, matchCount: 0 }
      : filterJsonByQuery(parsedState, request.query);
  const response: SnapshotWorkerResponse = {
    kind: "search-result",
    requestId: request.requestId,
    ...result,
  };
  self.postMessage(response);
};
