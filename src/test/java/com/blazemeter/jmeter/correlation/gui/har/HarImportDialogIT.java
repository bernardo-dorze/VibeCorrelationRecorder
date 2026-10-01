package com.blazemeter.jmeter.correlation.gui.har;

import static com.blazemeter.jmeter.correlation.core.har.HarBuilder.headers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.blazemeter.jmeter.correlation.CorrelationProxyControl;
import com.blazemeter.jmeter.correlation.CorrelationProxyControlBuilder;
import com.blazemeter.jmeter.correlation.JMeterTestUtils;
import com.blazemeter.jmeter.correlation.SwingTestRunner;
import com.blazemeter.jmeter.correlation.core.CorrelationRule;
import com.blazemeter.jmeter.correlation.core.RulesGroup;
import com.blazemeter.jmeter.correlation.core.extractors.RegexCorrelationExtractor;
import com.blazemeter.jmeter.correlation.core.extractors.ResultField;
import com.blazemeter.jmeter.correlation.core.har.HarBuilder;
import com.blazemeter.jmeter.correlation.core.replacements.RegexCorrelationReplacement;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.awt.Dialog;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.ThreadGroup;
import org.assertj.swing.core.GenericTypeMatcher;
import org.assertj.swing.core.matcher.JButtonMatcher;
import org.assertj.swing.finder.WindowFinder;
import org.assertj.swing.fixture.DialogFixture;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

@RunWith(SwingTestRunner.class)
public class HarImportDialogIT {

  private static final String TITLE = "Import HAR (apply correlation rules)";

  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();

  private CorrelationProxyControl recorder;
  private JMeterTreeModel treeModel;
  private JMeterTreeNode threadGroupNode;
  private HarImportDialog dialog;
  private DialogFixture fixture;
  private Runnable modelUpdater;

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
    treeModel.addComponent(recorder, planNode);
    recorder.setNonGuiTreeModel(treeModel);
    modelUpdater = org.mockito.Mockito.mock(Runnable.class);
    dialog = new HarImportDialog(null, () -> recorder, modelUpdater);
    fixture = new DialogFixture(dialog);
    fixture.show();
  }

  @After
  public void tearDown() {
    fixture.cleanUp();
    fixture = null;
  }

  /**
   * Finds the modal dialog (JOptionPane) shown by the import dialog, waiting for it to appear
   * since the import runs in background.
   */
  private DialogFixture findModalDialog(String title) {
    return WindowFinder.findDialog(new GenericTypeMatcher<Dialog>(Dialog.class) {
      @Override
      protected boolean isMatching(Dialog candidate) {
        return candidate.isModal() && candidate.isShowing() && title.equals(candidate.getTitle());
      }
    }).withTimeout(30000).using(fixture.robot());
  }

  private void setRules() {
    CorrelationRule csrf = new CorrelationRule("csrf",
        new RegexCorrelationExtractor<>("name=\"csrf\" value=\"([^\"]+)\"", 1, 1,
            ResultField.BODY),
        new RegexCorrelationReplacement<>("csrf=([^&]+)"));
    recorder.setCorrelationGroups(Collections.singletonList(new RulesGroup.Builder()
        .withId("Login").withRules(Collections.singletonList(csrf)).build()));
  }

  private File writeHar() throws Exception {
    String submit = "{\"mimeType\":\"application/x-www-form-urlencoded\","
        + "\"text\":\"csrf=tok123\"}";
    String har = new HarBuilder()
        .entry("2024-05-06T12:00:00.000Z", "GET", "https://app.test/login", headers(), null, 200,
            headers("content-type", "text/html"), "text/html",
            "<input name=\"csrf\" value=\"tok123\"/>")
        .entry("2024-05-06T12:00:02.000Z", "POST", "https://app.test/login",
            headers("content-type", "application/x-www-form-urlencoded"), submit, 200,
            headers("content-type", "text/html"), "text/html", "ok")
        .build();
    File file = tempFolder.newFile("gui.har");
    Files.write(file.toPath(), har.getBytes(StandardCharsets.UTF_8));
    return file;
  }

  @Test
  public void shouldImportHarApplyingRulesWhenImportClicked() throws Exception {
    setRules();
    fixture.textBox("harFileField").setText(writeHar().getAbsolutePath());
    fixture.button("importHarStartButton").click();

    fixture.robot().waitForIdle();
    DialogFixture report = findModalDialog("HAR import report");
    assertThat(report.textBox("reportText").text())
        .contains("Imported as samplers: 2")
        .contains("csrf: extracted 1 time(s)");
    report.button(JButtonMatcher.withText("OK")).click();

    verify(modelUpdater).run();
    assertThat(threadGroupNode.getChildCount()).isEqualTo(2);
    HTTPSamplerBase submit = (HTTPSamplerBase) ((JMeterTreeNode) threadGroupNode.getChildAt(1))
        .getTestElement();
    assertThat(submit.getArguments().getArgumentsAsMap()).containsEntry("csrf", "${csrf}");
    assertThat(fixture.label("statusLabel").text()).contains("2 request(s) imported");
  }

  @Test
  public void shouldShowErrorWhenFileDoesNotExist() {
    setRules();
    fixture.textBox("harFileField").setText(tempFolder.getRoot() + "/missing.har");
    fixture.button("importHarStartButton").click();
    fixture.robot().waitForIdle();
    DialogFixture errorFixture = findModalDialog(TITLE);
    assertThat(errorFixture.target().isVisible()).isTrue();
    errorFixture.button(JButtonMatcher.withText("OK")).click();
    assertThat(threadGroupNode.getChildCount()).isZero();
  }

  @Test
  public void shouldBuildOptionsFromCheckBoxes() {
    assertThat(dialog.getOptions().isSkipCachedEntries()).isTrue();
    assertThat(dialog.getOptions().isNormalizeHeaderNames()).isTrue();
    fixture.checkBox("skipCachedCheck").uncheck();
    fixture.checkBox("normalizeHeadersCheck").uncheck();
    fixture.checkBox("skipWithoutResponseCheck").uncheck();
    assertThat(dialog.getOptions().isSkipCachedEntries()).isFalse();
    assertThat(dialog.getOptions().isNormalizeHeaderNames()).isFalse();
    assertThat(dialog.getOptions().isSkipEntriesWithoutResponse()).isFalse();
  }
}
