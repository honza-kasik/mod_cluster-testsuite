#!/bin/bash

set -e

echo "ModCluster Test Suite - Setup Script"
echo "======================================"
echo

# Check for Java
if ! command -v java &> /dev/null; then
    echo "❌ Java not found. Please install Java 11 or higher."
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
if [ "$JAVA_VERSION" -lt 11 ]; then
    echo "❌ Java 11 or higher is required. Found: Java $JAVA_VERSION"
    exit 1
fi
echo "✓ Java $JAVA_VERSION found"

# Check for Maven
if ! command -v mvn &> /dev/null; then
    echo "❌ Maven not found. Please install Maven 3.6 or higher."
    exit 1
fi
echo "✓ Maven found"

# Check for Docker/Podman
if command -v docker &> /dev/null; then
    echo "✓ Docker found"
    CONTAINER_CMD="docker"
elif command -v podman &> /dev/null; then
    echo "✓ Podman found"
    CONTAINER_CMD="podman"
else
    echo "❌ Neither Docker nor Podman found. Please install one of them."
    exit 1
fi

# Check if container engine is running
if ! $CONTAINER_CMD ps &> /dev/null; then
    echo "❌ Container engine is not running. Please start Docker/Podman."
    exit 1
fi
echo "✓ Container engine is running"

echo

# Function to determine Java version from ZIP filename
get_java_version() {
    local zipfile="$1"
    local filename=$(basename "$zipfile")

    # Check for explicit override
    if [ -n "$CONTAINER_JAVA_VERSION" ]; then
        echo "openjdk-$CONTAINER_JAVA_VERSION"
        return
    fi

    # Extract version and determine Java requirement
    if [[ $filename =~ ^wildfly-([0-9]+) ]]; then
        local major_version="${BASH_REMATCH[1]}"
        if [ "$major_version" -ge 31 ]; then
            echo "openjdk-17"
        else
            echo "openjdk-11"
        fi
    elif [[ $filename =~ ^jboss-eap-([0-9]+) ]]; then
        local major_version="${BASH_REMATCH[1]}"
        if [ "$major_version" -ge 8 ]; then
            echo "openjdk-17"
        else
            echo "openjdk-11"
        fi
    else
        # Default to Java 17 for unknown formats
        echo "openjdk-17"
    fi
}

# Function to generate image tag from ZIP filename
get_image_tag() {
    local zipfile="$1"
    local java_version="$2"
    local filename=$(basename "$zipfile" .zip)

    # Convert to lowercase and replace dots with dashes
    local normalized=$(echo "$filename" | tr '[:upper:]' '[:lower:]' | tr '.' '-')

    echo "modcluster-test/${normalized}:${java_version}"
}

# Function to build Docker image from ZIP
build_image() {
    local zipfile="$1"
    local java_version="$2"
    local image_tag="$3"
    local zipname=$(basename "$zipfile")

    echo "  Building image: $image_tag"
    echo "  This may take a few minutes..."

    # Create temporary Dockerfile
    cat > distributions/Dockerfile.tmp <<EOF
FROM registry.access.redhat.com/ubi9/${java_version}:latest
USER root
RUN microdnf install -y unzip && microdnf clean all
WORKDIR /opt
COPY ${zipname} /opt/${zipname}
RUN echo 'Extracting ${zipname}...' && \\
    unzip -q /opt/${zipname} && \\
    rm /opt/${zipname} && \\
    (mv wildfly-* wildfly 2>/dev/null || mv jboss-eap-* wildfly 2>/dev/null || true) && \\
    chown -R 185:0 /opt/wildfly && \\
    chmod -R g+rw /opt/wildfly
USER 185
ENV JBOSS_HOME=/opt/wildfly
EXPOSE 8080 8443 9990 6666
EOF

    # Build the image
    cd distributions
    if $CONTAINER_CMD build -f Dockerfile.tmp -t "$image_tag" . > /tmp/modcluster-build.log 2>&1; then
        rm -f Dockerfile.tmp
        cd ..
        echo "  ✓ Successfully built: $image_tag"
        return 0
    else
        rm -f Dockerfile.tmp
        cd ..
        echo "  ❌ Failed to build image. Check /tmp/modcluster-build.log for details"
        return 1
    fi
}

# Check for WildFly/EAP ZIP and build images
echo "Checking for WildFly/EAP distributions..."
echo

if [ -d "distributions" ] && [ "$(ls -A distributions/*.zip 2>/dev/null)" ]; then
    ZIP_COUNT=$(ls -1 distributions/*.zip 2>/dev/null | wc -l)
    echo "Found $ZIP_COUNT ZIP distribution(s)"
    echo

    BUILT_COUNT=0
    CACHED_COUNT=0
    FAILED_COUNT=0

    for zipfile in distributions/*.zip; do
        zipname=$(basename "$zipfile")
        zipsize=$(du -h "$zipfile" | cut -f1)

        echo "📦 $zipname ($zipsize)"

        # Determine Java version
        java_version=$(get_java_version "$zipfile")
        echo "  Required Java: $java_version"

        # Generate image tag
        image_tag=$(get_image_tag "$zipfile" "$java_version")

        # Check if image already exists
        if $CONTAINER_CMD image inspect "$image_tag" &> /dev/null; then
            echo "  ✓ Image already exists: $image_tag"
            CACHED_COUNT=$((CACHED_COUNT + 1))
        else
            echo "  ⚙️  Building image (first time only)..."
            if build_image "$zipfile" "$java_version" "$image_tag"; then
                BUILT_COUNT=$((BUILT_COUNT + 1))
            else
                FAILED_COUNT=$((FAILED_COUNT + 1))
            fi
        fi
        echo
    done

    # Summary
    echo "======================================"
    echo "Image Build Summary:"
    echo "  ✓ Cached (already existed): $CACHED_COUNT"
    echo "  ✓ Built successfully: $BUILT_COUNT"
    if [ $FAILED_COUNT -gt 0 ]; then
        echo "  ❌ Failed: $FAILED_COUNT"
    fi
    echo

    if [ $FAILED_COUNT -gt 0 ]; then
        echo "⚠️  Some images failed to build. Check logs for details."
        echo "Tests may fall back to pre-built images or fail."
        echo
    fi

else
    echo "⚠️  No ZIP distributions found in distributions/"
    echo
    echo "To use custom WildFly/EAP distributions:"
    echo "  1. Download WildFly: https://www.wildfly.org/downloads/"
    echo "  2. Or get EAP from: https://access.redhat.com/"
    echo "  3. Place ZIP in: distributions/"
    echo "  4. Run this script again: ./setup.sh"
    echo
    echo "Tests will use pre-built container images as fallback."
    echo
fi

echo "======================================"
echo "Setup complete! You can now run tests:"
echo
echo "  # Run all tests with undertow balancer"
echo "  mvn test"
echo
echo "  # Run with httpd balancer"
echo "  mvn test -Phttpd"
echo
echo "  # Run specific test"
echo "  mvn test -Dtest=StickySessionTest"
echo
echo "  # Override Java version for containers"
echo "  CONTAINER_JAVA_VERSION=17 ./setup.sh"
echo "  mvn test -Dcontainer.java.version=17"
echo
echo "  # Clean up built images"
echo "  ./cleanup-images.sh"
echo
