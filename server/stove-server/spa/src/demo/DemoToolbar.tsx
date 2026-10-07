import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { evidencePath, navigateTo } from "../utils/location";
import { demo } from "./bootstrap";
import "./demo.css";

export default function DemoToolbar() {
  const queryClient = useQueryClient();
  const [ready, setReady] = useState(false);
  const [replaying, setReplaying] = useState(false);
  const [error, setError] = useState<string>();
  useEffect(() => {
    let active = true;
    let unsubscribe: (() => void) | undefined;
    demo
      .then((backend) => {
        if (!active) return;
        setReady(true);
        unsubscribe = backend.subscribe(() => setReplaying(backend.replaying));
      })
      .catch((cause) => {
        if (active) setError(String(cause));
      });
    return () => {
      active = false;
      unsubscribe?.();
    };
  }, []);
  async function reset() {
    const backend = await demo;
    await queryClient.cancelQueries();
    backend.reset();
    setReplaying(false);
    navigateTo("/");
    await queryClient.resetQueries();
  }
  async function replay() {
    const result = (await demo).replay();
    if (result) {
      setReplaying(true);
      navigateTo(`${evidencePath(result.runId, result.testId)}?tab=timeline`);
    }
  }
  return (
    <aside className="stove-demo-toolbar" aria-label="Interactive demo controls">
      <div className="stove-demo-intro">
        <strong>Interactive demo</strong>
        <span>
          {error ??
            (ready
              ? "Fictional test runs. Explore every tab; changes stay in this page."
              : "Preparing sample runs…")}
        </span>
      </div>
      <div className="stove-demo-actions">
        <button type="button" disabled={!ready || replaying} onClick={() => void replay()}>
          {replaying ? "Replaying test…" : "Replay a test"}
        </button>
        <button type="button" disabled={!ready} onClick={() => void reset()}>
          Reset demo
        </button>
        <a href="https://trendyol.github.io/stove/getting-started/">Try Stove ↗</a>
      </div>
    </aside>
  );
}
