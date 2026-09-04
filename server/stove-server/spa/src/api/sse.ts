import { useEffect, useRef, useState } from "react";
import { parseLiveDashboardEvent } from "./live-event";
import type { LiveDashboardEvent } from "./types";

const MAX_QUEUED_EVENTS = 2_000;

interface UseSSEOptions {
  onEvents: (events: readonly LiveDashboardEvent[]) => void;
  onGap?: (event: LiveDashboardEvent) => void;
  onOverflow?: () => void;
  onConnect?: () => void;
  onReconnect?: () => void;
  onDisconnect?: () => void;
}

export function useSSE({
  onEvents,
  onGap,
  onOverflow,
  onConnect,
  onReconnect,
  onDisconnect,
}: UseSSEOptions) {
  const callbacksRef = useRef({
    onEvents,
    onGap,
    onOverflow,
    onConnect,
    onReconnect,
    onDisconnect,
  });
  const lastSeqRef = useRef<number | null>(null);
  const hasConnectedRef = useRef(false);
  const openRef = useRef(false);
  const [connected, setConnected] = useState(false);

  callbacksRef.current = {
    onEvents,
    onGap,
    onOverflow,
    onConnect,
    onReconnect,
    onDisconnect,
  };

  useEffect(() => {
    let disposed = false;
    let source: EventSource | null = null;
    let frame: number | null = null;
    let queuedEvents: LiveDashboardEvent[] = [];
    let overflowed = false;

    function flushEvents() {
      frame = null;
      if (disposed) return;
      if (overflowed) {
        overflowed = false;
        queuedEvents = [];
        callbacksRef.current.onOverflow?.();
        return;
      }
      if (queuedEvents.length === 0) return;
      const events = queuedEvents;
      queuedEvents = [];
      callbacksRef.current.onEvents(events);
    }

    function enqueue(event: LiveDashboardEvent) {
      if (overflowed) return;
      if (queuedEvents.length >= MAX_QUEUED_EVENTS) {
        queuedEvents = [];
        overflowed = true;
      } else {
        queuedEvents.push(event);
      }
      if (frame === null) {
        frame = window.requestAnimationFrame(flushEvents);
      }
    }

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
        callbacksRef.current.onConnect?.();
        if (isReconnect) {
          callbacksRef.current.onReconnect?.();
        }
      };

      source.onmessage = (message) => {
        const event = parseLiveDashboardEvent(message.data);
        if (!event) return;

        if (lastSeqRef.current !== null && event.seq !== lastSeqRef.current + 1) {
          callbacksRef.current.onGap?.(event);
        }
        lastSeqRef.current = event.seq;
        enqueue(event);
      };

      source.onerror = () => {
        if (openRef.current) {
          openRef.current = false;
          setConnected(false);
          callbacksRef.current.onDisconnect?.();
        }
        // Native EventSource reconnection preserves Last-Event-ID, allowing the
        // server to replay durable events committed while this connection was down.
      };
    }

    connect();

    return () => {
      disposed = true;
      if (frame !== null) window.cancelAnimationFrame(frame);
      queuedEvents = [];
      overflowed = false;
      openRef.current = false;
      setConnected(false);
      source?.close();
    };
  }, []);

  return { connected };
}
