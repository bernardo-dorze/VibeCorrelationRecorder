package com.blazemeter.jmeter.correlation.gui.har;

import com.blazemeter.jmeter.commons.SwingUtils;
import com.blazemeter.jmeter.correlation.CorrelationProxyControl;
import com.blazemeter.jmeter.correlation.core.har.HarImportOptions;
import com.blazemeter.jmeter.correlation.core.har.HarImportReport;
import com.blazemeter.jmeter.correlation.core.har.HarImporter;
import com.google.common.annotations.VisibleForTesting;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.io.File;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dialog that imports a HAR file into the current Test Plan applying the correlation rules
 * configured in the recorder, as an alternative to recording with the proxy.
 */
public class HarImportDialog extends JDialog {

  private static final Logger LOG = LoggerFactory.getLogger(HarImportDialog.class);
  private static final String TITLE = "Import HAR (apply correlation rules)";
  private static final long serialVersionUID = 1L;

  private final JTextField harFileField = SwingUtils.createComponent("harFileField",
      new JTextField(), new Dimension(380, 25));
  private final JCheckBox skipCached = SwingUtils.createComponent("skipCachedCheck",
      new JCheckBox("Skip requests served from the browser cache", true));
  private final JCheckBox skipWithoutResponse = SwingUtils.createComponent(
      "skipWithoutResponseCheck",
      new JCheckBox("Skip requests without response (blocked or failed)", true));
  private final JCheckBox normalizeHeaders = SwingUtils.createComponent("normalizeHeadersCheck",
      new JCheckBox("Normalize HTTP/2 lower case header names (x-token to X-Token)", true));
  private final JLabel statusLabel = SwingUtils.createComponent("statusLabel", new JLabel(" "));
  private final JProgressBar progressBar = SwingUtils.createComponent("progressBar",
      new JProgressBar());
  private final JButton importButton = SwingUtils.createComponent("importHarStartButton",
      new JButton("Import"));
  private final JButton closeButton = SwingUtils.createComponent("closeButton",
      new JButton("Close"));

  private final Supplier<CorrelationProxyControl> modelSupplier;
  private final Runnable modelUpdater;
  private transient SwingWorker<HarImportReport, String> worker;

  /**
   * Creates the dialog.
   *
   * @param parent component used to position the dialog
   * @param modelSupplier provides the recorder that holds the correlation rules
   * @param modelUpdater pushes the rules displayed in the GUI into the recorder before importing
   */
  public HarImportDialog(Component parent, Supplier<CorrelationProxyControl> modelSupplier,
      Runnable modelUpdater) {
    super(parent instanceof java.awt.Window ? (java.awt.Window) parent : null, TITLE);
    this.modelSupplier = modelSupplier;
    this.modelUpdater = modelUpdater;
    setName("harImportDialog");
    setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    setModal(false);
    buildContent();
    pack();
    setLocationRelativeTo(parent);
  }

  private void buildContent() {
    JPanel main = new JPanel();
    main.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
    main.setLayout(new BoxLayout(main, BoxLayout.Y_AXIS));

    JLabel help = new JLabel("<html>The requests of the HAR file are added to the Test Plan as if "
        + "they had been recorded,<br/>applying the Correlation Rules of this recorder "
        + "(Legacy Correlation).</html>");
    help.setAlignmentX(Component.LEFT_ALIGNMENT);
    main.add(help);
    main.add(Box.createRigidArea(new Dimension(0, 10)));

    JPanel filePanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
    filePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
    filePanel.add(new JLabel("HAR file: "));
    filePanel.add(harFileField);
    JButton browseButton = SwingUtils.createComponent("browseButton", new JButton("Browse..."));
    browseButton.addActionListener(this::browse);
    filePanel.add(Box.createRigidArea(new Dimension(5, 0)));
    filePanel.add(browseButton);
    main.add(filePanel);
    main.add(Box.createRigidArea(new Dimension(0, 10)));

    for (JCheckBox check : new JCheckBox[] {skipCached, skipWithoutResponse, normalizeHeaders}) {
      check.setAlignmentX(Component.LEFT_ALIGNMENT);
      main.add(check);
    }
    main.add(Box.createRigidArea(new Dimension(0, 10)));

    progressBar.setIndeterminate(false);
    progressBar.setVisible(false);
    progressBar.setAlignmentX(Component.LEFT_ALIGNMENT);
    main.add(progressBar);
    statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
    main.add(statusLabel);
    main.add(Box.createRigidArea(new Dimension(0, 10)));

    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 0, 0));
    buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
    importButton.addActionListener(this::startImport);
    closeButton.addActionListener(e -> dispose());
    buttons.add(importButton);
    buttons.add(Box.createRigidArea(new Dimension(5, 0)));
    buttons.add(closeButton);
    main.add(buttons);

    getContentPane().add(main, BorderLayout.CENTER);
  }

  private void browse(ActionEvent event) {
    JFileChooser chooser = new JFileChooser();
    chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
    chooser.setFileFilter(new FileNameExtensionFilter("HTTP Archive (*.har)", "har"));
    if (!harFileField.getText().trim().isEmpty()) {
      chooser.setSelectedFile(new File(harFileField.getText().trim()));
    }
    if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
      harFileField.setText(chooser.getSelectedFile().getAbsolutePath());
    }
  }

  @VisibleForTesting
  public void setHarFile(String path) {
    harFileField.setText(path);
  }

  @VisibleForTesting
  public HarImportOptions getOptions() {
    return new HarImportOptions()
        .setSkipCachedEntries(skipCached.isSelected())
        .setSkipEntriesWithoutResponse(skipWithoutResponse.isSelected())
        .setNormalizeHeaderNames(normalizeHeaders.isSelected());
  }

  private void startImport(ActionEvent event) {
    String path = harFileField.getText().trim();
    if (path.isEmpty()) {
      showError("Select a HAR file to import.");
      return;
    }
    File harFile = new File(path);
    if (!harFile.isFile()) {
      showError("The file '" + path + "' does not exist.");
      return;
    }
    modelUpdater.run();
    CorrelationProxyControl model = modelSupplier.get();
    if (model == null) {
      showError("The recorder is not available. Add it to the Test Plan and try again.");
      return;
    }
    if (model.isServerRunning()) {
      showError("The recorder is running. Stop it before importing a HAR file.");
      return;
    }
    if (model.isHarImportInProgress()) {
      showError("An import is already in progress.");
      return;
    }
    if (model.findTargetControllerNode() == null) {
      showError("No place to store the requests was found. Add a Recording Controller or a "
          + "Thread Group to the Test Plan and try again.");
      return;
    }
    if (!model.hasLoadedRules() && !confirmNoRules()) {
      return;
    }
    runImport(harFile, model);
  }

  private boolean confirmNoRules() {
    return JOptionPane.showConfirmDialog(this,
        "There are no Correlation Rules configured, the requests will be imported without "
            + "correlation." + System.lineSeparator() + "Do you want to continue?", TITLE,
        JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
  }

  private void runImport(File harFile, CorrelationProxyControl model) {
    setInProgress(true);
    HarImportOptions options = getOptions();
    worker = new SwingWorker<HarImportReport, String>() {
      @Override
      protected HarImportReport doInBackground() throws Exception {
        return new HarImporter(model).importHar(harFile, options, this::publish);
      }

      @Override
      protected void process(List<String> messages) {
        statusLabel.setText(messages.get(messages.size() - 1));
      }

      @Override
      protected void done() {
        setInProgress(false);
        try {
          showReport(get());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException e) {
          LOG.error("Error importing the HAR file {}", harFile, e.getCause());
          statusLabel.setText("The import failed.");
          showError("The HAR file could not be imported: " + e.getCause().getMessage()
              + System.lineSeparator() + "Check the logs for more details.");
        }
      }
    };
    worker.execute();
  }

  private void setInProgress(boolean inProgress) {
    importButton.setEnabled(!inProgress);
    progressBar.setVisible(inProgress);
    progressBar.setIndeterminate(inProgress);
    if (inProgress) {
      statusLabel.setText("Importing...");
    }
  }

  private void showError(String message) {
    JOptionPane.showMessageDialog(this, message, TITLE, JOptionPane.ERROR_MESSAGE);
  }

  private void showReport(HarImportReport report) {
    statusLabel.setText(report.getImported() + " request(s) imported, "
        + report.getAppliedRulesCount() + " of " + report.getRules().size()
        + " rule(s) applied.");
    JTextArea text = SwingUtils.createComponent("reportText", new JTextArea(report.toText()));
    text.setEditable(false);
    text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
    text.setCaretPosition(0);
    JScrollPane scroll = new JScrollPane(text);
    scroll.setPreferredSize(new Dimension(700, 380));
    JOptionPane.showMessageDialog(this, scroll, "HAR import report",
        JOptionPane.INFORMATION_MESSAGE);
  }
}
