public class ProfileConfigTest {
  @org.junit.Test public void agentIsInstalledButWaitsForRunIdentity() throws Exception {
    org.junit.Assert.assertEquals("true", System.getProperty("stove.profiling.enabled"));
    org.junit.Assert.assertEquals("profile-service", System.getProperty("stove.profiling.service"));
    org.junit.Assert.assertEquals("512k", System.getProperty("stove.profiling.alloc"));
    java.nio.file.Files.writeString(java.nio.file.Path.of("observed-interval"), System.getProperty("stove.profiling.interval"));
    org.junit.Assert.assertEquals("false", System.getProperty("pyroscope.agent.enabled"));
    org.junit.Assert.assertNull(System.getProperty("otel.javaagent.extensions"));
    Class<?> agent = Class.forName("io.pyroscope.javaagent.PyroscopeAgent");
    org.junit.Assert.assertEquals(false, agent.getMethod("isStarted").invoke(null));
  }
}
