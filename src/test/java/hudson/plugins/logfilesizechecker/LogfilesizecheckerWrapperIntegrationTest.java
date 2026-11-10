package hudson.plugins.logfilesizechecker;

import hudson.FilePath;
import hudson.Functions;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.TopLevelItem;
import hudson.tasks.BatchFile;
import hudson.tasks.CommandInterpreter;
import hudson.tasks.Shell;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class LogfilesizecheckerWrapperIntegrationTest {

    private static final int TEST_FILE_BYTES = 2_150_400;

    private static void prepareWorkspace(JenkinsRule rule, TopLevelItem item) throws Exception {
        FilePath ws = rule.jenkins.getWorkspaceFor(item);
        if (ws == null) {
            throw new IllegalStateException("Workspace is null for " + item.getFullName());
        }
        ws.mkdirs();
        byte[] chunk = ("Lorem ipsum dolor sit amet, consectetur adipiscing elit, "
                        + "sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.\n")
                .getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = ws.child("lorem2100kB").write()) {
            int written = 0;
            while (written < TEST_FILE_BYTES) {
                int len = Math.min(chunk.length, TEST_FILE_BYTES - written);
                out.write(chunk, 0, len);
                written += len;
            }
        }
    }

    private static CommandInterpreter printFileCommand(String filename) {
        return Functions.isWindows()
                ? new BatchFile(String.format("type %s", filename))
                : new Shell(String.format("cat %s", filename));
    }

    private static CommandInterpreter sleepCommand(int seconds) {
        return Functions.isWindows()
                ? new BatchFile(String.format("ping -n %s 127.0.0.1 >nul", seconds))
                : new Shell(String.format("sleep %s", seconds));
    }

    private static String printFilePipelineStep(String filename) {
        return Functions.isWindows()
                ? String.format("bat 'type %s'", filename)
                : String.format("sh 'cat %s'", filename);
    }

    @Test
    void test1(JenkinsRule rule) throws Exception {
        // maxLogSize=1MB, failBuild=true, setOwn=true
        final FreeStyleProject project = rule.createFreeStyleProject("freestyle");
        prepareWorkspace(rule, project);
        project.getBuildersList().add(printFileCommand("lorem2100kB"));
        project.getBuildersList().add(sleepCommand(3));
        project.getBuildersList().add(printFileCommand("lorem2100kB"));
        project.getBuildWrappersList().add(new LogfilesizecheckerWrapper(1, true, true));

        final FreeStyleBuild build = project.scheduleBuild2(0).get();
        rule.assertBuildStatus(Result.FAILURE, build);
        rule.assertLogContains(">>> Max Log Size reached 1(MB). Aborting <<<", build);
    }

    @Test
    void test2(JenkinsRule rule) throws Exception {
        // maxLogSize=1MB, failBuild=false, setOwn=true
        final FreeStyleProject project = rule.createFreeStyleProject("freestyle");
        prepareWorkspace(rule, project);
        project.getBuildersList().add(printFileCommand("lorem2100kB"));
        project.getBuildersList().add(sleepCommand(3));
        project.getBuildersList().add(printFileCommand("lorem2100kB"));
        project.getBuildWrappersList().add(new LogfilesizecheckerWrapper(1, false, true));

        final FreeStyleBuild build = project.scheduleBuild2(0).get();
        rule.assertBuildStatus(Result.ABORTED, build);
        rule.assertLogContains(">>> Max Log Size reached 1(MB). Aborting <<<", build);
    }

    @Test
    void test3(JenkinsRule rule) throws Exception {
        // maxLogSize=1MB, failBuild=false, setOwn=false
        final FreeStyleProject project = rule.createFreeStyleProject("freestyle");
        prepareWorkspace(rule, project);
        project.getBuildersList().add(printFileCommand("lorem2100kB"));
        project.getBuildersList().add(sleepCommand(3));
        project.getBuildersList().add(printFileCommand("lorem2100kB"));
        project.getBuildWrappersList().add(new LogfilesizecheckerWrapper(1, false, false));

        final FreeStyleBuild build = project.scheduleBuild2(0).get();
        rule.assertBuildStatus(Result.SUCCESS, build);
    }

    @Test
    void test4(JenkinsRule rule) throws Exception {
        // maxLogSize=5MB, failBuild=false, setOwn=true
        final FreeStyleProject project = rule.createFreeStyleProject("freestyle");
        prepareWorkspace(rule, project);
        project.getBuildersList().add(printFileCommand("lorem2100kB"));
        project.getBuildersList().add(sleepCommand(3));
        project.getBuildersList().add(printFileCommand("lorem2100kB"));
        project.getBuildWrappersList().add(new LogfilesizecheckerWrapper(5, false, true));

        final FreeStyleBuild build = project.scheduleBuild2(0).get();
        rule.assertBuildStatus(Result.SUCCESS, build);
    }

    @Test
    void test5(JenkinsRule rule) throws Exception {
        // maxLogSize=0MB, failBuild=true, setOwn=false, defaultLogSize=1
        final FreeStyleProject project = rule.createFreeStyleProject("freestyle");
        prepareWorkspace(rule, project);
        project.getBuildersList().add(printFileCommand("lorem2100kB"));
        project.getBuildersList().add(sleepCommand(3));
        project.getBuildersList().add(printFileCommand("lorem2100kB"));
        project.getBuildWrappersList().add(new LogfilesizecheckerWrapper(0, true, false));

        int oldValue = LogfilesizecheckerWrapper.DESCRIPTOR.getDefaultLogSize();
        LogfilesizecheckerWrapper.DESCRIPTOR.setDefaultLogSize(1);
        try {
            final FreeStyleBuild build = project.scheduleBuild2(0).get();
            rule.assertBuildStatus(Result.FAILURE, build);
            rule.assertLogContains(">>> Max Log Size reached 1(MB). Aborting <<<", build);
        } finally {
            LogfilesizecheckerWrapper.DESCRIPTOR.setDefaultLogSize(oldValue);
        }
    }

    @Test
    void test6(JenkinsRule rule) throws Exception {
        // maxLogSize=1MB, failBuild=true, setOwn=true
        final WorkflowJob project = rule.createProject(WorkflowJob.class, "pipeline");
        prepareWorkspace(rule, project);
        project.setDefinition(
                new CpsFlowDefinition(
                        "node {\n"
                                + "  wrap([$class: 'LogfilesizecheckerWrapper', 'maxLogSize': 1, 'failBuild': true, 'setOwn': true]) {\n"
                                + String.format("    %s\n", printFilePipelineStep("lorem2100kB"))
                                + "    sleep 3\n"
                                + String.format("    %s\n", printFilePipelineStep("lorem2100kB"))
                                + "  }\n"
                                + "}",
                        true));

        final WorkflowRun build = project.scheduleBuild2(0).get();
        rule.assertBuildStatus(Result.FAILURE, build);
        rule.assertLogContains(">>> Max Log Size reached 1(MB). Aborting <<<", build);
    }

    @Test
    void test7(JenkinsRule rule) throws Exception {
        // maxLogSize=1MB, failBuild=false, setOwn=true
        final WorkflowJob project = rule.createProject(WorkflowJob.class, "pipeline");
        prepareWorkspace(rule, project);
        project.setDefinition(
                new CpsFlowDefinition(
                        "node {\n"
                                + "  wrap([$class: 'LogfilesizecheckerWrapper', 'maxLogSize': 1, 'failBuild': false, 'setOwn': true]) {\n"
                                + String.format("    %s\n", printFilePipelineStep("lorem2100kB"))
                                + "    sleep 3\n"
                                + String.format("    %s\n", printFilePipelineStep("lorem2100kB"))
                                + "  }\n"
                                + "}",
                        true));

        final WorkflowRun build = project.scheduleBuild2(0).get();
        rule.assertBuildStatus(Result.ABORTED, build);
        rule.assertLogContains(">>> Max Log Size reached 1(MB). Aborting <<<", build);
    }

    @Test
    void test8(JenkinsRule rule) throws Exception {
        // maxLogSize=1MB, failBuild=false, setOwn=false
        final WorkflowJob project = rule.createProject(WorkflowJob.class, "pipeline");
        prepareWorkspace(rule, project);
        project.setDefinition(
                new CpsFlowDefinition(
                        "node {\n"
                                + "  wrap([$class: 'LogfilesizecheckerWrapper', 'maxLogSize': 1, 'failBuild': false, 'setOwn': false]) {\n"
                                + String.format("    %s\n", printFilePipelineStep("lorem2100kB"))
                                + "    sleep 3\n"
                                + String.format("    %s\n", printFilePipelineStep("lorem2100kB"))
                                + "  }\n"
                                + "}",
                        true));

        final WorkflowRun build = project.scheduleBuild2(0).get();
        rule.assertBuildStatus(Result.SUCCESS, build);
    }

    @Test
    void test9(JenkinsRule rule) throws Exception {
        // maxLogSize=5MB, failBuild=false, setOwn=true
        final WorkflowJob project = rule.createProject(WorkflowJob.class, "pipeline");
        prepareWorkspace(rule, project);
        project.setDefinition(
                new CpsFlowDefinition(
                        "node {\n"
                                + "  wrap([$class: 'LogfilesizecheckerWrapper', 'maxLogSize': 5, 'failBuild': false, 'setOwn': true]) {\n"
                                + String.format("    %s\n", printFilePipelineStep("lorem2100kB"))
                                + "    sleep 3\n"
                                + String.format("    %s\n", printFilePipelineStep("lorem2100kB"))
                                + "  }\n"
                                + "}",
                        true));

        final WorkflowRun build = project.scheduleBuild2(0).get();
        rule.assertBuildStatus(Result.SUCCESS, build);
    }

    @Test
    void test10(JenkinsRule rule) throws Exception {
        // maxLogSize=0MB, failBuild=true, setOwn=false, defaultLogSize=1
        final WorkflowJob project = rule.createProject(WorkflowJob.class, "pipeline");
        prepareWorkspace(rule, project);
        project.setDefinition(
                new CpsFlowDefinition(
                        "node {\n"
                                + "  wrap([$class: 'LogfilesizecheckerWrapper', 'maxLogSize': 0, 'failBuild': true, 'setOwn': false]) {\n"
                                + String.format("    %s\n", printFilePipelineStep("lorem2100kB"))
                                + "    sleep 3\n"
                                + String.format("    %s\n", printFilePipelineStep("lorem2100kB"))
                                + "  }\n"
                                + "}",
                        true));

        int oldValue = LogfilesizecheckerWrapper.DESCRIPTOR.getDefaultLogSize();
        LogfilesizecheckerWrapper.DESCRIPTOR.setDefaultLogSize(1);
        try {
            final WorkflowRun build = project.scheduleBuild2(0).get();
            rule.assertBuildStatus(Result.FAILURE, build);
            rule.assertLogContains(">>> Max Log Size reached 1(MB). Aborting <<<", build);
        } finally {
            LogfilesizecheckerWrapper.DESCRIPTOR.setDefaultLogSize(oldValue);
        }
    }

    @Test
    void testPipelineSymbol(JenkinsRule rule) throws Exception {
        final WorkflowJob project = rule.createProject(WorkflowJob.class, "pipeline-symbol");
        prepareWorkspace(rule, project);
        project.setDefinition(
                new CpsFlowDefinition(
                        "node {\n"
                                + "  logfilesizechecker(maxLogSize: 1, failBuild: true, setOwn: true) {\n"
                                + String.format("    %s\n", printFilePipelineStep("lorem2100kB"))
                                + "    sleep 3\n"
                                + String.format("    %s\n", printFilePipelineStep("lorem2100kB"))
                                + "  }\n"
                                + "}",
                        true));

        final WorkflowRun build = project.scheduleBuild2(0).get();
        rule.assertBuildStatus(Result.FAILURE, build);
        rule.assertLogContains(">>> Max Log Size reached 1(MB). Aborting <<<", build);
    }

    @Test
    void testConfigRoundTrip(JenkinsRule rule) throws Exception {
        final FreeStyleProject project = rule.createFreeStyleProject();
        final LogfilesizecheckerWrapper before = new LogfilesizecheckerWrapper(3, true, true);
        project.getBuildWrappersList().add(before);

        rule.submit(rule.createWebClient().goTo("configure").getFormByName("config"));
        final LogfilesizecheckerWrapper after =
                project.getBuildWrappersList().get(LogfilesizecheckerWrapper.class);

        rule.assertEqualDataBoundBeans(before, after);
    }
}
