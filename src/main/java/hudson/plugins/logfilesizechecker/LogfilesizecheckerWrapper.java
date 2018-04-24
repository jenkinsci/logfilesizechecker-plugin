package hudson.plugins.logfilesizechecker;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Descriptor;
import hudson.model.Executor;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.triggers.SafeTimerTask;

import java.io.IOException;
import java.io.Serializable;
import java.util.concurrent.TimeUnit;

import jenkins.model.CauseOfInterruption;
import jenkins.tasks.SimpleBuildWrapper;
import jenkins.util.Timer;

import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

/**
 * {@link BuildWrapper} that terminates a build if its log file size is too big.
 *
 * @author Stefan Brausch
 */
public class LogfilesizecheckerWrapper extends SimpleBuildWrapper implements Serializable {

    /** Set your own max size instead of using the default.*/
    public boolean setOwn;

    /** If the log file for the build has more MB, it will be terminated. */
    public int maxLogSize;

    /** Fail the build rather than aborting it. */
    public boolean failBuild;
    
    /**Period for timer task that checks the logfile size.*/
    private static final long PERIOD = 1000L;

    /**Delay for timer task that checks the logfile size.*/
    private static final long DELAY = 1000L;

    /**Conversion factor for Mega Bytes.*/
    private static final long MB = 1024L * 1024L;
    
    /**
     * Contructor for data binding of form data.
     * @param maxLogSize job specific maximum log size
     * @param failBuild true if the build should be marked failed instead of aborted
     * @param setOwn true if a job specific log size is set, false if global setting is used
     */
    @DataBoundConstructor
    public LogfilesizecheckerWrapper(int maxLogSize, boolean failBuild, boolean setOwn) {
        this.maxLogSize = maxLogSize;
        this.failBuild = failBuild;
        this.setOwn = setOwn;
    }
    
    @Override
    public void setUp(Context context, Run<?, ?> build, FilePath workspace, Launcher launcher,
            TaskListener listener, EnvVars initialEnvironment) throws IOException, InterruptedException {

        int allowedLogSize;
        if (setOwn) {
            allowedLogSize = maxLogSize;
        } else {
            allowedLogSize = DESCRIPTOR.getDefaultLogSize();
        }

        if (allowedLogSize > 0) {
            LogSizeTimerTask logSizeTimerTask =
                    new LogSizeTimerTask(build, listener, allowedLogSize, failBuild);
            Timer.get().scheduleAtFixedRate(logSizeTimerTask, DELAY, PERIOD, TimeUnit.MILLISECONDS);
            context.setDisposer(new LogSizeTimerTaskDisposer(logSizeTimerTask));
        }
    }

    /**TimerTask that checks log file size in regular intervals.*/
    private static class LogSizeTimerTask extends SafeTimerTask {
        private final Run<?, ?> build;
        private final TaskListener listener;
        private final int allowedLogSize;
        private final boolean failBuild;

        /**
         * Constructor for TimerTask that checks log file size.
         * @param build the current build
         * @param listener TaskListener used for logging
         * @param allowedLogSize the maximum size of the log
         * @param failBuild fail the build rather than aborting it
         */
        private LogSizeTimerTask(
                Run<?, ?> build, TaskListener listener, int allowedLogSize, boolean failBuild) {
            this.build = build;
            this.listener = listener;
            this.allowedLogSize = allowedLogSize;
            this.failBuild = failBuild;
        }

        /**Interrupts build if log file is too big.*/
        public void doRun() {
            final Executor e = build.getExecutor();
            if (e != null
                    && build.getLogFile().length() > allowedLogSize * MB
                    && !e.isInterrupted()) {
                String cause = ">>> Max Log Size reached "+allowedLogSize+"(MB). Aborting <<<";
                listener.getLogger().println(cause);
                CauseOfInterruption causeOfInterruption = new CauseOfInterruption() {
                    @Override
                    public String getShortDescription() {
                        return cause;
                    }
                };
                e.interrupt(failBuild ? Result.FAILURE : Result.ABORTED, causeOfInterruption);
            }
        }
    }

    private static class LogSizeTimerTaskDisposer extends Disposer {

        private static final long serialVersionUID = 204030731559819462L;
        private transient LogSizeTimerTask logSizeTimerTask;

        public LogSizeTimerTaskDisposer(LogSizeTimerTask logSizeTimerTask) {
            this.logSizeTimerTask = logSizeTimerTask;
        }

        @Override
        public void tearDown(
                Run<?, ?> build, FilePath workspace, Launcher launcher, TaskListener listener)
                throws IOException, InterruptedException {
            if (logSizeTimerTask != null) {
                logSizeTimerTask.cancel();
            }
        }
    }
    

    @Override
    public Descriptor<BuildWrapper> getDescriptor() {
        return DESCRIPTOR;
    }

    /**Creates descriptor for the BuildWrapper.*/
    @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    /**The Descriptor for the BuildWrapper.*/
    public static final class DescriptorImpl extends BuildWrapperDescriptor {

        /**If there is no job specific size set, this will be used.*/
        private int defaultLogSize;

        /**Constructor loads previously saved form data.*/
        DescriptorImpl() {
            super(LogfilesizecheckerWrapper.class);
            load();
        }

        /**
         * Returns caption for our part of the config page.
         * @return caption
         */
        public String getDisplayName() {
            return Messages.Descriptor_DisplayName();
        }

        /**Certainly does something.
         * @param item Some item, I guess
         * @return true
         */
        public boolean isApplicable(AbstractProject<?, ?> item) {
            return true;
        }

        /**
         * Returns maximum log size set in global configuration.
         * @return the globally set max log size
         */
        public int getDefaultLogSize() {
            return defaultLogSize;
        }

        /**
         * Allows changing the global log file size - used for testing only.
         * @param size new default max log size
         */
        public void setDefaultLogSize(int size) {
            defaultLogSize = size;
        }

        /**
         * 
         * 
         * {@inheritDoc}
         */
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            final String size = formData.getString("defaultLogSize");

            if (size != null) {
                defaultLogSize = Integer.parseInt(size);
            } else {
                defaultLogSize = 0;
            }
            save();
            return super.configure(req, formData);
        }

       
    }
}
