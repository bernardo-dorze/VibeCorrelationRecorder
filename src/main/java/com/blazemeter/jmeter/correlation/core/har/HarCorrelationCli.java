package com.blazemeter.jmeter.correlation.core.har;

import com.blazemeter.jmeter.correlation.CorrelationProxyControl;
import com.blazemeter.jmeter.correlation.core.automatic.JMeterElementUtils;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.HashTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Imports a HAR file into a JMeter test plan without GUI.
 *
 * <p>The test plan has to contain a configured <code>bzm - Auto Correlation Recorder</code> with
 * the Correlation Rules to apply (for example, the plan saved after loading a Correlation
 * Template), and a Recording Controller or Thread Group where the requests are stored.
 *
 * <p>Usage (from the JMeter installation directory):
 * <pre>
 * java -cp "lib/ext/*:lib/*" \
 *   com.blazemeter.jmeter.correlation.core.har.HarCorrelationCli \
 *   plan.jmx recording.har result.jmx [options]
 * </pre>
 */
public final class HarCorrelationCli {

  private static final Logger LOG = LoggerFactory.getLogger(HarCorrelationCli.class);
  private static final String USAGE = "Usage: HarCorrelationCli <plan.jmx> <input.har> "
      + "<output.jmx> [--keep-cached] [--keep-without-response] [--no-header-normalization]"
      + System.lineSeparator()
      + "  <plan.jmx>    test plan containing the Auto Correlation Recorder with the rules"
      + System.lineSeparator()
      + "  <input.har>   HAR file to import" + System.lineSeparator()
      + "  <output.jmx>  file where the resulting test plan is saved";

  private HarCorrelationCli() {
  }

  /**
   * Entry point.
   *
   * @param args plan, har and output files, followed by the options
   * @throws Exception if the import fails
   */
  public static void main(String[] args) throws Exception {
    if (args.length < 3) {
      System.err.println(USAGE);
      System.exit(1);
    }
    HarImportOptions options = new HarImportOptions();
    for (int i = 3; i < args.length; i++) {
      switch (args[i]) {
        case "--keep-cached":
          options.setSkipCachedEntries(false);
          break;
        case "--keep-without-response":
          options.setSkipEntriesWithoutResponse(false);
          break;
        case "--no-header-normalization":
          options.setNormalizeHeaderNames(false);
          break;
        default:
          System.err.println("Unknown option: " + args[i]);
          System.err.println(USAGE);
          System.exit(1);
      }
    }
    initJMeterEnvironment();
    HarImportReport report = run(new File(args[0]), new File(args[1]), new File(args[2]), options);
    System.out.println(report.toText());
    if (report.getImported() == 0) {
      System.err.println("No request was imported, check the HAR file and the recorder filters.");
      System.exit(2);
    }
  }

  /**
   * Initializes the JMeter properties when the class is not launched by JMeter itself (plain
   * <code>java -cp</code>), using the JMeter installation pointed by the <code>jmeter.home</code>
   * system property, the <code>JMETER_HOME</code> environment variable or the current directory.
   */
  static void initJMeterEnvironment() {
    if (JMeterUtils.getJMeterProperties() != null) {
      return;
    }
    String home = System.getProperty("jmeter.home",
        System.getenv("JMETER_HOME") != null ? System.getenv("JMETER_HOME") : ".");
    File properties = new File(home, "bin/jmeter.properties");
    if (!properties.isFile()) {
      throw new IllegalStateException("Could not find " + properties.getAbsolutePath()
          + ". Run this tool from the JMeter directory or set the jmeter.home system property.");
    }
    JMeterUtils.setJMeterHome(new File(home).getAbsolutePath());
    JMeterUtils.loadJMeterProperties(properties.getAbsolutePath());
    JMeterUtils.initLocale();
  }

  /**
   * Imports the HAR file into the given test plan and saves the result.
   *
   * @param planFile test plan with the recorder and its correlation rules
   * @param harFile HAR file to import
   * @param outputFile file where the resulting test plan is saved
   * @param options import options
   * @return the import report
   * @throws IOException if any file can't be read or written
   */
  public static HarImportReport run(File planFile, File harFile, File outputFile,
      HarImportOptions options) throws IOException {
    if (!planFile.isFile()) {
      throw new IOException("Test plan not found: " + planFile);
    }
    if (!harFile.isFile()) {
      throw new IOException("HAR file not found: " + harFile);
    }
    HashTree plan = SaveService.loadTree(planFile);
    JMeterTreeModel treeModel = new JMeterTreeModel();
    try {
      treeModel.addSubTree(plan, (JMeterTreeNode) treeModel.getRoot());
    } catch (Exception e) {
      throw new IOException("Could not load the test plan " + planFile, e);
    }
    CorrelationProxyControl recorder = findRecorder(treeModel);
    recorder.setNonGuiTreeModel(treeModel);

    HarImportReport report = new HarImporter(recorder).importHar(harFile, options,
        message -> LOG.info("{}", message));

    HashTree resultPlan = treeModel.getTestPlan();
    // the tree of a JMeterTreeModel holds nodes, they have to be replaced by their test elements
    JMeterElementUtils.convertSubTree(resultPlan);
    Files.createDirectories(outputFile.getAbsoluteFile().toPath().getParent());
    try (java.io.OutputStream out = Files.newOutputStream(Paths.get(outputFile.getPath()))) {
      SaveService.saveTree(resultPlan, out);
    }
    LOG.info("Test plan with {} imported request(s) saved to {}", report.getImported(),
        outputFile);
    return report;
  }

  private static CorrelationProxyControl findRecorder(JMeterTreeModel treeModel)
      throws IOException {
    List<JMeterTreeNode> nodes = treeModel.getNodesOfType(CorrelationProxyControl.class);
    for (JMeterTreeNode node : nodes) {
      if (node.isEnabled()) {
        return (CorrelationProxyControl) node.getTestElement();
      }
    }
    if (!nodes.isEmpty()) {
      return (CorrelationProxyControl) nodes.get(0).getTestElement();
    }
    throw new IOException("The test plan does not contain a 'bzm - Auto Correlation Recorder'. "
        + "Add it with your Correlation Rules and save the plan.");
  }

}
