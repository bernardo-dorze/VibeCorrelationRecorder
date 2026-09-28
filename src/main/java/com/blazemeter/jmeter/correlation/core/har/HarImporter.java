package com.blazemeter.jmeter.correlation.core.har;

import com.blazemeter.jmeter.correlation.CorrelationProxyControl;
import com.blazemeter.jmeter.correlation.core.CorrelationRule;
import com.blazemeter.jmeter.correlation.core.RulesGroup;
import com.blazemeter.jmeter.correlation.core.analysis.AnalysisReporter;
import com.blazemeter.jmeter.correlation.core.analysis.AnalysisReporter.Report;
import com.blazemeter.jmeter.correlation.core.analysis.CorrelationRuleReport;
import com.blazemeter.jmeter.correlation.core.har.HarEntryConverter.ConvertedSample;
import com.blazemeter.jmeter.correlation.core.har.HarImportReport.RuleUsage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Imports a HAR file into the test plan through a {@link CorrelationProxyControl}, applying the
 * correlation rules configured in it (legacy correlation), as if the requests in the HAR had been
 * recorded by the recorder.
 */
public class HarImporter {

  private static final Logger LOG = LoggerFactory.getLogger(HarImporter.class);
  private static final int FLUSH_EVERY = 50;

  private final CorrelationProxyControl recorder;

  public HarImporter(CorrelationProxyControl recorder) {
    this.recorder = recorder;
  }

  public HarImportReport importHar(File harFile, HarImportOptions options) throws IOException {
    return importHar(harFile, options, LOG::debug);
  }

  /**
   * Imports the HAR file.
   *
   * @param harFile the HAR file
   * @param options import options
   * @param progress consumer of progress messages
   * @return the import report
   * @throws IOException if the HAR can't be read or the samplers can't be added to the plan
   */
  public HarImportReport importHar(File harFile, HarImportOptions options,
      Consumer<String> progress) throws IOException {
    progress.accept("Reading " + harFile.getName() + "...");
    List<HarEntry> entries = new HarParser().parse(harFile);
    HarImportReport report = new HarImportReport(harFile.getAbsolutePath());
    report.setTotalEntries(entries.size());
    LOG.info("Importing {} HAR entries from {} with {}", entries.size(), harFile, options);

    boolean wasCollecting = AnalysisReporter.isCollecting();
    boolean couldCorrelate = AnalysisReporter.canCorrelate();
    AnalysisReporter.startCollecting();
    // an analysis may have left the correlation disabled, the import has to apply the rules
    AnalysisReporter.enableCorrelation();
    recorder.startHarImport();
    try {
      HarEntryConverter converter = new HarEntryConverter(recorder, options);
      int processed = 0;
      for (HarEntry entry : entries) {
        if (Thread.currentThread().isInterrupted()) {
          report.setCancelled(true);
          break;
        }
        processed++;
        importEntry(entry, converter, options, report);
        if (report.getImported() > 0 && report.getImported() % FLUSH_EVERY == 0) {
          flush();
        }
        progress.accept("Processed " + processed + " of " + entries.size() + " entries");
      }
      flush();
      addRulesUsage(report);
    } finally {
      recorder.endHarImport();
      AnalysisReporter.clear();
      if (!wasCollecting) {
        AnalysisReporter.stopCollecting();
      }
      if (!couldCorrelate) {
        AnalysisReporter.disableCorrelation();
      }
    }
    LOG.info("{}", report.toText());
    return report;
  }

  private void importEntry(HarEntry entry, HarEntryConverter converter, HarImportOptions options,
      HarImportReport report) {
    String skipReason = HarEntryConverter.getSkipReason(entry, options);
    if (skipReason != null) {
      LOG.debug("Skipping {}: {}", entry, skipReason);
      report.addSkipped(skipReason);
      return;
    }
    ConvertedSample sample;
    try {
      sample = converter.convert(entry);
    } catch (Exception e) {
      LOG.warn("Could not convert HAR entry {}", entry, e);
      report.addFailure(entry, e);
      return;
    }
    boolean accepted = recorder.deliverHarSample(sample.getSampler(), sample.getChildren(),
        sample.getResult(), entry.getStartedMillis());
    if (accepted) {
      report.addImported();
    } else {
      report.addFilteredByRecorder();
    }
  }

  private void flush() throws IOException {
    try {
      recorder.flushHarSamplesIntoModel();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while adding samplers to the test plan", e);
    } catch (InvocationTargetException | RuntimeException e) {
      Throwable cause = e instanceof InvocationTargetException ? e.getCause() : e;
      throw new IOException("Could not add samplers to the test plan: " + cause, cause);
    }
  }

  private void addRulesUsage(HarImportReport report) {
    AnalysisReporter reporter = AnalysisReporter.getReporter();
    for (RulesGroup group : recorder.getHarImportGroups()) {
      for (CorrelationRule rule : group.getRules()) {
        CorrelationRuleReport ruleReport = reporter.getRuleReport(rule);
        report.addRule(new RuleUsage(group.getId(), rule.getReferenceName(),
            group.isEnable() && rule.isEnabled(), count(ruleReport.getExtractorReport()),
            count(ruleReport.getReplacementReport())));
      }
    }
  }

  private static int count(Report report) {
    return report == null ? 0 : report.getEntriesCount();
  }
}
