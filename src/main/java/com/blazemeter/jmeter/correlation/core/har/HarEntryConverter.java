package com.blazemeter.jmeter.correlation.core.har;

import com.blazemeter.jmeter.correlation.core.har.HarEntry.HarHeader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.http.impl.EnglishReasonPhraseCatalog;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.parser.HTMLParseException;
import org.apache.jmeter.protocol.http.proxy.FormCharSetFinder;
import org.apache.jmeter.protocol.http.proxy.HttpRequestHdr;
import org.apache.jmeter.protocol.http.proxy.ProxyControl;
import org.apache.jmeter.protocol.http.proxy.SamplerCreator;
import org.apache.jmeter.protocol.http.proxy.SamplerCreatorFactory;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.util.ConversionUtils;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.util.JOrphanUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converts HAR entries into the same objects the JMeter HTTP(S) Test Script Recorder proxy builds
 * for a live request: an HTTP sampler, its children (the Header Manager) and the sample result.
 *
 * <p>To get samplers identical to the ones created while recording, the request is rebuilt as a
 * raw HTTP request and parsed with JMeter's own {@link HttpRequestHdr} and {@link SamplerCreator}
 * (the classes used by {@code org.apache.jmeter.protocol.http.proxy.Proxy}), honoring the
 * recorder configuration (sampler naming, sampler type, GraphQL detection, headers capture and
 * removal, default encoding).
 */
public class HarEntryConverter {

  // Same key as AbstractSamplerCreator.DEFAULT_ENCODING_KEY (protected in JMeter)
  static final String DEFAULT_ENCODING_KEY = "__defaultEncoding";
  private static final Logger LOG = LoggerFactory.getLogger(HarEntryConverter.class);
  private static final String CRLF = "\r\n";
  private static final String PROXY_HEADERS_REMOVE_DEFAULT = "If-Modified-Since,If-None-Match,Host";
  private static final List<String> NOT_HTML_TEXT_TYPES = Arrays.asList("application/javascript",
      "application/json", "text/javascript");
  // Headers that don't make sense (or would be wrong) in the rebuilt request
  private static final List<String> REQUEST_HEADERS_TO_DROP = Arrays.asList("content-length",
      "transfer-encoding");

  /*
   Depending on the sampleresult.timestamp.start property, SampleResult takes the stamp given to
   setStampAndTime as the start or as the end of the sample. The property is read by SampleResult
   when the class is loaded, so instead of reading it again (it may have changed since then) the
   behaviour is probed once.
  */
  private static final boolean STAMP_IS_START_TIME = probeStampIsStartTime();

  private final SamplerCreatorFactory samplerCreatorFactory = new SamplerCreatorFactory();
  private final Map<String, String> pageEncodings = Collections.synchronizedMap(new HashMap<>());
  private final Map<String, String> formEncodings = Collections.synchronizedMap(new HashMap<>());
  private final String prefix;
  private final String samplerTypeName;
  private final int namingMode;
  private final String nameFormat;
  private final boolean detectGraphQl;
  private final boolean captureHeaders;
  private final String[] headersToRemove;
  private final HarImportOptions options;

  public HarEntryConverter(ProxyControl recorder, HarImportOptions options) {
    this.prefix = recorder.getPrefixHTTPSampleName();
    this.samplerTypeName = recorder.getSamplerTypeName();
    this.namingMode = recorder.getHTTPSampleNamingMode();
    this.nameFormat = recorder.getHttpSampleNameFormat();
    this.detectGraphQl = recorder.getDetectGraphQLRequest();
    this.captureHeaders = recorder.getCaptureHttpHeaders();
    this.options = options;
    this.headersToRemove = JOrphanUtils.split(JMeterUtils.getPropDefault("proxy.headers.remove",
        PROXY_HEADERS_REMOVE_DEFAULT), ",");
    pageEncodings.put(DEFAULT_ENCODING_KEY, recorder.getDefaultEncoding());
  }

  private static boolean probeStampIsStartTime() {
    SampleResult probe = new SampleResult();
    probe.setStampAndTime(1000L, 10L);
    return probe.getStartTime() == 1000L;
  }

  /**
   * Checks if an entry has to be skipped before conversion.
   *
   * @param entry the entry to check
   * @param options import options
   * @return the reason why the entry is skipped, or null when it has to be imported
   */
  public static String getSkipReason(HarEntry entry, HarImportOptions options) {
    String url = entry.getUrl().toLowerCase(Locale.ENGLISH);
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
      return "unsupported URL scheme (only http and https are supported)";
    }
    if (entry.getMethod().isEmpty() || HTTPConstants.CONNECT.equals(entry.getMethod())) {
      return "unsupported method";
    }
    if (options.isSkipCachedEntries() && entry.isFromCache()) {
      return "served from browser cache";
    }
    if (options.isSkipEntriesWithoutResponse() && entry.getStatus() <= 0) {
      return "no response (blocked, aborted or failed request)";
    }
    return null;
  }

  public ConvertedSample convert(HarEntry entry) throws Exception {
    List<HarHeader> requestHeaders = normalize(entry.getRequestHeaders());
    byte[] body = buildRequestBody(entry, requestHeaders);
    HttpRequestHdr request = new HttpRequestHdr(prefix, samplerTypeName, namingMode, nameFormat);
    request.setDetectGraphQLRequest(detectGraphQl);
    request.parse(new ByteArrayInputStream(buildRawRequest(entry, requestHeaders, body)));

    SamplerCreator samplerCreator = samplerCreatorFactory.getSamplerCreator(request,
        pageEncodings, formEncodings);
    HTTPSamplerBase sampler = samplerCreator.createAndPopulateSampler(request, pageEncodings,
        formEncodings);
    sampler.setUseKeepAlive(false);
    HeaderManager headers = request.getHeaderManager();
    sampler.setHeaderManager(headers);

    HTTPSampleResult result = buildResult(entry, sampler, requestHeaders, body);
    String pageEncoding = addPageEncoding(result);
    addFormEncodings(result, pageEncoding);
    samplerCreator.postProcessSampler(sampler, result);

    // Same clean up done by the JMeter proxy: cookies are handled by the Cookie Manager
    headers.removeHeaderNamed(HTTPConstants.HEADER_COOKIE);
    for (String header : headersToRemove) {
      headers.removeHeaderNamed(header.trim());
    }
    List<TestElement> children = new ArrayList<>();
    if (captureHeaders) {
      children.add(headers);
    }
    children.addAll(samplerCreator.createChildren(sampler, result));
    return new ConvertedSample(entry, sampler, children.toArray(new TestElement[0]), result);
  }

  private List<HarHeader> normalize(List<HarHeader> headers) {
    List<HarHeader> ret = new ArrayList<>();
    for (HarHeader header : headers) {
      String name = header.getName().trim();
      // HTTP/2 pseudo headers (:authority, :method, :path, :scheme, :status)
      if (name.isEmpty() || name.startsWith(":")) {
        continue;
      }
      if (options.isNormalizeHeaderNames()) {
        name = canonicalHeaderName(name);
      }
      ret.add(new HarHeader(name, header.getValue().replace('\r', ' ').replace('\n', ' ')));
    }
    return ret;
  }

  static String canonicalHeaderName(String name) {
    if (!name.equals(name.toLowerCase(Locale.ENGLISH))) {
      return name;
    }
    StringBuilder sb = new StringBuilder(name.length());
    boolean upperNext = true;
    for (char c : name.toCharArray()) {
      sb.append(upperNext ? Character.toUpperCase(c) : c);
      upperNext = c == '-';
    }
    return sb.toString();
  }

  private byte[] buildRequestBody(HarEntry entry, List<HarHeader> requestHeaders) {
    if (entry.getPostText() != null) {
      if (entry.isPostTextBase64()) {
        return Base64.getMimeDecoder().decode(entry.getPostText());
      }
      String contentType = entry.getPostMimeType() != null ? entry.getPostMimeType()
          : findHeader(requestHeaders, HTTPConstants.HEADER_CONTENT_TYPE);
      return entry.getPostText().getBytes(charsetOf(contentType, StandardCharsets.UTF_8));
    }
    if (!entry.getPostParams().isEmpty()) {
      // Some tools only provide the params of url encoded forms. Values are kept as they are.
      StringBuilder sb = new StringBuilder();
      for (HarHeader param : entry.getPostParams()) {
        if (sb.length() > 0) {
          sb.append('&');
        }
        sb.append(param.getName()).append('=').append(param.getValue());
      }
      return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
    return new byte[0];
  }

  private static byte[] buildRawRequest(HarEntry entry, List<HarHeader> headers, byte[] body)
      throws IOException {
    StringBuilder head = new StringBuilder();
    head.append(entry.getMethod()).append(' ').append(toAsciiUrl(entry.getUrl()))
        .append(" HTTP/1.1").append(CRLF);
    for (HarHeader header : headers) {
      if (!REQUEST_HEADERS_TO_DROP.contains(header.getName().toLowerCase(Locale.ENGLISH))) {
        head.append(header.getName()).append(": ").append(header.getValue()).append(CRLF);
      }
    }
    if (body.length > 0) {
      head.append(HTTPConstants.HEADER_CONTENT_LENGTH).append(": ").append(body.length)
          .append(CRLF);
    }
    head.append(CRLF);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(head.toString().getBytes(StandardCharsets.UTF_8));
    out.write(body);
    return out.toByteArray();
  }

  /*
   * The request line has to be ASCII and can't contain spaces (JMeter tokenizes it), so non ASCII
   * characters and spaces are percent-encoded (as the browser does when sending the request) and
   * the fragment (never sent to the server) is removed.
   */
  static String toAsciiUrl(String url) {
    int fragmentPos = url.indexOf('#');
    String noFragment = fragmentPos >= 0 ? url.substring(0, fragmentPos) : url;
    StringBuilder sb = new StringBuilder(noFragment.length());
    int i = 0;
    while (i < noFragment.length()) {
      int codePoint = noFragment.codePointAt(i);
      int next = i + Character.charCount(codePoint);
      if (codePoint > 32 && codePoint < 127) {
        sb.append((char) codePoint);
      } else {
        for (byte b : noFragment.substring(i, next).getBytes(StandardCharsets.UTF_8)) {
          sb.append('%').append(String.format("%02X", b & 0xFF));
        }
      }
      i = next;
    }
    return sb.toString();
  }

  private HTTPSampleResult buildResult(HarEntry entry, HTTPSamplerBase sampler,
      List<HarHeader> requestHeaders, byte[] requestBody) throws IOException {
    HTTPSampleResult res = new HTTPSampleResult();
    URL url = sampler.getUrl();
    res.setSampleLabel(SampleResult.isRenameSampleLabel() ? sampler.getName() : url.toString());
    res.setHTTPMethod(entry.getMethod());
    res.setURL(url);
    res.setStampAndTime(STAMP_IS_START_TIME ? entry.getStartedMillis()
        : entry.getStartedMillis() + entry.getTimeMillis(), entry.getTimeMillis());
    if (entry.getReceiveMillis() >= 0) {
      res.setLatency(Math.max(0, entry.getTimeMillis() - entry.getReceiveMillis()));
    }
    if (entry.getConnectMillis() >= 0) {
      res.setConnectTime(entry.getConnectMillis());
    }

    StringBuilder requestHeadersText = new StringBuilder();
    List<String> cookies = new ArrayList<>();
    for (HarHeader header : requestHeaders) {
      if (HTTPConstants.HEADER_COOKIE.equalsIgnoreCase(header.getName())) {
        cookies.add(header.getValue());
      } else {
        requestHeadersText.append(header.getName()).append(": ").append(header.getValue())
            .append('\n');
      }
    }
    res.setRequestHeaders(requestHeadersText.toString());
    res.setCookies(String.join("; ", cookies));
    if (requestBody.length > 0 && !HTTPConstants.GET.equals(entry.getMethod())
        && !HTTPConstants.HEAD.equals(entry.getMethod())) {
      String contentType = findHeader(requestHeaders, HTTPConstants.HEADER_CONTENT_TYPE);
      res.setQueryString(new String(requestBody, charsetOf(contentType,
          StandardCharsets.UTF_8)));
    }
    res.setSentBytes(requestHeadersText.length() + requestBody.length);

    int status = entry.getStatus();
    String message = entry.getStatusText();
    if ((message == null || message.isEmpty()) && status >= 100 && status < 600) {
      String reason = EnglishReasonPhraseCatalog.INSTANCE.getReason(status, Locale.ENGLISH);
      message = reason != null ? reason : "";
    }
    res.setResponseCode(String.valueOf(status));
    res.setResponseMessage(message);
    res.setSuccessful(status >= 200 && status < 400);

    List<HarHeader> responseHeaders = normalize(entry.getResponseHeaders());
    StringBuilder responseHeadersText = new StringBuilder("HTTP/1.1 ").append(status)
        .append(message.isEmpty() ? "" : " " + message).append('\n');
    for (HarHeader header : responseHeaders) {
      responseHeadersText.append(header.getName()).append(": ").append(header.getValue())
          .append('\n');
    }
    res.setResponseHeaders(responseHeadersText.toString());
    res.setHeadersSize(responseHeadersText.length());

    String contentType = findHeader(responseHeaders, HTTPConstants.HEADER_CONTENT_TYPE);
    if (contentType == null || contentType.isEmpty()) {
      contentType = entry.getResponseMimeType();
    }
    if (contentType != null && !contentType.isEmpty()) {
      res.setContentType(contentType);
      res.setEncodingAndType(contentType);
    }
    byte[] responseBody = buildResponseBody(entry, contentType, res);
    res.setResponseData(responseBody);
    res.setBodySize((long) responseBody.length);

    if (res.isRedirect()) {
      String location = findHeader(responseHeaders, HTTPConstants.HEADER_LOCATION);
      res.setRedirectLocation(location != null ? location : entry.getRedirectUrl());
    }
    return res;
  }

  private static byte[] buildResponseBody(HarEntry entry, String contentType,
      HTTPSampleResult res) {
    String text = entry.getResponseText();
    if (text == null || text.isEmpty()) {
      return new byte[0];
    }
    if (entry.isResponseTextBase64()) {
      try {
        return Base64.getMimeDecoder().decode(text);
      } catch (IllegalArgumentException e) {
        LOG.warn("Invalid base64 response body in {}, using it as text", entry);
      }
    }
    /*
     HAR stores decoded (unicode) text, so it is stored using the charset declared by the
     response, or UTF-8 when none is declared (instead of JMeter's ISO-8859-1 default, which
     would corrupt non latin characters).
     */
    Charset declared = charsetOf(contentType, null);
    if (declared == null) {
      res.setDataEncoding(StandardCharsets.UTF_8.name());
      return text.getBytes(StandardCharsets.UTF_8);
    }
    return text.getBytes(declared);
  }

  private static String findHeader(List<HarHeader> headers, String name) {
    for (HarHeader header : headers) {
      if (header.getName().equalsIgnoreCase(name)) {
        return header.getValue();
      }
    }
    return null;
  }

  private static Charset charsetOf(String contentType, Charset defaultCharset) {
    String encoding = contentType == null ? null
        : ConversionUtils.getEncodingFromContentType(contentType);
    if (encoding == null) {
      return defaultCharset;
    }
    try {
      return Charset.forName(encoding);
    } catch (RuntimeException e) {
      LOG.debug("Unsupported charset '{}', using default", encoding);
      return defaultCharset;
    }
  }

  // Replicates Proxy.addPageEncoding so the encoding of later requests is computed the same way
  private String addPageEncoding(SampleResult result) {
    String pageEncoding = null;
    try {
      pageEncoding = ConversionUtils.getEncodingFromContentType(result.getContentType());
    } catch (RuntimeException ex) {
      LOG.debug("Unsupported charset detected in contentType:'{}'", result.getContentType());
    }
    if (pageEncoding != null) {
      pageEncodings.put(getUrlWithoutQuery(result.getURL()), pageEncoding);
    }
    return pageEncoding;
  }

  // Replicates Proxy.addFormEncodings
  private void addFormEncodings(SampleResult result, String pageEncoding) {
    String contentType = result.getContentType();
    if (contentType == null || SampleResult.isBinaryType(contentType)) {
      return;
    }
    for (String mimeType : NOT_HTML_TEXT_TYPES) {
      if (contentType.startsWith(mimeType)) {
        return;
      }
    }
    try {
      new FormCharSetFinder().addFormActionsAndCharSet(result.getResponseDataAsString(),
          formEncodings, pageEncoding);
    } catch (HTMLParseException | RuntimeException e) {
      LOG.debug("Could not find form encodings for {}", result.getUrlAsString());
    }
  }

  private static String getUrlWithoutQuery(URL url) {
    String fullUrl = url.toString();
    String query = url.getQuery();
    return query == null ? fullUrl : fullUrl.substring(0, fullUrl.length() - query.length() - 1);
  }

  /**
   * Result of converting a HAR entry: the equivalent of what the JMeter proxy delivers to the
   * recorder for a request.
   */
  public static class ConvertedSample {

    private final HarEntry entry;
    private final HTTPSamplerBase sampler;
    private final TestElement[] children;
    private final HTTPSampleResult result;

    public ConvertedSample(HarEntry entry, HTTPSamplerBase sampler, TestElement[] children,
        HTTPSampleResult result) {
      this.entry = entry;
      this.sampler = sampler;
      this.children = children;
      this.result = result;
    }

    public HarEntry getEntry() {
      return entry;
    }

    public HTTPSamplerBase getSampler() {
      return sampler;
    }

    public TestElement[] getChildren() {
      return children;
    }

    public HTTPSampleResult getResult() {
      return result;
    }
  }
}
