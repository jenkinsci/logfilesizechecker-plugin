#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
JENKINS_IMAGE="${JENKINS_IMAGE:-artifactory.gcp.elta.co.il/docker.io/jenkins/jenkins:lts-jdk17}"

echo "=================================================================="
echo "  Jenkins logfilesizechecker-plugin — Live Jenkins E2E & Security "
echo "  Image:   ${JENKINS_IMAGE}"
echo "  Project: ${PROJECT_DIR}"
echo "=================================================================="

mkdir -p "${PROJECT_DIR}/target/surefire-reports" "${PROJECT_DIR}/target/site/allure-report/assets"
chmod -R 777 "${PROJECT_DIR}/target"

docker rm -f jenkins-live-e2e-ui >/dev/null 2>&1 || true

docker run -d \
    --name jenkins-live-e2e-ui \
    -p 8080:18080 \
    -p 18080:18080 \
    -p 3000:18081 \
    -p 8000:18081 \
    -p 18081:18081 \
    -v "${PROJECT_DIR}:/workspace:rw" \
    -w /workspace \
    --entrypoint bash \
    "${JENKINS_IMAGE}" \
    -c "sleep infinity" >/dev/null

docker exec jenkins-live-e2e-ui bash -c '
    set -euo pipefail

    echo "=================================================================="
    echo "  PHASE 1: Build logfilesizechecker.hpi & Run Unit Test Suite     "
    echo "=================================================================="
    rm -rf /tmp/jenkins-war-libs /tmp/hpi-build /workspace/target/classes
    mkdir -p /tmp/jenkins-war-libs /tmp/hpi-build /workspace/target/classes /workspace/target/surefire-reports
    cd /tmp/jenkins-war-libs
    jar xf /usr/share/jenkins/jenkins.war WEB-INF/lib/ WEB-INF/detached-plugins/ executable/winstone.jar

    mkdir -p /tmp/hpi-build/gen-src/hudson/plugins/logfilesizechecker
    cat << "EOF" > /tmp/hpi-build/gen-src/hudson/plugins/logfilesizechecker/Messages.java
package hudson.plugins.logfilesizechecker;
import org.jvnet.localizer.Localizable;
import org.jvnet.localizer.ResourceBundleHolder;

public class Messages {
    private static final ResourceBundleHolder holder = ResourceBundleHolder.get(Messages.class);
    public static String Descriptor_DisplayName() {
        return holder.format("Descriptor.DisplayName");
    }
    public static Localizable _Descriptor_DisplayName() {
        return new Localizable(holder, "Descriptor.DisplayName");
    }
}
EOF

    echo "[1/4] Compiling plugin sources with javac + SezPoz/Symbol annotation processors..."
    javac -parameters \
        --release 17 \
        -cp "/tmp/jenkins-war-libs/WEB-INF/lib/*:/tmp/jenkins-war-libs/executable/winstone.jar" \
        -d /workspace/target/classes \
        /tmp/hpi-build/gen-src/hudson/plugins/logfilesizechecker/Messages.java \
        /workspace/src/main/java/hudson/plugins/logfilesizechecker/LogfilesizecheckerWrapper.java

    cp -rf /workspace/src/main/resources/. /workspace/target/classes/
    mkdir -p /workspace/target/classes/META-INF/annotations
    echo "hudson.plugins.logfilesizechecker.LogfilesizecheckerWrapper\$DescriptorImpl" > /workspace/target/classes/META-INF/annotations/org.jenkinsci.Symbol
    echo "constructor=maxLogSize,failBuild,setOwn" > /workspace/target/classes/hudson/plugins/logfilesizechecker/LogfilesizecheckerWrapper.stapler

    echo "[2/4] Packaging target/logfilesizechecker.jar and target/logfilesizechecker.hpi..."
    mkdir -p /tmp/hpi-build/hpi-root/META-INF /tmp/hpi-build/hpi-root/WEB-INF/lib
    cat << "EOF" > /tmp/hpi-build/hpi-root/META-INF/MANIFEST.MF
Manifest-Version: 1.0
Group-Id: org.jenkins-ci.plugins
Artifact-Id: logfilesizechecker
Short-Name: logfilesizechecker
Long-Name: Jenkins build log file size checker plugin
Url: https://github.com/jenkinsci/logfilesizechecker-plugin
Plugin-Version: 1.6-SNAPSHOT
Hudson-Version: 2.479.3
Jenkins-Version: 2.479.3
Plugin-Developers: Stefan Brausch:stefanbrausch:stefanbrausch@protonmail.ch
Support-Dynamic-Loading: true

EOF
    jar cfm /workspace/target/logfilesizechecker.jar /tmp/hpi-build/hpi-root/META-INF/MANIFEST.MF -C /workspace/target/classes .
    cp /workspace/target/logfilesizechecker.jar /tmp/hpi-build/hpi-root/WEB-INF/lib/logfilesizechecker.jar
    jar cfm /workspace/target/logfilesizechecker.hpi /tmp/hpi-build/hpi-root/META-INF/MANIFEST.MF -C /tmp/hpi-build/hpi-root .
    ls -lh /workspace/target/logfilesizechecker.hpi

    echo "[3/4] Running Standalone Unit & Component Test Suite (12 tests)..."
    GROOVY_JAR=$(ls /tmp/jenkins-war-libs/WEB-INF/lib/groovy-all-*.jar | head -n 1)
    java -cp "${GROOVY_JAR}:/tmp/jenkins-war-libs/WEB-INF/lib/*:/tmp/jenkins-war-libs/executable/winstone.jar:/workspace/target/logfilesizechecker.jar" \
        groovy.ui.GroovyMain /workspace/test/e2e/run_unit_suite.groovy

    echo ""
    echo "=================================================================="
    echo "  PHASE 2: Boot Real Jenkins LTS + Install Plugin + Run 14 E2Es   "
    echo "=================================================================="
    export JENKINS_HOME=/tmp/jenkins_home
    rm -rf "${JENKINS_HOME}"
    mkdir -p "${JENKINS_HOME}/plugins" "${JENKINS_HOME}/init.groovy.d"

    for hpi in /tmp/jenkins-war-libs/WEB-INF/detached-plugins/*.hpi; do
        base=$(basename "$hpi" .hpi)
        cp "$hpi" "${JENKINS_HOME}/plugins/${base}.jpi"
    done
    cp /workspace/target/logfilesizechecker.hpi "${JENKINS_HOME}/plugins/logfilesizechecker.jpi"
    PLUGIN_COUNT=$(ls -1 "${JENKINS_HOME}/plugins"/*.jpi | wc -l)
    echo "Installed logfilesizechecker.jpi + $((PLUGIN_COUNT - 1)) detached plugins into ${JENKINS_HOME}/plugins/"

    cp /workspace/test/e2e/init_live_jenkins_e2e.groovy "${JENKINS_HOME}/init.groovy.d/01-run-plugin-e2e.groovy"

    echo "[4/4] Starting live Jenkins controller on port 18080 (mapped to 8080 & 18080)..."
    nohup java \
        -Djava.awt.headless=true \
        -Djenkins.install.runSetupWizard=false \
        -Dhudson.model.DirectoryBrowserSupport.CSP= \
        -Dhudson.remoting.ClassFilter=LivePluginE2ERunnerBuilder \
        -Dhudson.model.UpdateCenter.updateCenterUrl=http://127.0.0.1:1/ \
        -jar /usr/share/jenkins/jenkins.war \
        --httpPort=18080 > /tmp/jenkins_server.log 2>&1 < /dev/null &
    JENKINS_PID=$!

    echo "Polling live Jenkins REST API for job logfilesizechecker-e2e-suite #1 completion..."
    BUILD_FINISHED=false
    for i in $(seq 1 120); do
        if curl -fsS "http://localhost:18080/job/logfilesizechecker-e2e-suite/1/api/json" > /tmp/build_api.json 2>/dev/null; then
            if grep -q "\"building\":false" /tmp/build_api.json && ! grep -q "\"result\":null" /tmp/build_api.json; then
                BUILD_FINISHED=true
                break
            fi
        fi
        sleep 1
    done

    if [ "${BUILD_FINISHED}" != "true" ]; then
        echo "ERROR: Timed out waiting for logfilesizechecker-e2e-suite #1 to complete."
        cat /tmp/jenkins_server.log || true
        exit 1
    fi

    CONSOLE_OUT=$(curl -fsS "http://localhost:18080/job/logfilesizechecker-e2e-suite/1/consoleText")
    echo "${CONSOLE_OUT}"

    BUILD_JSON=$(curl -fsS "http://localhost:18080/job/logfilesizechecker-e2e-suite/1/api/json")
    TEST_JSON=$(curl -fsS "http://localhost:18080/job/logfilesizechecker-e2e-suite/1/testReport/api/json")

    cp -f /tmp/jenkins_home/workspace/logfilesizechecker-e2e-suite/target/surefire-reports/TEST-JenkinsLiveE2ETest.xml /workspace/target/surefire-reports/TEST-JenkinsLiveE2ETest.xml
    echo "${CONSOLE_OUT}" > /workspace/target/surefire-reports/live_jenkins_console.txt
    curl -fsS "http://localhost:18080/pluginManager/api/json?depth=1" > /workspace/target/surefire-reports/live_jenkins_plugins.json

    ECHARTS_JS=$(find /tmp/jenkins_home/plugins -name "echarts.min.js" 2>/dev/null | head -n 1 || true)
    if [ -n "${ECHARTS_JS}" ]; then
        cp -f "${ECHARTS_JS}" /workspace/target/site/allure-report/assets/echarts.min.js
    fi
    BOOTSTRAP_CSS=$(find /tmp/jenkins_home/plugins -name "bootstrap-custom-build.css" 2>/dev/null | head -n 1 || true)
    if [ -n "${BOOTSTRAP_CSS}" ]; then
        cp -f "${BOOTSTRAP_CSS}" /workspace/target/site/allure-report/assets/bootstrap.min.css
    fi

    echo "${BUILD_JSON}" | grep -q "\"result\":\"SUCCESS\""
    echo "${TEST_JSON}" | grep -q "\"failCount\":0"
    echo "${TEST_JSON}" | grep -q "\"passCount\":14"
'

echo ""
echo "=================================================================="
echo "  PHASE 3: Running Local Security & Sub-Dependency Scanner        "
echo "=================================================================="
python3 "${PROJECT_DIR}/test/security/run_security_scan.py"

echo ""
echo "=================================================================="
echo "  PHASE 4: Generating & Serving Allure Report Dashboard           "
echo "=================================================================="
python3 "${PROJECT_DIR}/test/e2e/generate_allure_dashboard.py"

docker exec jenkins-live-e2e-ui bash -c '
    mkdir -p /tmp/jenkins_home/userContent/allure-report/assets
    cp -rf /workspace/target/site/allure-report/. /tmp/jenkins_home/userContent/allure-report/
'

docker exec -d jenkins-live-e2e-ui bash -c '
GROOVY_JAR=$(ls /tmp/jenkins-war-libs/WEB-INF/lib/groovy-all-*.jar | head -n 1)
java -cp "${GROOVY_JAR}" groovy.ui.GroovyMain -e "
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
File root = new File(\"/workspace/target/site/allure-report\")
HttpServer server = HttpServer.create(new InetSocketAddress(\"0.0.0.0\", 18081), 0)
server.createContext(\"/\", { ex ->
    String path = ex.requestURI.path
    if (path == \"/\" || path == \"\") path = \"/index.html\"
    File f = new File(root, path)
    if (!f.exists() || f.isDirectory()) {
        byte[] msg = \"Not Found\".bytes
        ex.sendResponseHeaders(404, msg.length)
        ex.responseBody.write(msg)
        ex.close()
        return
    }
    String mime = path.endsWith(\".js\") ? \"application/javascript; charset=utf-8\" :
                  path.endsWith(\".css\") ? \"text/css; charset=utf-8\" :
                  \"text/html; charset=utf-8\"
    byte[] bytes = Files.readAllBytes(f.toPath())
    ex.responseHeaders.set(\"Content-Type\", mime)
    ex.sendResponseHeaders(200, bytes.length)
    ex.responseBody.write(bytes)
    ex.close()
})
server.start()
Thread.sleep(Long.MAX_VALUE)
" >/tmp/allure_http_18081.log 2>&1
'

for _ in $(seq 1 15); do
    if curl -fsS "http://localhost:18081/" >/dev/null 2>&1 && curl -fsS "http://localhost:3000/" >/dev/null 2>&1; then
        break
    fi
    sleep 1
done

curl -fsS "http://localhost:8080/job/logfilesizechecker-e2e-suite/1/testReport/" >/dev/null
curl -fsS "http://localhost:8080/userContent/allure-report/index.html" >/dev/null
curl -fsS "http://localhost:3000/" >/dev/null
curl -fsS "http://localhost:8000/" >/dev/null
curl -fsS "http://localhost:18081/" >/dev/null

echo "=================================================================="
echo "  ALL VERIFICATIONS PASSED (0 CRITICAL / 0 HIGH VULNERABILITIES)  "
echo "  [Live Jenkins UI (Port 8080)]:      http://localhost:8080/job/logfilesizechecker-e2e-suite/1/testReport/"
echo "  [Jenkins Embedded Allure Report]:   http://localhost:8080/userContent/allure-report/index.html"
echo "  [Allure Report UI (Port 3000)]:     http://localhost:3000/"
echo "  [Allure Report UI (Port 8000)]:     http://localhost:8000/"
if [ -n "${WEB_HOST:-}" ]; then
    echo "  [Browser Proxy - Jenkins (8080)]:   https://8080-${WEB_HOST}/job/logfilesizechecker-e2e-suite/1/testReport/"
    echo "  [Browser Proxy - Allure (3000)]:    https://3000-${WEB_HOST}/"
    echo "  [Browser Proxy - Allure (8000)]:    https://8000-${WEB_HOST}/"
fi
echo "=================================================================="
if [ "${STOP_CONTAINER_ON_EXIT:-false}" = "true" ]; then
    docker rm -f jenkins-live-e2e-ui >/dev/null 2>&1 || true
fi
