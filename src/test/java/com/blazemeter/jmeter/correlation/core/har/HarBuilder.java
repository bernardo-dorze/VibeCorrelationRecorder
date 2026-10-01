package com.blazemeter.jmeter.correlation.core.har;

import java.util.ArrayList;
import java.util.List;

/**
 * Small helper to build HAR JSON documents in tests.
 */
public class HarBuilder {

  private final List<String> entries = new ArrayList<>();

  public static String json(String value) {
    StringBuilder sb = new StringBuilder("\"");
    for (char c : value.toCharArray()) {
      switch (c) {
        case '"':
          sb.append("\\\"");
          break;
        case '\\':
          sb.append("\\\\");
          break;
        case '\n':
          sb.append("\\n");
          break;
        case '\r':
          sb.append("\\r");
          break;
        default:
          sb.append(c);
      }
    }
    return sb.append('"').toString();
  }

  public static String headers(String... nameValues) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < nameValues.length; i += 2) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append("{\"name\":").append(json(nameValues[i])).append(",\"value\":")
          .append(json(nameValues[i + 1])).append('}');
    }
    return sb.append(']').toString();
  }

  public HarBuilder entry(String started, String method, String url, String requestHeaders,
      String postData, int status, String responseHeaders, String mimeType, String body) {
    return rawEntry(started, method, url, requestHeaders, postData, status, responseHeaders,
        "{\"size\":" + body.length() + ",\"mimeType\":" + json(mimeType) + ",\"text\":"
            + json(body) + "}", "");
  }

  public HarBuilder rawEntry(String started, String method, String url, String requestHeaders,
      String postData, int status, String responseHeaders, String content, String extra) {
    entries.add("{\"startedDateTime\":" + json(started) + ",\"time\":120.5,"
        + "\"request\":{\"method\":" + json(method) + ",\"url\":" + json(url)
        + ",\"httpVersion\":\"h2\",\"headers\":" + requestHeaders
        + (postData != null ? ",\"postData\":" + postData : "")
        + "},\"response\":{\"status\":" + status + ",\"statusText\":\"\","
        + "\"httpVersion\":\"h2\",\"headers\":" + responseHeaders + ",\"content\":" + content
        + ",\"redirectURL\":\"\"},\"timings\":{\"send\":1,\"wait\":100,\"receive\":19.5}"
        + extra + "}");
    return this;
  }

  public String build() {
    return "{\"log\":{\"version\":\"1.2\",\"creator\":{\"name\":\"test\",\"version\":\"1\"},"
        + "\"pages\":[],\"entries\":[" + String.join(",", entries) + "]}}";
  }
}
