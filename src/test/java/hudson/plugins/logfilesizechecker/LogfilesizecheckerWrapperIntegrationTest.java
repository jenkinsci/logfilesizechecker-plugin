
package hudson.plugins.logfilesizechecker;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;

import org.jvnet.hudson.test.HudsonTestCase;
import org.jvnet.hudson.test.recipes.LocalData;


public class LogfilesizecheckerWrapperIntegrationTest extends HudsonTestCase {

    @LocalData
    public void test1() throws Exception {
        //maxLogSize=1MB, failBuild=true, setOwn=true 
        final FreeStyleProject project = (FreeStyleProject) hudson.getItem("1");
        System.out.println(project.getName());
        project.getBuildWrappersList().add(new LogfilesizecheckerWrapper(1, true, true));

        final FreeStyleBuild build = project.scheduleBuild2(0).get();
        System.out.println("LogFileLength: " + build.getLogFile().length());
        assertBuildStatus(Result.FAILURE, build);
    }
    
    @LocalData
    public void test2() throws Exception {
        //maxLogSize=1MB, failBuild=false, setOwn=true 
        final FreeStyleProject project = (FreeStyleProject) hudson.getItem("1");
        project.getBuildWrappersList().add(new LogfilesizecheckerWrapper(1, false, true));

        final FreeStyleBuild build = project.scheduleBuild2(0).get();
        System.out.println("LogFileLength: " + build.getLogFile().length());
        
        assertBuildStatus(Result.ABORTED, build);
    }
    
    @LocalData
    public void test3() throws Exception {
        //maxLogSize=1MB, failBuild=false, setOwn=false 
        final FreeStyleProject project = (FreeStyleProject) hudson.getItem("1");
        project.getBuildWrappersList().add(new LogfilesizecheckerWrapper(1, false, false));

        final FreeStyleBuild build = project.scheduleBuild2(0).get();
        System.out.println("LogFileLength: " + build.getLogFile().length());
        
        assertBuildStatus(Result.SUCCESS, build);
    }
    
    @LocalData
    public void test4() throws Exception {
        //maxLogSize=5MB, failBuild=false, setOwn=true 
        final FreeStyleProject project = (FreeStyleProject) hudson.getItem("1");
        project.getBuildWrappersList().add(new LogfilesizecheckerWrapper(5, false, true));

        final FreeStyleBuild build = project.scheduleBuild2(0).get();
        System.out.println("LogFileLength: " + build.getLogFile().length());
        
        assertBuildStatus(Result.SUCCESS, build);
    }

    @LocalData
    public void test5() throws Exception {
        //maxLogSize=0MB, failBuild=true, setOwn=false, defaultLogSize=1
        final FreeStyleProject project = (FreeStyleProject) hudson.getItem("1");
        final LogfilesizecheckerWrapper buildWrapper = new LogfilesizecheckerWrapper(0, true, false);
        ((LogfilesizecheckerWrapper.DescriptorImpl) buildWrapper.getDescriptor()).setDefaultLogSize(1);
        project.getBuildWrappersList().add(buildWrapper);

        final FreeStyleBuild build = project.scheduleBuild2(0).get();
        System.out.println("LogFileLength: " + build.getLogFile().length());
        
        assertBuildStatus(Result.FAILURE, build);
    }
    
    //configuration round trip test
    public void testConfigRoundTrip() throws Exception {
        final FreeStyleProject project = createFreeStyleProject();
        final LogfilesizecheckerWrapper before = new LogfilesizecheckerWrapper(3, true, true);
        project.getBuildWrappersList().add(before);
        
        submit(new WebClient().goTo("configure").getFormByName("config"));
        final LogfilesizecheckerWrapper after = project.getBuildWrappersList().get(LogfilesizecheckerWrapper.class);

        assertEqualDataBoundBeans(before, after);
    }
}
