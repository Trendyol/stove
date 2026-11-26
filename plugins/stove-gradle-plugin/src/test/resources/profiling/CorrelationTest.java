import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.pyroscope.http.Format;
import io.pyroscope.javaagent.*;
import io.pyroscope.javaagent.api.*;
import io.pyroscope.javaagent.config.Config;
import jdk.jfr.consumer.RecordingFile;
import org.junit.Test;
import java.nio.file.*;
import java.time.Instant;
import static org.junit.Assert.*;

public class CorrelationTest {
  private static volatile long work;

  @Test public void manualLifecycleCorrelatesRealSamplesAcrossRepeatedRuns() throws Exception {
    assertTrue(System.getProperty("otel.javaagent.extensions").contains("other-extension.jar,"));
    assertTrue(System.getProperty("otel.javaagent.extensions").endsWith("pyroscope-otel-javaagent-extension.jar"));
    assertEquals("false", System.getProperty("otel.pyroscope.start.profiling"));
    assertEquals("true", System.getProperty("otel.pyroscope.root.span.only"));
    assertFalse("The extension must wait for Stove's run identity", PyroscopeAgent.isStarted());
    for (int run = 0; run < 2; run++) {
      Capture capture = new Capture();
      Config config = new Config.Builder().setApplicationName("correlation-test")
        .setServerAddress("http://127.0.0.1:1").setFormat(Format.JFR)
        .setProfilingEvent(EventType.ITIMER).build();
      PyroscopeAgent.start(new PyroscopeAgent.Options.Builder(config)
        .setScheduler(capture).setExporter(capture).build());
      Span root = GlobalOpenTelemetry.getTracer("stove-test").spanBuilder("profiled-request")
        .setParent(Context.root()).startSpan();
      String spanId = root.getSpanContext().getSpanId();
      String traceId = root.getSpanContext().getTraceId();
      assertTrue(root.getSpanContext().isValid());
      try (Scope ignored = root.makeCurrent()) {
        burnCpu();
        Span child = GlobalOpenTelemetry.getTracer("stove-test").spanBuilder("child").startSpan();
        try (Scope childScope = child.makeCurrent()) { burnCpu(); }
        finally { child.end(); }
        burnCpu();
      } finally {
        root.end();
        PyroscopeAgent.stop();
      }
      assertFalse(PyroscopeAgent.isStarted());
      Path recording = Files.createTempFile("stove-correlated-", ".jfr");
      try {
        assertNotNull("Final recording was flushed", capture.snapshot);
        Files.write(recording, capture.snapshot.data);
        long id = Long.parseUnsignedLong(spanId, 16);
        long hi = Long.parseUnsignedLong(traceId.substring(0, 16), 16);
        long lo = Long.parseUnsignedLong(traceId.substring(16), 16);
        long matching = RecordingFile.readAllEvents(recording).stream()
          .filter(e -> e.getEventType().getName().equals("jdk.ExecutionSample"))
          .filter(e -> e.hasField("spanId") && e.getLong("spanId") == id)
          .filter(e -> e.getLong("traceIdHi") == hi && e.getLong("traceIdLo") == lo).count();
        assertTrue("Expected CPU samples tagged with the local root span and trace", matching > 0);
      } finally { Files.deleteIfExists(recording); }
    }
  }

  private static void burnCpu() {
    long until = System.nanoTime() + 300_000_000L;
    while (System.nanoTime() < until) work++;
  }

  private static class Capture implements ProfilingScheduler, Exporter {
    private ProfilerDelegate profiler;
    private Instant start;
    Snapshot snapshot;
    public void start(ProfilerDelegate value) {
      profiler = value;
      start = Instant.now();
      profiler.start();
    }
    public void stop() {
      if (profiler == null) return;
      profiler.stop();
      snapshot = profiler.dumpProfile(start, Instant.now());
      profiler = null;
    }
    public void export(Snapshot value) { snapshot = value; }
  }
}
