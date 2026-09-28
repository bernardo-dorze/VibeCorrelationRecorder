package com.blazemeter.jmeter.correlation.core.har;

/**
 * Options that control which HAR entries are imported and how they are converted.
 */
public class HarImportOptions {

  private boolean skipCachedEntries = true;
  private boolean skipEntriesWithoutResponse = true;
  private boolean normalizeHeaderNames = true;

  /**
   * Entries served from the browser cache never reached the server, so the JMeter recorder would
   * not have seen them either.
   *
   * @return true when cached entries are skipped
   */
  public boolean isSkipCachedEntries() {
    return skipCachedEntries;
  }

  public HarImportOptions setSkipCachedEntries(boolean skipCachedEntries) {
    this.skipCachedEntries = skipCachedEntries;
    return this;
  }

  /**
   * Entries with status 0 were blocked, aborted or failed in the browser and have no response.
   *
   * @return true when entries without response are skipped
   */
  public boolean isSkipEntriesWithoutResponse() {
    return skipEntriesWithoutResponse;
  }

  public HarImportOptions setSkipEntriesWithoutResponse(boolean skipEntriesWithoutResponse) {
    this.skipEntriesWithoutResponse = skipEntriesWithoutResponse;
    return this;
  }

  /**
   * HTTP/2 traffic (the usual case in browser HAR files) has all header names in lower case,
   * while rules created with live JMeter recordings usually expect names like
   * <code>Set-Cookie</code> or <code>Content-Type</code>. When enabled, all lower case header
   * names are converted to their canonical form (<code>x-csrf-token</code> to
   * <code>X-Csrf-Token</code>). Names containing upper case characters are left untouched.
   *
   * @return true when lower case header names are normalized
   */
  public boolean isNormalizeHeaderNames() {
    return normalizeHeaderNames;
  }

  public HarImportOptions setNormalizeHeaderNames(boolean normalizeHeaderNames) {
    this.normalizeHeaderNames = normalizeHeaderNames;
    return this;
  }

  @Override
  public String toString() {
    return "HarImportOptions{skipCachedEntries=" + skipCachedEntries
        + ", skipEntriesWithoutResponse=" + skipEntriesWithoutResponse
        + ", normalizeHeaderNames=" + normalizeHeaderNames + '}';
  }
}
