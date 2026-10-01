package com.blazemeter.jmeter.correlation.core.har;

import static com.blazemeter.jmeter.correlation.core.har.HarBuilder.headers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.blazemeter.jmeter.correlation.CorrelationProxyControl;
import com.blazemeter.jmeter.correlation.CorrelationProxyControlBuilder;
import com.blazemeter.jmeter.correlation.JMeterTestUtils;
import com.blazemeter.jmeter.correlation.core.CorrelationRule;
import com.blazemeter.jmeter.correlation.core.RulesGroup;
import com.blazemeter.jmeter.correlation.core.extractors.JsonCorrelationExtractor;
import com.blazemeter.jmeter.correlation.core.extractors.RegexCorrelationExtractor;
import com.blazemeter.jmeter.correlation.core.extractors.ResultField;
import com.blazemeter.jmeter.correlation.core.har.HarImportReport.RuleUsage;
import com.blazemeter.jmeter.correlation.core.replacements.RegexCorrelationReplacement;
import com.blazemeter.jmeter.correlation.custom.extension.PersistedCustomExtractor;
import com.blazemeter.jmeter.correlation.gui.CorrelationComponentsRegistry;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.extractor.RegexExtractor;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HTTPSampler;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.timers.ConstantTimer;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class HarImporterTest {

  private static final String LOGIN_PAGE = "<form><input type=\"hidden\" name=\"csrf\" "
      + "value=\"a1b2c3\"/></form>";

  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();

  private JMeterTreeModel treeModel;
  private JMeterTreeNode threadGroupNode;
  private CorrelationProxyControl recorder;

  @BeforeClass
  public static void setupClass() {
    JMeterTestUtils.setupJmeterEnv();
  }

  @Before
  public void setup() throws Exception {
    treeModel = new JMeterTreeModel(new TestPlan("Plan"));
    JMeterTreeNode planNode = (JMeterTreeNode) ((JMeterTreeNode) treeModel.getRoot())
        .getChildAt(0);
    ThreadGroup threadGroup = new ThreadGroup();
    threadGroup.setName("Thread Group");
    threadGroupNode = treeModel.addComponent(threadGroup, planNode);
    recorder = new CorrelationProxyControlBuilder()
        .withLocalConfigurationPath(tempFolder.getRoot().getAbsolutePath())
        .build();
    JMeterTreeNode recorderNode = treeModel.addComponent(recorder, planNode);
    ConstantTimer timerTemplate = new ConstantTimer();
    timerTemplate.setProperty(TestElement.GUI_CLASS, "ConstantTimerGui");
    timerTemplate.setDelay("${T}");
    treeModel.addComponent(timerTemplate, recorderNode);
    recorder.setNonGuiTreeModel(treeModel);
    recorder.setExcludeList(new java.util.HashSet<>(Collections.singletonList(".*\\.png")));
  }

  private void setRules(boolean enabledGroup) {
    CorrelationRule csrf = new CorrelationRule("csrf",
        new RegexCorrelationExtractor<>("name=\"csrf\" value=\"([^\"]+)\"", 1, 1,
            ResultField.BODY),
        new RegexCorrelationReplacement<>("csrf=([^&]+)"));
    CorrelationRule unused = new CorrelationRule("unused",
        new RegexCorrelationExtractor<>("never=(\\d+)"),
        new RegexCorrelationReplacement<>("never=([^&]+)"));
    recorder.setCorrelationGroups(Collections.singletonList(new RulesGroup.Builder()
        .withId("Login").withRules(Arrays.asList(csrf, unused)).isEnabled(enabledGroup)
        .build()));
  }

  private File writeHar() throws IOException {
    String submit = "{\"mimeType\":\"application/x-www-form-urlencoded\","
        + "\"text\":\"user=john&csrf=a1b2c3\"}";
    String har = new HarBuilder()
        .entry("2024-05-06T12:00:00.000Z", "GET", "https://app.test/login",
            headers("accept", "text/html"), null, 200,
            headers("content-type", "text/html"), "text/html", LOGIN_PAGE)
        .entry("2024-05-06T12:00:00.500Z", "GET", "https://app.test/logo.png", headers(), null,
            200, headers("content-type", "image/png"), "image/png", "")
        .rawEntry("2024-05-06T12:00:01.000Z", "GET", "https://app.test/cached.js", headers(),
            null, 200, headers(), "{\"mimeType\":\"text/javascript\",\"text\":\"\"}",
            ",\"_fromCache\":\"disk\"")
        .entry("2024-05-06T12:00:06.000Z", "POST", "https://app.test/login",
            headers("content-type", "application/x-www-form-urlencoded"), submit, 200,
            headers("content-type", "text/html"), "text/html", "welcome")
        .build();
    File file = tempFolder.newFile("test.har");
    Files.write(file.toPath(), har.getBytes(StandardCharsets.UTF_8));
    return file;
  }

  private List<JMeterTreeNode> children(JMeterTreeNode node) {
    List<JMeterTreeNode> ret = new ArrayList<>();
    for (int i = 0; i < node.getChildCount(); i++) {
      ret.add((JMeterTreeNode) node.getChildAt(i));
    }
    return ret;
  }

  private <T> T childOfType(JMeterTreeNode node, Class<T> type) {
    return children(node).stream().map(JMeterTreeNode::getTestElement)
        .filter(type::isInstance).map(type::cast).findFirst().orElse(null);
  }

  @Test
  public void shouldAddCorrelatedSamplersToTestPlan() throws Exception {
    setRules(true);
    HarImportReport report = new HarImporter(recorder).importHar(writeHar(),
        new HarImportOptions());

    List<JMeterTreeNode> samplers = children(threadGroupNode);
    assertThat(samplers).hasSize(2);
    JMeterTreeNode loginPage = samplers.get(0);
    JMeterTreeNode submit = samplers.get(1);
    assertThat(((HTTPSamplerBase) loginPage.getTestElement()).getMethod()).isEqualTo("GET");
    assertThat(loginPage.getTestElement().getComment()).contains("ORIGINAL_NAME=");

    RegexExtractor extractor = childOfType(loginPage, RegexExtractor.class);
    assertThat(extractor).isNotNull();
    assertThat(extractor.getRefName()).isEqualTo("csrf");
    assertThat(childOfType(loginPage, HeaderManager.class)).isNotNull();

    HTTPSamplerBase submitSampler = (HTTPSamplerBase) submit.getTestElement();
    assertThat(submitSampler.getArguments().getArgumentsAsMap())
        .containsEntry("csrf", "${csrf}").containsEntry("user", "john");
    // the timer delay comes from the HAR timestamps, not from the import time
    assertThat(childOfType(submit, ConstantTimer.class).getDelay()).isEqualTo("6000");

    assertThat(report.getTotalEntries()).isEqualTo(4);
    assertThat(report.getImported()).isEqualTo(2);
    assertThat(report.getFilteredByRecorder()).isEqualTo(1);
    assertThat(report.getSkipped()).containsEntry("served from browser cache", 1);
    assertThat(report.getFailures()).isEmpty();
    RuleUsage csrfUsage = report.getRules().get(0);
    assertThat(csrfUsage.getReferenceName()).isEqualTo("csrf");
    assertThat(csrfUsage.getExtractions()).isEqualTo(1);
    assertThat(csrfUsage.getReplacements()).isGreaterThanOrEqualTo(1);
    assertThat(report.getRules().get(1).wasApplied()).isFalse();
    assertThat(report.toText()).contains("unused").contains("NOT APPLIED");

    assertThat(recorder.isLegacyEnabled()).isFalse();
    assertThat(recorder.isHarImportInProgress()).isFalse();
  }

  @Test
  public void shouldApplyRulesAgainWhenImportingTwice() throws Exception {
    setRules(true);
    File har = writeHar();
    new HarImporter(recorder).importHar(har, new HarImportOptions());
    HarImportReport second = new HarImporter(recorder).importHar(har, new HarImportOptions());
    assertThat(children(threadGroupNode)).hasSize(4);
    assertThat(second.getRules().get(0).getExtractions()).isEqualTo(1);
    HTTPSamplerBase lastSubmit = (HTTPSamplerBase) children(threadGroupNode).get(3)
        .getTestElement();
    assertThat(lastSubmit.getArguments().getArgumentsAsMap()).containsEntry("csrf", "${csrf}");
  }

  @Test
  public void shouldNotCorrelateWhenGroupIsDisabled() throws Exception {
    setRules(false);
    HarImportReport report = new HarImporter(recorder).importHar(writeHar(),
        new HarImportOptions());
    HTTPSamplerBase submitSampler = (HTTPSamplerBase) children(threadGroupNode).get(1)
        .getTestElement();
    assertThat(submitSampler.getArguments().getArgumentsAsMap()).containsEntry("csrf",
        "a1b2c3");
    assertThat(report.getRules()).allMatch(r -> !r.isEnabled());
  }

  @Test
  public void shouldImportWithoutRules() throws Exception {
    HarImportReport report = new HarImporter(recorder).importHar(writeHar(),
        new HarImportOptions());
    assertThat(children(threadGroupNode)).hasSize(2);
    assertThat(report.getRules()).isEmpty();
    assertThat(report.toText()).contains("No correlation rules configured");
  }

  @Test
  public void shouldKeepRulesWhenComponentsAreNotRegisteredInTheRegistry() {
    /*
     The registry only resolves the extractors and replacements it considers "active", and its
     list of components depends on a class path scan that may not find every class (and other
     parts of the plugin replace the registry instance). The import has to activate the classes
     referenced by the rules, otherwise the rules would be silently dropped.
    */
    CorrelationComponentsRegistry.getNewInstance();
    CorrelationRule rule = new CorrelationRule("token",
        new PersistedCustomExtractor<>("token=([^&]+)"),
        new RegexCorrelationReplacement<>("token=([^&]+)"));
    recorder.setCorrelationGroups(Collections.singletonList(new RulesGroup.Builder()
        .withId("Custom").withRules(Collections.singletonList(rule)).build()));

    recorder.startHarImport();
    try {
      CorrelationRule imported = recorder.getHarImportGroups().get(0).getRules().get(0);
      assertThat(imported.getCorrelationExtractor())
          .isInstanceOf(PersistedCustomExtractor.class);
      assertThat(imported.getCorrelationReplacement())
          .isInstanceOf(RegexCorrelationReplacement.class);
    } finally {
      recorder.endHarImport();
    }
  }

  @Test
  public void shouldCorrelateTokenFromResponseHeaderIntoRequestHeader() throws Exception {
    CorrelationRule rule = new CorrelationRule("hdrToken",
        new RegexCorrelationExtractor<>("X-Token: (\\w+)", 1, 1, ResultField.RESPONSE_HEADERS),
        new RegexCorrelationReplacement<>("X-Token: (\\w+)"));
    recorder.setCorrelationGroups(Collections.singletonList(new RulesGroup.Builder()
        .withId("Headers").withRules(Collections.singletonList(rule)).build()));

    String har = new HarBuilder()
        .entry("2024-05-06T12:00:00.000Z", "GET", "https://app.test/start",
            headers("accept", "text/html"), null, 200,
            headers("content-type", "text/html", "x-token", "abc123"), "text/html", "<html/>")
        .entry("2024-05-06T12:00:01.000Z", "GET", "https://app.test/next",
            headers("x-token", "abc123"), null, 200, headers("content-type", "text/html"),
            "text/html", "<html/>")
        .build();
    File file = tempFolder.newFile("headers.har");
    Files.write(file.toPath(), har.getBytes(StandardCharsets.UTF_8));

    HarImportReport report = new HarImporter(recorder).importHar(file, new HarImportOptions());

    List<JMeterTreeNode> samplers = children(threadGroupNode);
    assertThat(samplers).hasSize(2);
    assertThat(childOfType(samplers.get(0), RegexExtractor.class).getRefName())
        .isEqualTo("hdrToken");
    HeaderManager headers = childOfType(samplers.get(1), HeaderManager.class);
    assertThat(headers.getFirstHeaderNamed("X-Token").getValue()).isEqualTo("${hdrToken}");
    assertThat(report.getRules().get(0).wasApplied()).isTrue();
  }

  @Test
  public void shouldReportNothingWhenHarHasNoEntries() throws Exception {
    File file = tempFolder.newFile("empty.har");
    Files.write(file.toPath(),
        "{\"log\":{\"version\":\"1.2\",\"entries\":[]}}".getBytes(StandardCharsets.UTF_8));
    HarImportReport report = new HarImporter(recorder).importHar(file, new HarImportOptions());
    assertThat(report.getTotalEntries()).isZero();
    assertThat(report.getImported()).isZero();
    assertThat(children(threadGroupNode)).isEmpty();
  }

  @Test
  public void shouldCorrelateTokenFromJsonBodyAndNotBeforeItIsExtracted() throws Exception {
    CorrelationRule rule = new CorrelationRule("jsonToken",
        new JsonCorrelationExtractor<>("$.token"),
        new RegexCorrelationReplacement<>("token=([^&]+)"));
    recorder.setCorrelationGroups(Collections.singletonList(new RulesGroup.Builder()
        .withId("Json").withRules(Collections.singletonList(rule)).build()));

    String earlyPost = "{\"mimeType\":\"application/x-www-form-urlencoded\","
        + "\"text\":\"token=zzz111\"}";
    String latePost = "{\"mimeType\":\"application/x-www-form-urlencoded\","
        + "\"text\":\"token=zzz111\"}";
    String har = new HarBuilder()
        // sent BEFORE the token is known: must stay as it was recorded
        .entry("2024-05-06T12:00:00.000Z", "POST", "https://app.test/early",
            headers("content-type", "application/x-www-form-urlencoded"), earlyPost, 200,
            headers("content-type", "text/html"), "text/html", "ok")
        .entry("2024-05-06T12:00:01.000Z", "GET", "https://app.test/api/session", headers(), null,
            200, headers("content-type", "application/json"), "application/json",
            "{\"token\":\"zzz111\",\"other\":1}")
        .entry("2024-05-06T12:00:02.000Z", "POST", "https://app.test/late",
            headers("content-type", "application/x-www-form-urlencoded"), latePost, 200,
            headers("content-type", "text/html"), "text/html", "ok")
        .build();
    File file = tempFolder.newFile("json.har");
    Files.write(file.toPath(), har.getBytes(StandardCharsets.UTF_8));

    new HarImporter(recorder).importHar(file, new HarImportOptions());

    List<JMeterTreeNode> samplers = children(threadGroupNode);
    assertThat(samplers).hasSize(3);
    HTTPSamplerBase early = (HTTPSamplerBase) samplers.get(0).getTestElement();
    HTTPSamplerBase late = (HTTPSamplerBase) samplers.get(2).getTestElement();
    assertThat(early.getArguments().getArgumentsAsMap()).containsEntry("token", "zzz111");
    assertThat(late.getArguments().getArgumentsAsMap()).containsEntry("token", "${jsonToken}");
    assertThat(children(samplers.get(1)).stream()
        .map(n -> n.getTestElement().getName())).anyMatch(n -> n.contains("jsonToken"));
  }

  @Test
  public void shouldGroupSamplersUsingTheHarTimesWhenGroupingInTransactionControllers()
      throws Exception {
    recorder.setGroupingMode(4); // group in transaction controllers
    recorder.setProperty("ProxyControlGui.grouping_mode", 4);
    recorder.setProxyPauseHTTPSample("2000");
    String har = new HarBuilder()
        .entry("2024-05-06T12:00:00.000Z", "GET", "https://app.test/a", headers(), null, 200,
            headers("content-type", "text/html"), "text/html", "a")
        .entry("2024-05-06T12:00:00.300Z", "GET", "https://app.test/b", headers(), null, 200,
            headers("content-type", "text/html"), "text/html", "b")
        // more than 2s later: a new transaction controller has to be created
        .entry("2024-05-06T12:00:30.000Z", "GET", "https://app.test/c", headers(), null, 200,
            headers("content-type", "text/html"), "text/html", "c")
        .build();
    File file = tempFolder.newFile("grouping.har");
    Files.write(file.toPath(), har.getBytes(StandardCharsets.UTF_8));

    new HarImporter(recorder).importHar(file, new HarImportOptions());

    List<JMeterTreeNode> groups = children(threadGroupNode);
    assertThat(groups).hasSize(2);
    assertThat(groups.get(0).getTestElement()).isInstanceOf(TransactionController.class);
    assertThat(children(groups.get(0)).stream().filter(n -> n.getTestElement()
        instanceof HTTPSamplerBase)).hasSize(2);
    assertThat(children(groups.get(1)).stream().filter(n -> n.getTestElement()
        instanceof HTTPSamplerBase)).hasSize(1);
  }

  @Test
  public void shouldKeepRecordingWorkingAfterAnImport() throws Exception {
    setRules(true);
    new HarImporter(recorder).importHar(writeHar(), new HarImportOptions());
    int importedSamplers = children(threadGroupNode).size();

    // simulates a request recorded by the proxy after the import
    HTTPSampler sampler = new HTTPSampler();
    sampler.setDomain("app.test");
    sampler.setPath("/recorded");
    sampler.setMethod("GET");
    sampler.setName("/recorded");
    sampler.setProperty(TestElement.GUI_CLASS, "HttpTestSampleGui");
    sampler.setHeaderManager(new HeaderManager());
    HTTPSampleResult result = new HTTPSampleResult();
    result.setURL(new java.net.URL("https://app.test/recorded"));
    result.setHTTPMethod("GET");
    result.setResponseCode("200");
    result.setSuccessful(true);
    result.setContentType("text/html");
    result.setResponseData("<html/>", StandardCharsets.UTF_8.name());

    recorder.startedProxy(Thread.currentThread());
    recorder.deliverSampler(sampler, new TestElement[] {}, result);
    recorder.endedProxy(Thread.currentThread());
    recorder.flushHarSamplesIntoModel();

    assertThat(children(threadGroupNode)).hasSize(importedSamplers + 1);
    assertThat(children(threadGroupNode).get(importedSamplers).getTestElement().getName())
        .isEqualTo("/recorded");
    assertThat(recorder.isHarImportInProgress()).isFalse();
  }

  @Test
  public void shouldFailWithInvalidHar() throws Exception {
    File file = tempFolder.newFile("bad.har");
    Files.write(file.toPath(), "{\"nolog\":{}}".getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> new HarImporter(recorder).importHar(file, new HarImportOptions()))
        .isInstanceOf(IOException.class);
    assertThat(recorder.isHarImportInProgress()).isFalse();
  }
}
