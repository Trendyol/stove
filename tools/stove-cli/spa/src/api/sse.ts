import { useEffect, useRef, useState } from "react";
import type { LiveDashboardEvent } from "./types";

interface UseSSEOptions {
  onEvent: (event: LiveDashboardEvent) => void;
  onGap?: (event: LiveDashboardEvent) => void;
  onReconnect?: () => void;
  onDisconnect?: () => void;
}

export function useSSE({ onEvent, onGap, onReconnect, onDisconnect }: UseSSEOptions) {
  const callbacksRef = useRef({ onEvent, onGap, onReconnect, onDisconnect });
  const lastSeqRef = useRef<number | null>(null);
  const hasConnectedRef = useRef(false);
  const openRef = useRef(false);
  const [connected, setConnected] = useState(false);

  callbacksRef.current = { onEvent, onGap, onReconnect, onDisconnect };

  useEffect(() => {
    let disposed = false;
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
    let source: EventSource | null = null;

    function connect() {
      if (disposed) {
        return;
      }

      source = new EventSource("/api/v1/events/stream");

      source.onopen = () => {
        const isReconnect = hasConnectedRef.current;
        hasConnectedRef.current = true;
        openRef.current = true;
        setConnected(true);
        if (isReconnect) {
          lastSeqRef.current = null;
          callbacksRef.current.onReconnect?.();
        }
      };

      source.onmessage = (message) => {
        try {
          const event: LiveDashboardEvent = JSON.parse(message.data);
          if (
            typeof event.seq !== "number" ||
            typeof event.run_id !== "string" ||
            typeof event.event_type !== "string"
          ) {
            return;
          }

          if (lastSeqRef.current !== null && event.seq !== lastSeqRef.current + 1) {
            callbacksRef.current.onGap?.(event);
          }
          lastSeqRef.current = event.seq;
          callbacksRef.current.onEvent(event);
        } catch {
          // Ignore malformed events
        }
      };

      source.onerror = () => {
        source?.close();
        source = null;
        if (openRef.current) {
          openRef.current = false;
          setConnected(false);
          callbacksRef.current.onDisconnect?.();
        }
        if (!disposed) {
          reconnectTimer = setTimeout(connect, 3000);
        }
      };
    }

    connect();

    return () => {
      disposed = true;
      if (reconnectTimer != null) {
        clearTimeout(reconnectTimer);
      }
      openRef.current = false;
      setConnected(false);
      source?.close();
    };
  }, []);

  return { connected };
}
