package com.blazemeter.jmeter.correlation.core.har;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Summary of a HAR import: how many entries were imported, skipped or failed and how many times
 * each correlation rule was applied.
 */
public class HarImportReport {

  private static final String NL = System.lineSeparator();

  private final String harFile;
  private int totalEntries;
  private int imported;
  private int filteredByRecorder;
  private boolean cancelled;
  private final Map<String, Integer> skipped = new LinkedHashMap<>();
  private final List<String> failures = new ArrayList<>();
  private final List<RuleUsage> rules = new ArrayList<>();

  public HarImportReport(String harFile) {
    this.harFile = harFile;
  }

  public String getHarFile() {
    return harFile;
  }

  public int getTotalEntries() {
    return totalEntries;
  }

  public void setTotalEntries(int totalEntries) {
    this.totalEntries = totalEntries;
  }

  public int getImported() {
    return imported;
  }

  public void addImported() {
    imported++;
  }

  public int getFilteredByRecorder() {
    return filteredByRecorder;
  }

  public void addFilteredByRecorder() {
    filteredByRecorder++;
  }

  public boolean isCancelled() {
    return cancelled;
  }

  public void setCancelled(boolean cancelled) {
    this.cancelled = cancelled;
  }

  public Map<String, Integer> getSkipped() {
    return skipped;
  }

  public int getSkippedCount() {
    return skipped.values().stream().mapToInt(Integer::intValue).sum();
  }

  public void addSkipped(String reason) {
    skipped.merge(reason, 1, Integer::sum);
  }

  public List<String> getFailures() {
    return failures;
  }

  public void addFailure(HarEntry entry, Exception e) {
    failures.add("#" + entry.getIndex() + " " + entry.getMethod() + " " + entry.getUrl() + ": "
        + e);
  }

  public List<RuleUsage> getRules() {
    return rules;
  }

  public void addRule(RuleUsage rule) {
    rules.add(rule);
  }

  public long getAppliedRulesCount() {
    return rules.stream().filter(RuleUsage::wasApplied).count();
  }

  public String toText() {
    StringBuilder sb = new StringBuilder();
    sb.append("HAR import ").append(cancelled ? "CANCELLED" : "finished").append(": ")
        .append(harFile).append(NL)
        .append("  Entries in HAR: ").append(totalEntries).append(NL)
        .append("  Imported as samplers: ").append(imported).append(NL)
        .append("  Excluded by recorder URL/content type filters: ").append(filteredByRecorder)
        .append(NL)
        .append("  Skipped: ").append(getSkippedCount()).append(NL);
    skipped.forEach((reason, count) -> sb.append("    - ").append(reason).append(": ")
        .append(count).append(NL));
    sb.append("  Failed to convert: ").append(failures.size()).append(NL);
    failures.forEach(f -> sb.append("    - ").append(f).append(NL));
    sb.append(NL).append("Correlation rules (").append(getAppliedRulesCount()).append(" of ")
        .append(rules.size()).append(" applied):").append(NL);
    if (rules.isEmpty()) {
      sb.append("  No correlation rules configured in the recorder, nothing was correlated.")
          .append(NL);
    }
    for (RuleUsage rule : rules) {
      sb.append("  - [").append(rule.getGroup()).append("] ").append(rule.getReferenceName())
          .append(": ");
      if (!rule.isEnabled()) {
        sb.append("disabled").append(NL);
        continue;
      }
      sb.append("extracted ").append(rule.getExtractions()).append(" time(s), replaced ")
          .append(rule.getReplacements()).append(" time(s)");
      if (!rule.wasApplied()) {
        sb.append("  <-- NOT APPLIED, review the rule against the HAR content");
      }
      sb.append(NL);
    }
    return sb.toString();
  }

  @Override
  public String toString() {
    return toText();
  }

  /**
   * How many times a correlation rule was applied during the import.
   */
  public static class RuleUsage {

    private final String group;
    private final String referenceName;
    private final boolean enabled;
    private final int extractions;
    private final int replacements;

    public RuleUsage(String group, String referenceName, boolean enabled, int extractions,
        int replacements) {
      this.group = group;
      this.referenceName = referenceName;
      this.enabled = enabled;
      this.extractions = extractions;
      this.replacements = replacements;
    }

    public String getGroup() {
      return group;
    }

    public String getReferenceName() {
      return referenceName;
    }

    public boolean isEnabled() {
      return enabled;
    }

    public int getExtractions() {
      return extractions;
    }

    public int getReplacements() {
      return replacements;
    }

    public boolean wasApplied() {
      return extractions > 0 || replacements > 0;
    }
  }
}
