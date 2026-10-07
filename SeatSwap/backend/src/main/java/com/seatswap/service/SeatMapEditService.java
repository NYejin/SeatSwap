package com.seatswap.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatswap.domain.CorrectionStatus;
import com.seatswap.domain.SeatCorrection;
import com.seatswap.domain.SeatMapField;
import com.seatswap.domain.SeatMapLayout;
import com.seatswap.domain.SeatMapRevision;
import com.seatswap.domain.SeatMapRevisionAction;
import com.seatswap.domain.SeatMapRevisionItem;
import com.seatswap.domain.SeatMapStatus;
import com.seatswap.domain.User;
import com.seatswap.dto.request.AuthInputNormalizer;
import com.seatswap.dto.request.CorrectionRequest;
import com.seatswap.dto.request.SeatEditChange;
import com.seatswap.dto.request.SeatEditRequest;
import com.seatswap.dto.response.CorrectionResponse;
import com.seatswap.dto.response.PageResponse;
import com.seatswap.dto.response.SeatMapResponse;
import com.seatswap.dto.response.SeatMapRevisionDetailResponse;
import com.seatswap.dto.response.SeatMapRevisionItemResponse;
import com.seatswap.dto.response.SeatMapRevisionSummaryResponse;
import com.seatswap.dto.response.SeatMapSeat;
import com.seatswap.exception.ConflictException;
import com.seatswap.exception.FieldValidationException;
import com.seatswap.exception.ForbiddenException;
import com.seatswap.exception.NotFoundException;
import com.seatswap.exception.SeatMapException;
import com.seatswap.repository.SeatCorrectionRepository;
import com.seatswap.repository.SeatMapLayoutRepository;
import com.seatswap.repository.SeatMapRevisionItemRepository;
import com.seatswap.repository.SeatMapRevisionRepository;
import com.seatswap.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 좌석표 라벨(행/열 번호) 수정, 정정 신고, 수정 로그 조회.
 * 규칙: DRAFT는 로그인 사용자 누구나 즉시 수정(USER_EDIT), OFFICIAL은 사용자 직접 수정 불가(정정 신고로만)이고
 * 관리자만 직접 수정(ADMIN_EDIT)한다. 정정 신고는 OFFICIAL 전용이며 같은 정정이 서로 다른 신고자
 * N건(seatmap.correction-threshold, 기본 2) 이상이면 자동 반영(CORRECTION_APPLIED), 미만이면 검토 대기.
 * 모든 반영은 수정 로그(seat_map_revision + item)를 남기며 로그는 append-only다.
 * 수정·정정 반영은 좌석표 행을 FOR UPDATE로 잠근 뒤 한 트랜잭션에서 처리해 revision_no(=version)가 겹치지 않게 한다.
 * 이미지·좌표(x,y,w,h)는 건드리지 않는다.
 */
@Slf4j
@Service
public class SeatMapEditService {

    static final int DEFAULT_CORRECTION_THRESHOLD = 2;
    static final int MAX_PAGE_SIZE = 100;
    static final String OFFICIAL_EDIT_FORBIDDEN_MESSAGE = "정식 좌석표는 정정 신고로만 수정할 수 있어요.";
    static final String ADMIN_ONLY_MESSAGE = "관리자만 볼 수 있어요.";
    static final String DRAFT_CORRECTION_MESSAGE = "임시 좌석표는 직접 수정할 수 있어요.";
    static final String DUPLICATE_CORRECTION_MESSAGE = "이미 같은 내용으로 신고했어요.";
    static final String VERSION_CONFLICT_MESSAGE = "다른 사용자가 먼저 좌석표를 수정했어요. 새로 불러온 뒤 다시 시도해주세요.";
    static final String UNKNOWN_SEAT_MESSAGE = "존재하지 않는 좌석이 포함되어 있어요.";
    static final String DUPLICATE_SEAT_NUMBER_MESSAGE = "같은 구획에 행·열 번호가 겹치는 좌석이 생겨 수정할 수 없어요.";
    static final String NO_CHANGE_MESSAGE = "현재 값과 같아서 바꿀 내용이 없어요.";
    static final String DUPLICATE_CHANGE_MESSAGE = "같은 좌석의 같은 항목이 여러 번 들어 있어요.";
    static final String LABEL_ONLY_MESSAGE = "행 번호(ROW_LABEL) 또는 열 번호(COL_LABEL)만 수정할 수 있어요.";
    static final String UNIQUE_CORRECTION = "uk_seat_correction_pending_key";
    static final String HOLD_NOTE = "중복 번호로 자동 반영 보류";
    static final String INVALID_REASON_MESSAGE = "수정 사유를 입력해주세요. (보이지 않는 문자는 쓸 수 없어요.)";
    static final String EDIT_LIMIT_MESSAGE = "하루에 보낼 수 있는 수정 요청 수를 넘었어요. 내일 다시 시도해주세요.";
    static final String CORRECTION_LIMIT_MESSAGE = "하루에 보낼 수 있는 정정 신고 수를 넘었어요. 내일 다시 시도해주세요.";
    static final String PENDING_LIMIT_MESSAGE = "검토 대기 중인 정정 신고가 너무 많아요. 처리된 뒤에 다시 신고해주세요.";
    static final long LIMIT_WINDOW_HOURS = 24;
    static final int MIN_CORRECTION_THRESHOLD = 2;

    private static final TypeReference<List<SeatMapSeat>> SEAT_LIST = new TypeReference<>() {
    };

    private final SeatMapLayoutRepository layoutRepository;
    private final SeatCorrectionRepository correctionRepository;
    private final SeatMapRevisionRepository revisionRepository;
    private final SeatMapRevisionItemRepository itemRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final SeatMapService seatMapService;
    private final Clock clock;
    /** 동일 정정 자동 반영에 필요한 서로 다른 신고자 수 (최소 2: 1이면 신고 한 건이 곧 공식 좌석표 수정이 된다). */
    private final int correctionThreshold;
    /** 사용자당 24시간 내 한도 (ADMIN 제외): 수정 요청 수, 정정 신고 수. 대기 신고 상한은 사용자당 (ADMIN 포함). */
    private final int editDailyLimit;
    private final int correctionDailyLimit;
    private final int correctionPendingLimit;

    public SeatMapEditService(SeatMapLayoutRepository layoutRepository,
                              SeatCorrectionRepository correctionRepository,
                              SeatMapRevisionRepository revisionRepository,
                              SeatMapRevisionItemRepository itemRepository,
                              UserRepository userRepository,
                              ObjectMapper objectMapper,
                              SeatMapService seatMapService,
                              Clock clock,
                              @Value("${seatmap.correction-threshold:2}") int correctionThreshold,
                              @Value("${seatmap.edit-daily-limit:200}") int editDailyLimit,
                              @Value("${seatmap.correction-daily-limit:30}") int correctionDailyLimit,
                              @Value("${seatmap.correction-pending-limit:50}") int correctionPendingLimit) {
        this.layoutRepository = layoutRepository;
        this.correctionRepository = correctionRepository;
        this.revisionRepository = revisionRepository;
        this.itemRepository = itemRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.seatMapService = seatMapService;
        this.clock = clock;
        if (correctionThreshold < MIN_CORRECTION_THRESHOLD) {
            log.warn("seatmap.correction-threshold={} is below {}; using {}", correctionThreshold,
                    MIN_CORRECTION_THRESHOLD, MIN_CORRECTION_THRESHOLD);
        }
        this.correctionThreshold = Math.max(MIN_CORRECTION_THRESHOLD, correctionThreshold);
        this.editDailyLimit = editDailyLimit;
        this.correctionDailyLimit = correctionDailyLimit;
        this.correctionPendingLimit = correctionPendingLimit;
    }

    /**
     * 좌석 라벨 직접 수정. 검사 순서: 404 -> 권한 403(OFFICIAL은 ADMIN만) -> 버전 409 VERSION_CONFLICT
     * -> 항목 검증(라벨 필드만, 중복 항목 422) -> 존재하지 않는 uid 422 UNKNOWN_SEAT -> 변경 없음 422 NO_CHANGE
     * -> 바뀐 좌석의 최종 (section,row,col) 중복 422 DUPLICATE_SEAT_NUMBER.
     * 현재 값과 같은 항목은 로그에 남기지 않고 건너뛴다 (전부 같으면 NO_CHANGE).
     */
    @Transactional
    public SeatMapResponse editSeats(Long seatMapId, Long userId, boolean admin, SeatEditRequest request) {
        SeatMapLayout layout = lockLayout(seatMapId);
        boolean official = layout.getStatus() == SeatMapStatus.OFFICIAL;
        if (official && !admin) {
            throw new ForbiddenException(OFFICIAL_EDIT_FORBIDDEN_MESSAGE);
        }
        if (request.expectedVersion() != layout.getVersion()) {
            throw versionConflict();
        }
        String reason = request.reason() == null ? "" : request.reason().strip();
        if (reason.isEmpty() || AuthInputNormalizer.containsInvisibleChar(reason)) {
            throw new FieldValidationException("reason", INVALID_REASON_MESSAGE);
        }
        requireDistinctLabelChanges(request.changes());
        User actor = requireUser(userId);
        if (!admin && revisionRepository.countByActor_IdAndActionTypeAndCreatedAtAfter(userId,
                SeatMapRevisionAction.USER_EDIT, LocalDateTime.now(clock).minusHours(LIMIT_WINDOW_HOURS)) >= editDailyLimit) {
            throw new SeatMapException(HttpStatus.TOO_MANY_REQUESTS, "EDIT_LIMIT_REACHED", EDIT_LIMIT_MESSAGE);
        }

        List<SeatMapSeat> seats = readSeats(layout);
        Map<String, Integer> indexByUid = indexByUid(seats);
        List<SeatMapSeat> updated = new ArrayList<>(seats);
        List<AppliedChange> applied = new ArrayList<>();
        for (SeatEditChange change : request.changes()) {
            Integer index = indexByUid.get(change.uid());
            if (index == null) {
                throw new SeatMapException(HttpStatus.UNPROCESSABLE_ENTITY, "UNKNOWN_SEAT", UNKNOWN_SEAT_MESSAGE);
            }
            SeatMapSeat current = updated.get(index);
            int before = labelOf(current, change.field());
            if (before == change.value()) {
                continue;
            }
            updated.set(index, withLabel(current, change.field(), change.value()));
            applied.add(new AppliedChange(change.uid(), change.field(), before, change.value(), index));
        }
        if (applied.isEmpty()) {
            throw new SeatMapException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_CHANGE", NO_CHANGE_MESSAGE);
        }
        requireNoDuplicateNumber(updated, applied);

        SeatMapRevisionAction action = official ? SeatMapRevisionAction.ADMIN_EDIT : SeatMapRevisionAction.USER_EDIT;
        persistChange(layout, updated, action, actor, reason, applied, null);
        if (official) {
            supersedePending(seatMapId, applied);
        }
        return SeatMapResponse.of(layout, updated, seatMapService.canDeleteLayout(layout, userId, admin));
    }

    /**
     * 정정 신고 (OFFICIAL 전용). 검사 순서: 404 -> DRAFT 409 -> 라벨 필드 검사 400 -> 존재하지 않는 uid 422 UNKNOWN_SEAT
     * -> 현재 값과 같음 422 NO_CHANGE -> 같은 사용자의 같은 정정 409.
     * 접수 후 같은 (좌석, 필드, 값)의 대기 신고가 임계값 이상이면 같은 트랜잭션에서 반영한다.
     * 반영하면 중복 번호가 생기는 경우에는 반영하지 않고 PENDING으로 남긴다 (서버 로그에만 기록).
     */
    @Transactional
    public CorrectionResponse reportCorrection(Long seatMapId, Long userId, boolean admin, CorrectionRequest request) {
        SeatMapLayout layout = lockLayout(seatMapId);
        if (layout.getStatus() != SeatMapStatus.OFFICIAL) {
            throw new ConflictException(DRAFT_CORRECTION_MESSAGE);
        }
        SeatMapField field = request.field();
        if (!field.isEditableLabel()) {
            throw new FieldValidationException("field", LABEL_ONLY_MESSAGE);
        }
        User reporter = requireUser(userId);
        String normalized = SeatCorrection.normalize(request.value());

        List<SeatMapSeat> seats = readSeats(layout);
        Integer index = indexByUid(seats).get(request.uid());
        if (index == null) {
            throw new SeatMapException(HttpStatus.UNPROCESSABLE_ENTITY, "UNKNOWN_SEAT", UNKNOWN_SEAT_MESSAGE);
        }
        int before = labelOf(seats.get(index), field);
        if (before == request.value()) {
            throw new SeatMapException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_CHANGE", NO_CHANGE_MESSAGE);
        }
        // 중복은 대기(PENDING) 신고만 본다. 종결(APPLIED/SUPERSEDED/REJECTED)된 뒤에는 같은 정정을 다시 신고할 수 있다
        if (correctionRepository.existsBySeatMapLayout_IdAndTargetSeatUidAndTargetFieldAndReporter_IdAndNormalizedValueAndStatus(
                seatMapId, request.uid(), field, userId, normalized, CorrectionStatus.PENDING)) {
            throw new ConflictException(DUPLICATE_CORRECTION_MESSAGE);
        }
        if (!admin && correctionRepository.countByReporter_IdAndCreatedAtAfter(userId,
                LocalDateTime.now(clock).minusHours(LIMIT_WINDOW_HOURS)) >= correctionDailyLimit) {
            throw new SeatMapException(HttpStatus.TOO_MANY_REQUESTS, "CORRECTION_LIMIT_REACHED", CORRECTION_LIMIT_MESSAGE);
        }
        if (correctionRepository.countByReporter_IdAndStatus(userId, CorrectionStatus.PENDING) >= correctionPendingLimit) {
            throw new SeatMapException(HttpStatus.TOO_MANY_REQUESTS, "PENDING_LIMIT_REACHED", PENDING_LIMIT_MESSAGE);
        }

        SeatCorrection correction;
        try {
            correction = correctionRepository.saveAndFlush(
                    SeatCorrection.report(layout, reporter, request.uid(), field, before, request.value()));
        } catch (DataIntegrityViolationException e) {
            if (DataIntegrityViolations.isViolationOf(e, UNIQUE_CORRECTION)) {
                throw new ConflictException(DUPLICATE_CORRECTION_MESSAGE);
            }
            throw e;
        }

        List<SeatCorrection> pending = correctionRepository
                .findBySeatMapLayout_IdAndTargetSeatUidAndTargetFieldAndStatusOrderByIdAsc(
                        seatMapId, request.uid(), field, CorrectionStatus.PENDING);
        List<SeatCorrection> agreeing = pending.stream()
                .filter(c -> normalized.equals(c.getNormalizedValue()))
                .toList();
        if (agreeing.size() < correctionThreshold) {
            return new CorrectionResponse(CorrectionStatus.PENDING, correction.getId());
        }

        List<SeatMapSeat> updated = new ArrayList<>(seats);
        updated.set(index, withLabel(seats.get(index), field, request.value()));
        AppliedChange change = new AppliedChange(request.uid(), field, before, request.value(), index);
        if (hasDuplicateNumber(updated, change)) {
            log.warn("Correction threshold reached but not applied (duplicate seat number): seatMap {}, seat {}, field {}",
                    seatMapId, request.uid(), field);
            // 관리자가 확인할 수 있게 동의한 신고 행에 표시를 남긴다 (reviewed_*는 건드리지 않는다)
            for (SeatCorrection c : agreeing) {
                c.holdForReview(HOLD_NOTE);
            }
            correctionRepository.saveAll(agreeing);
            return new CorrectionResponse(CorrectionStatus.PENDING, correction.getId());
        }

        SeatMapRevision revision = persistChange(layout, updated, SeatMapRevisionAction.CORRECTION_APPLIED, null,
                "정정 신고 " + agreeing.size() + "건 자동 반영", List.of(change), agreeing.get(0));
        for (SeatCorrection c : pending) {
            if (agreeing.contains(c)) {
                c.markApplied(revision);
            } else {
                c.markSuperseded();
            }
        }
        correctionRepository.saveAll(pending);
        return new CorrectionResponse(CorrectionStatus.APPLIED, correction.getId());
    }

    /** 수정 로그 목록 (ADMIN 전용: 아니면 403). page는 0부터, size는 1~100으로 보정한다. 좌석표가 없으면 404. */
    @Transactional(readOnly = true)
    public PageResponse<SeatMapRevisionSummaryResponse> listRevisions(Long seatMapId, boolean admin, int page, int size) {
        requireAdmin(admin);
        if (!layoutRepository.existsById(seatMapId)) {
            throw new NotFoundException(SeatMapService.SEATMAP_NOT_FOUND_MESSAGE);
        }
        Page<SeatMapRevisionSummaryResponse> result = revisionRepository.findSummariesBySeatMapId(seatMapId,
                PageRequest.of(Math.max(0, page), Math.min(MAX_PAGE_SIZE, Math.max(1, size))));
        return new PageResponse<>(result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    /** 수정 로그 상세 (ADMIN 전용). 다른 좌석표의 로그 id면 404. */
    @Transactional(readOnly = true)
    public SeatMapRevisionDetailResponse getRevision(Long seatMapId, Long revisionId, boolean admin) {
        requireAdmin(admin);
        SeatMapRevision revision = revisionRepository.findByIdAndSeatMapLayout_Id(revisionId, seatMapId)
                .orElseThrow(() -> new NotFoundException("수정 기록을 찾을 수 없습니다."));
        List<SeatMapRevisionItemResponse> items = itemRepository.findByRevision_IdOrderByIdAsc(revision.getId())
                .stream().map(SeatMapRevisionItemResponse::of).toList();
        return SeatMapRevisionDetailResponse.of(revision, items);
    }

    // ---- 내부 ----

    /** 좌석표 반영 + 로그 저장. layout.version이 올라간 뒤의 값을 revision_no로 쓴다. */
    private SeatMapRevision persistChange(SeatMapLayout layout, List<SeatMapSeat> updated,
                                          SeatMapRevisionAction action, User actor, String reason,
                                          List<AppliedChange> changes, SeatCorrection correction) {
        layout.updateSeatLabels(writeSeats(updated), updated.size());
        try {
            layoutRepository.saveAndFlush(layout);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw versionConflict();
        }
        SeatMapRevision revision = revisionRepository.save(
                SeatMapRevision.of(layout, layout.getVersion(), action, actor, reason, null, null));
        List<SeatMapRevisionItem> items = new ArrayList<>(changes.size());
        for (AppliedChange c : changes) {
            items.add(SeatMapRevisionItem.of(revision, c.uid(), c.field(),
                    Integer.toString(c.before()), Integer.toString(c.after()), correction));
        }
        itemRepository.saveAll(items);
        return revision;
    }

    /**
     * 직접 수정으로 바뀐 좌석·필드의 대기 신고를 SUPERSEDED로 닫는다. 오래된 신고가 남아 있다가
     * 새 신고 한 건과 합쳐져 방금 한 수정을 덮어쓰는 일을 막는다. 좌석표당 대기 신고를 한 번만 읽는다.
     */
    private void supersedePending(Long seatMapId, List<AppliedChange> changes) {
        Set<String> touched = new HashSet<>();
        for (AppliedChange c : changes) {
            touched.add(c.uid() + "|" + c.field());
        }
        List<SeatCorrection> stale = correctionRepository.findBySeatMapLayout_IdAndStatus(seatMapId, CorrectionStatus.PENDING)
                .stream().filter(c -> touched.contains(c.getTargetSeatUid() + "|" + c.getTargetField())).toList();
        if (stale.isEmpty()) {
            return;
        }
        stale.forEach(SeatCorrection::markSuperseded);
        correctionRepository.saveAll(stale);
    }

    private SeatMapLayout lockLayout(Long seatMapId) {
        return layoutRepository.findForUpdateById(seatMapId)
                .orElseThrow(() -> new NotFoundException(SeatMapService.SEATMAP_NOT_FOUND_MESSAGE));
    }

    private static void requireAdmin(boolean admin) {
        if (!admin) {
            throw new ForbiddenException(ADMIN_ONLY_MESSAGE);
        }
    }

    private static SeatMapException versionConflict() {
        return new SeatMapException(HttpStatus.CONFLICT, "VERSION_CONFLICT", VERSION_CONFLICT_MESSAGE);
    }

    /** 토큰은 유효하나 사용자가 없는 경우 -> 401 (spring-boot-conventions 예외 조항). */
    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new InsufficientAuthenticationException("인증된 사용자를 찾을 수 없습니다."));
    }

    private static void requireDistinctLabelChanges(List<SeatEditChange> changes) {
        Set<String> seen = new HashSet<>();
        for (SeatEditChange change : changes) {
            if (!change.field().isEditableLabel()) {
                throw new FieldValidationException("changes", LABEL_ONLY_MESSAGE);
            }
            if (!seen.add(change.uid() + "|" + change.field())) {
                throw new SeatMapException(HttpStatus.UNPROCESSABLE_ENTITY, "DUPLICATE_CHANGE", DUPLICATE_CHANGE_MESSAGE);
            }
        }
    }

    private static Map<String, Integer> indexByUid(List<SeatMapSeat> seats) {
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < seats.size(); i++) {
            index.putIfAbsent(seats.get(i).uid(), i);
        }
        return index;
    }

    private static int labelOf(SeatMapSeat seat, SeatMapField field) {
        return field == SeatMapField.ROW_LABEL ? seat.row() : seat.col();
    }

    private static SeatMapSeat withLabel(SeatMapSeat seat, SeatMapField field, int value) {
        return field == SeatMapField.ROW_LABEL
                ? new SeatMapSeat(seat.uid(), value, seat.col(), seat.x(), seat.y(), seat.w(), seat.h(), seat.section())
                : new SeatMapSeat(seat.uid(), seat.row(), value, seat.x(), seat.y(), seat.w(), seat.h(), seat.section());
    }

    private static void requireNoDuplicateNumber(List<SeatMapSeat> finalSeats, List<AppliedChange> changes) {
        Map<String, Integer> counts = numberCounts(finalSeats);
        for (AppliedChange change : changes) {
            if (counts.get(numberKey(finalSeats.get(change.index()))) > 1) {
                throw new SeatMapException(HttpStatus.UNPROCESSABLE_ENTITY, "DUPLICATE_SEAT_NUMBER",
                        DUPLICATE_SEAT_NUMBER_MESSAGE);
            }
        }
    }

    private static boolean hasDuplicateNumber(List<SeatMapSeat> finalSeats, AppliedChange change) {
        return numberCounts(finalSeats).get(numberKey(finalSeats.get(change.index()))) > 1;
    }

    /**
     * 최종 상태에서 (section,row,col)별 좌석 수. 바뀐 좌석이 관여한 중복만 거부하려고 호출자가 바뀐 좌석의 키만 확인한다
     * (수정과 무관하게 인식 단계부터 있던 중복 때문에 모든 수정이 막히지 않게 한다).
     */
    private static Map<String, Integer> numberCounts(List<SeatMapSeat> seats) {
        Map<String, Integer> counts = new HashMap<>();
        for (SeatMapSeat seat : seats) {
            counts.merge(numberKey(seat), 1, Integer::sum);
        }
        return counts;
    }

    private static String numberKey(SeatMapSeat seat) {
        return seat.section() + ":" + seat.row() + ":" + seat.col();
    }

    private String writeSeats(List<SeatMapSeat> seats) {
        try {
            return objectMapper.writeValueAsString(seats);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(SeatMapService.STORED_DATA_ERROR_MESSAGE);
        }
    }

    /** 저장된 seat_json 파싱. 깨져 있으면 내용은 로그에 남기지 않고 id만 남긴 뒤 일반 오류(500)로 응답한다. */
    private List<SeatMapSeat> readSeats(SeatMapLayout layout) {
        try {
            List<SeatMapSeat> seats = objectMapper.readValue(layout.getSeatJson(), SEAT_LIST);
            if (seats == null) {
                throw new IllegalStateException("seat_json is null");
            }
            return seats;
        } catch (IOException | RuntimeException e) {
            log.error("Corrupted seat_json for seatMap {}: {}", layout.getId(), e.getClass().getSimpleName());
            throw new IllegalStateException(SeatMapService.STORED_DATA_ERROR_MESSAGE);
        }
    }

    /** 실제로 바뀐 항목 (index는 좌석 목록 안의 위치). */
    private record AppliedChange(String uid, SeatMapField field, int before, int after, int index) {
    }
}
