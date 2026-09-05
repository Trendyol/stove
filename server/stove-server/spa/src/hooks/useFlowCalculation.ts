import { useEffect, useRef, useState } from "react";
import type { FlowInput, FlowResult } from "../utils/flow-work";
import { LatestTask } from "../utils/latest-task";

export interface FlowWork {
  input: FlowInput;
  scope: string;
  start: number;
  end: number;
  total: number;
}

/** One persistent worker, one running calculation and one replaceable pending input. */
export function useFlowCalculation(work: FlowWork) {
  const [completed, setCompleted] = useState<{ result: FlowResult; work: FlowWork } | null>(null);
  const schedulerRef = useRef<LatestTask<FlowWork, FlowResult> | null>(null);
  useEffect(() => {
    const worker = new Worker(new URL("../workers/flow-layout.worker.ts", import.meta.url), {
      type: "module",
    });
    const scheduler = new LatestTask<FlowWork, FlowResult>(
      (work) => worker.postMessage(work.input),
      (result, work) => setCompleted({ result, work }),
    );
    schedulerRef.current = scheduler;
    worker.onmessage = (message: MessageEvent<FlowResult>) => scheduler.complete(message.data);
    worker.onerror = () => scheduler.complete({ error: "Flow calculation failed" });
    return () => {
      scheduler.dispose();
      schedulerRef.current = null;
      worker.terminate();
    };
  }, []);

  useEffect(() => {
    schedulerRef.current?.submit(work.scope, work);
  }, [work]);
  return completed?.work.scope === work.scope ? completed : null;
}
