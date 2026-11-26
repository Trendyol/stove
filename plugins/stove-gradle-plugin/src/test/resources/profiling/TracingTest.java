public class TracingTest {
  @org.junit.Test public void traceWithoutProfiling() throws Exception {
    java.nio.file.Files.writeString(java.nio.file.Path.of("observed-service"), System.getProperty("otel.service.name"));
    org.junit.Assert.assertTrue(java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()
        .stream().anyMatch(arg -> arg.startsWith("-javaagent:") && arg.contains("opentelemetry")));
    org.junit.Assert.assertNull(System.getProperty("stove.profiling.enabled"));
    org.junit.Assert.assertNull(System.getProperty("otel.javaagent.extensions"));
  }
}
