# Performance Guide

## Understanding Container Startup Times

### First Run (Building from ZIP)

When you first run tests with a WildFly/EAP ZIP, expect the following timeline:

```
Total: ~3-5 minutes for first test
```

**Breakdown**:

1. **Testcontainers initialization** (~2-5 seconds)
   ```
   ✔ Docker server version check
   ✔ Ryuk container start (cleanup helper)
   ```

2. **Image build from ZIP** (~30-60 seconds)
   ```
   [INFO] Building WildFly image from ZIP: wildfly-31.0.1.Final.zip
   - Transferring ZIP to Docker daemon (249 MB)
   - Extracting ZIP in container
   - Setting up permissions
   ```

   ⚠️ **Warning you'll see**:
   ```
   WARN - A large amount of data was sent to the Docker daemon (249 MB).
          Consider using a .dockerignore file for better performance.
   ```

   **This is expected and normal!** The ZIP needs to be sent to Docker to build the image.
   We've added a `.dockerignore` to minimize other files being sent.

3. **Container startup** (~60-120 seconds)
   ```
   - WildFly initialization
   - Subsystems loading
   - mod_cluster configuration
   - Waiting for: WFLYSRV0025 (server started message)
   ```

4. **Test execution** (varies by test)

### Subsequent Runs (Cached Image)

After the first run, Docker caches the built image:

```
Total: ~1-2 minutes
```

**Breakdown**:
1. Testcontainers init: ~2-5 seconds
2. Image build: ~0 seconds (cached!)
3. Container startup: ~60-90 seconds
4. Test execution: varies

## Optimizations

### 1. Docker Layer Caching (Automatic)

Docker caches each layer of the Dockerfile. Once built, subsequent tests reuse cached layers:

```dockerfile
FROM ubi9/openjdk-11                    # ← Cached after first pull
RUN microdnf install unzip              # ← Cached after first build
COPY wildfly-31.0.1.Final.zip /opt/     # ← Only rebuilds if ZIP changes
RUN unzip wildfly-31.0.1.Final.zip      # ← Only rebuilds if ZIP changes
```

### 2. Container Reuse (Development Only)

**For development**, enable container reuse to skip startup entirely:

```bash
# Enable in testcontainers.properties
testcontainers.reuse.enable=true

# First run: ~3-5 minutes (builds + starts)
mvn test -Dtest=StickySessionTest

# Subsequent runs: ~10-20 seconds (reuses containers!)
mvn test -Dtest=LoadBalancingGroupFailoverTest
```

**⚠️ Important**:
- Must stop containers manually when done: `docker stop $(docker ps -aq)`
- **Never enable in CI/CD** - causes test flakiness
- Already disabled in Jenkins pipeline

### 3. Parallel Test Execution

Run multiple test classes in parallel:

```bash
# Run 2 test classes simultaneously
mvn test -DforkCount=2

# Run tests in parallel (use with caution)
mvn test -DforkCount=2C  # 2 * CPU cores
```

**Trade-offs**:
- ✅ Faster overall execution
- ❌ Higher memory usage (each fork needs ~2GB)
- ❌ Harder to debug failures

### 4. Selective Test Execution

Don't run the full suite during development:

```bash
# Single test class
mvn test -Dtest=StickySessionTest

# Single test method
mvn test -Dtest=StickySessionTest#testStickySessionsMaintainedAcrossRequests

# Package
mvn test -Dtest=org.jboss.modcluster.test.failover.*
```

### 5. Keep Distributions on Fast Storage

Place `distributions/` directory on SSD, not network drives:

```bash
# Slow (network drive)
export WILDFLY_ZIP_PATH=/mnt/network/wildfly-31.0.1.Final.zip

# Fast (local SSD)
export WILDFLY_ZIP_PATH=/home/user/distributions/wildfly-31.0.1.Final.zip
```

## Monitoring Container Build

### View Build Progress

```bash
# In another terminal while tests run
docker ps

# Watch logs
docker logs -f <container-id>
```

### Check Docker Resource Usage

```bash
# See memory/CPU usage
docker stats

# Check disk usage
docker system df
```

### Build Time Debugging

If builds are unusually slow:

```bash
# Check Docker daemon logs
journalctl -u docker -f

# Check for disk I/O bottlenecks
iostat -x 2

# Check available memory
free -h
```

## Expected Timings Reference

### By Test Complexity

| Test Type | Setup Time | Execution | Total |
|-----------|-----------|-----------|-------|
| Simple CLI test | 1-2 min | 5-10 sec | ~2 min |
| Session test (2 workers) | 2-3 min | 10-20 sec | ~3 min |
| Load test (100 requests) | 2-3 min | 30-60 sec | ~4 min |
| SSL test | 2-3 min | 10-20 sec | ~3 min |

### By Balancer Type

| Balancer | First Run | Cached Run |
|----------|-----------|------------|
| **Undertow** (from ZIP) | 3-5 min | 1-2 min |
| **Undertow** (pre-built) | 1-2 min | 45-90 sec |
| **httpd** (always pre-built) | 30-60 sec | 30-60 sec |

### Full Suite (All Tests)

| Scenario | Time |
|----------|------|
| First run, no cache | ~20-30 min |
| Subsequent runs, cached | ~10-15 min |
| With container reuse | ~5-10 min |
| Parallel (2 forks) | ~10-15 min |

## Troubleshooting Slow Performance

### Issue: Build Taking > 2 Minutes

**Check**:
```bash
# Docker disk space
docker system df

# Prune if needed
docker system prune -a
```

### Issue: Container Startup > 3 Minutes

**Possible causes**:
1. **Low memory**: Docker needs 4GB+ available
2. **CPU throttling**: Check `docker stats`
3. **Disk I/O**: Check with `iostat`

**Solutions**:
```bash
# Increase Docker memory (Docker Desktop)
# Settings → Resources → Memory: 6GB or more

# Check logs for actual error
docker logs <container-id>
```

### Issue: Timeout Errors

```
Container did not start within timeout
```

**Current timeout**: 5 minutes

**Increase if needed** in `WildFlyContainer.java`:
```java
.withStartupTimeout(Duration.ofMinutes(10))  // For very slow systems
```

### Issue: "Cannot connect to Docker daemon"

```bash
# Check Docker is running
docker ps

# Linux: Start Docker
sudo systemctl start docker

# Check permissions
sudo usermod -aG docker $USER
newgrp docker
```

## CI/CD Performance

### Jenkins Pipeline Optimization

Already optimized in `Jenkinsfile`:

```groovy
// Parallel matrix execution
matrix {
    axes {
        axis { name 'BALANCER_TYPE'; values 'undertow', 'httpd' }
    }
}

// Container cleanup after tests
post {
    always {
        sh 'docker container prune -f'
        sh 'docker network prune -f'
    }
}
```

### GitHub Actions / GitLab CI

Example optimization:

```yaml
jobs:
  test:
    strategy:
      matrix:
        balancer: [undertow, httpd]
    steps:
      - name: Cache Docker layers
        uses: actions/cache@v3
        with:
          path: /tmp/.buildx-cache
          key: ${{ runner.os }}-buildx-${{ github.sha }}

      - name: Run tests
        run: mvn test -P${{ matrix.balancer }}
```

## Best Practices Summary

### For Development

1. ✅ Use container reuse for repeated runs
2. ✅ Run specific tests, not full suite
3. ✅ Keep distributions on local SSD
4. ✅ Use cached Docker layers
5. ✅ Monitor with `docker stats`

### For CI/CD

1. ✅ Disable container reuse
2. ✅ Run full suite
3. ✅ Use parallel matrix builds
4. ✅ Clean up containers after tests
5. ✅ Cache Docker layers between builds

### General

1. ✅ First run will be slow (3-5 min) - **this is normal**
2. ✅ Subsequent runs are faster (1-2 min)
3. ✅ Docker needs 4GB+ memory
4. ✅ The "large data" warning is expected for ZIP files
5. ✅ httpd balancer is faster (no ZIP build needed)

## Performance Metrics to Track

### Key Metrics

Track these over time:

```bash
# Test execution time
mvn test | grep "Total time:"

# Container startup time
grep "started in" target/surefire-reports/*.txt

# Build time
grep "Building WildFly image" logs/test.log
```

### Alerting Thresholds

Set alerts if:
- Container build > 2 minutes (check Docker resources)
- Container startup > 3 minutes (check for errors)
- Test execution > 10 seconds (check test logic)
- Full suite > 30 minutes (consider parallelization)

## Quick Reference

```bash
# Fast iteration during development
testcontainers.reuse.enable=true
mvn test -Dtest=MyTest

# CI/CD execution
mvn test -Pci -Dbalancer.type=undertow

# Debug slow tests
mvn test -X -Dtest=SlowTest

# Check what's slow
time mvn test

# Monitor while running
watch -n 2 'docker stats --no-stream'
```
