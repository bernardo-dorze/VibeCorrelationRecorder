package com.blazemeter.jmeter.correlation.core.har;

import static com.blazemeter.jmeter.correlation.core.har.HarBuilder.headers;
import static com.blazemeter.jmeter.correlation.core.har.HarBuilder.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;

public class HarParserTest {

  private static List<HarEntry> parse(String har) throws IOException {
    return new HarParser().parse(new ByteArrayInputStream(har.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  public void shouldSortEntriesByStartTimeKeepingFileOrderForTies() throws IOException {
    String har = new HarBuilder()
        .entry("2024-05-06T12:00:02.000Z", "GET", "https://a.com/3", headers(), null, 200,
            headers(), "text/html", "")
        .entry("2024-05-06T12:00:01.000+00:00", "GET", "https://a.com/1", headers(), null, 200,
            headers(), "text/html", "")
        .entry("2024-05-06T14:00:01.000+02:00", "GET", "https://a.com/2", headers(), null, 200,
            headers(), "text/html", "")
        .build();
    List<String> urls = parse(har).stream().map(HarEntry::getUrl).collect(Collectors.toList());
    assertThat(urls).containsExactly("https://a.com/1", "https://a.com/2", "https://a.com/3");
  }

  @Test
  public void shouldParseRequestResponseAndTimings() throws IOException {
    String postData = "{\"mimeType\":\"application/x-www-form-urlencoded\","
        + "\"text\":\"a=1&b=2\",\"params\":[{\"name\":\"a\",\"value\":\"1\"}]}";
    String har = new HarBuilder()
        .entry("2024-05-06T12:00:00.123Z", "post", "https://a.com/login",
            headers("content-type", "application/x-www-form-urlencoded"), postData, 302,
            headers("location", "/home"), "text/html", "<b>moved</b>")
        .build();
    HarEntry entry = parse(har).get(0);
    assertThat(entry.getMethod()).isEqualTo("POST");
    assertThat(entry.getStartedMillis()).isEqualTo(1714996800123L);
    assertThat(entry.getTimeMillis()).isEqualTo(121);
    assertThat(entry.getReceiveMillis()).isEqualTo(20);
    assertThat(entry.getPostText()).isEqualTo("a=1&b=2");
    assertThat(entry.getPostParams()).hasSize(1);
    assertThat(entry.getRequestHeaders().get(0).getName()).isEqualTo("content-type");
    assertThat(entry.getStatus()).isEqualTo(302);
    assertThat(entry.getResponseHeaders().get(0).getValue()).isEqualTo("/home");
    assertThat(entry.getResponseText()).isEqualTo("<b>moved</b>");
    assertThat(entry.isResponseTextBase64()).isFalse();
    assertThat(entry.getRedirectUrl()).isNull();
  }

  @Test
  public void shouldDetectBase64ContentAndCachedEntries() throws IOException {
    String har = new HarBuilder()
        .rawEntry("2024-05-06T12:00:00Z", "GET", "https://a.com/img.png", headers(), null, 200,
            headers(), "{\"mimeType\":\"image/png\",\"text\":\"iVBORw0=\",\"encoding\":\"base64\"}",
            ",\"_fromCache\":\"memory\"")
        .build();
    HarEntry entry = parse(har).get(0);
    assertThat(entry.isResponseTextBase64()).isTrue();
    assertThat(entry.isFromCache()).isTrue();
  }

  @Test
  public void shouldIgnoreUnknownFieldsAndMissingOptionalOnes() throws IOException {
    String har = "{\"log\":{\"_custom\":{\"x\":[1,2]},\"entries\":[{\"request\":{\"method\":"
        + "\"GET\",\"url\":" + json("https://a.com/") + "},\"response\":{}}]},\"other\":1}";
    HarEntry entry = parse(har).get(0);
    assertThat(entry.getUrl()).isEqualTo("https://a.com/");
    assertThat(entry.getStatus()).isZero();
    assertThat(entry.getResponseText()).isNull();
    assertThat(entry.getStartedMillis()).isZero();
  }

  @Test
  public void shouldFailWhenNotAHar() {
    assertThatThrownBy(() -> parse("{\"foo\":1}")).isInstanceOf(IOException.class)
        .hasMessageContaining("log");
    assertThatThrownBy(() -> parse("[1]")).isInstanceOf(IOException.class);
  }
}
