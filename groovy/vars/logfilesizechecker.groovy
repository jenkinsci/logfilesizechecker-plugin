import hudson.AbortException
import hudson.model.Executor
import hudson.model.Result
import hudson.model.Run
import jenkins.model.CauseOfInterruption
import jenkins.util.Timer
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Standalone Jenkins Shared Library step (vars/logfilesizechecker.groovy) that enforces
 * a maximum console log size without installing the logfilesizechecker plugin.
 *
 * Parameters (Map):
 *   - maxLogSize  (int)     : Maximum allowed build log size in MB (<= 0 means unlimited).
 *   - failBuild   (boolean) : true = mark build FAILURE (default), false = mark build ABORTED.
 *   - checkPeriod (int)     : Poll interval in seconds (minimum 1, default 1).
 *
 * Example usage in Jenkinsfile:
 *   logfilesizechecker(maxLogSize: 1, failBuild: true, checkPeriod: 1) {
 *       sh './noisy-build.sh'
 *   }
 */
def call(Map config, Closure body) {
    return logfilesizechecker(config, body)
}

def call(Closure body) {
    return logfilesizechecker([:], body)
}

def logfilesizechecker(Map config, Closure body) {
    int maxLogSize = (config?.maxLogSize ?: 0) as int
    boolean failBuild = (config != null && config.containsKey('failBuild')) ? (config.failBuild as boolean) : true
    int checkPeriod = Math.max(1, (config?.checkPeriod ?: 1) as int)

    if (maxLogSize <= 0) {
        return body()
    }

    String monitorId = UUID.randomUUID().toString()
    startLogSizeMonitor(monitorId, maxLogSize, failBuild, checkPeriod)
    try {
        return body()
    } finally {
        boolean exceeded = stopAndVerifyLogSizeMonitor(monitorId, maxLogSize, failBuild)
        if (exceeded) {
            abortPipelineNow(maxLogSize, failBuild)
        }
    }
}

@NonCPS
void abortPipelineNow(int maxLogSizeMB, boolean failBuild) {
    String msg = "Max Log Size reached " + maxLogSizeMB + "(MB)"
    if (failBuild) {
        throw new AbortException(msg)
    }
    throw new FlowInterruptedException(
        Result.ABORTED,
        new CauseOfInterruption.UserInterruption(msg)
    )
}

@NonCPS
void startLogSizeMonitor(String monitorId, int maxLogSizeMB, boolean failBuild, int checkPeriodSec) {
    final Run<?, ?> build = currentBuild.rawBuild
    final long maxBytes = (maxLogSizeMB as long) * 1024L * 1024L
    final String propKey = "jenkins.logfilesizechecker." + monitorId
    final Result targetResult = failBuild ? Result.FAILURE : Result.ABORTED
    final String abortMsg = ">>> Max Log Size reached " + maxLogSizeMB + "(MB). Aborting <<<"
    final String causeMsg = "Max Log Size reached " + maxLogSizeMB + "(MB)"
    final ScheduledFuture<?>[] futureHolder = new ScheduledFuture<?>[1]

    System.setProperty(propKey, "active")

    // Pure closure referencing only final local variables — never touches 'this', println, echo, or currentBuild
    Closure checkTask = { ->
        try {
            String state = System.getProperty(propKey)
            if (!"active".equals(state) || !build.isBuilding()) {
                ScheduledFuture<?> f = futureHolder[0]
                if (f != null) {
                    f.cancel(false)
                }
                return
            }

            def logText = build.getLogText()
            long currentBytes = (logText != null) ? logText.length() : 0L

            if (currentBytes > maxBytes) {
                System.setProperty(propKey, "triggered")

                boolean logged = false
                try {
                    def flowExec = build.getExecution()
                    def listener = (flowExec != null && flowExec.getOwner() != null) ? flowExec.getOwner().getListener() : null
                    if (listener != null) {
                        listener.getLogger().println(abortMsg)
                        listener.getLogger().flush()
                        logged = true
                    }
                } catch (Throwable ignored) {}

                if (!logged) {
                    try {
                        File logFile = build.getLogFile()
                        if (logFile != null) {
                            FileOutputStream fos = new FileOutputStream(logFile, true)
                            try {
                                fos.write((abortMsg + "\n").getBytes(StandardCharsets.UTF_8))
                                fos.flush()
                            } finally {
                                fos.close()
                            }
                        }
                    } catch (Throwable ignored) {}
                }

                def cause = new CauseOfInterruption.UserInterruption(causeMsg)
                build.setResult(targetResult)

                // 1. Interrupt Pipeline CpsFlowExecution (cleanly kills running sh/bat steps on agents/pods)
                boolean interruptedFlow = false
                try {
                    def flowExec = build.getExecution()
                    if (flowExec != null) {
                        flowExec.interrupt(targetResult, cause)
                        interruptedFlow = true
                    }
                } catch (Throwable ignored) {}

                // 2. Fallback for non-Pipeline (Freestyle / direct Executor) builds
                if (!interruptedFlow) {
                    try {
                        Executor oneOff = build.getOneOffExecutor()
                        if (oneOff != null && !oneOff.isInterrupted()) {
                            oneOff.interrupt(targetResult, cause)
                        }
                    } catch (Throwable ignored) {}

                    try {
                        Executor exec = build.getExecutor()
                        if (exec != null && !exec.isInterrupted()) {
                            exec.interrupt(targetResult, cause)
                        }
                    } catch (Throwable ignored) {}
                }

                ScheduledFuture<?> f = futureHolder[0]
                if (f != null) {
                    f.cancel(false)
                }
            }
        } catch (Throwable ignored) {
        }
    }

    ScheduledFuture<?> future = Timer.get().scheduleAtFixedRate(
        (Runnable) checkTask,
        200L,
        (checkPeriodSec as long) * 1000L,
        TimeUnit.MILLISECONDS
    )
    futureHolder[0] = future
}

@NonCPS
boolean stopAndVerifyLogSizeMonitor(String monitorId, int maxLogSizeMB, boolean failBuild) {
    final String propKey = "jenkins.logfilesizechecker." + monitorId
    String prevState = System.getProperty(propKey)
    System.clearProperty(propKey)

    if ("triggered".equals(prevState)) {
        return true
    }

    // Synchronous final check to catch sub-second bursts (<200ms) that finished before the timer ticked
    final Run<?, ?> build = currentBuild.rawBuild
    final long maxBytes = (maxLogSizeMB as long) * 1024L * 1024L
    def logText = build.getLogText()
    long currentBytes = (logText != null) ? logText.length() : 0L

    if (currentBytes > maxBytes) {
        final String abortMsg = ">>> Max Log Size reached " + maxLogSizeMB + "(MB). Aborting <<<"
        final Result targetResult = failBuild ? Result.FAILURE : Result.ABORTED
        boolean logged = false
        try {
            def flowExec = build.getExecution()
            def listener = (flowExec != null && flowExec.getOwner() != null) ? flowExec.getOwner().getListener() : null
            if (listener != null) {
                listener.getLogger().println(abortMsg)
                listener.getLogger().flush()
                logged = true
            }
        } catch (Throwable ignored) {}

        if (!logged) {
            try {
                File logFile = build.getLogFile()
                if (logFile != null) {
                    FileOutputStream fos = new FileOutputStream(logFile, true)
                    try {
                        fos.write((abortMsg + "\n").getBytes(StandardCharsets.UTF_8))
                        fos.flush()
                    } finally {
                        fos.close()
                    }
                }
            } catch (Throwable ignored) {}
        }
        build.setResult(targetResult)
        return true
    }

    return false
}
