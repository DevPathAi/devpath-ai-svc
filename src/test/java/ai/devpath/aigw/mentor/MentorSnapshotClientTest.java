package ai.devpath.aigw.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.stream.Stream;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MentorSnapshotClientTest {

  private MockWebServer server;
  private final JsonMapper mapper = JsonMapper.builder().build();

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  @Test
  void forwardsFinalUserJwtAndConsumesTheExactGetContractWithoutBodyOrQuery() throws Exception {
    server.enqueue(json(200, validEnvelope(23)));

    MentorSnapshotContext context = client().consume(23L, "final-user-jwt");

    RecordedRequest request = server.takeRequest();
    assertThat(request.getMethod()).isEqualTo("GET");
    assertThat(request.getPath()).isEqualTo("/lcs/mentor/snapshots/23");
    assertThat(request.getRequestUrl().query()).isNull();
    assertThat(request.getBodySize()).isZero();
    assertThat(request.getHeader("Authorization")).isEqualTo("Bearer final-user-jwt");

    JsonNode persisted = mapper.readTree(context.envelopeJson());
    JsonNode provider = mapper.readTree(context.providerContextJson());
    assertThat(context.snapshotId()).isEqualTo(23L);
    assertThat(persisted.path("purpose").asString()).isEqualTo("mentor_prompt");
    assertThat(persisted.path("visibility").asString()).isEqualTo("private");
    assertThat(provider.path("fieldsIncluded")).isEqualTo(persisted.path("fieldsIncluded"));
    assertThat(provider.path("content")).isEqualTo(persisted.path("content"));
    assertThat(provider.properties()).hasSize(2);
  }

  @Test
  void validatesWireIntegrityAndKeyParityWithoutDuplicatingTheLcsFieldAllowlist() throws Exception {
    server.enqueue(json(200, """
        {"snapshotId":23,"purpose":"mentor_prompt","visibility":"private",
         "fieldsIncluded":["future_lcs_field"],
         "content":{"future_lcs_field":{"shape":"owned by LCS"}}}
        """));

    MentorSnapshotContext context = client().consume(23L, "jwt");

    assertThat(mapper.readTree(context.providerContextJson()).path("content")
        .path("future_lcs_field").path("shape").asString()).isEqualTo("owned by LCS");
  }

  @ParameterizedTest
  @MethodSource("corruptEnvelopes")
  void corruptOrUnauthorizedEnvelopeAlwaysBecomesTheSameGenericNotFound(String body) {
    server.enqueue(json(200, body));

    assertThatThrownBy(() -> client().consume(23L, "jwt"))
        .isInstanceOf(MentorSnapshotUnavailableException.class)
        .hasMessage("mentor snapshot unavailable");
  }

  @Test
  void ownerOrPurposeDenialFromLcsIsGenericNotFoundAndDoesNotExposeResponse() {
    server.enqueue(json(403, "{\"error\":\"owner=42 code=secret\"}"));

    assertThatThrownBy(() -> client().consume(23L, "jwt"))
        .isInstanceOf(MentorSnapshotUnavailableException.class)
        .hasMessage("mentor snapshot unavailable")
        .hasMessageNotContaining("23")
        .hasMessageNotContaining("jwt")
        .hasMessageNotContaining("owner=42")
        .hasMessageNotContaining("secret");
  }

  @Test
  void missingSnapshot404UsesTheSameGenericNotFound() {
    server.enqueue(json(404, "{\"error\":\"snapshot 23 missing\"}"));

    assertThatThrownBy(() -> client().consume(23L, "jwt"))
        .isInstanceOf(MentorSnapshotUnavailableException.class)
        .hasMessage("mentor snapshot unavailable")
        .hasMessageNotContaining("23");
  }

  @Test
  void lcsServerFailureIsARecoverableGenericServiceError() {
    server.enqueue(json(503, "{\"error\":\"db contains current_code=secret\"}"));

    assertThatThrownBy(() -> client().consume(23L, "jwt"))
        .isInstanceOf(MentorSnapshotServiceUnavailableException.class)
        .hasMessage("mentor snapshot temporarily unavailable")
        .hasMessageNotContaining("current_code")
        .hasMessageNotContaining("secret");
  }

  @Test
  void doesNotFollowRedirectsOrForwardTheUserJwtToAnotherOrigin() throws Exception {
    try (MockWebServer redirectTarget = new MockWebServer()) {
      redirectTarget.start();
      redirectTarget.enqueue(json(200, validEnvelope(23)));
      server.enqueue(new MockResponse().setResponseCode(302)
          .setHeader("Location", redirectTarget.url("/token-capture")));

      assertThatThrownBy(() -> client().consume(23L, "final-user-jwt"))
          .isInstanceOf(MentorSnapshotServiceUnavailableException.class)
          .hasMessage("mentor snapshot temporarily unavailable");
      assertThat(redirectTarget.getRequestCount()).isZero();
    }
  }

  @Test
  void connectionFailureIsARecoverableGenericServiceError() throws Exception {
    String url = server.url("/").toString();
    server.shutdown();
    MentorSnapshotClient client = new MentorSnapshotClient(url, Duration.ofMillis(100), mapper);

    assertThatThrownBy(() -> client.consume(23L, "jwt"))
        .isInstanceOf(MentorSnapshotServiceUnavailableException.class)
        .hasMessage("mentor snapshot temporarily unavailable");
  }

  @Test
  void nonPositiveIdAndBlankJwtFailBeforeAnyHttpRequest() {
    assertThatThrownBy(() -> client().consume(0L, "jwt"))
        .isInstanceOf(MentorSnapshotUnavailableException.class)
        .hasMessage("mentor snapshot unavailable");
    assertThatThrownBy(() -> client().consume(23L, "  "))
        .isInstanceOf(MentorSnapshotUnavailableException.class)
        .hasMessage("mentor snapshot unavailable");

    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void unexpectedHttpRuntimeFailureIsNormalizedWithoutLeakingTokenOrId() {
    String malformedToken = "jwt\r\nX-Synthetic-Secret: current_code";

    assertThatThrownBy(() -> client().consume(23L, malformedToken))
        .isInstanceOf(MentorSnapshotServiceUnavailableException.class)
        .hasMessage("mentor snapshot temporarily unavailable")
        .hasMessageNotContaining("23")
        .hasMessageNotContaining("current_code")
        .hasMessageNotContaining("X-Synthetic-Secret");

    assertThat(server.getRequestCount()).isZero();
  }

  private MentorSnapshotClient client() {
    return new MentorSnapshotClient(server.url("/").toString(), Duration.ofSeconds(1), mapper);
  }

  private static MockResponse json(int status, String body) {
    return new MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody(body);
  }

  private static String validEnvelope(long id) {
    return """
        {"snapshotId":%d,"purpose":"mentor_prompt","visibility":"private",
         "fieldsIncluded":["current_code","recent_output"],
         "content":{"current_code":"print(1)",
                    "recent_output":{"stdout":"1","stderr":"","truncated":false}}}
        """.formatted(id);
  }

  private static Stream<String> corruptEnvelopes() {
    return Stream.of(
        "not-json",
        "[]",
        "{\"snapshotId\":24,\"snapshotId\":23,\"purpose\":\"mentor_prompt\",\"visibility\":\"private\",\"fieldsIncluded\":[],\"content\":{}}",
        "{\"snapshotId\":23,\"purpose\":\"mentor_prompt\",\"visibility\":\"private\",\"fieldsIncluded\":[],\"content\":{}} {}",
        "{\"snapshotId\":23,\"purpose\":\"mentor_prompt\",\"visibility\":\"private\"}",
        "{\"snapshotId\":23,\"purpose\":\"mentor_prompt\",\"visibility\":\"private\",\"fieldsIncluded\":[],\"content\":{},\"extra\":1}",
        "{\"snapshotId\":24,\"purpose\":\"mentor_prompt\",\"visibility\":\"private\",\"fieldsIncluded\":[],\"content\":{}}",
        "{\"snapshotId\":23,\"purpose\":1,\"visibility\":\"private\",\"fieldsIncluded\":[],\"content\":{}}",
        "{\"snapshotId\":23,\"purpose\":\"question_attachment\",\"visibility\":\"private\",\"fieldsIncluded\":[],\"content\":{}}",
        "{\"snapshotId\":23,\"purpose\":\"mentor_prompt\",\"visibility\":true,\"fieldsIncluded\":[],\"content\":{}}",
        "{\"snapshotId\":23,\"purpose\":\"mentor_prompt\",\"visibility\":\"public\",\"fieldsIncluded\":[],\"content\":{}}",
        "{\"snapshotId\":23,\"purpose\":\"mentor_prompt\",\"visibility\":\"private\",\"fieldsIncluded\":[\"current_code\",\"current_code\"],\"content\":{\"current_code\":\"x\"}}",
        "{\"snapshotId\":23,\"purpose\":\"mentor_prompt\",\"visibility\":\"private\",\"fieldsIncluded\":[1],\"content\":{\"1\":\"x\"}}",
        "{\"snapshotId\":23,\"purpose\":\"mentor_prompt\",\"visibility\":\"private\",\"fieldsIncluded\":[\"current_code\"],\"content\":{}}",
        "{\"snapshotId\":23,\"purpose\":\"mentor_prompt\",\"visibility\":\"private\",\"fieldsIncluded\":{},\"content\":{}}",
        "{\"snapshotId\":23,\"purpose\":\"mentor_prompt\",\"visibility\":\"private\",\"fieldsIncluded\":[],\"content\":[]}");
  }
}
