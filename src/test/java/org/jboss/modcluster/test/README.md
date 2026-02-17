# ModCluster Tests - Real Production Tests

## Purpose

These tests verify **mod_cluster protocol and infrastructure** behavior, not application-specific functionality.

## What These Tests Verify

### ✅ Mod-Cluster Infrastructure
- Worker registration via MCMP protocol
- Load balancing distribution algorithms
- Failover behavior and session migration
- Health checks and status updates
- Configuration changes and their effects
- SSL/TLS between components
- Context lifecycle management

### ❌ NOT Testing
- Application-specific business logic
- JSP/Servlet functionality
- Your custom application behavior

## Test Organization

### `/cli/` - Management & Configuration
Tests for mod_cluster subsystem management, configuration validation, and MCMP protocol operations.

### `/failover/` - Failover Scenarios
Tests for worker failure detection, session failover, and graceful/ungraceful shutdown behavior.

### `/loadbalancing/` - Load Distribution
Tests for load balancing algorithms, request distribution patterns, and worker capacity management.

### `/ssl/` - Security & TLS
Tests for SSL/TLS between balancer and workers, certificate validation, and encrypted communication.

### `/configuration/` - Dynamic Configuration
Tests for runtime reconfiguration, hot deployment, and configuration change propagation.

### `/context/` - Context Lifecycle
Tests for context enable/disable, exclusion patterns, and deployment registration.

### `/session/` - Session Management
Tests for session affinity, timeout handling, and cookie configuration.

### `/integration/` - End-to-End Scenarios
Tests for complex multi-worker scenarios and performance under load.

## Test Application (demo.war)

A minimal application used **only** to:
- ✅ Verify worker is responding via balancer
- ✅ Identify which worker handled the request
- ✅ Enable session affinity testing
- ❌ NOT the focus of testing

##Writing New Tests

### Test Template

```java
@ExtendWith({ModClusterTestExtension.class, SoftAssertionsExtension.class})
public class MyModClusterTest {

    @InjectSoftAssertions
    private SoftAssertions softly;

    @Test
    public void testModClusterBehavior(TestCluster cluster, HttpClient client) throws Exception {
        // Start infrastructure
        cluster.startWorkers(2);

        // Test mod_cluster behavior (not app behavior!)
        // Example: Verify load distribution
        // Example: Test failover mechanism
        // Example: Validate configuration changes

        // Assertions about mod_cluster behavior
        softly.assertThat(...)
            .as("Describe what mod_cluster should do")
            .isTrue();
    }
}
```

### Focus on Mod-Cluster, Not Applications

**✅ Good Test** - Tests mod_cluster:
```java
@Test
public void testWorkerRegistersWithBalancer(TestCluster cluster) throws Exception {
    cluster.startWorkers(1);
    WildFlyContainer worker = cluster.getWorker1();

    // Verify worker registered via MCMP
    ModelNode proxyInfo = getProxyInfo(worker);
    softly.assertThat(proxyInfo.hasDefined("Node"))
        .as("Worker should register as a node with balancer")
        .isTrue();
}
```

**❌ Bad Test** - Tests application:
```java
@Test
public void testApplicationReturnsCorrectData(TestCluster cluster, HttpClient client) {
    // This tests YOUR application, not mod_cluster!
    HttpResponse response = client.get(url + "/myapp/data");
    softly.assertThat(response.getBody()).contains("expected data");
}
```

### Real-World Test Scenarios

**1. Worker Registration**
```java
@Test
public void testWorkerAutoRegistration(TestCluster cluster) {
    // Verify MCMP registration happens automatically
    // Check balancer receives STATUS messages
    // Validate worker appears in proxy info
}
```

**2. Load Distribution**
```java
@Test
public void testRoundRobinDistribution(TestCluster cluster, HttpClient client) {
    // Verify requests distribute according to algorithm
    // Not testing what app returns, but WHICH worker gets the request
}
```

**3. Failover**
```java
@Test
public void testSessionSurvivesWorkerFailure(TestCluster cluster, HttpClient client) {
    // Verify mod_cluster failover mechanism
    // Test session replication (WildFly feature)
    // Validate balancer removes failed worker
}
```

**4. Configuration**
```java
@Test
public void testDynamicProxyConfigUpdate(TestCluster cluster) {
    // Verify configuration changes take effect without restart
    // Test MCMP adapts to config changes
    // Validate worker behavior changes
}
```

## Key Principles

1. **Test Infrastructure, Not Apps**
   - Focus: Does mod_cluster load balance correctly?
   - Not: Does my app return correct data?

2. **Test Protocol Behavior**
   - Focus: Does MCMP registration work?
   - Not: Does JSP render correctly?

3. **Test Failure Scenarios**
   - Focus: Does failover work when worker dies?
   - Not: Does app handle null values?

4. **Test Configuration**
   - Focus: Do config changes affect routing?
   - Not: Does app configuration work?

## Using the Test Application

The demo.war is a tool, not the test subject:

```java
// ✅ Using demo.war correctly
HttpResponse response = client.get(balancerUrl + "/demo");
String worker = extractWorker(response);  // Which worker?
softly.assertThat(worker).isEqualTo("worker1");  // Testing routing!

// ❌ Using demo.war incorrectly
HttpResponse response = client.get(balancerUrl + "/demo");
softly.assertThat(response.getBody())
    .contains("Expected content");  // Testing app, not mod_cluster!
```

## Deployment Testing

When testing deployments, focus on mod_cluster integration:

```java
@Test
public void testContextRegistersWithBalancer(TestCluster cluster) {
    cluster.startWorkers(1);
    WildFlyContainer worker = cluster.getWorker1();

    // Deploy application
    worker.deploy(new File("myapp.war"));

    // ✅ Test: Does mod_cluster register the new context?
    ModelNode contexts = getRegisteredContexts(worker);
    softly.assertThat(contexts.asList())
        .as("Deployed context should register with mod_cluster")
        .anyMatch(ctx -> ctx.asString().contains("/myapp"));

    // ❌ Don't test: Does the application work correctly?
}
```

## Using Creaper

All tests use Creaper for WildFly management:

```java
@Test
public void testCreaperOperations(TestCluster cluster) throws Exception {
    cluster.startWorkers(1);
    WildFlyContainer worker = cluster.getWorker1();

    // Read mod_cluster attribute
    ModelNode statusInterval = worker.readModClusterAttribute("status-interval");

    // Write attribute
    worker.writeModClusterAttribute("status-interval", 20);

    // Verify
    ModelNode newValue = worker.readModClusterAttribute("status-interval");
    softly.assertThat(newValue.asInt()).isEqualTo(20);
}
```

## Documentation

- [CREAPER.md](../../../../../../../../CREAPER.md) - Managing WildFly with Creaper
- [TESTING.md](../../../../../../../../TESTING.md) - General testing guide
- [TROUBLESHOOTING.md](../../../../../../../../TROUBLESHOOTING.md) - Common issues

## Summary

**These are real mod_cluster tests** that verify:
- ✅ Load balancing infrastructure
- ✅ MCMP protocol behavior
- ✅ Failover mechanisms
- ✅ Configuration management
- ✅ Worker lifecycle
- ✅ Session affinity

**Not testing**:
- ❌ Your application logic
- ❌ JSP/Servlet functionality
- ❌ Business rules
- ❌ UI behavior

**Focus**: "Does mod_cluster work correctly?" not "Does my app work correctly?"
