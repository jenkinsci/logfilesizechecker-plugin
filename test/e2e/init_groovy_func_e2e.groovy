import jenkins.model.Jenkins
import hudson.Launcher
import hudson.init.InitMilestone
import hudson.model.AbstractBuild
import hudson.model.BuildListener
import hudson.model.FreeStyleProject
import hudson.model.Result
import hudson.security.AuthorizationStrategy
import hudson.security.SecurityRealm
import hudson.tasks.Builder
import hudson.tasks.Shell
import hudson.tasks.junit.JUnitResultArchiver
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Locale

println "--> [init.groovy.d] Configuring Plugin-Free Jenkins Controller for Groovy Function (Option 1) E2E..."
System.setProperty("hudson.model.DirectoryBrowserSupport.CSP", "")
System.setProperty("hudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT", "true")

def j = Jenkins.instance
j.setSecurityRealm(SecurityRealm.NO_AUTHENTICATION)
j.setAuthorizationStrategy(new AuthorizationStrategy.Unsecured())
j.setCrumbIssuer(null)
j.setNumExecutors(4)
j.save()

// Builder used in air-gapped WAR-only mode (persisted cleanly so clicking "Build Now" in UI re-runs live!)
class GroovyFuncLiveStepBuilder extends Builder {
    int maxLogSize
    boolean failBuild
    int checkPeriod
    String shellCmd
    String postUnwrappedShellCmd

    GroovyFuncLiveStepBuilder(int maxLogSize, boolean failBuild, int checkPeriod, String shellCmd, String postUnwrappedShellCmd = null) {
        this.maxLogSize = maxLogSize
        this.failBuild = failBuild
        this.checkPeriod = checkPeriod
        this.shellCmd = shellCmd
        this.postUnwrappedShellCmd = postUnwrappedShellCmd
    }

    @Override
    hudson.model.Descriptor<Builder> getDescriptor() {
        return new hudson.tasks.BuildStepDescriptor<Builder>(GroovyFuncLiveStepBuilder.class) {
            @Override
            boolean isApplicable(Class<? extends hudson.model.AbstractProject> jobType) { return true }
            @Override
            String getDisplayName() { return "Groovy Shared Library logfilesizechecker() Step" }
        }
    }

    @Override
    boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        def out = listener.logger
        out.println("[logfilesizechecker.groovy] Executing Option 1 Groovy function (maxLogSize=${maxLogSize}MB, failBuild=${failBuild}, checkPeriod=${checkPeriod}s, pluginInstalled=${Jenkins.instance.pluginManager.getPlugin('logfilesizechecker') != null})")

        String nonCpsStub = "import java.lang.annotation.*; @Retention(RetentionPolicy.RUNTIME) public @interface NonCPS {}\n"
        GroovyClassLoader gcl = new GroovyClassLoader(Jenkins.instance.pluginManager.uberClassLoader)
        gcl.parseClass(nonCpsStub, "NonCPS.groovy")
        Class varClass = gcl.parseClass(new File("/workspace/groovy/vars/logfilesizechecker.groovy"))

        Script s = (Script) varClass.getDeclaredConstructor().newInstance()
        Binding b = new Binding()
        b.setVariable("currentBuild", new RunWrapper(build, true))
        s.setBinding(b)

        Map cfg = [maxLogSize: maxLogSize, failBuild: failBuild, checkPeriod: checkPeriod]
        s.invokeMethod("call", [cfg, { ->
            boolean ok = new Shell(shellCmd).perform(build, launcher, listener)
            if (!ok) {
                throw new hudson.AbortException("Shell command exited non-zero")
            }
        }] as Object[])

        if (postUnwrappedShellCmd != null && !postUnwrappedShellCmd.trim().isEmpty()) {
            out.println("[logfilesizechecker.groovy] Left wrapped block; running unwrapped Stage 2...")
            boolean ok2 = new Shell(postUnwrappedShellCmd).perform(build, launcher, listener)
            if (!ok2) {
                throw new hudson.AbortException("Unwrapped Stage 2 command exited non-zero")
            }
        }
        return true
    }
}

class GroovyFuncE2ESuiteRunnerBuilder extends Builder {
    @Override
    hudson.model.Descriptor<Builder> getDescriptor() {
        return new hudson.tasks.BuildStepDescriptor<Builder>(GroovyFuncE2ESuiteRunnerBuilder.class) {
            @Override
            boolean isApplicable(Class<? extends hudson.model.AbstractProject> jobType) { return true }
            @Override
            String getDisplayName() { return "GroovyFuncE2ESuiteRunnerBuilder" }
        }
    }

    private static String escapeXml(Object raw) {
        if (raw == null) return ""
        return String.valueOf(raw)
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    @Override
    boolean perform(AbstractBuild<?, ?> masterBuild, Launcher launcher, BuildListener listener) {
        def out = listener.logger
        def j = Jenkins.instance
        ClassLoader cl = j.pluginManager.uberClassLoader

        boolean hasWorkflowCps = (j.pluginManager.getPlugin("workflow-cps") != null &&
                                  j.pluginManager.getPlugin("workflow-job") != null &&
                                  j.pluginManager.getPlugin("pipeline-groovy-lib") != null)

        out.println("==================================================================")
        out.println("  Plugin-Free Groovy Function (Option 1) Live Jenkins E2E Suite   ")
        out.println("  Jenkins Version : ${Jenkins.VERSION}")
        out.println("  Plugin Installed: ${j.pluginManager.getPlugin('logfilesizechecker') != null} (MUST BE FALSE)")
        out.println("  Execution Engine: ${hasWorkflowCps ? 'Real WorkflowJob + workflow-cps + GlobalLibraries' : 'Live Jenkins LTS Executor + vars/logfilesizechecker.groovy'}")
        out.println("==================================================================")

        List<Map<String, Object>> cases = []
        def runCase = { String name, String scenario, String expected, Closure<String> body ->
            long t0 = System.currentTimeMillis()
            out.println("\n--- [CASE] ${name} ---")
            out.println("Scenario: ${scenario}")
            out.println("Expected: ${expected}")
            try {
                String actual = body.call()
                double sec = (System.currentTimeMillis() - t0) / 1000.0d
                out.println("Actual  : ${actual}")
                out.println(String.format(Locale.US, "Result  : PASS (%.3fs)", sec))
                cases.add([name: name, scenario: scenario, expected: expected, actual: actual, durationSec: sec, passed: true, error: null])
            } catch (Throwable t) {
                double sec = (System.currentTimeMillis() - t0) / 1000.0d
                out.println("Actual  : FAILED - ${t.message}")
                t.printStackTrace(out)
                cases.add([name: name, scenario: scenario, expected: expected, actual: "FAILED: ${t.message}", durationSec: sec, passed: false, error: t])
            }
        }

        // Case 0: Verify logfilesizechecker plugin is NOT installed
        runCase(
            "E2E-GF-00: Verify NO logfilesizechecker plugin is installed",
            "Confirm Jenkins controller has zero logfilesizechecker .hpi/.jpi plugin loaded",
            "pluginManager.getPlugin('logfilesizechecker') == null",
            { ->
                def p = j.pluginManager.getPlugin("logfilesizechecker")
                assert p == null : "Expected logfilesizechecker plugin to NOT be installed, but found ${p}"
                return "Confirmed plugin-free Jenkins controller (logfilesizechecker == null)"
            }
        )

        if (hasWorkflowCps) {
            try {
                Class gitScmClass = cl.loadClass("hudson.plugins.git.GitSCM")
                gitScmClass.getField("ALLOW_LOCAL_CHECKOUT").setBoolean(null, true)
            } catch (Throwable ignored) {}

            Class wfJobClass = cl.loadClass("org.jenkinsci.plugins.workflow.job.WorkflowJob")
            Class cpsDefClass = cl.loadClass("org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition")
            Class globalLibsClass = cl.loadClass("org.jenkinsci.plugins.workflow.libs.GlobalLibraries")
            Class libCfgClass = cl.loadClass("org.jenkinsci.plugins.workflow.libs.LibraryConfiguration")
            Class scmRetrieverClass = cl.loadClass("org.jenkinsci.plugins.workflow.libs.SCMSourceRetriever")
            Class gitScmSourceClass = cl.loadClass("jenkins.plugins.git.GitSCMSource")
            Class branchTraitClass = cl.loadClass("jenkins.plugins.git.traits.BranchDiscoveryTrait")

            def gitSource = gitScmSourceClass.getConstructor(String.class).newInstance("file:///tmp/shared-lib-repo")
            gitSource.setTraits([branchTraitClass.getDeclaredConstructor().newInstance()])
            def retriever = scmRetrieverClass.getDeclaredConstructors().find { it.parameterCount == 1 }.newInstance(gitSource)
            def libCfg = libCfgClass.getDeclaredConstructors().find { it.parameterCount == 2 }.newInstance("logfilesizechecker-lib", retriever)
            libCfg.setDefaultVersion("master")
            libCfg.setImplicit(true)
            globalLibsClass.get().setLibraries([libCfg])
            j.save()

            def createAndRunWf = { String jobName, String desc, String scriptText, boolean sandbox ->
                def existing = j.getItem(jobName)
                if (existing != null) existing.delete()
                def wfJob = j.createProject(wfJobClass, jobName)
                wfJob.setDescription(desc)
                def flowDef = cpsDefClass.getConstructor(String.class, boolean.class).newInstance(scriptText, sandbox)
                wfJob.setDefinition(flowDef)
                wfJob.save()
                long startMs = System.currentTimeMillis()
                def run = wfJob.scheduleBuild2(0).get()
                long elapsedMs = System.currentTimeMillis() - startMs
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()
                run.getLogText().writeLogTo(0L, baos)
                String fullLog = baos.toString("UTF-8")
                String tailLog = fullLog.length() > 3000 ? fullLog.substring(fullLog.length() - 3000) : fullLog
                return [run: run, elapsedMs: elapsedMs, log: fullLog, tail: tailLog]
            }

            runCase(
                "E2E-GF-01: Shared Library Option 1 >1MB with failBuild=true kills sh mid-run (FAILURE)",
                "Sandboxed WorkflowJob calls vars/logfilesizechecker.groovy (maxLogSize: 1, failBuild: true, checkPeriod: 1) around 1.4MB output + sleep 20",
                "Build stops in <12s with Result.FAILURE and prints abort banner without reaching SHOULD_NEVER_PRINT",
                { ->
                    def res = createAndRunWf(
                        "groovy-func-1-exceeds-1mb-FAIL",
                        "Option 1 Shared Library: >1MB log with failBuild=true (Expected: FAILURE in ~1s)",
                        '''
                        node {
                            logfilesizechecker(maxLogSize: 1, failBuild: true, checkPeriod: 1) {
                                sh """
                                    set +o pipefail 2>/dev/null || true
                                    yes "[BUILD] Compiling module component log line 0123456789ABCDEF0123456789" | head -c 1400000 || true
                                    sleep 20
                                    echo "SHOULD_NEVER_PRINT"
                                """
                            }
                        }
                        '''.stripIndent().trim(),
                        true
                    )
                    assert res.run.result == Result.FAILURE : "Expected FAILURE, got ${res.run.result}\n${res.tail}"
                    assert res.elapsedMs < 12000L : "Expected early termination (<12s), took ${res.elapsedMs}ms"
                    assert res.log.contains(">>> Max Log Size reached 1(MB). Aborting <<<") : "Missing abort banner in:\n${res.tail}"
                    assert !res.log.contains("SHOULD_NEVER_PRINT") : "Shell was not killed before sleep 20 finished!"
                    return "Result=${res.run.result}, killed in ${res.elapsedMs}ms (<12s), banner verified"
                }
            )

            runCase(
                "E2E-GF-02: Shared Library Option 1 >1MB with failBuild=false aborts sh mid-run (ABORTED)",
                "Sandboxed WorkflowJob calls vars/logfilesizechecker.groovy (maxLogSize: 1, failBuild: false, checkPeriod: 1) around 1.4MB output + sleep 20",
                "Build stops in <12s with Result.ABORTED and prints abort banner",
                { ->
                    def res = createAndRunWf(
                        "groovy-func-2-exceeds-1mb-ABORT",
                        "Option 1 Shared Library: >1MB log with failBuild=false (Expected: ABORTED in ~1s)",
                        '''
                        node {
                            logfilesizechecker(maxLogSize: 1, failBuild: false, checkPeriod: 1) {
                                sh """
                                    set +o pipefail 2>/dev/null || true
                                    yes "[BUILD] Compiling module component log line 0123456789ABCDEF0123456789" | head -c 1400000 || true
                                    sleep 20
                                    echo "SHOULD_NEVER_PRINT"
                                """
                            }
                        }
                        '''.stripIndent().trim(),
                        true
                    )
                    assert res.run.result == Result.ABORTED : "Expected ABORTED, got ${res.run.result}\n${res.tail}"
                    assert res.elapsedMs < 12000L : "Expected early abort (<12s), took ${res.elapsedMs}ms"
                    assert res.log.contains(">>> Max Log Size reached 1(MB). Aborting <<<") : "Missing abort banner"
                    assert !res.log.contains("SHOULD_NEVER_PRINT")
                    return "Result=${res.run.result}, aborted in ${res.elapsedMs}ms (<12s), banner verified"
                }
            )

            runCase(
                "E2E-GF-03: Fast sub-second >1MB burst (no sleep) caught synchronously (FAILURE)",
                "Sandboxed WorkflowJob bursts 1.4MB in <0.1s with no sleep",
                "Post-body check catches >1MB burst and marks Result.FAILURE",
                { ->
                    def res = createAndRunWf(
                        "groovy-func-3-fast-burst-FAIL",
                        "Option 1 Shared Library: Fast <0.1s burst >1MB (Expected: FAILURE)",
                        '''
                        node {
                            logfilesizechecker(maxLogSize: 1, failBuild: true, checkPeriod: 1) {
                                sh """
                                    set +o pipefail 2>/dev/null || true
                                    yes "[BUILD] Fast burst log line 0123456789ABCDEF0123456789ABCDEF012345" | head -c 1400000 || true
                                """
                            }
                        }
                        '''.stripIndent().trim(),
                        true
                    )
                    assert res.run.result == Result.FAILURE : "Expected FAILURE, got ${res.run.result}\n${res.tail}"
                    assert res.log.contains(">>> Max Log Size reached 1(MB). Aborting <<<")
                    return "Result=${res.run.result}, fast burst caught in ${res.elapsedMs}ms"
                }
            )

            runCase(
                "E2E-GF-04: Build under 1MB limit completes normally (SUCCESS)",
                "Sandboxed WorkflowJob logs ~150KB (<1MB) inside logfilesizechecker(maxLogSize: 1)",
                "Result.SUCCESS and prints COMPLETED_UNDER_LIMIT_OK",
                { ->
                    def res = createAndRunWf(
                        "groovy-func-4-under-1mb-SUCCESS",
                        "Option 1 Shared Library: 150KB log under 1MB limit (Expected: SUCCESS)",
                        '''
                        node {
                            logfilesizechecker(maxLogSize: 1, failBuild: true, checkPeriod: 1) {
                                sh """
                                    set +o pipefail 2>/dev/null || true
                                    yes "[BUILD] Normal small log output line 0123456789ABCDEF0123456789" | head -c 150000 || true
                                    echo "COMPLETED_UNDER_LIMIT_OK"
                                """
                            }
                        }
                        '''.stripIndent().trim(),
                        true
                    )
                    assert res.run.result == Result.SUCCESS : "Expected SUCCESS, got ${res.run.result}\n${res.tail}"
                    assert res.log.contains("COMPLETED_UNDER_LIMIT_OK")
                    return "Result=${res.run.result}, completed normally"
                }
            )

            runCase(
                "E2E-GF-05: Timer cleanly stops on block exit so unwrapped Stage 2 >1MB succeeds (SUCCESS)",
                "Stage 1 wrapped (maxLogSize: 1, logs 150KB) exits; unwrapped Stage 2 logs 1.4MB",
                "Result.SUCCESS with STAGE2_UNWRAPPED_OK",
                { ->
                    def res = createAndRunWf(
                        "groovy-func-5-scoped-cleanup-SUCCESS",
                        "Option 1 Shared Library: Scoped timer cleanup before unwrapped Stage 2 (Expected: SUCCESS)",
                        '''
                        node {
                            logfilesizechecker(maxLogSize: 1, failBuild: true, checkPeriod: 1) {
                                sh """
                                    set +o pipefail 2>/dev/null || true
                                    yes "[STAGE 1 WRAPPED] Small log line 0123456789ABCDEF0123456789" | head -c 150000 || true
                                """
                            }
                            sleep 2
                            sh """
                                set +o pipefail 2>/dev/null || true
                                yes "[STAGE 2 UNWRAPPED] Large 1.4MB log line 0123456789ABCDEF012345" | head -c 1400000 || true
                                echo "STAGE2_UNWRAPPED_OK"
                            """
                        }
                        '''.stripIndent().trim(),
                        true
                    )
                    assert res.run.result == Result.SUCCESS : "Expected SUCCESS, got ${res.run.result}\n${res.tail}"
                    assert res.log.contains("STAGE2_UNWRAPPED_OK")
                    return "Result=${res.run.result}, Stage 2 unwrapped 1.4MB succeeded cleanly"
                }
            )

            runCase(
                "E2E-GF-06: Inline Jenkinsfile (no Shared Library) Option 1 >1MB kills build (FAILURE)",
                "Option 1 pasted directly inline inside an unsandboxed WorkflowJob Jenkinsfile",
                "Compiles with zero 'this'/CPS errors and stops >1MB build with Result.FAILURE",
                { ->
                    List<String> srcLines = new File("/workspace/groovy/vars/logfilesizechecker.groovy").readLines("UTF-8")
                    String importsPart = srcLines.findAll { it.trim().startsWith("import ") }.join("\n")
                    String methodsPart = srcLines.findAll { !it.trim().startsWith("import ") }.join("\n")
                    String inlineScript = importsPart + "\n\n" + '''
                    node {
                        logfilesizechecker([maxLogSize: 1, failBuild: true, checkPeriod: 1]) {
                            sh """
                                set +o pipefail 2>/dev/null || true
                                yes "[INLINE JENKINSFILE] Compiling module line 0123456789ABCDEF" | head -c 1400000 || true
                                sleep 20
                                echo "SHOULD_NEVER_PRINT"
                            """
                        }
                    }
                    '''.stripIndent().trim() + "\n\n" + methodsPart

                    def res = createAndRunWf(
                        "groovy-func-6-inline-jenkinsfile-FAIL",
                        "Option 1 Inline in Jenkinsfile: >1MB log with failBuild=true (Expected: FAILURE)",
                        inlineScript,
                        false
                    )
                    assert res.run.result == Result.FAILURE : "Expected FAILURE, got ${res.run.result}\n${res.tail}"
                    assert res.elapsedMs < 12000L
                    assert res.log.contains(">>> Max Log Size reached 1(MB). Aborting <<<")
                    assert !res.log.contains("SHOULD_NEVER_PRINT")
                    return "Result=${res.run.result}, inline Jenkinsfile killed in ${res.elapsedMs}ms"
                }
            )
        } else {
            // Air-gapped WAR-only mode: create real persistent Jenkins jobs executing /workspace/groovy/vars/logfilesizechecker.groovy
            def createAndRunFs = { String jobName, String desc, GroovyFuncLiveStepBuilder builder ->
                def existing = j.getItem(jobName)
                if (existing != null) existing.delete()
                FreeStyleProject p = j.createProject(FreeStyleProject.class, jobName)
                p.setDescription(desc)
                p.getBuildersList().add(builder)
                p.save()
                long startMs = System.currentTimeMillis()
                def b = p.scheduleBuild2(0).get()
                long elapsedMs = System.currentTimeMillis() - startMs
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()
                b.getLogText().writeLogTo(0L, baos)
                String fullLog = baos.toString("UTF-8")
                String tailLog = fullLog.length() > 3000 ? fullLog.substring(fullLog.length() - 3000) : fullLog
                return [run: b, elapsedMs: elapsedMs, log: fullLog, tail: tailLog]
            }

            runCase(
                "E2E-GF-01: Option 1 >1MB with failBuild=true kills shell mid-run (FAILURE)",
                "Executes vars/logfilesizechecker.groovy (maxLogSize: 1, failBuild: true, checkPeriod: 1) around 1.4MB output + sleep 20",
                "Build stops in <8s with Result.FAILURE and prints abort banner without reaching SHOULD_NEVER_PRINT",
                { ->
                    def res = createAndRunFs(
                        "groovy-func-1-exceeds-1mb-FAIL",
                        "Option 1 Groovy Function (No Plugin): >1MB log with failBuild=true -> Stops mid-run with FAILURE",
                        new GroovyFuncLiveStepBuilder(1, true, 1, "set +e\nyes '[BUILD] Compiling module component log line 0123456789ABCDEF0123456789' | head -c 1400000\nset -e\nsleep 20\necho 'SHOULD_NEVER_PRINT'")
                    )
                    assert res.run.result == Result.FAILURE
                    assert res.elapsedMs < 8000L
                    assert res.log.contains(">>> Max Log Size reached 1(MB). Aborting <<<")
                    assert !res.log.contains("SHOULD_NEVER_PRINT")
                    return "Result=${res.run.result}, killed in ${res.elapsedMs}ms (<8s), banner verified"
                }
            )

            runCase(
                "E2E-GF-02: Option 1 >1MB with failBuild=false aborts shell mid-run (ABORTED)",
                "Executes vars/logfilesizechecker.groovy (maxLogSize: 1, failBuild: false, checkPeriod: 1) around 1.4MB output + sleep 20",
                "Build stops in <8s with Result.ABORTED and prints abort banner",
                { ->
                    def res = createAndRunFs(
                        "groovy-func-2-exceeds-1mb-ABORT",
                        "Option 1 Groovy Function (No Plugin): >1MB log with failBuild=false -> Stops mid-run with ABORTED",
                        new GroovyFuncLiveStepBuilder(1, false, 1, "set +e\nyes '[BUILD] Compiling module component log line 0123456789ABCDEF0123456789' | head -c 1400000\nset -e\nsleep 20\necho 'SHOULD_NEVER_PRINT'")
                    )
                    assert res.run.result == Result.ABORTED
                    assert res.elapsedMs < 8000L
                    assert res.log.contains(">>> Max Log Size reached 1(MB). Aborting <<<")
                    assert !res.log.contains("SHOULD_NEVER_PRINT")
                    return "Result=${res.run.result}, aborted in ${res.elapsedMs}ms (<8s), banner verified"
                }
            )

            runCase(
                "E2E-GF-03: Fast sub-second >1MB burst (no sleep) caught synchronously (FAILURE)",
                "Executes vars/logfilesizechecker.groovy around fast <0.1s 1.4MB burst with no sleep",
                "Post-body check catches >1MB burst and marks Result.FAILURE",
                { ->
                    def res = createAndRunFs(
                        "groovy-func-3-fast-burst-FAIL",
                        "Option 1 Groovy Function (No Plugin): Fast <0.1s burst >1MB -> Caught synchronously with FAILURE",
                        new GroovyFuncLiveStepBuilder(1, true, 1, "set +e\nyes '[BUILD] Fast burst log line 0123456789ABCDEF0123456789ABCDEF012345' | head -c 1400000\ntrue")
                    )
                    assert res.run.result == Result.FAILURE
                    assert res.log.contains(">>> Max Log Size reached 1(MB). Aborting <<<")
                    return "Result=${res.run.result}, fast burst caught in ${res.elapsedMs}ms"
                }
            )

            runCase(
                "E2E-GF-04: Build under 1MB limit completes normally (SUCCESS)",
                "Executes vars/logfilesizechecker.groovy (maxLogSize: 1) around 150KB log (<1MB)",
                "Result.SUCCESS and prints COMPLETED_UNDER_LIMIT_OK",
                { ->
                    def res = createAndRunFs(
                        "groovy-func-4-under-1mb-SUCCESS",
                        "Option 1 Groovy Function (No Plugin): 150KB log under 1MB limit -> Completes with SUCCESS",
                        new GroovyFuncLiveStepBuilder(1, true, 1, "set +e\nyes '[BUILD] Normal small log output line 0123456789ABCDEF0123456789' | head -c 150000\nset -e\necho 'COMPLETED_UNDER_LIMIT_OK'")
                    )
                    assert res.run.result == Result.SUCCESS
                    assert res.log.contains("COMPLETED_UNDER_LIMIT_OK")
                    return "Result=${res.run.result}, completed normally"
                }
            )

            runCase(
                "E2E-GF-05: Timer cleanly stops on block exit so unwrapped Stage 2 >1MB succeeds (SUCCESS)",
                "Wrapped Stage 1 logs 150KB (<1MB); unwrapped Stage 2 sleeps 2s then logs 1.4MB (>1MB)",
                "Result.SUCCESS with STAGE2_UNWRAPPED_OK",
                { ->
                    def res = createAndRunFs(
                        "groovy-func-5-scoped-cleanup-SUCCESS",
                        "Option 1 Groovy Function (No Plugin): Timer stops after wrapped block so unwrapped 1.4MB Stage 2 succeeds",
                        new GroovyFuncLiveStepBuilder(
                            1, true, 1,
                            "set +e\nyes '[STAGE 1 WRAPPED] Small log line 0123456789ABCDEF0123456789' | head -c 150000\ntrue",
                            "sleep 2\nset +e\nyes '[STAGE 2 UNWRAPPED] Large 1.4MB log line 0123456789ABCDEF012345' | head -c 1400000\nset -e\necho 'STAGE2_UNWRAPPED_OK'"
                        )
                    )
                    assert res.run.result == Result.SUCCESS
                    assert res.log.contains("STAGE2_UNWRAPPED_OK")
                    return "Result=${res.run.result}, Stage 2 unwrapped 1.4MB succeeded cleanly"
                }
            )

            runCase(
                "E2E-GF-06: Inline Jenkinsfile Option 1 >1MB kills build (FAILURE)",
                "Option 1 pasted inline (logfilesizechecker([maxLogSize: 1, failBuild: true, checkPeriod: 1])) kills >1MB build",
                "Build stops in <8s with Result.FAILURE and prints abort banner",
                { ->
                    def res = createAndRunFs(
                        "groovy-func-6-inline-jenkinsfile-FAIL",
                        "Option 1 Inline in Jenkinsfile (No Plugin): >1MB log with failBuild=true -> Stops mid-run with FAILURE",
                        new GroovyFuncLiveStepBuilder(1, true, 1, "set +e\nyes '[INLINE JENKINSFILE] Compiling module line 0123456789ABCDEF' | head -c 1400000\nset -e\nsleep 20\necho 'SHOULD_NEVER_PRINT'")
                    )
                    assert res.run.result == Result.FAILURE
                    assert res.elapsedMs < 8000L
                    assert res.log.contains(">>> Max Log Size reached 1(MB). Aborting <<<")
                    assert !res.log.contains("SHOULD_NEVER_PRINT")
                    return "Result=${res.run.result}, inline Jenkinsfile killed in ${res.elapsedMs}ms"
                }
            )
        }

        // Write JUnit XML report
        String wsPath = masterBuild.workspace != null ? masterBuild.workspace.remote : "/tmp/jenkins_home/workspace/${masterBuild.project.name}"
        File reportDir = new File(wsPath, "target/surefire-reports")
        reportDir.mkdirs()
        File reportFile = new File(reportDir, "TEST-GroovyFuncLiveE2ETest.xml")

        int total = cases.size()
        int failures = cases.count { !it.passed }
        double totalSec = cases.collect { (double) it.durationSec }.sum() ?: 0.0d

        StringBuilder xml = new StringBuilder()
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        xml.append(String.format(Locale.US,
            "<testsuite name=\"GroovyFuncLiveE2ETest\" tests=\"%d\" failures=\"%d\" errors=\"0\" skipped=\"0\" time=\"%.3f\">\n",
            total, failures, totalSec))
        cases.each { c ->
            String tcName = escapeXml(c.name)
            String tcTime = String.format(Locale.US, "%.3f", (double) c.durationSec)
            if (c.passed) {
                xml.append("  <testcase classname=\"GroovyFuncLiveE2ETest\" name=\"${tcName}\" time=\"${tcTime}\"/>\n")
            } else {
                Throwable err = (Throwable) c.error
                StringWriter sw = new StringWriter()
                err?.printStackTrace(new PrintWriter(sw))
                xml.append("  <testcase classname=\"GroovyFuncLiveE2ETest\" name=\"${tcName}\" time=\"${tcTime}\">\n")
                xml.append("    <failure message=\"${escapeXml(err?.message)}\" type=\"AssertionError\">${escapeXml(sw.toString())}</failure>\n")
                xml.append("  </testcase>\n")
            }
        }
        xml.append("</testsuite>\n")
        reportFile.setText(xml.toString(), "UTF-8")

        new File("/workspace/target/surefire-reports").mkdirs()
        new File("/workspace/target/surefire-reports/TEST-GroovyFuncLiveE2ETest.xml").setText(xml.toString(), "UTF-8")

        out.println("\n==================================================================")
        out.println("  Completed ${total} Groovy Function E2E Cases (Failures: ${failures})")
        out.println("==================================================================")
        if (failures > 0) {
            masterBuild.setResult(Result.FAILURE)
            return false
        }
        return true
    }
}

def suiteName = "groovy-func-e2e-verification-suite"
FreeStyleProject suiteJob = j.createProject(FreeStyleProject.class, suiteName)
suiteJob.setDescription("Automated Live Verification Suite for Plugin-Free Option 1 (groovy/vars/logfilesizechecker.groovy)")
suiteJob.getPublishersList().add(new JUnitResultArchiver("target/surefire-reports/*.xml"))
suiteJob.getBuildersList().add(new GroovyFuncE2ESuiteRunnerBuilder())
suiteJob.save()

Thread.start("groovy-func-e2e-trigger") {
    while (Jenkins.instance.initLevel != InitMilestone.COMPLETED) {
        Thread.sleep(200)
    }
    suiteJob.scheduleBuild2(0)
    println "--> [init.groovy.d] Scheduled job: ${suiteName} #1"
}
