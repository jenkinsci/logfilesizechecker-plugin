#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
JENKINS_IMAGE="${JENKINS_IMAGE:-artifactory.gcp.elta.co.il/docker.io/jenkins/jenkins:lts-jdk17}"
INSTALL_WORKFLOW_CPS="${INSTALL_WORKFLOW_CPS:-false}"
CONTAINER_NAME="${CONTAINER_NAME:-jenkins-live-e2e-ui}"

echo "=================================================================="
echo "  Plugin-Free Groovy Function (Option 1) — Live Jenkins LTS E2E   "
echo "  Image            : ${JENKINS_IMAGE}"
echo "  Groovy Function  : ${PROJECT_DIR}/groovy/vars/logfilesizechecker.groovy"
echo "  Install CPS      : ${INSTALL_WORKFLOW_CPS}"
echo "=================================================================="

mkdir -p "${PROJECT_DIR}/target/surefire-reports" "${PROJECT_DIR}/target/site/allure-report/assets"

docker rm -f "${CONTAINER_NAME}" >/dev/null 2>&1 || true

docker run -d \
    --name "${CONTAINER_NAME}" \
    -p 8080:18080 \
    -p 18080:18080 \
    -p 3000:18081 \
    -p 8000:18081 \
    -p 18081:18081 \
    -e INSTALL_WORKFLOW_CPS="${INSTALL_WORKFLOW_CPS}" \
    -v "${PROJECT_DIR}:/workspace:rw" \
    -w /workspace \
    --entrypoint bash \
    "${JENKINS_IMAGE}" \
    -c "sleep infinity" >/dev/null

docker exec -u 0 "${CONTAINER_NAME}" chmod -R 777 /workspace/target

docker exec "${CONTAINER_NAME}" bash -c '
    set -euo pipefail

    export JENKINS_HOME=/tmp/jenkins_home
    rm -rf "${JENKINS_HOME}" /tmp/jenkins-war-libs /tmp/shared-lib-repo
    mkdir -p "${JENKINS_HOME}/plugins" "${JENKINS_HOME}/init.groovy.d" /tmp/jenkins-war-libs

    echo "[1/4] Extracting base Jenkins WAR detached plugins (WITHOUT logfilesizechecker.hpi!)..."
    cd /tmp/jenkins-war-libs
    jar xf /usr/share/jenkins/jenkins.war WEB-INF/lib/ WEB-INF/detached-plugins/
    for hpi in /tmp/jenkins-war-libs/WEB-INF/detached-plugins/*.hpi; do
        base=$(basename "$hpi" .hpi)
        cp "$hpi" "${JENKINS_HOME}/plugins/${base}.jpi"
    done

    if [ "${INSTALL_WORKFLOW_CPS}" = "true" ]; then
        echo "[1b/4] Installing official Pipeline & Shared Library plugins via jenkins-plugin-cli..."
        jenkins-plugin-cli \
            --war /usr/share/jenkins/jenkins.war \
            --plugin-download-directory "${JENKINS_HOME}/plugins" \
            --plugins workflow-job workflow-cps workflow-durable-task-step workflow-basic-steps pipeline-groovy-lib git
    fi

    # Verify logfilesizechecker plugin is NOT present in plugins directory
    if ls "${JENKINS_HOME}/plugins"/logfilesizechecker* >/dev/null 2>&1; then
        echo "ERROR: logfilesizechecker plugin must NOT be installed in plugin-free test!"
        exit 1
    fi

    echo "[2/4] Initializing local Git repository for Global Shared Library (/workspace/groovy/vars)..."
    git config --global protocol.file.allow always
    mkdir -p /tmp/shared-lib-repo/vars
    cp -f /workspace/groovy/vars/logfilesizechecker.groovy /tmp/shared-lib-repo/vars/logfilesizechecker.groovy
    (
        cd /tmp/shared-lib-repo
        git init -b master >/dev/null 2>&1 || (git init >/dev/null 2>&1 && git checkout -b master >/dev/null 2>&1)
        git config user.email "e2e@jenkins.local"
        git config user.name "Jenkins E2E"
        git add vars/logfilesizechecker.groovy
        git commit -m "Add Option 1 vars/logfilesizechecker.groovy" >/dev/null
    )

    cp /workspace/test/e2e/init_groovy_func_e2e.groovy "${JENKINS_HOME}/init.groovy.d/01-run-groovy-func-e2e.groovy"

    echo "[3/4] Booting plugin-free live Jenkins controller on port 18080 (mapped to 8080)..."
    nohup java \
        -Djava.awt.headless=true \
        -Djenkins.install.runSetupWizard=false \
        -Dhudson.model.DirectoryBrowserSupport.CSP= \
        -Dhudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT=true \
        -Djenkins.plugins.git.GitSCMSource.ALLOW_LOCAL_CHECKOUT=true \
        -Dhudson.remoting.ClassFilter=GroovyFuncLiveStepBuilder,GroovyFuncE2ESuiteRunnerBuilder \
        -Dhudson.model.UpdateCenter.updateCenterUrl=http://127.0.0.1:1/ \
        -jar /usr/share/jenkins/jenkins.war \
        --httpPort=18080 > /tmp/jenkins_server.log 2>&1 < /dev/null &

    echo "[4/4] Polling live Jenkins REST API for groovy-func-e2e-verification-suite #1 completion..."
    BUILD_FINISHED=false
    for i in $(seq 1 180); do
        if curl -fsS "http://localhost:18080/job/groovy-func-e2e-verification-suite/1/api/json" > /tmp/gf_build_api.json 2>/dev/null; then
            if grep -q "\"building\":false" /tmp/gf_build_api.json && ! grep -q "\"result\":null" /tmp/gf_build_api.json; then
                BUILD_FINISHED=true
                break
            fi
        fi
        sleep 1
    done

    if [ "${BUILD_FINISHED}" != "true" ]; then
        echo "ERROR: Timed out waiting for groovy-func-e2e-verification-suite #1 to complete."
        cat /tmp/jenkins_server.log || true
        exit 1
    fi

    CONSOLE_OUT=$(curl -fsS "http://localhost:18080/job/groovy-func-e2e-verification-suite/1/consoleText")
    echo "${CONSOLE_OUT}"

    BUILD_JSON=$(curl -fsS "http://localhost:18080/job/groovy-func-e2e-verification-suite/1/api/json")
    TEST_JSON=$(curl -fsS "http://localhost:18080/job/groovy-func-e2e-verification-suite/1/testReport/api/json")
    PLUGINS_JSON=$(curl -fsS "http://localhost:18080/pluginManager/api/json?depth=1")

    # Verify plugin is NOT installed & all 7 E2E cases passed
    if echo "${PLUGINS_JSON}" | grep -q "\"shortName\":\"logfilesizechecker\""; then
        echo "ERROR: logfilesizechecker plugin was unexpectedly found in /pluginManager/api/json!"
        exit 1
    fi
    echo "${BUILD_JSON}" | grep -q "\"result\":\"SUCCESS\""
    echo "${TEST_JSON}" | grep -q "\"failCount\":0"
    echo "${TEST_JSON}" | grep -q "\"passCount\":7"

    ECHARTS_JS=$(find /tmp/jenkins_home/plugins -name "echarts.min.js" 2>/dev/null | head -n 1 || true)
    if [ -n "${ECHARTS_JS}" ]; then
        cp -f "${ECHARTS_JS}" /workspace/target/site/allure-report/assets/echarts.min.js
    fi
    BOOTSTRAP_CSS=$(find /tmp/jenkins_home/plugins -name "bootstrap-custom-build.css" 2>/dev/null | head -n 1 || true)
    if [ -n "${BOOTSTRAP_CSS}" ]; then
        cp -f "${BOOTSTRAP_CSS}" /workspace/target/site/allure-report/assets/bootstrap.min.css
    fi
'

docker exec -u 0 "${CONTAINER_NAME}" chmod -R 777 /workspace/target

python3 "${PROJECT_DIR}/test/e2e/generate_allure_dashboard.py"

docker exec "${CONTAINER_NAME}" bash -c '
    mkdir -p /tmp/jenkins_home/userContent/allure-report/assets
    cp -rf /workspace/target/site/allure-report/. /tmp/jenkins_home/userContent/allure-report/
'

docker exec -d "${CONTAINER_NAME}" bash -c '
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
    if curl -fsS "http://localhost:18081/" >/dev/null 2>&1 && curl -fsS "http://localhost:8080/api/json" >/dev/null 2>&1; then
        break
    fi
    sleep 1
done

curl -fsS "http://localhost:8080/job/groovy-func-e2e-verification-suite/1/testReport/" >/dev/null
curl -fsS "http://localhost:8080/userContent/allure-report/index.html" >/dev/null
curl -fsS "http://localhost:3000/" >/dev/null
curl -fsS "http://localhost:8000/" >/dev/null

echo "=================================================================="
echo "  PLUGIN-FREE GROOVY FUNCTION (OPTION 1) VERIFIED: 7/7 PASSED     "
echo "  [Live Jenkins Dashboard (8080)]:   http://localhost:8080/"
echo "  [JUnit Suite Report (8080)]:       http://localhost:8080/job/groovy-func-e2e-verification-suite/1/testReport/"
echo "  [Allure Report UI (3000)]:         http://localhost:3000/"
if [ -n "${WEB_HOST:-}" ]; then
    echo "  [Browser URL - Jenkins UI]:        https://8080-${WEB_HOST}/"
    echo "  [Browser URL - JUnit Report]:      https://8080-${WEB_HOST}/job/groovy-func-e2e-verification-suite/1/testReport/"
    echo "  [Browser URL - Allure Report]:     https://3000-${WEB_HOST}/"
fi
echo "=================================================================="

if [ "${STOP_CONTAINER_ON_EXIT:-false}" = "true" ]; then
    docker rm -f "${CONTAINER_NAME}" >/dev/null 2>&1 || true
fi
