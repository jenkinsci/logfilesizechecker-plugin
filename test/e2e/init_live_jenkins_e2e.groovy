import jenkins.model.Jenkins
import jenkins.model.CauseOfInterruption
import jenkins.model.InterruptedBuildAction
import hudson.FilePath
import hudson.Launcher
import hudson.init.InitMilestone
import hudson.model.AbstractBuild
import hudson.model.BuildListener
import hudson.model.FreeStyleProject
import hudson.model.FreeStyleBuild
import hudson.model.Result
import hudson.security.AuthorizationStrategy
import hudson.security.SecurityRealm
import hudson.tasks.Builder
import hudson.tasks.Shell
import hudson.tasks.junit.JUnitResultArchiver
import hudson.plugins.logfilesizechecker.LogfilesizecheckerWrapper
import hudson.plugins.logfilesizechecker.LogfilesizecheckerWrapper.MaxLogSizeCause
import net.sf.json.JSONObject
import org.jenkinsci.Symbol
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.util.Locale

println "--> [init.groovy.d] Configuring Live Jenkins Controller for logfilesizechecker-plugin E2E..."
System.setProperty("hudson.model.DirectoryBrowserSupport.CSP", "")
def j = Jenkins.instance
j.setSecurityRealm(SecurityRealm.NO_AUTHENTICATION)
j.setAuthorizationStrategy(new AuthorizationStrategy.Unsecured())
j.setCrumbIssuer(null)
j.setNumExecutors(4)
j.save()

class LivePluginE2ERunnerBuilder extends Builder {
    @Override
    hudson.model.Descriptor<Builder> getDescriptor() {
        return new hudson.tasks.BuildStepDescriptor<Builder>(LivePluginE2ERunnerBuilder.class) {
            @Override
            boolean isApplicable(Class<? extends hudson.model.AbstractProject> jobType) { return true }
            @Override
            String getDisplayName() { return "LivePluginE2ERunnerBuilder" }
        }
    }

    private static String escapeXml(Object raw) {
        if (raw == null) return ""
        return raw.toString()
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private static void writeLoremFile(FreeStyleProject proj, int bytes) {
        FilePath ws = Jenkins.instance.getWorkspaceFor(proj)
        ws.mkdirs()
        byte[] chunk = ("Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.\n").getBytes(StandardCharsets.UTF_8)
        def out = ws.child("lorem2100kB").write()
        try {
            int written = 0
            while (written < bytes) {
                int len = Math.min(chunk.length, bytes - written)
                out.write(chunk, 0, len)
                written += len
            }
        } finally {
            out.close()
        }
    }

    private static FreeStyleProject createTestJob(String name, int maxLogSize, boolean failBuild, boolean setOwn) {
        def j = Jenkins.instance
        def existing = j.getItem(name)
        if (existing != null) {
            existing.delete()
        }
        FreeStyleProject p = j.createProject(FreeStyleProject.class, name)
        writeLoremFile(p, 2_150_400)
        p.getBuildersList().add(new Shell("cat lorem2100kB"))
        p.getBuildersList().add(new Shell("sleep 3"))
        p.getBuildersList().add(new Shell("cat lorem2100kB"))
        p.getBuildWrappersList().add(new LogfilesizecheckerWrapper(maxLogSize, failBuild, setOwn))
        p.save()
        return p
    }

    @Override
    boolean perform(AbstractBuild<?, ?> masterBuild, Launcher launcher, BuildListener listener) {
        def out = listener.logger
        out.println("==================================================================")
        out.println("  LIVE JENKINS CONTROLLER E2E SUITE — logfilesizechecker-plugin   ")
        out.println("==================================================================")
        List<Map<String, Object>> cases = []

        def runCase = { String name, String scenario, String expected, Closure<Map<String, String>> body ->
            long t0 = System.nanoTime()
            out.println("\n---> Running E2E Case: ${name}")
            out.println("     Scenario: ${scenario}")
            try {
                Map<String, String> res = body.call()
                double sec = (System.nanoTime() - t0) / 1e9d
                out.println("     [PASS] ${name} -> Actual: ${res.actual} | ${res.assertion} (${String.format(Locale.US, '%.3fs', sec)})")
                cases << [
                    name       : name,
                    scenario   : scenario,
                    expected   : expected,
                    actual     : res.actual,
                    assertion  : res.assertion,
                    durationSec: Math.round(sec * 1000.0d) / 1000.0d,
                    passed     : true,
                    error      : null
                ]
            } catch (Throwable t) {
                double sec = (System.nanoTime() - t0) / 1e9d
                out.println("     [FAIL] ${name}: ${t.message}")
                t.printStackTrace(out)
                cases << [
                    name       : name,
                    scenario   : scenario,
                    expected   : expected,
                    actual     : "ERROR: " + (t.message ?: t.class.simpleName),
                    assertion  : t.toString(),
                    durationSec: Math.round(sec * 1000.0d) / 1000.0d,
                    passed     : false,
                    error      : t
                ]
            }
        }

        def desc = LogfilesizecheckerWrapper.DESCRIPTOR
        desc.setDefaultLogSize(0)
        desc.setCheckPeriod(1)

        // Case 1: Plugin installed & active in live Jenkins PluginManager
        runCase(
            "e2e_01_plugin_installed_and_active",
            'Verify logfilesizechecker.jpi is loaded, active, enabled, and @Symbol("logfilesizechecker") is registered',
            "ACTIVE & ENABLED (1.6-SNAPSHOT)",
            {
                def pw = Jenkins.instance.pluginManager.getPlugin("logfilesizechecker")
                assert pw != null : "Plugin logfilesizechecker not found in PluginManager"
                assert pw.isActive() : "Plugin logfilesizechecker is not active"
                assert pw.isEnabled() : "Plugin logfilesizechecker is not enabled"
                def d = Jenkins.instance.getDescriptor(LogfilesizecheckerWrapper.class)
                assert d != null
                def sym = d.class.getAnnotation(Symbol.class)
                assert sym != null && sym.value().contains("logfilesizechecker")
                return [
                    actual   : "ACTIVE & ENABLED (${pw.version})",
                    assertion: "PluginManager active=true, enabled=true, Descriptor=${d.displayName}, @Symbol=logfilesizechecker"
                ]
            }
        )

        // Case 2: Job-specific maxLogSize=1MB, failBuild=true, setOwn=true -> FAILURE + MaxLogSizeCause
        runCase(
            "e2e_02_freestyle_job_specific_exceed_fail",
            "FreeStyle job (maxLogSize=1MB, failBuild=true, setOwn=true) writing >2.1MB log",
            "Result.FAILURE + MaxLogSizeCause",
            {
                desc.setDefaultLogSize(0)
                FreeStyleProject p = createTestJob("e2e-job-02-fail", 1, true, true)
                FreeStyleBuild b = p.scheduleBuild2(0).get()
                String log = b.getLog(1000).join("\n")
                assert b.result == Result.FAILURE : "Expected FAILURE but got ${b.result}"
                assert log.contains(">>> Max Log Size reached 1(MB). Aborting <<<")
                InterruptedBuildAction iba = b.getAction(InterruptedBuildAction.class)
                assert iba != null && iba.causes.any { it instanceof MaxLogSizeCause && ((MaxLogSizeCause) it).maxLogSize == 1 }
                return [
                    actual   : "Result.${b.result} + MaxLogSizeCause(1MB)",
                    assertion: "Build #1 interrupted with FAILURE and logged '>>> Max Log Size reached 1(MB). Aborting <<<'"
                ]
            }
        )

        // Case 3: Job-specific maxLogSize=1MB, failBuild=false, setOwn=true -> ABORTED + MaxLogSizeCause
        runCase(
            "e2e_03_freestyle_job_specific_exceed_abort",
            "FreeStyle job (maxLogSize=1MB, failBuild=false, setOwn=true) writing >2.1MB log",
            "Result.ABORTED + MaxLogSizeCause",
            {
                desc.setDefaultLogSize(0)
                FreeStyleProject p = createTestJob("e2e-job-03-abort", 1, false, true)
                FreeStyleBuild b = p.scheduleBuild2(0).get()
                String log = b.getLog(1000).join("\n")
                assert b.result == Result.ABORTED : "Expected ABORTED but got ${b.result}"
                assert log.contains(">>> Max Log Size reached 1(MB). Aborting <<<")
                InterruptedBuildAction iba = b.getAction(InterruptedBuildAction.class)
                assert iba != null && iba.causes.any { it instanceof MaxLogSizeCause }
                return [
                    actual   : "Result.${b.result} + MaxLogSizeCause(1MB)",
                    assertion: "Build #1 interrupted with ABORTED and logged '>>> Max Log Size reached 1(MB). Aborting <<<'"
                ]
            }
        )

        // Case 4: Job-specific maxLogSize=5MB, failBuild=true, setOwn=true -> SUCCESS (log ~4.2MB < 5MB)
        runCase(
            "e2e_04_freestyle_within_job_limit_success",
            "FreeStyle job (maxLogSize=5MB, failBuild=true, setOwn=true) writing ~4.2MB total log",
            "Result.SUCCESS",
            {
                desc.setDefaultLogSize(0)
                FreeStyleProject p = createTestJob("e2e-job-04-within-limit", 5, true, true)
                FreeStyleBuild b = p.scheduleBuild2(0).get()
                String log = b.getLog(200).join("\n")
                assert b.result == Result.SUCCESS : "Expected SUCCESS but got ${b.result}"
                assert !log.contains(">>> Max Log Size reached")
                return [
                    actual   : "Result.${b.result} (log=${b.logText.length()} bytes)",
                    assertion: "Build #1 completed normally beneath 5MB threshold"
                ]
            }
        )

        // Case 5: setOwn=false with global defaultLogSize=0 (unlimited) -> ignores job maxLogSize=1MB -> SUCCESS
        runCase(
            "e2e_05_freestyle_global_unlimited_success",
            "FreeStyle job (maxLogSize=1MB, failBuild=true, setOwn=false) with global defaultLogSize=0",
            "Result.SUCCESS",
            {
                desc.setDefaultLogSize(0)
                FreeStyleProject p = createTestJob("e2e-job-05-global-unlimited", 1, true, false)
                FreeStyleBuild b = p.scheduleBuild2(0).get()
                assert b.result == Result.SUCCESS : "Expected SUCCESS but got ${b.result}"
                return [
                    actual   : "Result.${b.result}",
                    assertion: "Ignored job maxLogSize=1MB because setOwn=false and global defaultLogSize=0"
                ]
            }
        )

        // Case 6: setOwn=false with global defaultLogSize=1MB and failBuild=true -> FAILURE
        runCase(
            "e2e_06_freestyle_global_limit_exceed_fail",
            "FreeStyle job (maxLogSize=0, failBuild=true, setOwn=false) with global defaultLogSize=1MB",
            "Result.FAILURE",
            {
                desc.setDefaultLogSize(1)
                try {
                    FreeStyleProject p = createTestJob("e2e-job-06-global-fail", 0, true, false)
                    FreeStyleBuild b = p.scheduleBuild2(0).get()
                    String log = b.getLog(1000).join("\n")
                    assert b.result == Result.FAILURE : "Expected FAILURE but got ${b.result}"
                    assert log.contains(">>> Max Log Size reached 1(MB). Aborting <<<")
                    return [
                        actual   : "Result.${b.result}",
                        assertion: "Enforced global defaultLogSize=1MB and marked build FAILURE"
                    ]
                } finally {
                    desc.setDefaultLogSize(0)
                }
            }
        )

        // Case 7: setOwn=false with global defaultLogSize=1MB and failBuild=false -> ABORTED
        runCase(
            "e2e_07_freestyle_global_limit_exceed_abort",
            "FreeStyle job (maxLogSize=0, failBuild=false, setOwn=false) with global defaultLogSize=1MB",
            "Result.ABORTED",
            {
                desc.setDefaultLogSize(1)
                try {
                    FreeStyleProject p = createTestJob("e2e-job-07-global-abort", 0, false, false)
                    FreeStyleBuild b = p.scheduleBuild2(0).get()
                    String log = b.getLog(1000).join("\n")
                    assert b.result == Result.ABORTED : "Expected ABORTED but got ${b.result}"
                    assert log.contains(">>> Max Log Size reached 1(MB). Aborting <<<")
                    return [
                        actual   : "Result.${b.result}",
                        assertion: "Enforced global defaultLogSize=1MB and marked build ABORTED"
                    ]
                } finally {
                    desc.setDefaultLogSize(0)
                }
            }
        )

        // Case 8: Job override smaller than global (global=5MB, job=1MB, setOwn=true) -> FAILURE
        runCase(
            "e2e_08_job_override_smaller_than_global",
            "Global defaultLogSize=5MB, job overrides with setOwn=true, maxLogSize=1MB, failBuild=true",
            "Result.FAILURE",
            {
                desc.setDefaultLogSize(5)
                try {
                    FreeStyleProject p = createTestJob("e2e-job-08-override-smaller", 1, true, true)
                    FreeStyleBuild b = p.scheduleBuild2(0).get()
                    assert b.result == Result.FAILURE : "Expected FAILURE but got ${b.result}"
                    return [
                        actual   : "Result.${b.result}",
                        assertion: "Job-specific 1MB limit overrode global 5MB limit and terminated build"
                    ]
                } finally {
                    desc.setDefaultLogSize(0)
                }
            }
        )

        // Case 9: Job override larger than global (global=1MB, job=5MB, setOwn=true) -> SUCCESS
        runCase(
            "e2e_09_job_override_larger_than_global",
            "Global defaultLogSize=1MB, job overrides with setOwn=true, maxLogSize=5MB, failBuild=true",
            "Result.SUCCESS",
            {
                desc.setDefaultLogSize(1)
                try {
                    FreeStyleProject p = createTestJob("e2e-job-09-override-larger", 5, true, true)
                    FreeStyleBuild b = p.scheduleBuild2(0).get()
                    assert b.result == Result.SUCCESS : "Expected SUCCESS but got ${b.result}"
                    return [
                        actual   : "Result.${b.result}",
                        assertion: "Job-specific 5MB limit overrode global 1MB limit and allowed 4.2MB build to succeed"
                    ]
                } finally {
                    desc.setDefaultLogSize(0)
                }
            }
        )

        // Case 10: Fast checkPeriod=1s stops build before post-sleep step executes
        runCase(
            "e2e_10_custom_check_period_fast_detection",
            "Check period=1s interrupts build during sleep before final step can run",
            "Interrupted before STEP_AFTER_SLEEP",
            {
                desc.setCheckPeriod(1)
                FreeStyleProject p = Jenkins.instance.createProject(FreeStyleProject.class, "e2e-job-10-fast-check")
                writeLoremFile(p, 2_150_400)
                p.getBuildersList().add(new Shell("cat lorem2100kB"))
                p.getBuildersList().add(new Shell("sleep 3"))
                p.getBuildersList().add(new Shell("echo SHOULD_NOT_REACH_THIS_STEP"))
                p.getBuildWrappersList().add(new LogfilesizecheckerWrapper(1, true, true))
                p.save()
                FreeStyleBuild b = p.scheduleBuild2(0).get()
                String log = b.getLog(1000).join("\n")
                assert b.result == Result.FAILURE
                assert !log.contains("SHOULD_NOT_REACH_THIS_STEP")
                return [
                    actual   : "Interrupted prior to 3rd build step",
                    assertion: "Timer fired at 1s interval during sleep and prevented subsequent build step execution"
                ]
            }
        )

        // Case 11: Disposer tearDown cancels ScheduledFuture<?> cleanly
        runCase(
            "e2e_11_disposer_cancels_scheduled_future",
            "Verify LogSizeTimerTaskDisposer.tearDown cancels ScheduledFuture<?> in Timer pool",
            "ScheduledFuture.isCancelled() == true",
            {
                def taskCls = Class.forName('hudson.plugins.logfilesizechecker.LogfilesizecheckerWrapper$LogSizeTimerTask')
                def dispCls = Class.forName('hudson.plugins.logfilesizechecker.LogfilesizecheckerWrapper$LogSizeTimerTaskDisposer')
                def ctor = taskCls.getDeclaredConstructors()[0]
                ctor.accessible = true
                def task = ctor.newInstance(masterBuild, listener, 100, false)
                def future = jenkins.util.Timer.get().scheduleAtFixedRate(task, 60L, 60L, java.util.concurrent.TimeUnit.SECONDS)
                def setFuture = taskCls.getDeclaredMethod("setFuture", java.util.concurrent.ScheduledFuture.class)
                setFuture.accessible = true
                setFuture.invoke(task, future)
                assert !future.isCancelled()
                def dispCtor = dispCls.getDeclaredConstructors()[0]
                dispCtor.accessible = true
                def disposer = dispCtor.newInstance(task)
                disposer.tearDown(masterBuild, Jenkins.instance.getWorkspaceFor(masterBuild.project), launcher, listener)
                assert future.isCancelled() : "ScheduledFuture was not cancelled by Disposer.tearDown!"
                return [
                    actual   : "future.isCancelled() == true",
                    assertion: "Disposer.tearDown cancelled ScheduledFuture<?> and prevented Timer thread pool leak"
                ]
            }
        )

        // Case 12: Global Configuration configure(StaplerRequest2, JSONObject) & edge-case validation
        runCase(
            "e2e_12_global_configuration_form_submission_and_edge_cases",
            "Submit DescriptorImpl.configure with valid, blank, negative, and non-numeric inputs",
            "Validated & Persisted to XML",
            {
                def configureMethod = desc.class.getMethod("configure", org.kohsuke.stapler.StaplerRequest2.class, JSONObject.class)
                JSONObject validJson = new JSONObject()
                validJson.put("defaultLogSize", "8")
                validJson.put("checkPeriod", "3")
                configureMethod.invoke(desc, null, validJson)
                assert desc.getDefaultLogSize() == 8
                assert desc.getCheckPeriod() == 3

                JSONObject blankJson = new JSONObject()
                blankJson.put("defaultLogSize", "   ")
                blankJson.put("checkPeriod", "")
                configureMethod.invoke(desc, null, blankJson)
                assert desc.getDefaultLogSize() == 0
                assert desc.getCheckPeriod() == 1

                JSONObject negJson = new JSONObject()
                negJson.put("defaultLogSize", "-10")
                negJson.put("checkPeriod", "0")
                configureMethod.invoke(desc, null, negJson)
                assert desc.getDefaultLogSize() == 0
                assert desc.getCheckPeriod() == 1

                JSONObject nonNumJson = new JSONObject()
                nonNumJson.put("defaultLogSize", "not-a-number")
                nonNumJson.put("checkPeriod", "invalid")
                configureMethod.invoke(desc, null, nonNumJson)
                assert desc.getDefaultLogSize() == 0
                assert desc.getCheckPeriod() == 1

                File xmlFile = new File(Jenkins.instance.rootDir, "hudson.plugins.logfilesizechecker.LogfilesizecheckerWrapper.xml")
                assert xmlFile.exists()
                return [
                    actual   : "defaultLogSize=0, checkPeriod=1 (XML saved)",
                    assertion: "DescriptorImpl.configure handled valid, blank, and negative values safely and persisted XML"
                ]
            }
        )

        // Case 13: Job config.xml persistence round-trip
        runCase(
            "e2e_13_config_xml_persistence_roundtrip",
            "Save FreeStyleProject with LogfilesizecheckerWrapper(3, true, true) to config.xml and reload",
            "maxLogSize=3, failBuild=true, setOwn=true",
            {
                FreeStyleProject p = Jenkins.instance.createProject(FreeStyleProject.class, "e2e-job-13-roundtrip")
                p.getBuildWrappersList().add(new LogfilesizecheckerWrapper(3, true, true))
                p.save()
                p.doReload()
                LogfilesizecheckerWrapper reloaded = p.getBuildWrappersList().get(LogfilesizecheckerWrapper.class)
                assert reloaded != null
                assert reloaded.getMaxLogSize() == 3
                assert reloaded.isFailBuild() == true
                assert reloaded.isSetOwn() == true
                return [
                    actual   : "maxLogSize=${reloaded.maxLogSize}, failBuild=${reloaded.failBuild}, setOwn=${reloaded.setOwn}",
                    assertion: "Wrapper configuration persisted to jobs/e2e-job-13-roundtrip/config.xml and reloaded cleanly"
                ]
            }
        )

        // Case 14: Concurrent multi-executor builds isolation
        runCase(
            "e2e_14_concurrent_builds_isolation",
            "Run 2 concurrent builds on separate executors (Job A: 1MB limit -> FAIL, Job B: 5MB limit -> SUCCESS)",
            "Job A=FAILURE, Job B=SUCCESS",
            {
                desc.setDefaultLogSize(0)
                desc.setCheckPeriod(1)
                FreeStyleProject pA = createTestJob("e2e-job-14a-concurrent-fail", 1, true, true)
                FreeStyleProject pB = createTestJob("e2e-job-14b-concurrent-pass", 5, true, true)
                def fA = pA.scheduleBuild2(0)
                def fB = pB.scheduleBuild2(0)
                FreeStyleBuild bA = fA.get()
                FreeStyleBuild bB = fB.get()
                assert bA.result == Result.FAILURE : "Expected Job A FAILURE but got ${bA.result}"
                assert bB.result == Result.SUCCESS : "Expected Job B SUCCESS but got ${bB.result}"
                return [
                    actual   : "Job A=${bA.result}, Job B=${bB.result}",
                    assertion: "Concurrent builds isolated properly across Jenkins executors"
                ]
            }
        )

        // Write JUnit XML report and JSON details
        File wsDir = new File(Jenkins.instance.getWorkspaceFor(masterBuild.project).remote)
        File reportDir = new File(wsDir, "target/surefire-reports")
        reportDir.mkdirs()
        File reportFile = new File(reportDir, "TEST-JenkinsLiveE2ETest.xml")
        int total = cases.size()
        int failures = cases.count { !it.passed }
        double suiteTime = cases.collect { (double) it.durationSec }.sum() as double

        StringBuilder xml = new StringBuilder()
        xml.append('<?xml version="1.0" encoding="UTF-8"?>\n')
        xml.append(String.format(Locale.US,
            '<testsuite name="JenkinsLiveE2ESuite" tests="%d" failures="%d" errors="0" skipped="0" time="%.3f">\n',
            total, failures, suiteTime))
        cases.each { Map<String, Object> c ->
            String tcName = escapeXml(c.name)
            String tcTime = String.format(Locale.US, "%.3f", (double) c.durationSec)
            if (c.passed) {
                xml.append("  <testcase classname=\"hudson.plugins.logfilesizechecker.JenkinsLiveE2ETest\" name=\"${tcName}\" time=\"${tcTime}\"/>\n")
            } else {
                Throwable err = (Throwable) c.error
                String errMsg = escapeXml(err?.message ?: err?.toString() ?: "Failed")
                StringWriter sw = new StringWriter()
                err?.printStackTrace(new PrintWriter(sw))
                xml.append("  <testcase classname=\"hudson.plugins.logfilesizechecker.JenkinsLiveE2ETest\" name=\"${tcName}\" time=\"${tcTime}\">\n")
                xml.append("    <failure message=\"${errMsg}\" type=\"AssertionError\">${escapeXml(sw.toString())}</failure>\n")
                xml.append("  </testcase>\n")
            }
        }
        xml.append("</testsuite>\n")
        reportFile.setText(xml.toString(), "UTF-8")

        def jsonArr = cases.collect { c ->
            [
                name       : c.name,
                scenario   : c.scenario,
                expected   : c.expected,
                actual     : c.actual,
                assertion  : c.assertion,
                durationSec: c.durationSec,
                passed     : c.passed
            ]
        }
        new File("/workspace/target/surefire-reports/live_jenkins_e2e_cases.json")
            .setText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(jsonArr)), "UTF-8")

        out.println("\n==================================================================")
        out.println("  Completed ${total} Live Jenkins E2E Cases (Failures: ${failures})")
        out.println("==================================================================")
        if (failures > 0) {
            masterBuild.setResult(Result.FAILURE)
            return false
        }
        return true
    }
}

def jobName = "logfilesizechecker-e2e-suite"
FreeStyleProject job = j.createProject(FreeStyleProject.class, jobName)
job.getPublishersList().add(new JUnitResultArchiver("target/surefire-reports/*.xml"))
job.save()
hudson.BulkChange bc = new hudson.BulkChange(job)
try {
    job.getBuildersList().add(new LivePluginE2ERunnerBuilder())
} finally {
    bc.abort()
}

Thread.start("e2e-build-trigger") {
    while (Jenkins.instance.initLevel != InitMilestone.COMPLETED) {
        Thread.sleep(200)
    }
    job.scheduleBuild2(0)
    println "--> [init.groovy.d] Scheduled job: ${jobName} #1"
}
