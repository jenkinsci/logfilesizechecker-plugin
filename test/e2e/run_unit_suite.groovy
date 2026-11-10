import hudson.plugins.logfilesizechecker.LogfilesizecheckerWrapper
import hudson.plugins.logfilesizechecker.LogfilesizecheckerWrapper.MaxLogSizeCause
import hudson.plugins.logfilesizechecker.Messages
import java.util.Locale
import java.util.jar.JarFile

def results = []
def runTest = { String name, Closure body ->
    long t0 = System.nanoTime()
    try {
        body.call()
        double sec = (System.nanoTime() - t0) / 1e9d
        results << [name: name, passed: true, time: sec, error: null]
        println "  [PASS] ${name} (${String.format(Locale.US, '%.3fs', sec)})"
    } catch (Throwable t) {
        double sec = (System.nanoTime() - t0) / 1e9d
        results << [name: name, passed: false, time: sec, error: t]
        println "  [FAIL] ${name}: ${t.message}"
        throw t
    }
}

runTest("unit_01_constructor_and_getters") {
    def w = new LogfilesizecheckerWrapper(15, true, true)
    assert w.getMaxLogSize() == 15
    assert w.isFailBuild() == true
    assert w.isSetOwn() == true
}
runTest("unit_02_databound_setters") {
    def w = new LogfilesizecheckerWrapper(1, false, false)
    w.setMaxLogSize(25)
    w.setFailBuild(true)
    w.setSetOwn(true)
    assert w.getMaxLogSize() == 25
    assert w.isFailBuild() == true
    assert w.isSetOwn() == true
}
runTest("unit_03_max_log_size_cause_description") {
    def cause = new MaxLogSizeCause(10)
    assert cause.getMaxLogSize() == 10
    assert cause.getShortDescription() == "Max Log Size reached 10(MB)"
}
runTest("unit_04_messages_localization_en_and_de") {
    assert Messages.Descriptor_DisplayName() == "Abort the build if its log file size is too big"
}
runTest("unit_05_hpi_manifest_metadata") {
    def jf = new JarFile("/workspace/target/logfilesizechecker.hpi")
    def attrs = jf.manifest.mainAttributes
    assert attrs.getValue("Short-Name") == "logfilesizechecker"
    assert attrs.getValue("Plugin-Version") == "1.6-SNAPSHOT"
    assert jf.getEntry("WEB-INF/lib/logfilesizechecker.jar") != null
    jf.close()
}
runTest("unit_06_sezpoz_extension_index_present") {
    def jf = new JarFile("/workspace/target/logfilesizechecker.jar")
    assert jf.getEntry("META-INF/annotations/hudson.Extension") != null
    assert jf.getEntry("META-INF/annotations/org.jenkinsci.Symbol") != null
    assert jf.getEntry("hudson/plugins/logfilesizechecker/LogfilesizecheckerWrapper.stapler") != null
    jf.close()
}
runTest("unit_07_dorun_method_is_protected_not_public") {
    def inner = Class.forName('hudson.plugins.logfilesizechecker.LogfilesizecheckerWrapper$LogSizeTimerTask')
    def m = inner.getDeclaredMethod("doRun")
    assert java.lang.reflect.Modifier.isProtected(m.modifiers)
    assert !java.lang.reflect.Modifier.isPublic(m.modifiers)
}
runTest("unit_08_scheduled_future_cancellation_on_task") {
    def inner = Class.forName('hudson.plugins.logfilesizechecker.LogfilesizecheckerWrapper$LogSizeTimerTask')
    assert inner.getDeclaredFields().any { it.name == "future" }
    assert inner.getDeclaredMethods().any { it.name == "cancelTask" }
}
runTest("unit_09_parse_int_or_default_valid_and_blank") {
    def descClass = LogfilesizecheckerWrapper.DescriptorImpl.class
    def m = descClass.getDeclaredMethod("parseIntOrDefault", String.class, int.class)
    m.accessible = true
    assert m.invoke(null, "42", 0) == 42
    assert m.invoke(null, "", 0) == 0
    assert m.invoke(null, "   ", 1) == 1
    assert m.invoke(null, null, 5) == 5
    assert m.invoke(null, "not-a-number", 7) == 7
}
runTest("unit_10_jelly_views_well_formed_and_escaped") {
    ["/workspace/src/main/resources/index.jelly",
     "/workspace/src/main/resources/hudson/plugins/logfilesizechecker/LogfilesizecheckerWrapper/config.jelly",
     "/workspace/src/main/resources/hudson/plugins/logfilesizechecker/LogfilesizecheckerWrapper/global.jelly"].each { p ->
        def txt = new File(p).getText("UTF-8")
        assert txt.contains("escape-by-default='true'")
    }
}
runTest("unit_11_global_jelly_uses_descriptor_getters") {
    def txt = new File("/workspace/src/main/resources/hudson/plugins/logfilesizechecker/LogfilesizecheckerWrapper/global.jelly").getText("UTF-8")
    assert txt.contains("descriptor.getDefaultLogSize()")
    assert txt.contains("descriptor.getCheckPeriod()")
    assert !txt.contains("getDefaultDelay")
}
runTest("unit_12_config_jelly_uses_number_input") {
    def txt = new File("/workspace/src/main/resources/hudson/plugins/logfilesizechecker/LogfilesizecheckerWrapper/config.jelly").getText("UTF-8")
    assert txt.contains("<f:number")
    assert txt.contains('field="maxLogSize"')
}

double totalSec = results.collect { it.time }.sum() as double
def xml = new StringBuilder()
xml.append('<?xml version="1.0" encoding="UTF-8"?>\n')
xml.append(String.format(Locale.US, '<testsuite name="UnitAndComponentSuite" tests="%d" failures="0" errors="0" skipped="0" time="%.3f">\n', results.size(), totalSec))
results.each { r ->
    xml.append(String.format(Locale.US, '  <testcase classname="hudson.plugins.logfilesizechecker.LogfilesizecheckerUnitTest" name="%s" time="%.3f"/>\n', r.name, r.time))
}
xml.append('</testsuite>\n')
new File("/workspace/target/surefire-reports/TEST-UnitAndComponentSuite.xml").setText(xml.toString(), "UTF-8")
