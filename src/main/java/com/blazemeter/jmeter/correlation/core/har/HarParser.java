package com.blazemeter.jmeter.correlation.core.har;

import com.blazemeter.jmeter.correlation.core.har.HarEntry.HarHeader;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the entries of a HAR file.
 *
 * <p>The file is read with the Jackson streaming API, entry by entry, so only one entry JSON tree
 * is kept in memory at once. Entries are returned sorted by their start time (keeping file order
 * for equal times), which is the order in which the browser sent the requests.
 */
public class HarParser {

  private static final Logger LOG = LoggerFactory.getLogger(HarParser.class);

  private final ObjectMapper mapper;

  public HarParser() {
    JsonFactory factory = new JsonFactory();
    relaxStringLengthLimit(factory);
    mapper = new ObjectMapper(factory);
  }

  /*
   * Jackson >= 2.15 (shipped with JMeter 5.6.x) limits string values to 20MB by default, which
   * base64 encoded response bodies may exceed. The API does not exist in older Jackson versions
   * (JMeter 5.5), so it is invoked through reflection.
   */
  private static void relaxStringLengthLimit(JsonFactory factory) {
    try {
      Class<?> constraintsClass =
          Class.forName("com.fasterxml.jackson.core.StreamReadConstraints");
      Object builder = constraintsClass.getMethod("builder").invoke(null);
      builder.getClass().getMethod("maxStringLength", int.class)
          .invoke(builder, Integer.MAX_VALUE);
      Object constraints = builder.getClass().getMethod("build").invoke(builder);
      Method setter = JsonFactory.class.getMethod("setStreamReadConstraints", constraintsClass);
      setter.invoke(factory, constraints);
    } catch (ClassNotFoundException e) {
      LOG.debug("Jackson without StreamReadConstraints, no string length limit to relax");
    } catch (ReflectiveOperationException | RuntimeException e) {
      LOG.warn("Could not relax Jackson string length limit, big HAR bodies may fail to load", e);
    }
  }

  public List<HarEntry> parse(File harFile) throws IOException {
    try (InputStream in = Files.newInputStream(harFile.toPath())) {
      return parse(in);
    }
  }

  public List<HarEntry> parse(InputStream in) throws IOException {
    List<HarEntry> entries = new ArrayList<>();
    try (JsonParser parser = mapper.getFactory().createParser(in)) {
      if (parser.nextToken() != JsonToken.START_OBJECT) {
        throw new IOException("Invalid HAR file: root element is not a JSON object");
      }
      boolean foundLog = false;
      while (parser.nextToken() == JsonToken.FIELD_NAME) {
        String field = parser.getCurrentName();
        parser.nextToken();
        if ("log".equals(field) && parser.currentToken() == JsonToken.START_OBJECT) {
          foundLog = true;
          readLog(parser, entries);
        } else {
          parser.skipChildren();
        }
      }
      if (!foundLog) {
        throw new IOException("Invalid HAR file: missing 'log' element");
      }
    }
    // stable sort: keeps file order for entries started at the same millisecond
    entries.sort(Comparator.comparingLong(HarEntry::getStartedMillis));
    return entries;
  }

  private void readLog(JsonParser parser, List<HarEntry> entries) throws IOException {
    while (parser.nextToken() == JsonToken.FIELD_NAME) {
      String field = parser.getCurrentName();
      parser.nextToken();
      if ("entries".equals(field) && parser.currentToken() == JsonToken.START_ARRAY) {
        int index = 0;
        while (parser.nextToken() == JsonToken.START_OBJECT) {
          JsonNode node = mapper.readTree(parser);
          entries.add(toEntry(node, index++));
        }
      } else {
        parser.skipChildren();
      }
    }
  }

  static HarEntry toEntry(JsonNode node, int index) {
    HarEntry entry = new HarEntry();
    entry.setIndex(index);
    entry.setStartedMillis(parseDate(text(node, "startedDateTime")));
    entry.setTimeMillis(Math.max(0, Math.round(node.path("time").asDouble(0))));
    // Chrome adds "_fromCache" ("memory"/"disk") for responses served from the browser cache
    entry.setFromCache(node.hasNonNull("_fromCache"));

    JsonNode request = node.path("request");
    entry.setMethod(text(request, "method").trim().toUpperCase(java.util.Locale.ENGLISH));
    entry.setUrl(text(request, "url").trim());
    entry.setHttpVersion(text(request, "httpVersion"));
    readHeaders(request.path("headers"), entry.getRequestHeaders());
    JsonNode postData = request.path("postData");
    if (postData.isObject()) {
      entry.setPostMimeType(nullableText(postData, "mimeType"));
      entry.setPostText(nullableText(postData, "text"));
      entry.setPostTextBase64("base64".equalsIgnoreCase(text(postData, "encoding")));
      readHeaders(postData.path("params"), entry.getPostParams());
    }

    JsonNode response = node.path("response");
    entry.setStatus(response.path("status").asInt(0));
    entry.setStatusText(text(response, "statusText"));
    readHeaders(response.path("headers"), entry.getResponseHeaders());
    JsonNode content = response.path("content");
    entry.setResponseMimeType(nullableText(content, "mimeType"));
    entry.setResponseText(nullableText(content, "text"));
    entry.setResponseTextBase64("base64".equalsIgnoreCase(text(content, "encoding")));
    String redirect = text(response, "redirectURL");
    entry.setRedirectUrl(redirect.isEmpty() ? null : redirect);

    JsonNode timings = node.path("timings");
    entry.setWaitMillis(timing(timings, "wait"));
    entry.setReceiveMillis(timing(timings, "receive"));
    entry.setConnectMillis(timing(timings, "connect"));
    return entry;
  }

  private static long timing(JsonNode timings, String name) {
    double value = timings.path(name).asDouble(-1);
    return value < 0 ? -1 : Math.round(value);
  }

  private static void readHeaders(JsonNode headers, List<HarHeader> target) {
    if (!headers.isArray()) {
      return;
    }
    for (JsonNode header : headers) {
      target.add(new HarHeader(text(header, "name"), text(header, "value")));
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isValueNode() && !value.isNull() ? value.asText() : "";
  }

  private static String nullableText(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isValueNode() && !value.isNull() ? value.asText() : null;
  }

  static long parseDate(String isoDate) {
    if (isoDate == null || isoDate.isEmpty()) {
      return 0;
    }
    try {
      return OffsetDateTime.parse(isoDate).toInstant().toEpochMilli();
    } catch (DateTimeParseException e) {
      LOG.warn("Could not parse HAR startedDateTime '{}'", isoDate);
      return 0;
    }
  }
}
