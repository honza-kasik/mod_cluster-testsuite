# ModCluster Test Coverage Plan

## Current Coverage (17 tests)

### ✅ Already Implemented

| Test Class | Tests | Coverage |
|------------|-------|----------|
| **CliManagementTest** | 6 | CLI operations, configuration read/write, deployment status |
| **DynamicReconfTest** | 3 | Dynamic worker registration, configuration changes, unregistration |
| **StickySessionTest** | 2 | Session affinity, sticky sessions, multi-client scenarios |
| **LoadBalancingGroupFailoverTest** | 2 | Load distribution, automatic failover |
| **SSLTest** | 3 | HTTPS connectivity, SSL session persistence |
| **DebugTest** | 1 | Diagnostic verification of setup |

---

## Gap Analysis vs noe-tests (64 test files)

### 🔴 Critical Missing Coverage (High Priority)

#### 1. Advanced Failover Tests
**From noe-tests:**
- `FailoverTest.groovy` - General failover scenarios beyond sticky sessions
- `DeterministicFailoverTest.groovy` - Predictable failover based on configuration
- `SmoothFailoverTest.groovy` - Graceful failover without dropped requests
- `FailoverUnregisterTest.groovy` - Failover during worker unregistration
- `FailoverSettingsTest.groovy` - Testing various failover configuration options

**Missing in our tests:**
- Failover with active sessions
- Failover to specific workers (deterministic)
- Failover during heavy load
- Session migration validation
- Graceful vs hard failover comparison

**Recommended new test class:**
```java
AdvancedFailoverTest.java
- testFailoverWithActiveSessions()
- testDeterministicFailover()
- testGracefulFailoverNoDroppedRequests()
- testFailoverDuringUnregistration()
- testFailoverUnderLoad()
```

---

#### 2. Context Lifecycle Management
**From noe-tests:**
- `AutoEnableContextsTest.groovy` - Auto-enable contexts on deployment
- `ExcludedContextsTest.groovy` - Context exclusion patterns
- `DisableAndSTOPappTest.groovy` - DISABLE-APP and STOP-APP operations
- `StopContextTimeoutTest.groovy` - Context stop timeout behavior
- `ManyContextsTest.groovy` - Multiple contexts per worker

**Missing in our tests:**
- Automatic context registration
- Context exclusion/inclusion rules
- Context disable/enable operations
- Context lifecycle (deploy, undeploy, redeploy)
- Multiple contexts routing

**Recommended new test class:**
```java
ContextLifecycleTest.java
- testAutoEnableContexts()
- testExcludedContextsNotRegistered()
- testDisableContext()
- testStopContext()
- testMultipleContextsPerWorker()
- testContextRedeployment()
```

---

#### 3. Load Calculation & Metrics
**From noe-tests:**
- `LoadCalculationTest.groovy` - Load metrics calculation
- `LoadCalculationNoHttpdTest.groovy` - Load calculation without httpd
- `InitialLoadTest.groovy` - Initial load reporting

**Missing in our tests:**
- Load factor calculation
- Custom load metrics
- Load balancing based on metrics
- Load reporting verification
- Initial vs runtime load

**Recommended new test class:**
```java
LoadMetricsTest.java
- testLoadFactorCalculation()
- testCustomLoadMetrics()
- testLoadBasedRouting()
- testInitialLoadReporting()
- testDynamicLoadAdjustment()
```

---

#### 4. Session Management
**From noe-tests:**
- `SessionTimeoutTest.groovy` - Session timeout handling
- `CookieNameTest.groovy` - Custom session cookie names
- `JvmRouteTest.groovy` - JVM route configuration

**Missing in our tests:**
- Session timeout expiration
- Custom session cookie configuration
- JVM route validation
- Session replication scenarios

**Recommended new test class:**
```java
SessionManagementTest.java
- testSessionTimeout()
- testCustomCookieName()
- testJvmRouteConfiguration()
- testSessionIdFormat()
- testSessionReplication()
```

---

#### 5. Hot Standby & High Availability
**From noe-tests:**
- `HotStandByTest.groovy` - Hot standby scenarios
- `TwoBalancerSettingsTest.groovy` - Multiple balancer configuration
- `TwoHttpdInstancesTest.groovy` - Multiple balancer instances

**Missing in our tests:**
- Hot standby worker activation
- Multiple balancer coordination
- Balancer failover
- High availability scenarios

**Recommended new test class:**
```java
HighAvailabilityTest.java
- testHotStandbyActivation()
- testMultipleBalancers()
- testBalancerFailover()
- testWorkerPriority()
```

---

### 🟡 Important Missing Coverage (Medium Priority)

#### 6. Advanced SSL/Security Tests
**From noe-tests:**
- `SslFailoverTest.groovy` - SSL with failover
- `SslFailoverElytronTest.groovy` - SSL with Elytron
- `SslCrlTest.groovy` - Certificate Revocation List
- `SslWorkerAuthentizationTest.groovy` - Mutual SSL authentication
- `SSLValveTestCase.groovy` - SSL valve configuration
- `SecurityFeaturesTest.groovy` - General security features

**Missing in our tests:**
- SSL failover scenarios
- Certificate validation
- CRL checking
- Client certificate authentication
- SSL cipher configuration
- Elytron integration

**Recommended new test class:**
```java
AdvancedSSLTest.java
- testSslFailover()
- testClientCertificateAuth()
- testCrlValidation()
- testElytronIntegration()
- testSslCipherConfiguration()
```

---

#### 7. Protocol & Communication Tests
**From noe-tests:**
- `ModClusterAJP.groovy` - AJP protocol testing
- `WebSocketsTest.groovy` - WebSocket support
- `EjbViaHttpTest.groovy` - EJB over HTTP
- `LargeMessageTest.groovy` - Large message handling

**Missing in our tests:**
- AJP connector validation
- WebSocket proxying
- EJB invocation through balancer
- Large request/response handling

**Recommended new test class:**
```java
ProtocolTest.java
- testAjpConnector()
- testWebSocketProxying()
- testEjbInvocation()
- testLargeMessageHandling()
```

---

#### 8. Configuration & Settings Tests
**From noe-tests:**
- `SettingsTest.groovy` - General settings validation
- `NodeIdentityTest.groovy` - Node identity configuration
- `LocationContextTest.groovy` - Location context handling
- `ContextDelimiterTest.groovy` - Context delimiter configuration
- `WaitBeforeRemovePropertyTest.groovy` - Graceful removal settings

**Missing in our tests:**
- Comprehensive settings validation
- Node naming and identity
- Context path handling
- Removal timing configuration

**Recommended new test class:**
```java
ConfigurationSettingsTest.java
- testNodeIdentityConfiguration()
- testContextPathDelimiters()
- testWaitBeforeRemove()
- testProxyListConfiguration()
- testAdvertiseConfiguration()
```

---

### 🟢 Lower Priority Coverage

#### 9. Edge Cases & Regression Tests
**From noe-tests (JBCS/JBQA bug-specific tests):**
- `JBCS1040.groovy` - Specific bug regression
- `JBCS1103.groovy` - Specific bug regression
- `JBCS729.groovy` - Specific bug regression
- `JBQA7242.groovy` - Specific bug regression
- (Multiple other bug-specific tests)

**Recommended:**
- Add tests as bugs are discovered
- Document bug ID in Javadoc
- Keep in separate package: `org.jboss.modcluster.test.regression`

---

#### 10. Stress & Performance Tests
**From noe-tests:**
- `SoakTest.groovy` - Long-running stress test
- `HighCpuUsage.groovy` - High CPU scenarios
- `ThreadExhaustionTest.groovy` - Thread pool exhaustion
- `ManyWorkersTest.groovy` - Scaling with many workers

**Missing in our tests:**
- Long-running stability tests
- Resource exhaustion scenarios
- High load testing
- Scalability validation

**Recommended new test class:**
```java
StressTest.java
- testLongRunningSoak()
- testHighCpuUnderLoad()
- testThreadPoolExhaustion()
- testManyWorkersScaling()
```

---

#### 11. Apache httpd Integration Tests
**From noe-tests:**
- `ModProxyTest.groovy` - mod_proxy configuration
- `ModRewriteTest.groovy` - mod_rewrite rules
- `ModProxyWsTunnelSegFault.groovy` - WebSocket tunnel issues
- `DirectoryIndexNegativelyImpactMCMPRequests.groovy` - Directory index issues

**Note:** These are httpd-specific and less relevant for Undertow-based testing.

---

## Implementation Priority

### Phase 1: Core Missing Features (Weeks 1-2)
1. **AdvancedFailoverTest** (5 tests)
   - Failover with sessions, deterministic routing, graceful failover
2. **ContextLifecycleTest** (6 tests)
   - Auto-enable, exclusions, disable/enable operations
3. **LoadMetricsTest** (5 tests)
   - Load calculation, metrics, routing based on load

**Phase 1 Goal:** 16 additional tests → Total: 33 tests

---

### Phase 2: Enhanced Coverage (Weeks 3-4)
4. **SessionManagementTest** (5 tests)
   - Timeouts, custom cookies, JVM routes
5. **HighAvailabilityTest** (4 tests)
   - Hot standby, multiple balancers, HA scenarios
6. **AdvancedSSLTest** (5 tests)
   - SSL failover, client certs, Elytron

**Phase 2 Goal:** 14 additional tests → Total: 47 tests

---

### Phase 3: Protocol & Edge Cases (Weeks 5-6)
7. **ProtocolTest** (4 tests)
   - AJP, WebSockets, EJB, large messages
8. **ConfigurationSettingsTest** (5 tests)
   - Node identity, context paths, removal timing
9. **StressTest** (4 tests)
   - Soak testing, resource exhaustion, scaling

**Phase 3 Goal:** 13 additional tests → Total: 60 tests

---

### Phase 4: Regression & Documentation
10. **Regression package** (as needed)
    - Bug-specific tests with JIRA references
11. **Documentation updates**
    - Update README with new coverage
    - Test matrix documentation
    - Known limitations

---

## Test Coverage Target

| Milestone | Tests | Coverage Areas |
|-----------|-------|----------------|
| **Current** | 17 | Basic failover, sticky sessions, SSL, CLI, dynamic config |
| **Phase 1** | 33 | + Advanced failover, context lifecycle, load metrics |
| **Phase 2** | 47 | + Session mgmt, HA, advanced SSL |
| **Phase 3** | 60 | + Protocols, config settings, stress testing |
| **noe-tests** | ~200+ | Full production suite with httpd, regression, specialized scenarios |

---

## Success Criteria

### Phase 1 Complete:
- ✓ All critical failover scenarios covered
- ✓ Context lifecycle fully tested
- ✓ Load-based routing validated
- ✓ All tests pass with Undertow balancer
- ✓ Test execution time < 10 minutes

### Phase 2 Complete:
- ✓ Session management edge cases covered
- ✓ HA scenarios validated
- ✓ SSL security features tested
- ✓ All tests pass with both balancer types
- ✓ Test execution time < 15 minutes

### Phase 3 Complete:
- ✓ Protocol compatibility verified
- ✓ Configuration matrix tested
- ✓ Stress/stability validated
- ✓ Comprehensive test coverage documented
- ✓ Test execution time < 20 minutes

---

## Notes

1. **Focus on Undertow:** Our tests primarily target Undertow balancer (WildFly native). Apache httpd tests are secondary.

2. **Test Independence:** Each test must be independent and able to run in isolation.

3. **Container Reuse:** Leverage Testcontainers reuse for faster local development.

4. **Soft Assertions:** Continue using AssertJ soft assertions for better failure diagnostics.

5. **Async Testing:** Use Awaitility for all time-dependent assertions.

6. **Javadoc:** Every test method must have Javadoc with description and passing criteria.

7. **Connection Management:** Use "Connection: close" header for accurate load distribution testing.

---

## Quick Reference: Test Method Counts

```
Current:
- CliManagementTest: 6
- DynamicReconfTest: 3
- StickySessionTest: 2
- LoadBalancingGroupFailoverTest: 2
- SSLTest: 3
- DebugTest: 1
Total: 17

Target (Phase 1):
+ AdvancedFailoverTest: 5
+ ContextLifecycleTest: 6
+ LoadMetricsTest: 5
Total: 33 (+16)

Target (Phase 2):
+ SessionManagementTest: 5
+ HighAvailabilityTest: 4
+ AdvancedSSLTest: 5
Total: 47 (+14)

Target (Phase 3):
+ ProtocolTest: 4
+ ConfigurationSettingsTest: 5
+ StressTest: 4
Total: 60 (+13)
```

---

Generated: 2026-02-17
Based on: noe-tests/modcluster (64 test files analyzed)
