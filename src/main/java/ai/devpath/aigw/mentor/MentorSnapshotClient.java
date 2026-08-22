package ai.devpath.aigw.mentor;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 사용자 JWT로 LCS의 owner/purpose 승인 Mentor snapshot을 SSE 개시 전에 동기 소비한다. */
@Component
public class MentorSnapshotClient {

  private static final Set<String> ENVELOPE_KEYS = Set.of(
      "snapshotId", "purpose", "visibility", "fieldsIncluded", "content");

  private final RestClient restClient;
  private final JsonMapper jsonMapper;
  private final ObjectReader strictReader;

  public MentorSnapshotClient(
      @Value("${devpath.lcs.base-url:http://localhost:8087}") String baseUrl,
      @Value("${devpath.lcs.timeout:PT2S}") Duration timeout,
      JsonMapper jsonMapper) {
    HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    var factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(timeout);
    this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    this.jsonMapper = jsonMapper;
    this.strictReader = jsonMapper.reader()
        .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  public MentorSnapshotContext consume(long snapshotId, String finalJwt) {
    if (snapshotId <= 0 || finalJwt == null || finalJwt.isBlank()) {
      throw new MentorSnapshotUnavailableException();
    }
    try {
      return restClient.get()
          .uri("/lcs/mentor/snapshots/{id}", snapshotId)
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + finalJwt)
          .exchange((request, response) -> consumeResponse(snapshotId, response.getStatusCode(),
              response.getBody()));
    } catch (MentorSnapshotUnavailableException | MentorSnapshotServiceUnavailableException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new MentorSnapshotServiceUnavailableException();
    }
  }

  private MentorSnapshotContext consumeResponse(
      long requestedId, HttpStatusCode status, java.io.InputStream body) {
    if (status.is4xxClientError()) {
      throw new MentorSnapshotUnavailableException();
    }
    if (!status.is2xxSuccessful()) {
      throw new MentorSnapshotServiceUnavailableException();
    }
    try {
      JsonNode root = strictReader.readTree(body);
      validateEnvelope(root, requestedId);
      ObjectNode providerContext = jsonMapper.createObjectNode();
      providerContext.set("fieldsIncluded", root.path("fieldsIncluded"));
      providerContext.set("content", root.path("content"));
      return new MentorSnapshotContext(requestedId,
          jsonMapper.writeValueAsString(root),
          jsonMapper.writeValueAsString(providerContext));
    } catch (MentorSnapshotUnavailableException e) {
      throw e;
    } catch (Exception e) {
      throw new MentorSnapshotUnavailableException();
    }
  }

  /** LCS 정책/allowlist는 복제하지 않고 transport shape와 key parity만 검증한다. */
  private void validateEnvelope(JsonNode root, long requestedId) {
    if (root == null || !root.isObject()
        || !new HashSet<>(root.propertyNames()).equals(ENVELOPE_KEYS)) {
      throw new MentorSnapshotUnavailableException();
    }
    JsonNode id = root.path("snapshotId");
    if (!id.isIntegralNumber() || !id.canConvertToLong()
        || id.longValue() <= 0 || id.longValue() != requestedId
        || !"mentor_prompt".equals(root.path("purpose").stringValue())
        || !"private".equals(root.path("visibility").stringValue())) {
      throw new MentorSnapshotUnavailableException();
    }
    JsonNode fields = root.path("fieldsIncluded");
    JsonNode content = root.path("content");
    if (!fields.isArray() || !content.isObject()) {
      throw new MentorSnapshotUnavailableException();
    }
    Set<String> included = new HashSet<>();
    for (JsonNode field : fields) {
      String name = field.stringValue();
      if (name == null || name.isBlank() || !included.add(name)) {
        throw new MentorSnapshotUnavailableException();
      }
    }
    if (!included.equals(new HashSet<>(content.propertyNames()))) {
      throw new MentorSnapshotUnavailableException();
    }
  }
}
