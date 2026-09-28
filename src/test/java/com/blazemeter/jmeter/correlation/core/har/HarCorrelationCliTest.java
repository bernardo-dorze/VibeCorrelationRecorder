package com.blazemeter.jmeter.correlation.core.har;

import static com.blazemeter.jmeter.correlation.core.har.HarBuilder.headers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.blazemeter.jmeter.correlation.CorrelationProxyControl;
import com.blazemeter.jmeter.correlation.CorrelationProxyControlBuilder;
import com.blazemeter.jmeter.correlation.JMeterTestUtils;
import com.blazemeter.jmeter.correlation.core.CorrelationRule;
import com.blazemeter.jmeter.correlation.core.RulesGroup;
import com.blazemeter.jmeter.correlation.core.automatic.JMeterElementUtils;
import com.blazemeter.jmeter.correlation.core.extractors.RegexCorrelationExtractor;
import com.blazemeter.jmeter.correlation.core.extractors.ResultField;
import com.blazemeter.jmeter.correlation.core.replacements.RegexCorrelationReplacement;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.jmeter.control.GenericController;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.control.RecordingController;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jorphan.collections.HashTree;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class HarCorrelationCliTest {

  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();

  @BeforeClass
  public static void setupClass() {
    JMeterTestUtils.setupJmeterEnv();
  }

  private File writePlan(boolean withRecorder) throws Exception {
    TestPlan plan = new TestPlan("Plan");
    plan.setProperty(TestElement.GUI_CLASS, "TestPlanGui");
    JMeterTreeModel treeModel = new JMeterTreeModel(plan);
    JMeterTreeNode planNode = (JMeterTreeNode) ((JMeterTreeNode) treeModel.getRoot())
        .getChildAt(0);
    ThreadGroup threadGroup = new ThreadGroup();
    threadGroup.setName("Thread Group");
    threadGroup.setProperty(TestElement.GUI_CLASS, "ThreadGroupGui");
    JMeterTreeNode threadGroupNode = treeModel.addComponent(threadGroup, planNode);
    RecordingController recordingController = new RecordingController();
    recordingController.setName("Recording Controller");
    recordingController.setProperty(TestElement.GUI_CLASS, "RecordControllerGui");
    treeModel.addComponent(recordingController, threadGroupNode);

    if (withRecorder) {
      CorrelationProxyControl recorder = new CorrelationProxyControlBuilder()
          .withLocalConfigurationPath(tempFolder.getRoot().getAbsolutePath())
          .build();
      recorder.setProperty(TestElement.GUI_CLASS, "CorrelationProxyControlGui");
      CorrelationRule csrf = new CorrelationRule("csrf",
          new RegexCorrelationExtractor<>("name=\"csrf\" value=\"([^\"]+)\"", 1, 1,
              ResultField.BODY),
          new RegexCorrelationReplacement<>("csrf=([^&]+)"));
      recorder.setCorrelationGroups(Collections.singletonList(new RulesGroup.Builder()
          .withId("Login").withRules(Collections.singletonList(csrf)).build()));
      treeModel.addComponent(recorder, planNode);
    }

    File planFile = tempFolder.newFile(withRecorder ? "plan.jmx" : "no-recorder.jmx");
    HashTree planTree = treeModel.getTestPlan();
    // the tree of a JMeterTreeModel holds nodes, they have to be replaced by their test elements
    JMeterElementUtils.convertSubTree(planTree);
    try (OutputStream out = Files.newOutputStream(planFile.toPath())) {
      SaveService.saveTree(planTree, out);
    }
    return planFile;
  }

  private File writeHar() throws IOException {
    String submit = "{\"mimeType\":\"application/x-www-form-urlencoded\","
        + "\"text\":\"user=jane&csrf=zzz999\"}";
    String har = new HarBuilder()
        .entry("2024-05-06T12:00:00.000Z", "GET", "https://app.test/login", headers(), null, 200,
            headers("content-type", "text/html"), "text/html",
            "<input name=\"csrf\" value=\"zzz999\"/>")
        .entry("2024-05-06T12:00:03.000Z", "POST", "https://app.test/login",
            headers("content-type", "application/x-www-form-urlencoded"), submit, 200,
            headers("content-type", "text/html"), "text/html", "welcome jane")
        .build();
    File file = tempFolder.newFile("cli.har");
    Files.write(file.toPath(), har.getBytes(StandardCharsets.UTF_8));
    return file;
  }

  private static List<HTTPSamplerBase> samplersOf(HashTree tree) {
    List<HTTPSamplerBase> samplers = new java.util.ArrayList<>();
    collectSamplers(tree, samplers);
    return samplers;
  }

  private static void collectSamplers(HashTree tree, List<HTTPSamplerBase> samplers) {
    for (Object key : tree.list()) {
      if (key instanceof HTTPSamplerBase) {
        samplers.add((HTTPSamplerBase) key);
      }
      collectSamplers(tree.getTree(key), samplers);
    }
  }

  @Test
  public void shouldImportHarIntoPlanApplyingRules() throws Exception {
    File output = new File(tempFolder.getRoot(), "out/result.jmx");
    HarImportReport report = HarCorrelationCli.run(writePlan(true), writeHar(), output,
        new HarImportOptions());

    assertThat(report.getImported()).isEqualTo(2);
    assertThat(report.getRules().get(0).getExtractions()).isEqualTo(1);
    assertThat(output).exists();

    HashTree saved = SaveService.loadTree(output);
    List<HTTPSamplerBase> samplers = samplersOf(saved);
    assertThat(samplers).hasSize(2);
    assertThat(samplers.get(1).getArguments().getArgumentsAsMap())
        .containsEntry("csrf", "${csrf}").containsEntry("user", "jane");
    List<String> extractorNames = children(saved, samplers.get(0)).stream()
        .map(TestElement::getName).collect(Collectors.toList());
    assertThat(extractorNames).contains("RegExp - csrf");
  }

  private static List<TestElement> children(HashTree tree, TestElement parent) {
    HashTree subTree = findSubTree(tree, parent);
    if (subTree == null) {
      return Collections.emptyList();
    }
    return subTree.list().stream().map(TestElement.class::cast).collect(Collectors.toList());
  }

  private static HashTree findSubTree(HashTree tree, Object key) {
    for (Object current : tree.list()) {
      if (current == key) {
        return tree.getTree(current);
      }
      HashTree found = findSubTree(tree.getTree(current), key);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  @Test
  public void shouldStoreSamplersInsideRecordingController() throws Exception {
    File output = new File(tempFolder.getRoot(), "result2.jmx");
    HarCorrelationCli.run(writePlan(true), writeHar(), output, new HarImportOptions());
    HashTree saved = SaveService.loadTree(output);
    List<GenericController> controllers = new java.util.ArrayList<>();
    collectControllers(saved, controllers);
    RecordingController recording = (RecordingController) controllers.stream()
        .filter(RecordingController.class::isInstance).findFirst().orElse(null);
    assertThat(recording).isNotNull();
    assertThat(samplersOf(findSubTree(saved, recording))).hasSize(2);
  }

  private static void collectControllers(HashTree tree, List<GenericController> controllers) {
    for (Object key : tree.list()) {
      if (key instanceof GenericController) {
        controllers.add((GenericController) key);
      }
      collectControllers(tree.getTree(key), controllers);
    }
  }

  @Test
  public void shouldFailWhenPlanHasNoRecorder() throws Exception {
    File output = new File(tempFolder.getRoot(), "none.jmx");
    assertThatThrownBy(() -> HarCorrelationCli.run(writePlan(false), writeHar(), output,
        new HarImportOptions()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Auto Correlation Recorder");
  }

  @Test
  public void shouldFailWhenFilesAreMissing() throws Exception {
    File missing = new File(tempFolder.getRoot(), "missing");
    assertThatThrownBy(() -> HarCorrelationCli.run(missing, writeHar(),
        new File(tempFolder.getRoot(), "o.jmx"), new HarImportOptions()))
        .isInstanceOf(IOException.class).hasMessageContaining("Test plan not found");
    assertThatThrownBy(() -> HarCorrelationCli.run(writePlan(true), missing,
        new File(tempFolder.getRoot(), "o.jmx"), new HarImportOptions()))
        .isInstanceOf(IOException.class).hasMessageContaining("HAR file not found");
  }
}
