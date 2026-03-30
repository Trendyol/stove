import { useEffect, useRef } from "react";

interface SseEvent {
  run_id: string;
  event_type: string;
}

export function useSSE(onEvent: (event: SseEvent) => void) {
  const callbackRef = useRef(onEvent);
  callbackRef.current = onEvent;

  useEffect(() => {
    let disposed = false;
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
    let source: EventSource | null = null;

    function connect() {
      if (disposed) return;
      source = new EventSource("/api/v1/events/stream");

      source.onmessage = (e) => {
        try {
          const event: SseEvent = JSON.parse(e.data);
          callbackRef.current(event);
        } catch {
          // Ignore malformed events
        }
      };

      source.onerror = () => {
        source?.close();
        source = null;
        if (!disposed) {
          reconnectTimer = setTimeout(connect, 3000);
        }
      };
    }

    connect();

    return () => {
      disposed = true;
      if (reconnectTimer != null) clearTimeout(reconnectTimer);
      source?.close();
    };
  }, []);
}
