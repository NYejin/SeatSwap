package com.seatswap.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatswap.domain.SeatMapLayout;
import com.seatswap.domain.SeatMapStatus;
import com.seatswap.domain.User;
import com.seatswap.domain.Venue;
import com.seatswap.dto.response.SeatMapResponse;
import com.seatswap.dto.response.SeatMapSeat;
import com.seatswap.dto.response.SeatMapSummaryResponse;
import com.seatswap.exception.ConflictException;
import com.seatswap.exception.FieldValidationException;
import com.seatswap.exception.ForbiddenException;
import com.seatswap.exception.GlobalExceptionHandler;
import com.seatswap.exception.NotFoundException;
import com.seatswap.exception.SeatMapException;
import com.seatswap.repository.SeatCorrectionRepository;
import com.seatswap.repository.SeatMapLayoutRepository;
import com.seatswap.repository.TicketRepository;
import com.seatswap.repository.UserRepository;
import com.seatswap.repository.VenueRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 좌석맵 저장/조회 + 오류 신고 처리 (FR-03~07).
 * 실제 이미지 인식(OpenCV/OCR)은 이 서비스가 아니라 seatmap-service(FastAPI)가 담당하며,
 * 이 클래스는 그 결과 좌표 JSON을 받아 SeatMapLayout에 저장하는 역할만 한다
 * (seatmap-vision-engineer 에이전트는 seatmap-service 쪽 작업 전담).
 * 원본 이미지는 저장하지 않는다 (업로드는 메모리에서 seatmap-service로 중계만).
 */
@Slf4j
@Service
public class SeatMapService {

    static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    static final int ZONE_NAME_MAX_LENGTH = 100;
    static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/png", "image/jpeg", "image/webp");
    static final Set<String> AISLE_MODES = Set.of("continue", "skip");
    static final int DEFAULT_MAX_CONCURRENT_RECOGNITIONS = 3;
    static final long DEFAULT_PERMIT_WAIT_MS = 5000;
    static final String BUSY_MESSAGE = "좌석 인식 요청이 많습니다. 잠시 후 다시 시도해주세요.";
    static final String USER_BUSY_MESSAGE = "이미 인식 중인 이미지가 있습니다. 완료된 후 다시 시도해주세요.";
    static final String OCR_DONE = "OCR_DONE";
    static final String UNIQUE_DRAFT_KEY = "uk_seat_map_layout_draft_key";
    static final int DEFAULT_MAX_ZONES_PER_VENUE = 20;
    static final int DEFAULT_DAILY_LIMIT_PER_USER = 10;
    static final long DAILY_WINDOW_HOURS = 24;
    static final String ZONE_LIMIT_MESSAGE = "이 공연장에 등록할 수 있는 구역 수를 넘었어요.";
    static final String DAILY_LIMIT_MESSAGE = "하루에 등록할 수 있는 좌석표 수를 넘었어요. 내일 다시 시도해주세요.";
    static final String DELETE_FORBIDDEN_MESSAGE = "작성자 또는 관리자만 삭제할 수 있어요.";

    static final String VENUE_NOT_FOUND_MESSAGE = "존재하지 않는 공연장입니다.";
    static final String SEATMAP_NOT_FOUND_MESSAGE = "좌석표를 찾을 수 없습니다.";
    static final String DUPLICATE_DRAFT_MESSAGE = "이미 등록된 임시 좌석표가 있습니다.";
    static final String STORED_DATA_ERROR_MESSAGE = "좌석표 데이터를 불러오지 못했습니다.";

    private static final TypeReference<List<SeatMapSeat>> SEAT_LIST = new TypeReference<>() {
    };

    private final SeatMapLayoutRepository seatMapLayoutRepository;
    private final SeatCorrectionRepository seatCorrectionRepository;
    private final VenueRepository venueRepository;
    private final UserRepository userRepository;
    private final SeatMapRecognitionClient recognitionClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    /** 공연장당 좌석표(구역) 수 상한. */
    private final int maxZonesPerVenue;
    /** 사용자당 24시간 내 등록 상한 (ADMIN 제외). */
    private final int dailyLimitPerUser;

    // TEMP(테스트용): 추후 제거 또는 비활성화 — TEMP-DRAFT-DELETE (이 필드와 canDelete 안의 플래그 분기)
    // true인 동안은 로그인한 누구나 DRAFT를 지울 수 있다. false면 작성자·관리자 규칙(영구 규칙)만 적용한다.
    @Value("${seatmap-service.dev-draft-delete-enabled:true}")
    private boolean devDraftDeleteEnabled = true;
    // TEMP-DRAFT-DELETE: 생성자를 건드리지 않고 지울 수 있게 필드 주입
    @Autowired
    private TicketRepository ticketRepository;

    /** 전역 동시 인식 상한 (대기 시간 초과 시 503 BUSY). */
    private final Semaphore recognitionPermits;
    private final long permitWaitMs;
    /** 인식 중인 사용자 (사용자당 동시 1건). */
    private final Set<Long> recognizingUsers = ConcurrentHashMap.newKeySet();

    public SeatMapService(SeatMapLayoutRepository seatMapLayoutRepository,
                          SeatCorrectionRepository seatCorrectionRepository,
                          VenueRepository venueRepository,
                          UserRepository userRepository,
                          SeatMapRecognitionClient recognitionClient,
                          ObjectMapper objectMapper,
                          PlatformTransactionManager transactionManager) {
        this(seatMapLayoutRepository, seatCorrectionRepository, venueRepository, userRepository, recognitionClient,
                objectMapper, transactionManager, DEFAULT_MAX_CONCURRENT_RECOGNITIONS, DEFAULT_PERMIT_WAIT_MS,
                Clock.systemDefaultZone(), DEFAULT_MAX_ZONES_PER_VENUE, DEFAULT_DAILY_LIMIT_PER_USER);
    }

    @Autowired
    public SeatMapService(SeatMapLayoutRepository seatMapLayoutRepository,
                          SeatCorrectionRepository seatCorrectionRepository,
                          VenueRepository venueRepository,
                          UserRepository userRepository,
                          SeatMapRecognitionClient recognitionClient,
                          ObjectMapper objectMapper,
                          PlatformTransactionManager transactionManager,
                          @Value("${seatmap-service.max-concurrent-recognitions:3}") int maxConcurrentRecognitions,
                          @Value("${seatmap-service.permit-wait-ms:5000}") long permitWaitMs,
                          Clock clock,
                          @Value("${seatmap-service.max-zones-per-venue:20}") int maxZonesPerVenue,
                          @Value("${seatmap-service.daily-limit-per-user:10}") int dailyLimitPerUser) {
        this.recognitionPermits = new Semaphore(Math.max(1, maxConcurrentRecognitions));
        this.permitWaitMs = permitWaitMs;
        this.seatMapLayoutRepository = seatMapLayoutRepository;
        this.seatCorrectionRepository = seatCorrectionRepository;
        this.venueRepository = venueRepository;
        this.userRepository = userRepository;
        this.recognitionClient = recognitionClient;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.maxZonesPerVenue = maxZonesPerVenue;
        this.dailyLimitPerUser = dailyLimitPerUser;
    }

    /**
     * 업로드 이미지를 인식해 DRAFT 좌석표로 저장한다.
     * 인식(비싼 호출) 전에 순서대로 검사한다: 공연장 404 -> 같은 구역 DRAFT 중복 409 {"message","seatMapId"}
     * -> 공연장 구역 수 상한 422 ZONE_LIMIT_REACHED -> 사용자 일일 등록 제한 429 DAILY_LIMIT_REACHED (ADMIN 제외)
     * -> (인식 동시성 제한: 429 RATE_LIMITED / 503 BUSY).
     * 상한 검사는 count 쿼리라 동시 요청이 겹치면 상한을 소폭 넘을 수 있다 (허용 오차, 잠금 없음).
     * 인식(HTTP, 최대 60초) 동안 DB 트랜잭션을 잡지 않도록 메서드 전체를 @Transactional로 묶지 않는다
     * (PerformanceService.create와 같은 이유). 사전 확인과 insert 사이의 레이스는 draft_key UNIQUE가 최종 방어.
     *
     * TODO(정책 미정): 이미 OFFICIAL 좌석표가 있는 공연장에도 DRAFT 생성을 막지 않는다.
     */
    public SeatMapResponse createDraft(Long venueId, Long userId, boolean admin, MultipartFile file, String zoneName, String aisleMode) {
        String mode = resolveAisleMode(aisleMode);
        String zone = resolveZoneName(zoneName);
        validateFile(file);
        // 바이트 배열은 한 번만 만들어 시그니처 검사와 중계에 같이 쓴다
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new SeatMapException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "이미지 파일을 읽을 수 없습니다.");
        }
        String detectedType = detectImageType(bytes);
        if (detectedType == null) {
            throw new SeatMapException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_IMAGE",
                    "PNG, JPEG, WebP 이미지만 올릴 수 있습니다.");
        }

        // 공연장 404 -> 중복 DRAFT 409 순서로, 인식 호출 전에 확인
        transactionTemplate.executeWithoutResult(status -> {
            requireVenue(venueId);
            findDraftId(venueId, zone).ifPresent(id -> {
                throw duplicateDraft(id);
            });
            checkRegistrationLimits(venueId, userId, admin);
        });

        SeatMapRecognitionClient.Recognition recognition = recognizeLimited(userId, bytes, detectedType, mode);
        String seatJson = writeSeatJson(recognition.seats());

        try {
            return transactionTemplate.execute(status -> {
                findDraftId(venueId, zone).ifPresent(id -> {
                    throw duplicateDraft(id);
                });
                Venue venue = requireVenue(venueId);
                User creator = requireUser(userId);
                SeatMapLayout layout = seatMapLayoutRepository.saveAndFlush(SeatMapLayout.createDraft(
                        venue, zone, seatJson, OCR_DONE,
                        recognition.image().width(), recognition.image().height(), creator));
                return SeatMapResponse.of(layout, recognition.seats(), true);
            });
        } catch (DataIntegrityViolationException e) {
            if (!DataIntegrityViolations.isViolationOf(e, UNIQUE_DRAFT_KEY)) {
                throw e;
            }
            // 실패한 insert 트랜잭션은 이미 롤백됐다. 새 트랜잭션에서 먼저 저장된 DRAFT id를 조회한다.
            log.info("Draft seatmap create race on venue {}", venueId);
            Long winnerId = transactionTemplate.execute(status -> findDraftId(venueId, zone).orElse(null));
            if (winnerId == null) {
                // 먼저 저장된 DRAFT가 그 사이 사라진 경우: id 없이 일반 충돌 응답
                throw new ConflictException(GlobalExceptionHandler.DATA_CONFLICT_MESSAGE);
            }
            throw duplicateDraft(winnerId);
        }
    }

    @Transactional(readOnly = true)
    public SeatMapResponse get(Long seatMapId, Long userId, boolean admin) {
        SeatMapLayout layout = seatMapLayoutRepository.findDetailById(seatMapId)
                .orElseThrow(() -> new NotFoundException(SEATMAP_NOT_FOUND_MESSAGE));
        Long creatorId = layout.getCreatedBy() == null ? null : layout.getCreatedBy().getId();
        return SeatMapResponse.of(layout, readSeatJson(layout),
                canDelete(layout.getStatus(), creatorId, userId, admin));
    }

    /** 공연장의 좌석표 목록. 좌석표가 없으면 빈 배열, 공연장이 없으면 404. */
    @Transactional(readOnly = true)
    public List<SeatMapSummaryResponse> listByVenue(Long venueId) {
        requireVenue(venueId);
        return seatMapLayoutRepository.findSummariesByVenueId(venueId);
    }

    /**
     * 삭제 권한 (영구 규칙): DRAFT이고 (작성자 본인 또는 ADMIN). created_by가 NULL인 기존 행은 관리자만.
     * TODO: 임시 기능 — TEMP-DRAFT-DELETE: 플래그(dev-draft-delete-enabled)가 true인 동안은 로그인한 누구나 허용.
     * 역할은 JWT가 아니라 필터가 DB의 User.role로 만든 principal(admin)을 쓴다.
     */
    private boolean canDelete(SeatMapStatus status, Long creatorId, Long userId, boolean admin) {
        if (status != SeatMapStatus.DRAFT) {
            return false;
        }
        return devDraftDeleteEnabled || admin || (creatorId != null && creatorId.equals(userId));
    }

    static final String DELETE_OFFICIAL_MESSAGE = "정식 등록된 좌석표는 삭제할 수 없습니다.";
    static final String DELETE_REFERENCED_MESSAGE = "티켓 또는 오류 신고가 연결된 좌석표는 삭제할 수 없습니다.";

    /**
     * DRAFT 좌석표 삭제: 작성자 또는 ADMIN만 (아니면 403). OFFICIAL이거나 티켓·오류 신고가 참조하면 409.
     * 순서: 404 -> OFFICIAL 409 -> 권한 403 -> 참조 409.
     * 로그에는 사용자 id와 좌석표 id만 남긴다 (seat_json은 읽지도 않는다).
     * 한계: 삭제는 흔적이 남지 않아(수정 로그는 V3 예정) 삭제 후 재업로드로 일일 제한을 우회할 수 있다.
     */
    @Transactional
    public void deleteDraft(Long seatMapId, Long userId, boolean admin) {
        SeatMapLayoutRepository.OwnerView view = seatMapLayoutRepository.findOwnerViewById(seatMapId)
                .orElseThrow(() -> new NotFoundException(SEATMAP_NOT_FOUND_MESSAGE));
        if (view.getStatus() != SeatMapStatus.DRAFT) {
            throw new ConflictException(DELETE_OFFICIAL_MESSAGE);
        }
        if (!canDelete(view.getStatus(), view.getCreatorId(), userId, admin)) {
            throw new ForbiddenException(DELETE_FORBIDDEN_MESSAGE);
        }
        if (ticketRepository.countBySeatMapLayout_Id(seatMapId) > 0
                || seatCorrectionRepository.countBySeatMapLayout_Id(seatMapId) > 0) {
            throw new ConflictException(DELETE_REFERENCED_MESSAGE);
        }
        if (seatMapLayoutRepository.deleteDraftById(seatMapId) == 0) {
            // 조회 직후 다른 요청이 먼저 지웠다
            throw new NotFoundException(SEATMAP_NOT_FOUND_MESSAGE);
        }
        log.info("Draft seatMap {} deleted by user {}", seatMapId, userId);
    }

    // TODO: reportCorrection() — 동일 정정 2건 이상 시 자동 반영 로직

    // ---- 내부 ----

    /**
     * 구역 수 상한(공연장당, 모든 사용자 공통)과 사용자 일일 등록 제한(ADMIN 제외)을 DB count로 검사한다.
     * 한계: 일일 제한은 현재 남아 있는 행(created_by, created_at)만 센다. 삭제된 행은 집계되지 않아
     * 삭제 후 재업로드로 우회할 수 있다 (V3 수정 로그에서 보완 예정). 새 인덱스는 V3 후보.
     * count와 insert 사이에 락이 없어 동시 요청은 상한을 조금 넘길 수 있다 (허용 오차).
     */
    private void checkRegistrationLimits(Long venueId, Long userId, boolean admin) {
        if (seatMapLayoutRepository.countByVenue_Id(venueId) >= maxZonesPerVenue) {
            throw new SeatMapException(HttpStatus.UNPROCESSABLE_ENTITY, "ZONE_LIMIT_REACHED", ZONE_LIMIT_MESSAGE);
        }
        if (admin) {
            return;
        }
        LocalDateTime since = LocalDateTime.now(clock).minusHours(DAILY_WINDOW_HOURS);
        if (seatMapLayoutRepository.countByCreatedBy_IdAndCreatedAtAfter(userId, since) >= dailyLimitPerUser) {
            throw new SeatMapException(HttpStatus.TOO_MANY_REQUESTS, "DAILY_LIMIT_REACHED", DAILY_LIMIT_MESSAGE);
        }
    }

    /**
     * 인식 호출에 동시성 제한을 건다: 사용자당 동시 1건(중복이면 429 RATE_LIMITED), 전역 동시 N건
     * (대기 시간 안에 자리를 못 얻으면 503 BUSY). 예외가 나도 자리와 사용자 표시는 반드시 해제한다.
     */
    private SeatMapRecognitionClient.Recognition recognizeLimited(Long userId, byte[] bytes, String contentType,
                                                                  String aisleMode) {
        if (!recognizingUsers.add(userId)) {
            throw new SeatMapException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", USER_BUSY_MESSAGE);
        }
        try {
            boolean acquired;
            try {
                acquired = recognitionPermits.tryAcquire(permitWaitMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SeatMapException(HttpStatus.SERVICE_UNAVAILABLE, "BUSY", BUSY_MESSAGE);
            }
            if (!acquired) {
                throw new SeatMapException(HttpStatus.SERVICE_UNAVAILABLE, "BUSY", BUSY_MESSAGE);
            }
            try {
                return recognitionClient.recognize(bytes, contentType, aisleMode);
            } finally {
                recognitionPermits.release();
            }
        } finally {
            recognizingUsers.remove(userId);
        }
    }

    /** 파일 앞부분(매직 넘버)으로 이미지 형식을 판별한다. 지원하지 않으면 null. */
    static String detectImageType(byte[] b) {
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return "image/png";
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private Optional<Long> findDraftId(Long venueId, String zone) {
        return zone == null
                ? seatMapLayoutRepository.findDraftIdWithoutZone(venueId)
                : seatMapLayoutRepository.findDraftIdByZone(venueId, zone);
    }

    private Venue requireVenue(Long venueId) {
        return venueRepository.findById(venueId).orElseThrow(() -> new NotFoundException(VENUE_NOT_FOUND_MESSAGE));
    }

    /** 토큰은 유효하나 사용자가 없는 경우 -> 401 (spring-boot-conventions 예외 조항). */
    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new InsufficientAuthenticationException("인증된 사용자를 찾을 수 없습니다."));
    }

    private static ConflictException duplicateDraft(Long existingId) {
        return new ConflictException(DUPLICATE_DRAFT_MESSAGE, Map.of("seatMapId", existingId));
    }

    private static String resolveAisleMode(String aisleMode) {
        if (aisleMode == null || aisleMode.isBlank()) {
            return "continue";
        }
        String mode = aisleMode.strip();
        if (!AISLE_MODES.contains(mode)) {
            throw new FieldValidationException("aisleMode", "aisleMode는 continue 또는 skip이어야 합니다.");
        }
        return mode;
    }

    private static String resolveZoneName(String zoneName) {
        String normalized = SeatMapLayout.normalizeZoneName(zoneName);
        if (normalized != null && normalized.length() > ZONE_NAME_MAX_LENGTH) {
            throw new FieldValidationException("zoneName", "구역명은 " + ZONE_NAME_MAX_LENGTH + "자 이하로 입력해주세요.");
        }
        return normalized;
    }

    private static String baseContentType(MultipartFile file) {
        String contentType = file.getContentType();
        return contentType == null ? "" : contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
    }

    private static void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new SeatMapException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "이미지 파일을 선택해주세요.");
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw new SeatMapException(HttpStatus.PAYLOAD_TOO_LARGE, "IMAGE_TOO_LARGE", "이미지는 10MB 이하만 올릴 수 있습니다.");
        }
        if (!ALLOWED_CONTENT_TYPES.contains(baseContentType(file))) {
            throw new SeatMapException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_IMAGE",
                    "PNG, JPEG, WebP 이미지만 올릴 수 있습니다.");
        }
    }

    private String writeSeatJson(List<SeatMapSeat> seats) {
        try {
            return objectMapper.writeValueAsString(seats);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize recognized seats", e);
            throw new SeatMapException(HttpStatus.BAD_GATEWAY, "SEATMAP_SERVICE_ERROR",
                    SeatMapRecognitionClient.SERVICE_ERROR_MESSAGE);
        }
    }

    /** 저장된 seat_json 파싱. 깨져 있으면 내용은 로그에 남기지 않고 id만 남긴 뒤 일반 오류(500)로 응답한다. */
    private List<SeatMapSeat> readSeatJson(SeatMapLayout layout) {
        try {
            List<SeatMapSeat> seats = objectMapper.readValue(layout.getSeatJson(), SEAT_LIST);
            if (seats == null) {
                throw new IllegalStateException("seat_json is null");
            }
            return seats;
        } catch (IOException | RuntimeException e) {
            log.error("Corrupted seat_json for seatMap {}: {}", layout.getId(), e.getClass().getSimpleName());
            throw new IllegalStateException(STORED_DATA_ERROR_MESSAGE);
        }
    }
}
