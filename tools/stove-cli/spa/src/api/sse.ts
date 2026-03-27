import { useCallback, useEffect, useRef } from "react";

interface SseEvent {
  run_id: string;
  event_type: string;
}

export function useSSE(onEvent: (event: SseEvent) => void) {
  const callbackRef = useRef(onEvent);
  callbackRef.current = onEvent;

  const connect = useCallback(() => {
    const source = new EventSource("/api/v1/events/stream");

    source.onmessage = (e) => {
      try {
        const event: SseEvent = JSON.parse(e.data);
        callbackRef.current(event);
      } catch {
        // Ignore malformed events
      }
    };

    source.onerror = () => {
      source.close();
      // Reconnect after 3 seconds
      setTimeout(connect, 3000);
    };

    return source;
  }, []);

  useEffect(() => {
    const source = connect();
    return () => source.close();
  }, [connect]);
}
