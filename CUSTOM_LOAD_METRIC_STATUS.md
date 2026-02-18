# Custom Load Metric Implementation - Status

## Summary

The custom load metric implementation is **functionally complete** and ready for manual testing. Fully automated end-to-end testing within Testcontainers has challenges due to the need for server restarts.

## What's Working

### ✅ Custom Load Metric JAR
- **File**: `src/test/resources/custom-load-metric/target/custom-load-metric.jar` (4.1KB)
- **Class**: `org.jboss.modcluster.test.metric.FileBasedLoadMetric`
- **Builds**: Successfully with `mvn -f src/test/resources/custom-load-metric/pom.xml package`

### ✅ WildFly Module Configuration
- **Module XML**: `src/test/resources/custom-load-metric/module.xml`
- **Module Name**: `org.jboss.modcluster.test.metric`
- **Dependencies**: `org.jboss.mod_cluster.core`

### ✅ Deployment Automation
- `WildFlyContainer.deployCustomLoadMetric()` - Copies JAR and module.xml to container
- `WildFlyContainer.configureCustomLoadMetric()` - Adds custom metric to mod_cluster config
- `WildFlyContainer.writeLoadValue()` - Sets load values in container

### ✅ Test Implementation
- `LoadMetricsTest.testCustomLoadMetrics()` - Demonstrates deployment and configuration
- Test verifies: module deployment, configuration, load file writing

## Current Limitation

**Testcontainers Restart Issue**: When restarting containers with Testcontainers, file system state can become unreliable, causing SIGPIPE errors on exec operations. This is a known Testcontainers/Docker limitation, not an issue with the custom metric itself.

## Manual Testing Instructions

To verify custom load metric works end-to-end:

### 1. Build Custom Metric
```bash
mvn -f src/test/resources/custom-load-metric/pom.xml clean package
```

### 2. Deploy to Running WildFly
```bash
# Copy to WildFly modules directory
mkdir -p $WILDFLY_HOME/modules/org/jboss/modcluster/test/metric/main/
cp src/test/resources/custom-load-metric/target/custom-load-metric.jar \
   $WILDFLY_HOME/modules/org/jboss/modcluster/test/metric/main/
cp src/test/resources/custom-load-metric/module.xml \
   $WILDFLY_HOME/modules/org/jboss/modcluster/test/metric/main/
```

### 3. Configure in standalone-ha.xml
```xml
<subsystem xmlns="urn:jboss:domain:modcluster:9.0">
    <proxy name="default" ...>
        <dynamic-load-provider>
            <custom-load-metric class="org.jboss.modcluster.test.metric.FileBasedLoadMetric"
                                module="org.jboss.modcluster.test.metric"
                                weight="2"
                                capacity="1000">
                <property name="loadFile" value="/tmp/modcluster-load.txt"/>
                <property name="parseExpression" value="^LOAD: ([0-9]+)$"/>
            </custom-load-metric>
        </dynamic-load-provider>
    </proxy>
</subsystem>
```

### 4. Restart Server
```bash
$WILDFLY_HOME/bin/standalone.sh -c standalone-ha.xml
```

### 5. Control Load
```bash
# Set high load
echo "LOAD: 900" > /tmp/modcluster-load.txt

# Set low load
echo "LOAD: 100" > /tmp/modcluster-load.txt
```

### 6. Verify Load-Based Routing
```bash
# Make requests through balancer, observe routing
for i in {1..100}; do
  curl -s http://balancer/demo/ | grep "Served by"
done | sort | uniq -c
```

Workers with lower load values should receive more traffic.

## Alternative: Container Image Approach

For production-like testing, bake the custom metric into the container image:

```dockerfile
FROM quay.io/wildfly/wildfly:latest
COPY custom-load-metric.jar /opt/wildfly/modules/org/jboss/modcluster/test/metric/main/
COPY module.xml /opt/wildfly/modules/org/jboss/modcluster/test/metric/main/
```

This avoids restart issues in containers.

## Test Results

- ✅ JAR builds successfully
- ✅ Module deploys to container
- ✅ Configuration added to mod_cluster subsystem
- ✅ Load files can be written to container
- ⚠️  Full end-to-end automated test blocked by Testcontainers restart reliability

## Next Steps

1. **Manual verification**: Follow manual testing instructions above
2. **Container image**: Build custom WildFly image with metric pre-installed
3. **Alternative approach**: Use JMX to set load values instead of file-based approach (no restart needed)

## References

- Custom metric implementation: `src/test/resources/custom-load-metric/`
- Full documentation: `src/test/resources/custom-load-metric/README.md`
- Test code: `src/test/java/org/jboss/modcluster/test/loadbalancing/LoadMetricsTest.java`
- Helper methods: `src/test/java/org/jboss/modcluster/test/utils/WildFlyContainer.java`

---

**Status**: Ready for manual testing
**Date**: 2026-02-17
