package com.seatswap.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatswap.exception.SeatMapException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SeatMapRecognitionClientTest {

    private static final String BASE = "http://seatmap-service:8001";
    private static final String URL = BASE + "/api/seatmap/recognize?aisleMode=continue";
    private static final String OK_BODY = """
            {"image":{"width":700,"height":400},
             "seats":[{"uid":"s0001","row":1,"col":1,"x":70,"y":40,"w":18,"h":18},
                      {"uid":"s0002","row":1,"col":2,"x":90,"y":40,"w":18,"h":18}],
             "rows":[],"stats":{},"warnings":[]}""";

    private MockRestServiceServer server;

    private SeatMapRecognitionClient client(String key) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        return new SeatMapRecognitionClient(builder.build(), new ObjectMapper(), key);
    }

    private static SeatMapException call(SeatMapRecognitionClient client) {
        try {
            client.recognize(new byte[]{1, 2, 3}, "image/png", "continue");
        } catch (SeatMapException e) {
            return e;
        }
        throw new AssertionError("SeatMapException expected");
    }

    private SeatMapException errorFor(HttpStatus status, String body) {
        SeatMapRecognitionClient client = client(null);
        server.expect(requestTo(URL))
                .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body(body));
        return call(client);
    }

    @Test
    void sendsMultipartWithKeyAndParsesResult() {
        SeatMapRecognitionClient client = client("secret");
        server.expect(requestTo(BASE + "/api/seatmap/recognize?aisleMode=skip"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(queryParam("aisleMode", "skip"))
                .andExpect(header("X-Internal-Key", "secret"))
                .andExpect(header("Content-Type", startsWith("multipart/form-data")))
                .andExpect(content().string(containsString("name=\"file\"")))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        SeatMapRecognitionClient.Recognition result = client.recognize(new byte[]{1, 2, 3}, "image/png", "skip");

        assertThat(result.image().width()).isEqualTo(700);
        assertThat(result.seats()).hasSize(2);
        assertThat(result.seats().get(1).uid()).isEqualTo("s0002");
        server.verify();
    }

    @Test
    void omitsKeyHeaderWhenNotConfigured() {
        for (String key : new String[]{null, "", "  "}) {
            SeatMapRecognitionClient client = client(key);
            server.expect(requestTo(URL))
                    .andExpect(request -> assertThat(request.getHeaders().containsKey("X-Internal-Key")).isFalse())
                    .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));
            client.recognize(new byte[]{1}, "image/png", "continue");
            server.verify();
        }
    }

    @Test
    void unprocessableKeepsUpstreamCodeAndMessage() {
        SeatMapException e = errorFor(HttpStatus.UNPROCESSABLE_ENTITY,
                "{\"code\":\"NO_SEATS_DETECTED\",\"message\":\"좌석을 찾지 못했습니다.\"}");
        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(e.getCode()).isEqualTo("NO_SEATS_DETECTED");
        // 업스트림 message는 쓰지 않고 code별 고정 문구
        assertThat(e.getMessage()).contains("좌석을 찾지 못했습니다").isNotEqualTo("좌석을 찾지 못했습니다.");

        e = errorFor(HttpStatus.UNPROCESSABLE_ENTITY, "{\"code\":\"IMAGE_TOO_COMPLEX\",\"message\":\"복잡\"}");
        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(e.getCode()).isEqualTo("IMAGE_TOO_COMPLEX");
    }

    @Test
    void clientErrorsMapToSameStatus() {
        assertThat(errorFor(HttpStatus.PAYLOAD_TOO_LARGE, "{\"code\":\"IMAGE_TOO_LARGE\",\"message\":\"크다\"}").getStatus())
                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        SeatMapException e = errorFor(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "{\"code\":\"UNSUPPORTED_IMAGE\",\"message\":\"형식\"}");
        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(e.getCode()).isEqualTo("UNSUPPORTED_IMAGE");
        e = errorFor(HttpStatus.BAD_REQUEST, "{\"code\":\"BAD_REQUEST\",\"message\":\"형식 오류\"}");
        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rateLimitAndBusyBecome503WithGenericMessage() {
        SeatMapException busy = errorFor(HttpStatus.SERVICE_UNAVAILABLE, "{\"code\":\"BUSY\",\"message\":\"internal detail\"}");
        assertThat(busy.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(busy.getCode()).isEqualTo("BUSY");
        assertThat(busy.getMessage()).isEqualTo(SeatMapRecognitionClient.BUSY_MESSAGE);

        SeatMapException limited = errorFor(HttpStatus.TOO_MANY_REQUESTS, "{\"code\":\"RATE_LIMITED\",\"message\":\"x\"}");
        assertThat(limited.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(limited.getCode()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void serverAndConfigErrorsBecome502WithoutLeakingDetails() {
        SeatMapException unauthorized = errorFor(HttpStatus.UNAUTHORIZED, "{\"code\":\"UNAUTHORIZED\",\"message\":\"key mismatch\"}");
        assertThat(unauthorized.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(unauthorized.getCode()).isEqualTo("SEATMAP_SERVICE_ERROR");
        assertThat(unauthorized.getMessage()).isEqualTo(SeatMapRecognitionClient.SERVICE_ERROR_MESSAGE);

        SeatMapException internal = errorFor(HttpStatus.INTERNAL_SERVER_ERROR,
                "{\"code\":\"INTERNAL_ERROR\",\"message\":\"Traceback at http://seatmap-service:8001\"}");
        assertThat(internal.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(internal.getCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(internal.getMessage()).doesNotContain("seatmap-service");

        SeatMapException notJson = errorFor(HttpStatus.BAD_GATEWAY, "<html>bad gateway</html>");
        assertThat(notJson.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(notJson.getCode()).isEqualTo("SEATMAP_SERVICE_ERROR");
    }

    @Test
    void connectionFailureBecomes503WithoutInternalUrl() {
        SeatMapRecognitionClient client = client(null);
        server.expect(requestTo(URL))
                .andRespond(request -> {
                    throw new IOException("Connection refused: seatmap-service:8001");
                });

        SeatMapException e = call(client);

        assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(e.getCode()).isEqualTo("SEATMAP_SERVICE_UNAVAILABLE");
        assertThat(e.getMessage()).doesNotContain("seatmap-service").doesNotContain("8001");
    }

    @Test
    void unknownCodeGetsDefaultMessageAndUpstreamMessageIsNotForwarded() {
        SeatMapException e = errorFor(HttpStatus.UNPROCESSABLE_ENTITY,
                "{\"code\":\"WEIRD_NEW_CODE\",\"message\":\"secret upstream text\"}");
        assertThat(e.getCode()).isEqualTo("WEIRD_NEW_CODE");
        assertThat(e.getMessage()).doesNotContain("secret upstream text").isNotBlank();
    }

    @Test
    void readTimeoutBecomes503Unavailable() {
        SeatMapRecognitionClient client = client(null);
        server.expect(requestTo(URL)).andRespond(request -> {
            throw new java.net.SocketTimeoutException("Read timed out");
        });

        SeatMapException e = call(client);

        assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(e.getCode()).isEqualTo("SEATMAP_SERVICE_UNAVAILABLE");
    }

    private SeatMapRecognitionClient clientWithSeats(String seatsJson, int maxSeats) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        SeatMapRecognitionClient client = new SeatMapRecognitionClient(builder.build(), new ObjectMapper(), null, maxSeats);
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"image\":{\"width\":100,\"height\":50},\"seats\":" + seatsJson + "}", MediaType.APPLICATION_JSON));
        return client;
    }

    @Test
    void seatValueViolationsBecome502() {
        String[] bad = {
                "{\"uid\":\"a\",\"row\":0,\"col\":1,\"x\":0,\"y\":0,\"w\":10,\"h\":10}",
                "{\"uid\":\"a\",\"row\":1,\"col\":0,\"x\":0,\"y\":0,\"w\":10,\"h\":10}",
                "{\"uid\":\"a\",\"row\":1,\"col\":1,\"x\":0,\"y\":0,\"w\":0,\"h\":10}",
                "{\"uid\":\"a\",\"row\":1,\"col\":1,\"x\":0,\"y\":0,\"w\":10,\"h\":-1}",
                "{\"uid\":\"a\",\"row\":1,\"col\":1,\"x\":-1,\"y\":0,\"w\":10,\"h\":10}",
                "{\"uid\":\"a\",\"row\":1,\"col\":1,\"x\":0,\"y\":-5,\"w\":10,\"h\":10}",
                "{\"uid\":\"a\",\"row\":1,\"col\":1,\"x\":95,\"y\":0,\"w\":10,\"h\":10}",
                "{\"uid\":\"a\",\"row\":1,\"col\":1,\"x\":0,\"y\":45,\"w\":10,\"h\":10}",
                "{\"uid\":\"a\",\"row\":1,\"col\":1,\"y\":0,\"w\":10,\"h\":10}",
                "{\"uid\":\"a\",\"col\":1,\"x\":0,\"y\":0,\"w\":10,\"h\":10}"
        };
        for (String seat : bad) {
            SeatMapException e = call(clientWithSeats("[" + seat + "]", 6000));
            assertThat(e.getStatus()).as(seat).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(e.getCode()).isEqualTo("SEATMAP_SERVICE_ERROR");
        }
    }

    private static String seatWithSection(String uid, String sectionJson) {
        return "{\"uid\":\"" + uid + "\",\"row\":1,\"col\":1,\"x\":0,\"y\":0,\"w\":10,\"h\":10"
                + (sectionJson == null ? "" : ",\"section\":" + sectionJson) + "}";
    }

    @Test
    void sectionIsKeptAndDefaultsToOneWhenAbsent() {
        // 층이 달라 (row, col)이 같은 좌석, section 없는 좌석은 1, 명시적 null도 1
        String seats = "[" + seatWithSection("a", "2") + "," + seatWithSection("b", null) + ","
                + seatWithSection("c", "null") + "," + seatWithSection("d", "50") + "]";
        SeatMapRecognitionClient.Recognition result =
                clientWithSeats(seats, 6000).recognize(new byte[]{1}, "image/png", "continue");

        assertThat(result.seats()).extracting(s -> s.section()).containsExactly(2, 1, 1, 50);
    }

    @Test
    void duplicateSectionRowColIsStoredNotRejected() {
        // (section,row,col) 중복은 보정 전 데이터에서 생길 수 있어 거부하지 않는다 (uid만 유일하면 된다)
        String seats = "[" + seatWithSection("a", "1") + "," + seatWithSection("b", "1") + "]";
        assertThat(clientWithSeats(seats, 6000).recognize(new byte[]{1}, "image/png", "continue").seats()).hasSize(2);
    }

    @Test
    void invalidSectionBecomes502() {
        for (String bad : new String[]{"0", "-1", "51", "1.5", "\"2\"", "true", "3000000000"}) {
            SeatMapException e = call(clientWithSeats("[" + seatWithSection("a", bad) + "]", 6000));
            assertThat(e.getStatus()).as(bad).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(e.getCode()).isEqualTo("SEATMAP_SERVICE_ERROR");
        }
    }

    @Test
    void tooManySeatsBecome502() {
        String seat = "{\"uid\":\"%s\",\"row\":1,\"col\":1,\"x\":0,\"y\":0,\"w\":10,\"h\":10}";
        SeatMapException e = call(clientWithSeats("[" + String.format(seat, "a") + "," + String.format(seat, "b") + "]", 1));
        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void validSeatsAndSmallBoundsOverflowAreAccepted() {
        // x+w=102, y+h=52: 이미지(100x50)를 2px 벗어나는 것은 허용 오차
        String edge = "{\"uid\":\"a\",\"row\":1,\"col\":1,\"x\":92,\"y\":42,\"w\":10,\"h\":10}";
        SeatMapRecognitionClient.Recognition result =
                clientWithSeats("[" + edge + "]", 6000).recognize(new byte[]{1}, "image/png", "continue");
        assertThat(result.seats()).hasSize(1);
        assertThat(result.seats().get(0).x()).isEqualTo(92);
    }

    @Test
    void malformedSuccessBodyBecomes502() {
        SeatMapRecognitionClient client = client(null);
        server.expect(requestTo(URL))
                .andRespond(withSuccess("{\"image\":{\"width\":0,\"height\":400},\"seats\":[]}", MediaType.APPLICATION_JSON));
        assertThat(call(client).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);

        client = client(null);
        server.expect(requestTo(URL)).andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));
        assertThat(call(client).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void emptySeatsIs422NoSeatsDetected() {
        SeatMapRecognitionClient client = client(null);
        server.expect(requestTo(URL))
                .andRespond(withSuccess("{\"image\":{\"width\":10,\"height\":10},\"seats\":[]}", MediaType.APPLICATION_JSON));
        SeatMapException e = call(client);
        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(e.getCode()).isEqualTo("NO_SEATS_DETECTED");
    }

    @Test
    void duplicateUidBecomes502() {
        SeatMapRecognitionClient client = client(null);
        server.expect(requestTo(URL))
                .andRespond(withSuccess("""
                        {"image":{"width":10,"height":10},"seats":[
                         {"uid":"a","row":1,"col":1,"x":1,"y":1,"w":1,"h":1},
                         {"uid":"a","row":1,"col":2,"x":2,"y":1,"w":1,"h":1}]}""", MediaType.APPLICATION_JSON));
        assertThat(call(client).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }
}
