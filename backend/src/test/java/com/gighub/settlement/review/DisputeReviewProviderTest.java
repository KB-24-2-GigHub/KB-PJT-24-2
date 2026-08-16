package com.gighub.settlement.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gighub.config.ApiJsonMapper;
import com.gighub.settlement.domain.SettlementStatus;
import com.gighub.work.domain.WorkCaseStatus;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DisputeReviewProviderTest {

    private static final String REQUEST_ID = "123e4567-e89b-12d3-a456-426614174000";
    private final ObjectMapper objectMapper = ApiJsonMapper.create();

    @Test
    void responsesStructuredOutputIsValidatedAndSensitiveNumberShapesAreRedacted() throws Exception {
        HttpClient client = mock(HttpClient.class);
        String resultJson = """
                {
                  "decision":"RELEASE_TO_WORKER",
                  "reasonCodes":["AGREED_WAGE_UNPAID"],
                  "summary":"약정 일급 지급 흐름을 재개합니다.",
                  "confidence":0.91
                }
                """;
        HttpResponse<String> response = response(200, completedResponse("resp_123", resultJson));
        when(client.send(any(), anyStringHandler())).thenReturn(response);
        OpenAiDisputeReviewProvider provider = provider(client);

        DisputeReviewProviderResult result = provider.review(REQUEST_ID, new DisputeReviewInput(
                "010-1234-5678 연락 요청",
                "계좌 123-456-789012 지급 여부",
                WorkCaseStatus.COMPLETED,
                SettlementStatus.ON_HOLD,
                120_000L,
                1L
        ));

        assertEquals("resp_123", result.getProviderResponseId());
        assertEquals(DisputeReviewDecision.RESOLVE, result.getResult().getDecision());
    }

    @Test
    void requestUsesBoundedJsonSchemaAndDoesNotSendSensitiveContactShapes() throws Exception {
        HttpClient client = mock(HttpClient.class);
        String resultJson = """
                {
                  "decision":"NEEDS_MORE_INFO",
                  "reasonCodes":["EVIDENCE_NEEDED"],
                  "summary":"추가 확인이 필요합니다.",
                  "confidence":0.5
                }
                """;
        HttpResponse<String> response = response(200, completedResponse("resp_1", resultJson));
        org.mockito.ArgumentCaptor<HttpRequest> requestCaptor =
                org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        when(client.send(requestCaptor.capture(), anyStringHandler())).thenReturn(response);

        provider(client).review(REQUEST_ID, new DisputeReviewInput(
                "010-1234-5678 또는 worker@example.com 연락 요청",
                "계좌 123-456-789012 지급 여부",
                WorkCaseStatus.COMPLETED,
                SettlementStatus.ON_HOLD,
                120_000L,
                1L
        ));

        String body = readBody(requestCaptor.getValue());
        assertEquals(
                REQUEST_ID,
                requestCaptor.getValue().headers().firstValue("X-Client-Request-Id").orElseThrow());
        assertTrue(body.contains("\"store\":false"));
        assertTrue(body.contains("\"type\":\"json_schema\""));
        assertTrue(body.contains("RELEASE_TO_WORKER"));
        assertTrue(body.contains("\"minItems\":1"));
        assertTrue(body.contains("\"maxItems\":5"));
        assertTrue(body.contains("REDACTED_EMAIL"));
        assertTrue(body.contains("REDACTED_PHONE"));
        assertTrue(body.contains("REDACTED_FINANCIAL_NUMBER"));
        assertFalse(body.contains("worker@example.com"));
        assertFalse(body.contains("010-1234-5678"));
        assertFalse(body.contains("123-456-789012"));
    }

    @Test
    void providerFailureAndMalformedOutputStayClosed() throws Exception {
        HttpClient serverErrorClient = mock(HttpClient.class);
        HttpResponse<String> serverErrorResponse = response(503, "{}");
        when(serverErrorClient.send(any(), anyStringHandler()))
                .thenReturn(serverErrorResponse);
        DisputeReviewProviderException serverError = assertThrows(
                DisputeReviewProviderException.class,
                () -> provider(serverErrorClient).review(REQUEST_ID, input()));
        assertEquals("PROVIDER_5XX", serverError.getFailureCode());

        HttpClient malformedClient = mock(HttpClient.class);
        HttpResponse<String> malformedResponse = response(
                200,
                completedResponse("resp_bad", "{\"decision\":\"PAY_HALF\"}")
        );
        when(malformedClient.send(any(), anyStringHandler())).thenReturn(malformedResponse);
        DisputeReviewProviderException malformed = assertThrows(
                DisputeReviewProviderException.class,
                () -> provider(malformedClient).review(REQUEST_ID, input()));
        assertEquals("INVALID_PROVIDER_OUTPUT", malformed.getFailureCode());
    }

    @Test
    void missingOrNonNumericConfidenceIsRejectedInsteadOfBecomingZero() throws Exception {
        for (String resultJson : List.of(
                """
                        {
                          "decision":"RELEASE_TO_WORKER",
                          "reasonCodes":["AGREED_WAGE_UNPAID"],
                          "summary":"약정 일급 지급 흐름을 재개합니다."
                        }
                        """,
                """
                        {
                          "decision":"RELEASE_TO_WORKER",
                          "reasonCodes":["AGREED_WAGE_UNPAID"],
                          "summary":"약정 일급 지급 흐름을 재개합니다.",
                          "confidence":"high"
                         }
                         """)) {
            HttpClient client = mock(HttpClient.class);
            HttpResponse<String> response = response(
                    200, completedResponse("resp_invalid_confidence", resultJson));
            when(client.send(any(), anyStringHandler())).thenReturn(response);

            DisputeReviewProviderException failure = assertThrows(
                    DisputeReviewProviderException.class,
                    () -> provider(client).review(REQUEST_ID, input()));

            assertEquals("INVALID_PROVIDER_OUTPUT", failure.getFailureCode());
        }
    }

    @Test
    void refusalIsRejectedEvenWhenOutputTextAppearsFirst() throws Exception {
        String resultJson = """
                {
                  "decision":"RELEASE_TO_WORKER",
                  "reasonCodes":["AGREED_WAGE_UNPAID"],
                  "summary":"약정 일급 지급 흐름을 재개합니다.",
                  "confidence":0.91
                }
                """;
        com.fasterxml.jackson.databind.node.ObjectNode root = objectMapper.createObjectNode();
        root.put("id", "resp_refusal");
        root.put("status", "completed");
        com.fasterxml.jackson.databind.node.ArrayNode content = root.putArray("output")
                .addObject()
                .put("type", "message")
                .putArray("content");
        content.addObject().put("type", "output_text").put("text", resultJson);
        content.addObject().put("type", "refusal").put("refusal", "cannot comply");
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = response(200, objectMapper.writeValueAsString(root));
        when(client.send(any(), anyStringHandler())).thenReturn(response);

        DisputeReviewProviderException failure = assertThrows(
                DisputeReviewProviderException.class,
                () -> provider(client).review(REQUEST_ID, input()));

        assertEquals("PROVIDER_REFUSAL", failure.getFailureCode());
    }

    @Test
    void fakeProviderCoversAllClosedDecisionsWithoutMoneyCommands() {
        for (DisputeReviewDecision decision : DisputeReviewDecision.values()) {
            DisputeReviewProviderResult response =
                    new FakeDisputeReviewProvider(decision).review(REQUEST_ID, input());
            assertEquals(decision, response.getResult().getDecision());
            assertTrue(response.getProviderResponseId().startsWith("fake-"));
        }
    }

    private OpenAiDisputeReviewProvider provider(HttpClient client) {
        return new OpenAiDisputeReviewProvider(
                "test-key",
                URI.create("https://example.test/v1/responses"),
                "gpt-5.4-nano",
                "dispute-review-v1",
                Duration.ofSeconds(2),
                client,
                objectMapper
        );
    }

    private String completedResponse(String responseId, String resultJson) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode root = objectMapper.createObjectNode();
        root.put("id", responseId);
        root.put("status", "completed");
        com.fasterxml.jackson.databind.node.ObjectNode message = root.putArray("output")
                .addObject();
        message.put("type", "message");
        com.fasterxml.jackson.databind.node.ObjectNode text = message.putArray("content")
                .addObject();
        text.put("type", "output_text");
        text.put("text", resultJson);
        return objectMapper.writeValueAsString(root);
    }

    private static DisputeReviewInput input() {
        return new DisputeReviewInput(
                "임금 확인",
                "약정 일급 지급 여부를 확인해주세요.",
                WorkCaseStatus.COMPLETED,
                SettlementStatus.ON_HOLD,
                120_000L,
                1L
        );
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse.BodyHandler<String> anyStringHandler() {
        return any(HttpResponse.BodyHandler.class);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    private static String readBody(HttpRequest request) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                bytes.writeBytes(chunk);
            }

            @Override
            public void onError(Throwable throwable) {
                throw new AssertionError(throwable);
            }

            @Override
            public void onComplete() {
                // ofString Publisher는 구독 Thread에서 동기 완료됩니다.
            }
        });
        return bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
