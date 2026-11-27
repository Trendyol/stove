public class OptOutTest {
  @org.junit.Test public void profilingAndTracingRemainAvailable() throws Exception {
    org.junit.Assert.assertEquals("true", System.getProperty("stove.profiling.enabled"));
    org.junit.Assert.assertEquals("otlp", System.getProperty("otel.traces.exporter"));
    org.junit.Assert.assertNull(System.getProperty("otel.javaagent.extensions"));
    org.junit.Assert.assertEquals(false, Class.forName("io.pyroscope.javaagent.PyroscopeAgent").getMethod("isStarted").invoke(null));
  }
}
