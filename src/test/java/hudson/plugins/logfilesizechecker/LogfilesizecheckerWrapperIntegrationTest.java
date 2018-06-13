
package hudson.plugins.logfilesizechecker;

import hudson.Functions;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.tasks.BatchFile;
import hudson.tasks.CommandInterpreter;
import hudson.tasks.Shell;

import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.recipes.LocalData;

public class LogfilesizecheckerWrapperIntegrationTest {

    @Rule public JenkinsRule rule = new JenkinsRule();

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
    @LocalData
    public void test1() throws Exception {
        // maxLogSize=1MB, failBuild=true, setOwn=true
        final FreeStyleProject project = rule.createFreeStyleProject("freestyle");
        project.getBuildersList().add(printFileCommand("lorem2100kB"));
        project.getBuildersList().add(sleepCommand(1));
        project.getBuildersList().add(printFileCommand("lorem2100kB"));
        project.getBuildWrappersList().add(new LogfilesizecheckerWrapper(1, true, true));

        final FreeStyleBuild build = project.scheduleBuild2(0).get();
        System.out.println("LogFileLength: " + build.getLogFile().length());
        rule.assertBuildStatus(Result.FAILURE, build);
    }
    
    @Test
    @LocalData
    public void test2() throws Exception {
        // maxLogSize=1MB, failBuild=false, setOwn=true
        final FreeStyleProject project = rule.createFreeStyleProject("freestyle");
        project.getBuildersList().add(printFileCommand("lorem2100kB"));
        project.getBuildersList().add(sleepCommand(1));
        project.getBuildersList().add(printFileCommand("lorem2100kB"));
        project.getBuildWrappersList().add(new LogfilesizecheckerWrapper(1, false, true));

        final FreeStyleBuild build = project.scheduleBuild2(0).get();
        System.out.println("LogFileLength: " + build.getLogFile().length());
        
        rule.assertBuildStatus(Result.ABORTED, build);
    }
    
    @Test
    @LocalData
    public void test3() throws Exception {
        // maxLogSize=1MB, failBuild=false, setOwn=false
        final FreeStyleProject project = rule.createFreeStyleProject("freestyle");
        project.getBuildersList().add(printFileCommand("lorem2100kB"));
        project.getBuildersList().add(sleepCommand(1));
        project.getBuildersList().add(printFileCommand("lorem2100kB"));
        project.getBuildWrappersList().add(new LogfilesizecheckerWrapper(1, false, false));

        final FreeStyleBuild build = project.scheduleBuild2(0).get();
        System.out.println("LogFileLength: " + build.getLogFile().length());
        
        rule.assertBuildStatus(Result.SUCCESS, build);
    }
    
    @Test
    @LocalData
    public void test4() throws Exception {
        // maxLogSize=5MB, failBuild=false, setOwn=true
        final FreeStyleProject project = rule.createFreeStyleProject("freestyle");
        project.getBuildersList().add(printFileCommand("lorem2100kB"));
        project.getBuildersList().add(sleepCommand(1));
        project.getBuildersList().add(printFileCommand("lorem2100kB"));
        project.getBuildWrappersList().add(new LogfilesizecheckerWrapper(5, false, true));

        final FreeStyleBuild build = project.scheduleBuild2(0).get();
        System.out.println("LogFileLength: " + build.getLogFile().length());
        
        rule.assertBuildStatus(Result.SUCCESS, build);
    }

    @Test
    @LocalData
    public void test5() throws Exception {
        // maxLogSize=0MB, failBuild=true, setOwn=false, defaultLogSize=1
        final FreeStyleProject project = rule.createFreeStyleProject("freestyle");
        project.getBuildersList().add(printFileCommand("lorem2100kB"));
        project.getBuildersList().add(sleepCommand(1));
        project.getBuildersList().add(printFileCommand("lorem2100kB"));
        project.getBuildWrappersList().add(new LogfilesizecheckerWrapper(0, true, false));

        int oldValue = LogfilesizecheckerWrapper.DESCRIPTOR.getDefaultLogSize();
        LogfilesizecheckerWrapper.DESCRIPTOR.setDefaultLogSize(1);
        try {
            final FreeStyleBuild build = project.scheduleBuild2(0).get();
            System.out.println("LogFileLength: " + build.getLogFile().length());
            rule.assertBuildStatus(Result.FAILURE, build);
        } finally {
            LogfilesizecheckerWrapper.DESCRIPTOR.setDefaultLogSize(oldValue);
        }
    }

    @Test
    public void test6() throws Exception {
        // maxLogSize=1MB, failBuild=true, setOwn=true
        final WorkflowJob project = rule.createProject(WorkflowJob.class, "pipeline");
        project.setDefinition(
                new CpsFlowDefinition(
                        "node {\n"
                                + "  wrap([$class: 'LogfilesizecheckerWrapper', 'maxLogSize': 1, 'failBuild': true, 'setOwn': true]) {\n"
                                + String.format("    %s\n", printFilePipelineStep("lorem2100kB"))
                                + "    sleep 1\n"
                                + String.format("    %s\n", printFilePipelineStep("lorem2100kB"))
                                + "  }\n"
                                + "}",
                        true));

        final WorkflowRun build = project.scheduleBuild2(0).get();
        System.out.println("LogFileLength: " + build.getLogFile().length());
        
        rule.assertBuildStatus(Result.FAILURE, build);
    }

    @Test
    @LocalData
    public void test7() throws Exception {
        // maxLogSize=1MB, failBuild=false, setOwn=true
        final WorkflowJob project = rule.createProject(WorkflowJob.class, "pipeline");
        project.setDefinition(
                new CpsFlowDefinition(
                        "node {\n"
                                + "  wrap([$class: 'LogfilesizecheckerWrapper', 'maxLogSize': 1, 'failBuild': false, 'setOwn': true]) {\n"
                                + String.format("    %s\n", printFilePipelineStep("lorem2100kB"))
                                + "    sleep 1\n"
                                + String.format("    %s\n", printFilePipelineStep("lorem2100kB"))
                                + "  }\n"
                                + "}",
                        true));

        final WorkflowRun build = project.scheduleBuild2(0).get();
        System.out.println("LogFileLength: " + build.getLogFile().length());
        rule.assertBuildStatus(Result.ABORTED, build);
    }

    @Test
    @LocalData
    public void test8() throws Exception {
        // maxLogSize=1MB, failBuild=false, setOwn=false
        final WorkflowJob project = rule.createProject(WorkflowJob.class, "pipeline");
        project.setDefinition(
                new CpsFlowDefinition(
                        "node {\n"
                                + "  wrap([$class: 'LogfilesizecheckerWrapper', 'maxLogSize': 1, 'failBuild': false, 'setOwn': false]) {\n"
                                + String.format("    %s\n", printFilePipelineStep("lorem2100kB"))
                                + "    sleep 1\n"
                                + String.format("    %s\n", printFilePipelineStep("lorem2100kB"))
                                + "  }\n"
                                + "}",
                        true));

        final WorkflowRun build = project.scheduleBuild2(0).get();
        System.out.println("LogFileLength: " + build.getLogFile().length());
        rule.assertBuildStatus(Result.SUCCESS, build);
    }

    @Test
    @LocalData
    public void test9() throws Exception {
        // maxLogSize=5MB, failBuild=false, setOwn=true
        final WorkflowJob project = rule.createProject(WorkflowJob.class, "pipeline");
        project.setDefinition(
                new CpsFlowDefinition(
                        "node {\n"
                                + "  wrap([$class: 'LogfilesizecheckerWrapper', 'maxLogSize': 5, 'failBuild': false, 'setOwn': true]) {\n"
                                + String.format("    %s\n", printFilePipelineStep("lorem2100kB"))
                                + "    sleep 1\n"
                                + String.format("    %s\n", printFilePipelineStep("lorem2100kB"))
                                + "  }\n"
                                + "}",
                        true));

        final WorkflowRun build = project.scheduleBuild2(0).get();
        System.out.println("LogFileLength: " + build.getLogFile().length());
        rule.assertBuildStatus(Result.SUCCESS, build);
    }

    @Test
    @LocalData
    public void test10() throws Exception {
        // maxLogSize=0MB, failBuild=true, setOwn=false, defaultLogSize=1
        final WorkflowJob project = rule.createProject(WorkflowJob.class, "pipeline");
        project.setDefinition(
                new CpsFlowDefinition(
                        "node {\n"
                                + "  wrap([$class: 'LogfilesizecheckerWrapper', 'maxLogSize': 0, 'failBuild': true, 'setOwn': false]) {\n"
                                + String.format("    %s\n", printFilePipelineStep("lorem2100kB"))
                                + "    sleep 1\n"
                                + String.format("    %s\n", printFilePipelineStep("lorem2100kB"))
                                + "  }\n"
                                + "}",
                        true));

        int oldValue = LogfilesizecheckerWrapper.DESCRIPTOR.getDefaultLogSize();
        LogfilesizecheckerWrapper.DESCRIPTOR.setDefaultLogSize(1);
        try {
            final WorkflowRun build = project.scheduleBuild2(0).get();
            System.out.println("LogFileLength: " + build.getLogFile().length());
            rule.assertBuildStatus(Result.FAILURE, build);
        } finally {
            LogfilesizecheckerWrapper.DESCRIPTOR.setDefaultLogSize(oldValue);
        }
    }

    // configuration round trip test
    @Test
    public void testConfigRoundTrip() throws Exception {
        final FreeStyleProject project = rule.createFreeStyleProject();
        final LogfilesizecheckerWrapper before = new LogfilesizecheckerWrapper(3, true, true);
        project.getBuildWrappersList().add(before);

        rule.submit(rule.createWebClient().goTo("configure").getFormByName("config"));
        final LogfilesizecheckerWrapper after =
                project.getBuildWrappersList().get(LogfilesizecheckerWrapper.class);

        rule.assertEqualDataBoundBeans(before, after);
    }
}
