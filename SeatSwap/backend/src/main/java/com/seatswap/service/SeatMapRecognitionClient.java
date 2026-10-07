package com.seatswap.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatswap.dto.response.SeatMapSeat;
import com.seatswap.exception.SeatMapException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * seatmap-service(FastAPI) /api/seatmap/recognize 호출 클라이언트. 인식은 그쪽이 하고, 여기서는 이미지를 중계하고
 * 좌표 결과만 받는다. 이미지는 메모리에서만 다루며 로그에 남기지 않는다.
 * 오류는 모두 {@link SeatMapException}으로 바꾼다 (내부 URL·스택은 응답에 노출하지 않는다).
 */
@Slf4j
@Component
public class SeatMapRecognitionClient {

    static final String RECOGNIZE_PATH = "/api/seatmap/recognize";
    static final String INTERNAL_KEY_HEADER = "X-Internal-Key";

    static final String UNAVAILABLE_MESSAGE = "좌석 인식 서비스에 연결할 수 없습니다. 잠시 후 다시 시도해주세요.";
    static final String BUSY_MESSAGE = "좌석 인식 서비스가 혼잡합니다. 잠시 후 다시 시도해주세요.";
    static final String SERVICE_ERROR_MESSAGE = "좌석 인식 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.";

    private static final Pattern CODE_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");
    private static final int MAX_ERROR_BODY_BYTES = 16 * 1024;
    /** 좌표가 이미지 경계를 이 값(px)만큼 벗어나는 것은 반올림 오차로 보고 허용한다. */
    private static final int BOUNDS_TOLERANCE_PX = 2;
    static final int DEFAULT_MAX_SEATS = 6000;
    /** 구획(층) 번호 상한. 넘으면 업스트림 이상으로 본다. */
    static final int MAX_SECTION = 50;

    /** 업스트림 오류 code -> 고정 한국어 문구. 업스트림 message는 전달도 로그도 하지 않는다. */
    private static final Map<String, String> CODE_MESSAGES = Map.of(
            "NO_SEATS_DETECTED", "이미지에서 좌석을 찾지 못했습니다. 좌석 배치도 이미지인지 확인해주세요.",
            "IMAGE_TOO_COMPLEX", "이미지가 너무 복잡해 인식할 수 없습니다. 좌석 배치도 부분만 잘라서 올려주세요.",
            "IMAGE_TOO_LARGE", "이미지가 너무 큽니다. 크기를 줄여서 다시 올려주세요.",
            "UNSUPPORTED_IMAGE", "PNG, JPEG, WebP 이미지만 인식할 수 있습니다.",
            "BAD_REQUEST", "이미지를 처리할 수 없는 요청입니다.");

    private final RestClient restClient;
    private final String internalKey;
    private final ObjectMapper objectMapper;
    private final int maxSeats;

    @Autowired
    public SeatMapRecognitionClient(RestClient.Builder builder,
                                    ObjectMapper objectMapper,
                                    @Value("${seatmap-service.base-url}") String baseUrl,
                                    @Value("${seatmap-service.internal-key:}") String internalKey,
                                    @Value("${seatmap-service.connect-timeout-ms:5000}") int connectTimeoutMs,
                                    @Value("${seatmap-service.read-timeout-ms:60000}") int readTimeoutMs,
                                    @Value("${seatmap-service.max-seats:6000}") int maxSeats) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        this.restClient = builder.baseUrl(baseUrl).requestFactory(factory).build();
        this.internalKey = internalKey;
        this.objectMapper = objectMapper;
        this.maxSeats = maxSeats;
        if (internalKey == null || internalKey.isBlank()) {
            log.warn("SEATMAP_INTERNAL_KEY is not set: requests to seatmap-service are sent without X-Internal-Key "
                    + "(development mode). Set the same key on both services in production.");
        }
    }

    /** 테스트용: 이미 설정된 RestClient(MockRestServiceServer 바인딩)를 받는다. */
    SeatMapRecognitionClient(RestClient restClient, ObjectMapper objectMapper, String internalKey) {
        this(restClient, objectMapper, internalKey, DEFAULT_MAX_SEATS);
    }

    SeatMapRecognitionClient(RestClient restClient, ObjectMapper objectMapper, String internalKey, int maxSeats) {
        this.restClient = restClient;
        this.internalKey = internalKey;
        this.objectMapper = objectMapper;
        this.maxSeats = maxSeats;
    }

    /** seatmap-service 인식 결과 중 저장에 필요한 (검증을 통과한) 부분. */
    public record Recognition(Image image, List<SeatMapSeat> seats) {
        public record Image(Integer width, Integer height) {
        }
    }

    /**
     * 업스트림 응답 원본. 원시 int로 받으면 필드 누락이 0으로 채워져 검증을 통과하므로 Integer로 받아 null을 거부한다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawRecognition(Recognition.Image image, List<RawSeat> seats) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawSeat(String uid, Integer row, Integer col, Integer x, Integer y, Integer w, Integer h,
                   JsonNode section) {
    }

    public Recognition recognize(byte[] imageBytes, String contentType, String aisleMode) {
        // MultipartBodyBuilder는 reactive-streams(Publisher)가 필요해 쓰지 않는다. 파일명은 사용자 파일명 대신 고정값.
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(contentType));
        ByteArrayResource resource = new ByteArrayResource(imageBytes) {
            @Override
            public String getFilename() {
                return "image";
            }
        };
        MultiValueMap<String, Object> multipart = new LinkedMultiValueMap<>();
        multipart.add("file", new HttpEntity<>(resource, partHeaders));

        RawRecognition result;
        try {
            RestClient.RequestBodySpec request = restClient.post()
                    .uri(uri -> uri.path(RECOGNIZE_PATH).queryParam("aisleMode", aisleMode).build())
                    .contentType(MediaType.MULTIPART_FORM_DATA);
            if (internalKey != null && !internalKey.isBlank()) {
                request = request.header(INTERNAL_KEY_HEADER, internalKey);
            }
            result = request.body(multipart)
                    .retrieve()
                    .onStatus(status -> status.isError(), (req, res) -> {
                        throw mapError(res.getStatusCode().value(), readBody(res.getBody()));
                    })
                    .body(RawRecognition.class);
        } catch (SeatMapException e) {
            throw e;
        } catch (ResourceAccessException e) {
            // 연결 실패/타임아웃. 원인(내부 호스트명 등)은 응답에 싣지 않고 로그에도 예외 종류만 남긴다.
            log.warn("seatmap-service unreachable: {}", e.getMostSpecificCause().getClass().getSimpleName());
            throw new SeatMapException(HttpStatus.SERVICE_UNAVAILABLE, "SEATMAP_SERVICE_UNAVAILABLE", UNAVAILABLE_MESSAGE);
        } catch (RestClientException e) {
            log.warn("seatmap-service response unreadable: {}", e.getClass().getSimpleName());
            throw badGateway();
        }
        return validate(result);
    }

    /** 인식 결과 값 검증. 위반하면 업스트림 이상(502)으로 본다 (값은 로그에 남기지 않는다). */
    private Recognition validate(RawRecognition result) {
        if (result == null || result.image() == null || result.seats() == null
                || result.image().width() == null || result.image().height() == null
                || result.image().width() <= 0 || result.image().height() <= 0) {
            log.warn("seatmap-service returned an incomplete recognition result");
            throw badGateway();
        }
        if (result.seats().isEmpty()) {
            throw new SeatMapException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_SEATS_DETECTED",
                    CODE_MESSAGES.get("NO_SEATS_DETECTED"));
        }
        if (result.seats().size() > maxSeats) {
            log.warn("seatmap-service returned too many seats: {}", result.seats().size());
            throw badGateway();
        }
        int width = result.image().width();
        int height = result.image().height();
        Set<String> uids = new HashSet<>();
        List<SeatMapSeat> seats = new ArrayList<>(result.seats().size());
        for (RawSeat seat : result.seats()) {
            if (seat == null || seat.uid() == null || seat.uid().isBlank() || seat.uid().length() > 32
                    || !uids.add(seat.uid())) {
                log.warn("seatmap-service returned seats with missing or duplicated uid");
                throw badGateway();
            }
            if (seat.row() == null || seat.col() == null || seat.x() == null || seat.y() == null
                    || seat.w() == null || seat.h() == null
                    || seat.row() < 1 || seat.col() < 1 || seat.w() <= 0 || seat.h() <= 0
                    || seat.x() < 0 || seat.y() < 0
                    || (long) seat.x() + seat.w() > (long) width + BOUNDS_TOLERANCE_PX
                    || (long) seat.y() + seat.h() > (long) height + BOUNDS_TOLERANCE_PX) {
                log.warn("seatmap-service returned a seat with missing or out-of-range values");
                throw badGateway();
            }
            int section = 1;
            if (seat.section() != null && !seat.section().isNull()) {
                // 선택 필드. 있으면 1..MAX_SECTION의 정수여야 한다 (1.5, "2" 같은 값은 거부)
                if (!seat.section().isIntegralNumber() || !seat.section().canConvertToInt()
                        || seat.section().asInt() < 1 || seat.section().asInt() > MAX_SECTION) {
                    log.warn("seatmap-service returned a seat with an invalid section");
                    throw badGateway();
                }
                section = seat.section().asInt();
            }
            seats.add(new SeatMapSeat(seat.uid(), seat.row(), seat.col(), seat.x(), seat.y(), seat.w(), seat.h(), section));
        }
        return new Recognition(result.image(), seats);
    }

    private static SeatMapException badGateway() {
        return new SeatMapException(HttpStatus.BAD_GATEWAY, "SEATMAP_SERVICE_ERROR", SERVICE_ERROR_MESSAGE);
    }

    private JsonNode readBody(InputStream in) {
        try {
            return objectMapper.readTree(in.readNBytes(MAX_ERROR_BODY_BYTES));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** seatmap-service 오류 응답 -> 백엔드 응답. 서비스의 code(대문자 식별자 형식일 때만)는 유지한다. */
    SeatMapException mapError(int upstreamStatus, JsonNode body) {
        String code = text(body, "code");
        if (code != null && !CODE_PATTERN.matcher(code).matches()) {
            code = null;
        }
        // 업스트림 message는 쓰지도 로그에 남기지도 않는다. code로 고정 문구를 고르고, 모르는 code는 기본 문구.
        log.warn("seatmap-service error response: status={}, code={}", upstreamStatus, code);

        return switch (upstreamStatus) {
            case 400 -> new SeatMapException(HttpStatus.BAD_REQUEST, or(code, "BAD_REQUEST"),
                    messageFor(code, "이미지를 처리할 수 없는 요청입니다."));
            case 413 -> new SeatMapException(HttpStatus.PAYLOAD_TOO_LARGE, or(code, "IMAGE_TOO_LARGE"),
                    messageFor(code, CODE_MESSAGES.get("IMAGE_TOO_LARGE")));
            case 415 -> new SeatMapException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, or(code, "UNSUPPORTED_IMAGE"),
                    messageFor(code, CODE_MESSAGES.get("UNSUPPORTED_IMAGE")));
            case 422 -> new SeatMapException(HttpStatus.UNPROCESSABLE_ENTITY, or(code, "UNPROCESSABLE_IMAGE"),
                    messageFor(code, "이미지에서 좌석을 인식하지 못했습니다."));
            case 429, 503 -> new SeatMapException(HttpStatus.SERVICE_UNAVAILABLE, or(code, "SEATMAP_SERVICE_BUSY"),
                    BUSY_MESSAGE);
            // 401(내부 키 불일치)·404·405는 설정 문제라 사용자에게 원인 code를 노출하지 않는다
            case 401, 404, 405 -> badGateway();
            default -> new SeatMapException(HttpStatus.BAD_GATEWAY, or(code, "SEATMAP_SERVICE_ERROR"),
                    SERVICE_ERROR_MESSAGE);
        };
    }

    private static String text(JsonNode body, String field) {
        if (body == null || !body.isObject()) {
            return null;
        }
        JsonNode node = body.get(field);
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    private static String messageFor(String code, String fallback) {
        return code != null && CODE_MESSAGES.containsKey(code) ? CODE_MESSAGES.get(code) : fallback;
    }

    private static String or(String value, String fallback) {
        return value != null ? value : fallback;
    }
}
