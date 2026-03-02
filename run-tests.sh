#!/bin/bash
#
# Jenkins builder script for mod_cluster testsuite.
#
# Expected environment (set by Jenkins job / matrix axes):
#   WHICH_BALANCER     - "undertow" or "httpd" (matrix axis)
#   BUILD_ZIP_URL      - URL to server ZIP (file:// or http(s)://)
#   BUILD_ZIP_ROOT_DIR - top-level directory inside the ZIP (unused by tests, reserved)
#   WILDFLY_ZIP_PATH   - explicit path to a local ZIP (skips download)
#   WILDFLY_VERSION    - WildFly version to download from Maven Central as last resort
#   EAP_VERSION        - EAP version string for build description (derived automatically)
#   USERNAME / TOKEN   - credentials for authenticated downloads from Jenkins
#

set -euo pipefail

# ---------- Helpers ----------

# Download or copy a remote file given by URL to a local path.
# Supports file://, http://, https:// schemes.
# Uses USERNAME/TOKEN credentials for jenkins.*.redhat.com URLs.
#   $1 - remote file URL
#   $2 - local output file path
download_file() {
    local DOWNLOAD_URL=$1
    local OUTPUT_PATH=$2

    if echo "$DOWNLOAD_URL" | grep -q '^file://'; then
        local LOCAL_PATH
        LOCAL_PATH=$(echo "$DOWNLOAD_URL" | sed 's#\\\\#/#g' | sed 's|^file:///\([a-zA-Z]:\)|\1|' | sed 's|^file:/*|/|')
        cp "$LOCAL_PATH" "$OUTPUT_PATH"
    else
        local CREDENTIALS_ARGS=""
        if [ "x${USERNAME:-}" != "x" ] && [ "x${TOKEN:-}" != "x" ] && [[ "$DOWNLOAD_URL" =~ jenkins.*\.redhat\.com ]]; then
            CREDENTIALS_ARGS="--http-user ${USERNAME} --http-password ${TOKEN} --auth-no-challenge"
        fi
        wget ${CREDENTIALS_ARGS} -q --no-check-certificate --tries=99 --waitretry=120 --retry-connrefused --timeout=5400 "--output-document=${OUTPUT_PATH}" "$DOWNLOAD_URL"
    fi
}

# ---------- Banner ----------

echo "============================================="
echo " mod_cluster testsuite"
echo "============================================="
echo " Balancer:  ${WHICH_BALANCER:-undertow}"
echo " Node:      $(hostname)"
echo " Date:      $(date)"
echo " Java:      $(java -version 2>&1 | head -1)"
echo " Maven:     $(mvn --version 2>&1 | head -1)"
echo "============================================="
echo

# ---------- Resolve distribution ZIP ----------
# Priority:
#   1. WILDFLY_ZIP_PATH          - explicit local path
#   2. BUILD_ZIP_URL             - download from URL
#   3. distributions/*.zip       - auto-detect local ZIP
#   4. WILDFLY_VERSION           - download from Maven Central

mkdir -p distributions

if [ -n "${WILDFLY_ZIP_PATH:-}" ]; then
    echo "Using explicit ZIP path: $WILDFLY_ZIP_PATH"

elif [ -n "${BUILD_ZIP_URL:-}" ]; then
    ZIP_FILENAME=$(basename "$BUILD_ZIP_URL")
    WILDFLY_ZIP_PATH="distributions/${ZIP_FILENAME}"
    echo "Downloading server ZIP from: $BUILD_ZIP_URL"
    download_file "$BUILD_ZIP_URL" "$WILDFLY_ZIP_PATH"
    export WILDFLY_ZIP_PATH
    echo "Downloaded: $WILDFLY_ZIP_PATH"

else
    # Auto-detect from distributions/
    ZIP=$(find distributions/ -maxdepth 1 -name '*.zip' 2>/dev/null | head -1)
    if [ -n "$ZIP" ]; then
        export WILDFLY_ZIP_PATH="$ZIP"
        echo "Auto-detected ZIP: $WILDFLY_ZIP_PATH"
    else
        # Last resort: download from Maven Central
        WILDFLY_VERSION="${WILDFLY_VERSION:-34.0.1.Final}"
        echo "No ZIP found — downloading WildFly ${WILDFLY_VERSION} from Maven Central..."
        mvn -B generate-test-resources \
            -Pdownload-wildfly \
            -Dwildfly.version="${WILDFLY_VERSION}" \
            -DskipTests
        export WILDFLY_ZIP_PATH="distributions/wildfly-${WILDFLY_VERSION}.zip"
    fi
fi

echo "Using distribution: $WILDFLY_ZIP_PATH"

# ---------- Derive EAP_VERSION for build description ----------

if [ -z "${EAP_VERSION:-}" ]; then
    EAP_VERSION=$(basename "${WILDFLY_ZIP_PATH}" .zip)
    export EAP_VERSION
fi
echo "EAP_VERSION=${EAP_VERSION}"
echo

# ---------- Run tests ----------

mvn -B test \
    -Pci \
    -Dbalancer.type="${WHICH_BALANCER:-undertow}" \
    -Dwildfly.zip.path="$WILDFLY_ZIP_PATH"
