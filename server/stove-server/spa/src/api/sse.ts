import { useEffect, useRef, useState } from "react";
import { BoundedEventQueue } from "../utils/bounded-event-queue";
import { parseLiveDashboardEvent } from "./live-event";
import type { LiveDashboardEvent } from "./types";

const MAX_QUEUED_EVENTS = 2_000;
const MAX_QUEUED_BYTES = 8 * 1024 * 1024;

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
    const queue = new BoundedEventQueue<LiveDashboardEvent>(MAX_QUEUED_EVENTS, MAX_QUEUED_BYTES);
    const encoder = new TextEncoder();
    let reconnectTimer: number | null = null;

    function flushEvents() {
      frame = null;
      if (disposed) return;
      const batch = queue.drain();
      if (batch.overflowed) {
        callbacksRef.current.onOverflow?.();
      } else if (batch.items.length > 0) {
        callbacksRef.current.onEvents(batch.items);
      }
    }

    function enqueue(event: LiveDashboardEvent, bytes: number) {
      queue.push(event, bytes);
      if (frame === null) frame = window.requestAnimationFrame(flushEvents);
    }

    function connect() {
      if (disposed) {
        return;
      }

      const after = lastSeqRef.current;
      source = new EventSource(`/api/v1/events/stream${after === null ? "" : `?after=${after}`}`);
      source.addEventListener("resync", (message) => {
        let watermark: unknown;
        try {
          watermark = JSON.parse((message as MessageEvent<string>).data).watermark;
        } catch {
          return;
        }
        if (typeof watermark !== "number" || !Number.isSafeInteger(watermark) || watermark < 0)
          return;
        source?.close();
        queue.clear();
        lastSeqRef.current = watermark;
        openRef.current = false;
        setConnected(false);
        callbacksRef.current.onOverflow?.();
        reconnectTimer = window.setTimeout(connect, 250);
      });

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

        // Global IDs can have legitimate gaps (filtered streams and rolled-back
        // PostgreSQL transactions). The server explicitly signals missing history.
        if (lastSeqRef.current !== null && event.seq <= lastSeqRef.current) return;
        lastSeqRef.current = event.seq;
        enqueue(event, encoder.encode(message.data).byteLength);
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
      queue.clear();
      openRef.current = false;
      setConnected(false);
      source?.close();
      if (reconnectTimer !== null) window.clearTimeout(reconnectTimer);
    };
  }, []);

  return { connected };
}
