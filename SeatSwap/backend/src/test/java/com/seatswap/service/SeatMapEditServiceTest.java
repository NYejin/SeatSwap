package com.seatswap.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatswap.domain.CorrectionStatus;
import com.seatswap.domain.SeatCorrection;
import com.seatswap.domain.SeatMapField;
import com.seatswap.domain.SeatMapLayout;
import com.seatswap.domain.SeatMapRevision;
import com.seatswap.domain.SeatMapRevisionAction;
import com.seatswap.domain.SeatMapRevisionItem;
import com.seatswap.domain.SeatMapStatus;
import com.seatswap.dto.request.CorrectionRequest;
import com.seatswap.dto.request.SeatEditChange;
import com.seatswap.dto.request.SeatEditRequest;
import com.seatswap.dto.response.CorrectionResponse;
import com.seatswap.dto.response.PageResponse;
import com.seatswap.dto.response.SeatMapResponse;
import com.seatswap.dto.response.SeatMapRevisionDetailResponse;
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
import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static com.seatswap.service.PerformanceFixtures.user;
import static com.seatswap.service.PerformanceFixtures.venue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 좌석표 수정·정정 신고·수정 로그 조회 (DB 없음: 저장소는 메모리 페이크). */
class SeatMapEditServiceTest {

    private final SeatMapLayoutRepository layoutRepository = mock(SeatMapLayoutRepository.class);
    private final SeatCorrectionRepository correctionRepository = mock(SeatCorrectionRepository.class);
    private final SeatMapRevisionRepository revisionRepository = mock(SeatMapRevisionRepository.class);
    private final SeatMapRevisionItemRepository itemRepository = mock(SeatMapRevisionItemRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final SeatMapService seatMapService = mock(SeatMapService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final List<SeatCorrection> corrections = new ArrayList<>();
    private final List<SeatMapRevision> revisions = new ArrayList<>();
    private final List<SeatMapRevisionItem> items = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(100);

    private SeatMapEditService service;
    private SeatMapLayout layout;

    /** 구획 1: (1,1)(1,2)(2,1) / 구획 2: (1,1). x/y 좌표는 수정되면 안 되는 값. */
    private static final List<SeatMapSeat> SEATS = List.of(
            new SeatMapSeat("s0001", 1, 1, 70, 40, 18, 18, 1),
            new SeatMapSeat("s0002", 1, 2, 90, 40, 18, 18, 1),
            new SeatMapSeat("s0003", 2, 1, 70, 60, 18, 18, 1),
            new SeatMapSeat("s0004", 1, 1, 70, 200, 18, 18, 2));

    @BeforeEach
    void setUp() throws Exception {
        service = newService(2);
        layout = newLayout(SeatMapStatus.DRAFT, SEATS);
        for (long id = 1; id <= 5; id++) {
            when(userRepository.findById(id)).thenReturn(Optional.of(user(id, "u" + id)));
        }
        when(layoutRepository.findForUpdateById(55L)).thenReturn(Optional.of(layout));
        when(layoutRepository.existsById(55L)).thenReturn(true);
        // 저장하면 @Version이 올라간다 (실제 JPA 동작을 흉내)
        when(layoutRepository.saveAndFlush(any(SeatMapLayout.class))).thenAnswer(inv -> {
            SeatMapLayout l = inv.getArgument(0);
            ReflectionTestUtils.setField(l, "version", l.getVersion() + 1);
            return l;
        });
        when(revisionRepository.save(any(SeatMapRevision.class))).thenAnswer(inv -> {
            SeatMapRevision r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "id", ids.incrementAndGet());
            revisions.add(r);
            return r;
        });
        when(itemRepository.saveAll(any())).thenAnswer(inv -> {
            Iterable<SeatMapRevisionItem> saved = inv.getArgument(0);
            saved.forEach(items::add);
            return List.copyOf(items);
        });
        when(correctionRepository.saveAndFlush(any(SeatCorrection.class))).thenAnswer(inv -> {
            SeatCorrection c = inv.getArgument(0);
            ReflectionTestUtils.setField(c, "id", ids.incrementAndGet());
            corrections.add(c);
            return c;
        });
        when(correctionRepository.existsBySeatMapLayout_IdAndTargetSeatUidAndTargetFieldAndReporter_IdAndNormalizedValueAndStatus(
                anyLong(), any(), any(), anyLong(), any(), any())).thenAnswer(inv -> corrections.stream().anyMatch(c ->
                c.getTargetSeatUid().equals(inv.getArgument(1)) && c.getTargetField() == inv.getArgument(2)
                        && c.getReporter().getId().equals(inv.getArgument(3))
                        && c.getNormalizedValue().equals(inv.getArgument(4))
                        && c.getStatus() == inv.getArgument(5)));
        when(correctionRepository.findBySeatMapLayout_IdAndStatus(anyLong(), any())).thenAnswer(inv ->
                corrections.stream().filter(c -> c.getStatus() == inv.getArgument(1)).toList());
        when(correctionRepository.findBySeatMapLayout_IdAndTargetSeatUidAndTargetFieldAndStatusOrderByIdAsc(
                anyLong(), any(), any(), any())).thenAnswer(inv -> corrections.stream().filter(c ->
                c.getTargetSeatUid().equals(inv.getArgument(1)) && c.getTargetField() == inv.getArgument(2)
                        && c.getStatus() == inv.getArgument(3)).toList());
        when(seatMapService.canDeleteLayout(any(), any(), any(Boolean.class))).thenReturn(false);
    }

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    private SeatMapEditService newService(int threshold) {
        return newService(threshold, 200, 30, 50);
    }

    private SeatMapEditService newService(int threshold, int editDaily, int correctionDaily, int pendingLimit) {
        return new SeatMapEditService(layoutRepository, correctionRepository, revisionRepository, itemRepository,
                userRepository, objectMapper, seatMapService, CLOCK, threshold, editDaily, correctionDaily, pendingLimit);
    }

    private SeatMapLayout newLayout(SeatMapStatus status, List<SeatMapSeat> seats) throws Exception {
        SeatMapLayout l = SeatMapLayout.createDraft(venue(10L, "KSPO DOME"), "A구역",
                objectMapper.writeValueAsString(seats), seats.size(), "OCR_DONE", 700, 400, user(9L, "owner"));
        ReflectionTestUtils.setField(l, "id", 55L);
        ReflectionTestUtils.setField(l, "status", status);
        return l;
    }

    private static SeatEditRequest edit(int expectedVersion, SeatEditChange... changes) {
        return new SeatEditRequest(expectedVersion, "실제 좌석번호와 달라요", List.of(changes));
    }

    private static SeatEditChange row(String uid, int value) {
        return new SeatEditChange(uid, SeatMapField.ROW_LABEL, value);
    }

    private static SeatEditChange col(String uid, int value) {
        return new SeatEditChange(uid, SeatMapField.COL_LABEL, value);
    }

    private static CorrectionRequest report(String uid, SeatMapField field, int value) {
        return new CorrectionRequest(uid, field, value, null);
    }

    private static void assertSeatMapError(Throwable e, HttpStatus status, String code) {
        assertThat(e).isInstanceOfSatisfying(SeatMapException.class, ex -> {
            assertThat(ex.getStatus()).isEqualTo(status);
            assertThat(ex.getCode()).isEqualTo(code);
        });
    }

    private List<SeatMapSeat> storedSeats() throws Exception {
        return List.of(objectMapper.readValue(layout.getSeatJson(), SeatMapSeat[].class));
    }

    // ---- 직접 수정: 권한 매트릭스 ----

    @ParameterizedTest
    @CsvSource({
            "DRAFT,    false, USER_EDIT",
            "DRAFT,    true,  USER_EDIT",
            "OFFICIAL, true,  ADMIN_EDIT",
    })
    void editPermissionAllowedCases(SeatMapStatus status, boolean admin, SeatMapRevisionAction expected) throws Exception {
        ReflectionTestUtils.setField(layout, "status", status);

        SeatMapResponse response = service.editSeats(55L, 1L, admin, edit(1, row("s0003", 3)));

        assertThat(revisions).hasSize(1);
        assertThat(revisions.get(0).getActionType()).isEqualTo(expected);
        assertThat(revisions.get(0).getLayoutStatus()).isEqualTo(status);
        assertThat(revisions.get(0).getActor().getId()).isEqualTo(1L);
        assertThat(response.version()).isEqualTo(2);
    }

    @Test
    void officialEditByNormalUserIs403WithFixedMessageAndWritesNothing() {
        ReflectionTestUtils.setField(layout, "status", SeatMapStatus.OFFICIAL);

        assertThatThrownBy(() -> service.editSeats(55L, 1L, false, edit(1, row("s0003", 3))))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        e -> assertThat(e.getMessage()).isEqualTo("정식 좌석표는 정정 신고로만 수정할 수 있어요."));
        verify(layoutRepository, never()).saveAndFlush(any());
        assertThat(revisions).isEmpty();
        assertThat(layout.getVersion()).isEqualTo(1);
    }

    @Test
    void editUnknownSeatMapIs404() {
        when(layoutRepository.findForUpdateById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.editSeats(404L, 1L, false, edit(1, row("s0003", 3))))
                .isInstanceOf(NotFoundException.class);
    }

    // ---- 직접 수정: 로그·반영 ----

    @Test
    void editWritesOneRevisionWithItemsAndKeepsCoordinatesAndSeatCount() throws Exception {
        SeatMapResponse response = service.editSeats(55L, 1L, false,
                edit(1, row("s0003", 3), col("s0002", 5), row("s0002", 1)));

        // 한 요청 = revision 1건 (revision_no = 새 version), 현재 값과 같은 항목(row s0002=1)은 로그에 남기지 않는다
        assertThat(revisions).hasSize(1);
        SeatMapRevision revision = revisions.get(0);
        assertThat(revision.getRevisionNo()).isEqualTo(2);
        assertThat(revision.getReason()).isEqualTo("실제 좌석번호와 달라요");
        assertThat(revision.getBeforeJson()).isNull();
        assertThat(items).hasSize(2);
        assertThat(items).extracting(SeatMapRevisionItem::getSeatUid, SeatMapRevisionItem::getField,
                        SeatMapRevisionItem::getBeforeValue, SeatMapRevisionItem::getAfterValue)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("s0003", SeatMapField.ROW_LABEL, "2", "3"),
                        org.assertj.core.groups.Tuple.tuple("s0002", SeatMapField.COL_LABEL, "2", "5"));
        assertThat(items).allSatisfy(i -> {
            assertThat(i.getRevision()).isSameAs(revision);
            assertThat(i.getCorrection()).isNull();
        });

        // seat_json 갱신, 좌표·구획·좌석 수 유지
        List<SeatMapSeat> stored = storedSeats();
        assertThat(stored).hasSize(4);
        assertThat(layout.getSeatCount()).isEqualTo(4);
        assertThat(stored.get(2)).isEqualTo(new SeatMapSeat("s0003", 3, 1, 70, 60, 18, 18, 1));
        assertThat(stored.get(1)).isEqualTo(new SeatMapSeat("s0002", 1, 5, 90, 40, 18, 18, 1));
        assertThat(stored.get(0)).isEqualTo(SEATS.get(0));
        assertThat(stored.get(3)).isEqualTo(SEATS.get(3));
        // 응답은 새 version과 수정된 좌석
        assertThat(response.version()).isEqualTo(2);
        assertThat(response.seats()).isEqualTo(stored);
        assertThat(response.imageWidth()).isEqualTo(700);
    }

    @Test
    void versionMismatchIs409VersionConflictAndWritesNothing() {
        assertThatThrownBy(() -> service.editSeats(55L, 1L, false, edit(7, row("s0003", 3))))
                .satisfies(e -> assertSeatMapError(e, HttpStatus.CONFLICT, "VERSION_CONFLICT"));
        verify(layoutRepository, never()).saveAndFlush(any());
        assertThat(revisions).isEmpty();
    }

    @Test
    void staleSecondEditAfterFirstIsRejected() throws Exception {
        service.editSeats(55L, 1L, false, edit(1, row("s0003", 3)));

        // 같은 expectedVersion(1)으로 다시 보내면 이미 version 2
        assertThatThrownBy(() -> service.editSeats(55L, 2L, false, edit(1, row("s0003", 4))))
                .satisfies(e -> assertSeatMapError(e, HttpStatus.CONFLICT, "VERSION_CONFLICT"));
        assertThat(revisions).hasSize(1);
        service.editSeats(55L, 2L, false, edit(2, row("s0003", 4)));
        assertThat(revisions).extracting(SeatMapRevision::getRevisionNo).containsExactly(2, 3);
    }

    @Test
    void concurrentVersionBumpAtFlushIsMappedToVersionConflict() {
        when(layoutRepository.saveAndFlush(any(SeatMapLayout.class)))
                .thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(SeatMapLayout.class, 55L));

        assertThatThrownBy(() -> service.editSeats(55L, 1L, false, edit(1, row("s0003", 3))))
                .satisfies(e -> assertSeatMapError(e, HttpStatus.CONFLICT, "VERSION_CONFLICT"));
        assertThat(revisions).isEmpty();
    }

    @Test
    void unknownUidIs422UnknownSeatWithoutPartialWrite() {
        assertThatThrownBy(() -> service.editSeats(55L, 1L, false, edit(1, row("s0003", 3), row("nope", 1))))
                .satisfies(e -> assertSeatMapError(e, HttpStatus.UNPROCESSABLE_ENTITY, "UNKNOWN_SEAT"));
        verify(layoutRepository, never()).saveAndFlush(any());
        assertThat(revisions).isEmpty();
    }

    @Test
    void duplicateSeatNumberInFinalStateIs422() {
        // s0003 (구획1, 행2, 열1) -> 행1이 되면 s0001 (구획1, 행1, 열1)과 겹친다
        assertThatThrownBy(() -> service.editSeats(55L, 1L, false, edit(1, row("s0003", 1))))
                .satisfies(e -> assertSeatMapError(e, HttpStatus.UNPROCESSABLE_ENTITY, "DUPLICATE_SEAT_NUMBER"));
        verify(layoutRepository, never()).saveAndFlush(any());
        assertThat(layout.getVersion()).isEqualTo(1);
    }

    @Test
    void sameNumberInAnotherSectionIsNotADuplicate() throws Exception {
        // 구획 2의 s0004를 (1,2)로 바꿔도 구획 1의 (1,2)와는 다른 좌석
        service.editSeats(55L, 1L, false, edit(1, col("s0004", 2)));

        assertThat(storedSeats().get(3).col()).isEqualTo(2);
    }

    @Test
    void swappingNumbersInOneRequestIsAllowedBecauseOnlyFinalStateCounts() throws Exception {
        service.editSeats(55L, 1L, false, edit(1, col("s0001", 2), col("s0002", 1)));

        assertThat(storedSeats()).extracting(SeatMapSeat::col).containsExactly(2, 1, 1, 1);
        assertThat(items).hasSize(2);
    }

    @Test
    void preexistingDuplicateElsewhereDoesNotBlockUnrelatedEdit() throws Exception {
        List<SeatMapSeat> withDuplicate = List.of(
                new SeatMapSeat("a", 1, 1, 10, 10, 5, 5, 1),
                new SeatMapSeat("b", 1, 1, 20, 10, 5, 5, 1),
                new SeatMapSeat("c", 2, 1, 30, 10, 5, 5, 1));
        layout = newLayout(SeatMapStatus.DRAFT, withDuplicate);
        when(layoutRepository.findForUpdateById(55L)).thenReturn(Optional.of(layout));

        service.editSeats(55L, 1L, false, edit(1, col("c", 9)));
        assertThat(storedSeats().get(2).col()).isEqualTo(9);
        // 이미 겹쳐 있는 (1,1)에 또 하나를 얹으면 거부
        assertThatThrownBy(() -> service.editSeats(55L, 1L, false, edit(2, row("c", 1), col("c", 1))))
                .satisfies(e -> assertSeatMapError(e, HttpStatus.UNPROCESSABLE_ENTITY, "DUPLICATE_SEAT_NUMBER"));
        // 겹친 좌석 하나를 옮겨 중복을 풀어 주는 수정은 허용
        service.editSeats(55L, 1L, false, edit(2, col("a", 2)));
        assertThat(storedSeats().get(0).col()).isEqualTo(2);
    }

    @Test
    void allUnchangedIs422NoChangeAndNoVersionBump() {
        assertThatThrownBy(() -> service.editSeats(55L, 1L, false, edit(1, row("s0001", 1))))
                .satisfies(e -> assertSeatMapError(e, HttpStatus.UNPROCESSABLE_ENTITY, "NO_CHANGE"));
        assertThat(layout.getVersion()).isEqualTo(1);
    }

    @Test
    void sameSeatFieldTwiceIs422DuplicateChange() {
        assertThatThrownBy(() -> service.editSeats(55L, 1L, false, edit(1, row("s0003", 3), row("s0003", 4))))
                .satisfies(e -> assertSeatMapError(e, HttpStatus.UNPROCESSABLE_ENTITY, "DUPLICATE_CHANGE"));
    }

    @Test
    void nonLabelFieldIsRejectedAs400() {
        SeatEditChange x = new SeatEditChange("s0001", SeatMapField.X, 5);
        assertThatThrownBy(() -> service.editSeats(55L, 1L, false, edit(1, x)))
                .isInstanceOf(FieldValidationException.class);
        assertThat(revisions).isEmpty();
    }

    // ---- 정정 신고 ----

    @Test
    void correctionOnDraftIs409() {
        assertThatThrownBy(() -> service.reportCorrection(55L, 1L, false, report("s0003", SeatMapField.ROW_LABEL, 3)))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getMessage()).isEqualTo("임시 좌석표는 직접 수정할 수 있어요."));
        assertThat(corrections).isEmpty();
    }

    private void makeOfficial() {
        ReflectionTestUtils.setField(layout, "status", SeatMapStatus.OFFICIAL);
    }

    @Test
    void firstCorrectionStaysPendingWithoutChangingTheLayout() {
        makeOfficial();

        CorrectionResponse response = service.reportCorrection(55L, 1L, false, report("s0003", SeatMapField.ROW_LABEL, 3));

        assertThat(response.status()).isEqualTo(CorrectionStatus.PENDING);
        assertThat(response.correctionId()).isEqualTo(corrections.get(0).getId());
        SeatCorrection saved = corrections.get(0);
        assertThat(saved.getTargetSeatUid()).isEqualTo("s0003");
        assertThat(saved.getTargetField()).isEqualTo(SeatMapField.ROW_LABEL);
        assertThat(saved.getNormalizedValue()).isEqualTo("3");
        assertThat(saved.getOriginalLabel()).isEqualTo("2");
        assertThat(saved.getCorrectedLabel()).isEqualTo("3");
        assertThat(saved.getLayoutVersion()).isEqualTo(1);
        assertThat(saved.getStatus()).isEqualTo(CorrectionStatus.PENDING);
        assertThat(layout.getVersion()).isEqualTo(1);
        assertThat(revisions).isEmpty();
    }

    @Test
    void secondDistinctReporterAutoAppliesAndSupersedesOtherPending() throws Exception {
        makeOfficial();
        // 같은 좌석·필드에 다른 값(4)을 제안한 신고가 이미 대기 중
        service.reportCorrection(55L, 3L, false, report("s0003", SeatMapField.ROW_LABEL, 4));
        CorrectionResponse first = service.reportCorrection(55L, 1L, false, report("s0003", SeatMapField.ROW_LABEL, 3));
        assertThat(first.status()).isEqualTo(CorrectionStatus.PENDING);

        CorrectionResponse second = service.reportCorrection(55L, 2L, false, report("s0003", SeatMapField.ROW_LABEL, 3));

        assertThat(second.status()).isEqualTo(CorrectionStatus.APPLIED);
        // 좌석표 반영 + revision (actor null = 시스템)
        assertThat(storedSeats().get(2).row()).isEqualTo(3);
        assertThat(layout.getVersion()).isEqualTo(2);
        assertThat(revisions).hasSize(1);
        SeatMapRevision revision = revisions.get(0);
        assertThat(revision.getActionType()).isEqualTo(SeatMapRevisionAction.CORRECTION_APPLIED);
        assertThat(revision.getActor()).isNull();
        assertThat(revision.getRevisionNo()).isEqualTo(2);
        assertThat(revision.getLayoutStatus()).isEqualTo(SeatMapStatus.OFFICIAL);
        // item은 정정 신고와 연결된다
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getCorrection()).isSameAs(corrections.get(1));
        assertThat(items.get(0).getBeforeValue()).isEqualTo("2");
        assertThat(items.get(0).getAfterValue()).isEqualTo("3");
        // 동의한 두 신고는 APPLIED(+applied_revision), 다른 값 신고는 SUPERSEDED
        SeatCorrection otherValue = corrections.get(0);
        SeatCorrection a = corrections.get(1);
        SeatCorrection b = corrections.get(2);
        assertThat(a.getStatus()).isEqualTo(CorrectionStatus.APPLIED);
        assertThat(b.getStatus()).isEqualTo(CorrectionStatus.APPLIED);
        assertThat(a.getAppliedRevision()).isSameAs(revision);
        assertThat(b.getAppliedRevision()).isSameAs(revision);
        assertThat(otherValue.getStatus()).isEqualTo(CorrectionStatus.SUPERSEDED);
        assertThat(otherValue.getAppliedRevision()).isNull();
    }

    @Test
    void correctionsOnDifferentSeatsOrValuesDoNotCountTogether() {
        makeOfficial();
        service.reportCorrection(55L, 1L, false, report("s0003", SeatMapField.ROW_LABEL, 3));
        assertThat(service.reportCorrection(55L, 2L, false, report("s0003", SeatMapField.ROW_LABEL, 4)).status())
                .isEqualTo(CorrectionStatus.PENDING);
        assertThat(service.reportCorrection(55L, 2L, false, report("s0002", SeatMapField.ROW_LABEL, 3)).status())
                .isEqualTo(CorrectionStatus.PENDING);
        assertThat(service.reportCorrection(55L, 4L, false, report("s0003", SeatMapField.COL_LABEL, 3)).status())
                .isEqualTo(CorrectionStatus.PENDING);
        assertThat(revisions).isEmpty();
    }

    @Test
    void sameUserSameCorrectionTwiceIs409AndNeverCountsTwice() {
        makeOfficial();
        service.reportCorrection(55L, 1L, false, report("s0003", SeatMapField.ROW_LABEL, 3));

        assertThatThrownBy(() -> service.reportCorrection(55L, 1L, false, report("s0003", SeatMapField.ROW_LABEL, 3)))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getMessage()).isEqualTo("이미 같은 내용으로 신고했어요."));
        assertThat(corrections).hasSize(1);
        assertThat(revisions).isEmpty();
        assertThat(layout.getVersion()).isEqualTo(1);
    }

    @Test
    void duplicateSeatNumberKeepsCorrectionPendingAndDoesNotApply() throws Exception {
        makeOfficial();
        // s0003 -> 행1 이면 s0001과 겹친다. 임계값을 채워도 반영하지 않는다
        service.reportCorrection(55L, 1L, false, report("s0003", SeatMapField.ROW_LABEL, 1));
        CorrectionResponse second = service.reportCorrection(55L, 2L, false, report("s0003", SeatMapField.ROW_LABEL, 1));

        assertThat(second.status()).isEqualTo(CorrectionStatus.PENDING);
        assertThat(corrections).extracting(SeatCorrection::getStatus)
                .containsOnly(CorrectionStatus.PENDING);
        assertThat(layout.getVersion()).isEqualTo(1);
        assertThat(storedSeats().get(2).row()).isEqualTo(2);
        assertThat(revisions).isEmpty();
        // 관리자가 구분할 수 있게 동의한 신고 행에 보류 사유를 남기고 reviewed_*는 건드리지 않는다
        assertThat(corrections).allSatisfy(c -> {
            assertThat(c.getReviewNote()).isEqualTo("중복 번호로 자동 반영 보류");
            assertThat(c.getReviewedBy()).isNull();
            assertThat(c.getReviewedAt()).isNull();
        });
    }

    @Test
    void thresholdIsConfigurable() {
        service = newService(3);
        makeOfficial();
        service.reportCorrection(55L, 1L, false, report("s0003", SeatMapField.ROW_LABEL, 3));
        assertThat(service.reportCorrection(55L, 2L, false, report("s0003", SeatMapField.ROW_LABEL, 3)).status())
                .isEqualTo(CorrectionStatus.PENDING);
        assertThat(service.reportCorrection(55L, 4L, false, report("s0003", SeatMapField.ROW_LABEL, 3)).status())
                .isEqualTo(CorrectionStatus.APPLIED);
    }

    @Test
    void correctionValidation() {
        makeOfficial();
        assertThatThrownBy(() -> service.reportCorrection(55L, 1L, false, report("nope", SeatMapField.ROW_LABEL, 3)))
                .satisfies(e -> assertSeatMapError(e, HttpStatus.UNPROCESSABLE_ENTITY, "UNKNOWN_SEAT"));
        assertThatThrownBy(() -> service.reportCorrection(55L, 1L, false, report("s0003", SeatMapField.ROW_LABEL, 2)))
                .satisfies(e -> assertSeatMapError(e, HttpStatus.UNPROCESSABLE_ENTITY, "NO_CHANGE"));
        assertThatThrownBy(() -> service.reportCorrection(55L, 1L, false, report("s0003", SeatMapField.X, 2)))
                .isInstanceOf(FieldValidationException.class);
        when(layoutRepository.findForUpdateById(404L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reportCorrection(404L, 1L, false, report("s0003", SeatMapField.ROW_LABEL, 3)))
                .isInstanceOf(NotFoundException.class);
        assertThat(corrections).isEmpty();
    }

    // ---- 수정 로그 조회 ----

    @Test
    void revisionListIsAdminOnly() {
        SeatMapRevisionSummaryResponse row = new SeatMapRevisionSummaryResponse(101L, 2,
                SeatMapRevisionAction.USER_EDIT, SeatMapStatus.DRAFT, 1L, "사유", LocalDateTime.of(2026, 10, 7, 12, 0), 3);
        when(revisionRepository.findSummariesBySeatMapId(any(Long.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1));

        assertThatThrownBy(() -> service.listRevisions(55L, false, 0, 20))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        e -> assertThat(e.getMessage()).isEqualTo("관리자만 볼 수 있어요."));
        PageResponse<SeatMapRevisionSummaryResponse> page = service.listRevisions(55L, true, 0, 20);

        assertThat(page.content()).containsExactly(row);
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.page()).isZero();
    }

    @Test
    void revisionListClampsPagingAndUnknownSeatMapIs404() {
        when(revisionRepository.findSummariesBySeatMapId(any(Long.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

        service.listRevisions(55L, true, -3, 100000);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(revisionRepository).findSummariesBySeatMapId(any(Long.class), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isZero();
        assertThat(captor.getValue().getPageSize()).isEqualTo(100);

        when(layoutRepository.existsById(404L)).thenReturn(false);
        assertThatThrownBy(() -> service.listRevisions(404L, true, 0, 20)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void revisionDetailIsAdminOnlyAndIncludesItems() throws Exception {
        service.editSeats(55L, 1L, false, edit(1, row("s0003", 3)));
        SeatMapRevision revision = revisions.get(0);
        when(revisionRepository.findByIdAndSeatMapLayout_Id(revision.getId(), 55L)).thenReturn(Optional.of(revision));
        when(itemRepository.findByRevision_IdOrderByIdAsc(revision.getId())).thenReturn(items);

        assertThatThrownBy(() -> service.getRevision(55L, revision.getId(), false))
                .isInstanceOf(ForbiddenException.class);
        SeatMapRevisionDetailResponse detail = service.getRevision(55L, revision.getId(), true);

        assertThat(detail.revisionNo()).isEqualTo(2);
        assertThat(detail.actorId()).isEqualTo(1L);
        assertThat(detail.items()).hasSize(1);
        assertThat(detail.items().get(0).seatUid()).isEqualTo("s0003");
        assertThat(detail.items().get(0).beforeValue()).isEqualTo("2");
        assertThat(detail.items().get(0).afterValue()).isEqualTo("3");
        assertThat(detail.items().get(0).correctionId()).isNull();

        when(revisionRepository.findByIdAndSeatMapLayout_Id(999L, 55L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getRevision(55L, 999L, true)).isInstanceOf(NotFoundException.class);
    }

    // ---- append-only ----

    @Test
    void revisionEntitiesAreImmutableWithoutSetters() {
        for (Class<?> type : List.of(SeatMapRevision.class, SeatMapRevisionItem.class)) {
            assertThat(type.isAnnotationPresent(Immutable.class)).as(type.getSimpleName() + " @Immutable").isTrue();
            for (Method method : type.getDeclaredMethods()) {
                assertThat(method.getName()).as(type.getSimpleName() + "." + method.getName()).doesNotStartWith("set");
            }
            // 모든 필드는 컬럼이 update 대상이 아니다 (id는 생성 값)
            for (var field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getName().equals("id")) {
                    continue;
                }
                var column = field.getAnnotation(jakarta.persistence.Column.class);
                var join = field.getAnnotation(jakarta.persistence.JoinColumn.class);
                boolean updatable = column != null ? column.updatable() : join == null || join.updatable();
                assertThat(updatable).as(type.getSimpleName() + "." + field.getName() + " updatable").isFalse();
            }
        }
    }

    @Test
    void layoutLabelUpdateHealsStaleSeatCountInsteadOfFailing() throws Exception {
        ReflectionTestUtils.setField(layout, "seatCount", 0); // 과거 행처럼 어긋난 값

        service.editSeats(55L, 1L, false, edit(1, row("s0003", 3)));

        assertThat(layout.getSeatCount()).isEqualTo(4);
        assertThatThrownBy(() -> layout.updateSeatLabels(" ", 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> layout.updateSeatLabels("[]", -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invisibleOnlyReasonIs400() {
        for (String reason : List.of("   ", "\u200B\u200B", "\u3164", "사유\n줄바꿈")) {
            assertThatThrownBy(() -> service.editSeats(55L, 1L, false,
                    new SeatEditRequest(1, reason, List.of(row("s0003", 3)))))
                    .isInstanceOf(FieldValidationException.class);
        }
        assertThat(revisions).isEmpty();
    }

    @Test
    void editDailyLimitAppliesToUsersButNotAdmins() {
        service = newService(2, 1, 30, 50);
        when(revisionRepository.countByActor_IdAndActionTypeAndCreatedAtAfter(
                anyLong(), any(SeatMapRevisionAction.class), any(LocalDateTime.class))).thenReturn(1L);

        assertThatThrownBy(() -> service.editSeats(55L, 1L, false, edit(1, row("s0003", 3))))
                .satisfies(e -> assertSeatMapError(e, HttpStatus.TOO_MANY_REQUESTS, "EDIT_LIMIT_REACHED"));
        assertThat(revisions).isEmpty();
        // 집계 기준은 24시간 전 (고정 시계 기준)
        verify(revisionRepository).countByActor_IdAndActionTypeAndCreatedAtAfter(1L, SeatMapRevisionAction.USER_EDIT,
                LocalDateTime.of(2026, 10, 6, 12, 0));
        service.editSeats(55L, 2L, true, edit(1, row("s0003", 3)));
        assertThat(revisions).hasSize(1);
    }

    // ---- 신고 중복 방지는 PENDING 한정 / 오래된 PENDING 무효화 / 한도 ----

    @Test
    void sameUserCanReportAgainAfterTheirReportWasClosed() {
        makeOfficial();
        service.reportCorrection(55L, 1L, false, report("s0003", SeatMapField.ROW_LABEL, 4));
        // 다른 두 사용자의 정정(3)이 먼저 반영되면 u1의 정정(4)은 SUPERSEDED로 종결된다
        service.reportCorrection(55L, 2L, false, report("s0003", SeatMapField.ROW_LABEL, 3));
        service.reportCorrection(55L, 3L, false, report("s0003", SeatMapField.ROW_LABEL, 3));
        assertThat(corrections.get(0).getStatus()).isEqualTo(CorrectionStatus.SUPERSEDED);

        // 종결 뒤에는 같은 사용자가 같은 정정을 다시 신고할 수 있다 (대기 중이면 409)
        CorrectionResponse again = service.reportCorrection(55L, 1L, false, report("s0003", SeatMapField.ROW_LABEL, 4));
        assertThat(again.status()).isEqualTo(CorrectionStatus.PENDING);
        assertThatThrownBy(() -> service.reportCorrection(55L, 1L, false, report("s0003", SeatMapField.ROW_LABEL, 4)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void adminEditSupersedesPendingCorrectionsSoOneNewReportCannotOverwriteIt() throws Exception {
        makeOfficial();
        service.reportCorrection(55L, 1L, false, report("s0003", SeatMapField.ROW_LABEL, 3));
        service.reportCorrection(55L, 1L, false, report("s0002", SeatMapField.COL_LABEL, 8)); // 다른 좌석: 그대로 대기
        assertThat(corrections).extracting(SeatCorrection::getStatus).containsOnly(CorrectionStatus.PENDING);

        // 관리자가 같은 좌석·필드를 다른 값(5)으로 직접 수정
        service.editSeats(55L, 4L, true, edit(1, row("s0003", 5)));

        assertThat(corrections.get(0).getStatus()).isEqualTo(CorrectionStatus.SUPERSEDED);
        assertThat(corrections.get(1).getStatus()).isEqualTo(CorrectionStatus.PENDING);
        // 새 신고 한 건은 옛 신고와 합쳐지지 않으므로 PENDING이고 관리자 수정이 유지된다
        CorrectionResponse fresh = service.reportCorrection(55L, 2L, false, report("s0003", SeatMapField.ROW_LABEL, 3));
        assertThat(fresh.status()).isEqualTo(CorrectionStatus.PENDING);
        assertThat(storedSeats().get(2).row()).isEqualTo(5);
    }

    @Test
    void draftEditDoesNotTouchCorrections() {
        service.editSeats(55L, 1L, false, edit(1, row("s0003", 3)));

        verify(correctionRepository, never()).findBySeatMapLayout_IdAndStatus(anyLong(), any());
    }

    @Test
    void thresholdBelowTwoIsForcedToTwo() {
        service = newService(1);
        makeOfficial();

        assertThat(service.reportCorrection(55L, 1L, false, report("s0003", SeatMapField.ROW_LABEL, 3)).status())
                .isEqualTo(CorrectionStatus.PENDING);
        assertThat(layout.getVersion()).isEqualTo(1);
        assertThat(service.reportCorrection(55L, 2L, false, report("s0003", SeatMapField.ROW_LABEL, 3)).status())
                .isEqualTo(CorrectionStatus.APPLIED);
    }

    @Test
    void correctionDailyLimitAppliesToUsersButNotAdmins() {
        service = newService(2, 200, 1, 50);
        makeOfficial();
        when(correctionRepository.countByReporter_IdAndCreatedAtAfter(anyLong(), any(LocalDateTime.class))).thenReturn(1L);

        assertThatThrownBy(() -> service.reportCorrection(55L, 1L, false, report("s0003", SeatMapField.ROW_LABEL, 3)))
                .satisfies(e -> assertSeatMapError(e, HttpStatus.TOO_MANY_REQUESTS, "CORRECTION_LIMIT_REACHED"));
        assertThat(corrections).isEmpty();
        assertThat(service.reportCorrection(55L, 4L, true, report("s0003", SeatMapField.ROW_LABEL, 3)).status())
                .isEqualTo(CorrectionStatus.PENDING);
    }

    @Test
    void pendingCorrectionLimitPerUser() {
        service = newService(2, 200, 30, 2);
        makeOfficial();
        when(correctionRepository.countByReporter_IdAndStatus(1L, CorrectionStatus.PENDING)).thenReturn(2L);

        assertThatThrownBy(() -> service.reportCorrection(55L, 1L, false, report("s0003", SeatMapField.ROW_LABEL, 3)))
                .satisfies(e -> assertSeatMapError(e, HttpStatus.TOO_MANY_REQUESTS, "PENDING_LIMIT_REACHED"));
        assertThat(corrections).isEmpty();
    }
}
