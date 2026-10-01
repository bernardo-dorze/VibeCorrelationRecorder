package com.blazemeter.jmeter.correlation.core.har;

import static com.blazemeter.jmeter.correlation.core.har.HarBuilder.headers;
import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.correlation.JMeterTestUtils;
import com.blazemeter.jmeter.correlation.core.har.HarEntryConverter.ConvertedSample;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.jmeter.config.Argument;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.proxy.ProxyControl;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class HarEntryConverterTest {

  private static final String STARTED = "2024-05-06T12:00:00.000Z";
  private ProxyControl recorder;

  @BeforeClass
  public static void setupClass() {
    JMeterTestUtils.setupJmeterEnv();
  }

  @Before
  public void setup() {
    recorder = new ProxyControl();
  }

  private ConvertedSample convert(HarBuilder builder, HarImportOptions options) throws Exception {
    List<HarEntry> entries = new HarParser().parse(new ByteArrayInputStream(builder.build()
        .getBytes(StandardCharsets.UTF_8)));
    return new HarEntryConverter(recorder, options).convert(entries.get(0));
  }

  private ConvertedSample convert(HarBuilder builder) throws Exception {
    return convert(builder, new HarImportOptions());
  }

  private static HeaderManager headerManager(ConvertedSample sample) {
    return (HeaderManager) sample.getChildren()[0];
  }

  @Test
  public void shouldBuildGetSamplerLikeTheRecorder() throws Exception {
    ConvertedSample sample = convert(new HarBuilder().entry(STARTED, "GET",
        "https://example.com/app/search?q=hello%20world&page=2",
        headers(":authority", "example.com", ":path", "/app/search", "accept", "text/html",
            "cookie", "SESSION=1", "x-csrf-token", "tok", "If-None-Match", "abc"),
        null, 200, headers("content-type", "text/html; charset=utf-8", "set-cookie",
            "SESSION=2; Path=/"), "text/html", "<html>héllo</html>"));

    HTTPSamplerBase sampler = sample.getSampler();
    assertThat(sampler.getMethod()).isEqualTo("GET");
    assertThat(sampler.getProtocol()).isEqualTo("https");
    assertThat(sampler.getDomain()).isEqualTo("example.com");
    assertThat(sampler.getPath()).isEqualTo("/app/search");
    // JMeter numbers recorded requests by default (proxy.number.requests=true)
    assertThat(sampler.getName()).matches("/app/search(-\\d+)?");
    assertThat(sampler.getArguments().getArgumentsAsMap()).containsEntry("q", "hello world")
        .containsEntry("page", "2");

    HeaderManager headers = headerManager(sample);
    assertThat(headers.getFirstHeaderNamed("Accept").getValue()).isEqualTo("text/html");
    assertThat(headers.getFirstHeaderNamed("X-Csrf-Token").getName()).isEqualTo("X-Csrf-Token");
    assertThat(headers.getFirstHeaderNamed("Cookie")).isNull();
    assertThat(headers.getFirstHeaderNamed("If-None-Match")).isNull();
    assertThat(headers.getFirstHeaderNamed(":authority")).isNull();

    HTTPSampleResult result = sample.getResult();
    assertThat(result.getURL().toString())
        .isEqualTo("https://example.com/app/search?q=hello+world&page=2");
    assertThat(result.getResponseCode()).isEqualTo("200");
    assertThat(result.getResponseMessage()).isEqualTo("OK");
    assertThat(result.isSuccessful()).isTrue();
    assertThat(result.getResponseHeaders()).startsWith("HTTP/1.1 200 OK\n")
        .contains("Set-Cookie: SESSION=2; Path=/\n");
    assertThat(result.getCookies()).isEqualTo("SESSION=1");
    assertThat(result.getRequestHeaders()).contains("X-Csrf-Token: tok").doesNotContain("Cookie");
    assertThat(result.getResponseDataAsString()).isEqualTo("<html>héllo</html>");
    assertThat(result.getStartTime()).isEqualTo(1714996800000L);
  }

  @Test
  public void shouldKeepHeaderNamesWhenNormalizationDisabled() throws Exception {
    ConvertedSample sample = convert(new HarBuilder().entry(STARTED, "GET", "http://a.com/",
        headers("x-csrf-token", "tok"), null, 200, headers("set-cookie", "a=1"), "text/html", ""),
        new HarImportOptions().setNormalizeHeaderNames(false));
    assertThat(headerManager(sample).getHeader(0).getName()).isEqualTo("x-csrf-token");
    assertThat(sample.getResult().getResponseHeaders()).contains("set-cookie: a=1");
  }

  @Test
  public void shouldBuildFormPost() throws Exception {
    String postData = "{\"mimeType\":\"application/x-www-form-urlencoded\","
        + "\"text\":\"user=john&token=abc%2B1\"}";
    ConvertedSample sample = convert(new HarBuilder().entry(STARTED, "POST",
        "https://a.com:8443/login", headers("content-type", "application/x-www-form-urlencoded",
            "content-length", "999"), postData, 302, headers("location", "https://a.com/home"),
        "text/html", ""));
    HTTPSamplerBase sampler = sample.getSampler();
    assertThat(sampler.getMethod()).isEqualTo("POST");
    assertThat(sampler.getPort()).isEqualTo(8443);
    assertThat(sampler.getPostBodyRaw()).isFalse();
    Argument token = sampler.getArguments().getArgument(1);
    assertThat(token.getName()).isEqualTo("token");
    assertThat(token.getValue()).isEqualTo("abc+1");
    assertThat(headerManager(sample).getFirstHeaderNamed("Content-Length")).isNull();
    assertThat(sample.getResult().getQueryString()).isEqualTo("user=john&token=abc%2B1");
    assertThat(sample.getResult().isRedirect()).isTrue();
    assertThat(sample.getResult().getRedirectLocation()).isEqualTo("https://a.com/home");
  }

  @Test
  public void shouldBuildRawJsonBodyPost() throws Exception {
    String body = "{\"token\":\"ñandú\",\"n\":1}";
    String postData = "{\"mimeType\":\"application/json\",\"text\":" + HarBuilder.json(body)
        + "}";
    ConvertedSample sample = convert(new HarBuilder().entry(STARTED, "POST",
        "https://a.com/api", headers("content-type", "application/json"), postData, 201,
        headers("content-type", "application/json"), "application/json", "{\"id\":7}"));
    HTTPSamplerBase sampler = sample.getSampler();
    assertThat(sampler.getPostBodyRaw()).isTrue();
    assertThat(sampler.getArguments().getArgument(0).getValue()).isEqualTo(body);
    assertThat(sample.getResult().getResponseMessage()).isEqualTo("Created");
    assertThat(sample.getResult().getResponseDataAsString()).isEqualTo("{\"id\":7}");
  }

  @Test
  public void shouldDecodeBase64ResponseBody() throws Exception {
    ConvertedSample sample = convert(new HarBuilder().rawEntry(STARTED, "GET",
        "https://a.com/data", headers(), null, 200, headers("content-type", "text/plain"),
        "{\"mimeType\":\"text/plain\",\"text\":\"dG9rZW49eHl6\",\"encoding\":\"base64\"}", ""));
    assertThat(sample.getResult().getResponseDataAsString()).isEqualTo("token=xyz");
  }

  @Test
  public void shouldEncodeNonAsciiUrls() {
    assertThat(HarEntryConverter.toAsciiUrl("https://a.com/caf\u00e9 x?q=\u00f1#frag"))
        .isEqualTo("https://a.com/caf%C3%A9%20x?q=%C3%B1");
    assertThat(HarEntryConverter.toAsciiUrl("https://a.com/\uD83D\uDE00"))
        .isEqualTo("https://a.com/%F0%9F%98%80");
  }

  @Test
  public void shouldCanonicalizeOnlyLowerCaseHeaderNames() {
    assertThat(HarEntryConverter.canonicalHeaderName("x-csrf-token")).isEqualTo("X-Csrf-Token");
    assertThat(HarEntryConverter.canonicalHeaderName("X-CSRF-Token")).isEqualTo("X-CSRF-Token");
    assertThat(HarEntryConverter.canonicalHeaderName("etag")).isEqualTo("Etag");
  }

  @Test
  public void shouldReportSkipReasons() throws IOException {
    HarImportOptions options = new HarImportOptions();
    HarEntry entry = new HarEntry();
    entry.setMethod("GET");
    entry.setUrl("data:image/png;base64,AAA");
    entry.setStatus(200);
    assertThat(HarEntryConverter.getSkipReason(entry, options)).contains("scheme");
    entry.setUrl("https://a.com/");
    assertThat(HarEntryConverter.getSkipReason(entry, options)).isNull();
    entry.setFromCache(true);
    assertThat(HarEntryConverter.getSkipReason(entry, options)).contains("cache");
    assertThat(HarEntryConverter.getSkipReason(entry, options.setSkipCachedEntries(false)))
        .isNull();
    entry.setStatus(0);
    assertThat(HarEntryConverter.getSkipReason(entry, options)).contains("no response");
    assertThat(HarEntryConverter.getSkipReason(entry,
        options.setSkipEntriesWithoutResponse(false))).isNull();
  }
}
