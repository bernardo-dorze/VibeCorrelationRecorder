package com.blazemeter.jmeter.correlation.core.har;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal, JMeter independent representation of a HAR (HTTP Archive) entry, containing only the
 * information required to rebuild the request and the response of a recorded exchange.
 */
public class HarEntry {

  private int index;
  private long startedMillis;
  private long timeMillis;
  private boolean fromCache;
  private String method = "";
  private String url = "";
  private String httpVersion = "";
  private final List<HarHeader> requestHeaders = new ArrayList<>();
  private String postMimeType;
  private String postText;
  private boolean postTextBase64;
  private final List<HarHeader> postParams = new ArrayList<>();
  private int status;
  private String statusText = "";
  private final List<HarHeader> responseHeaders = new ArrayList<>();
  private String responseMimeType;
  private String responseText;
  private boolean responseTextBase64;
  private String redirectUrl;
  private long waitMillis = -1;
  private long receiveMillis = -1;
  private long connectMillis = -1;

  /**
   * Position of the entry in the original HAR file (0 based).
   *
   * @return the entry position
   */
  public int getIndex() {
    return index;
  }

  public void setIndex(int index) {
    this.index = index;
  }

  public long getStartedMillis() {
    return startedMillis;
  }

  public void setStartedMillis(long startedMillis) {
    this.startedMillis = startedMillis;
  }

  public long getTimeMillis() {
    return timeMillis;
  }

  public void setTimeMillis(long timeMillis) {
    this.timeMillis = timeMillis;
  }

  public boolean isFromCache() {
    return fromCache;
  }

  public void setFromCache(boolean fromCache) {
    this.fromCache = fromCache;
  }

  public String getMethod() {
    return method;
  }

  public void setMethod(String method) {
    this.method = method;
  }

  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
  }

  public String getHttpVersion() {
    return httpVersion;
  }

  public void setHttpVersion(String httpVersion) {
    this.httpVersion = httpVersion;
  }

  public List<HarHeader> getRequestHeaders() {
    return requestHeaders;
  }

  public String getPostMimeType() {
    return postMimeType;
  }

  public void setPostMimeType(String postMimeType) {
    this.postMimeType = postMimeType;
  }

  public String getPostText() {
    return postText;
  }

  public void setPostText(String postText) {
    this.postText = postText;
  }

  public boolean isPostTextBase64() {
    return postTextBase64;
  }

  public void setPostTextBase64(boolean postTextBase64) {
    this.postTextBase64 = postTextBase64;
  }

  public List<HarHeader> getPostParams() {
    return postParams;
  }

  public int getStatus() {
    return status;
  }

  public void setStatus(int status) {
    this.status = status;
  }

  public String getStatusText() {
    return statusText;
  }

  public void setStatusText(String statusText) {
    this.statusText = statusText;
  }

  public List<HarHeader> getResponseHeaders() {
    return responseHeaders;
  }

  public String getResponseMimeType() {
    return responseMimeType;
  }

  public void setResponseMimeType(String responseMimeType) {
    this.responseMimeType = responseMimeType;
  }

  public String getResponseText() {
    return responseText;
  }

  public void setResponseText(String responseText) {
    this.responseText = responseText;
  }

  public boolean isResponseTextBase64() {
    return responseTextBase64;
  }

  public void setResponseTextBase64(boolean responseTextBase64) {
    this.responseTextBase64 = responseTextBase64;
  }

  public String getRedirectUrl() {
    return redirectUrl;
  }

  public void setRedirectUrl(String redirectUrl) {
    this.redirectUrl = redirectUrl;
  }

  public long getWaitMillis() {
    return waitMillis;
  }

  public void setWaitMillis(long waitMillis) {
    this.waitMillis = waitMillis;
  }

  public long getReceiveMillis() {
    return receiveMillis;
  }

  public void setReceiveMillis(long receiveMillis) {
    this.receiveMillis = receiveMillis;
  }

  public long getConnectMillis() {
    return connectMillis;
  }

  public void setConnectMillis(long connectMillis) {
    this.connectMillis = connectMillis;
  }

  @Override
  public String toString() {
    return "HarEntry{#" + index + " " + method + " " + url + " -> " + status + "}";
  }

  /**
   * Name/value pair used for headers and post parameters.
   */
  public static class HarHeader {

    private final String name;
    private final String value;

    public HarHeader(String name, String value) {
      this.name = name == null ? "" : name;
      this.value = value == null ? "" : value;
    }

    public String getName() {
      return name;
    }

    public String getValue() {
      return value;
    }

    @Override
    public String toString() {
      return name + ": " + value;
    }
  }
}
