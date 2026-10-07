package com.seatswap.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatswap.domain.SeatMapLayout;
import com.seatswap.domain.SeatMapRevision;
import com.seatswap.domain.SeatMapRevisionAction;
import com.seatswap.domain.SeatMapStatus;
import com.seatswap.dto.response.SeatMapResponse;
import com.seatswap.dto.response.SeatMapSeat;
import com.seatswap.exception.ConflictException;
import com.seatswap.exception.FieldValidationException;
import com.seatswap.exception.ForbiddenException;
import com.seatswap.exception.GlobalExceptionHandler;
import com.seatswap.exception.NotFoundException;
import com.seatswap.exception.SeatMapException;
import com.seatswap.repository.SeatCorrectionRepository;
import com.seatswap.repository.SeatMapLayoutRepository;
import com.seatswap.repository.SeatMapRevisionRepository;
import com.seatswap.repository.TicketRepository;
import com.seatswap.repository.UserRepository;
import com.seatswap.repository.VenueRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static com.seatswap.service.PerformanceFixtures.uniqueViolation;
import static com.seatswap.service.PerformanceFixtures.user;
import static com.seatswap.service.PerformanceFixtures.venue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeatMapServiceTest {

    private final SeatMapLayoutRepository layoutRepository = mock(SeatMapLayoutRepository.class);
    private final VenueRepository venueRepository = mock(VenueRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final SeatMapRecognitionClient client = mock(SeatMapRecognitionClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SeatCorrectionRepository correctionRepository = mock(SeatCorrectionRepository.class);
    private final TicketRepository ticketRepository = mock(TicketRepository.class);
    private final SeatMapRevisionRepository revisionRepository = mock(SeatMapRevisionRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-07T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final SeatMapService service = newService(3);

    private SeatMapService newService(int maxConcurrent) {
        SeatMapService created = new SeatMapService(layoutRepository, correctionRepository, revisionRepository,
                venueRepository, userRepository, client, objectMapper, new SyncTransactionManager(),
                maxConcurrent, 20, clock, 20, 10);
        // TEMP-DRAFT-DELETE: 필드 주입된 의존성/플래그
        ReflectionTestUtils.setField(created, "ticketRepository", ticketRepository);
        ReflectionTestUtils.setField(created, "devDraftDeleteEnabled", true);
        return created;
    }

    /** 실제로 트랜잭션 동기화(isActualTransactionActive)를 켜는 최소 트랜잭션 매니저 (DB 없음). */
    static class SyncTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }

    private static final byte[] PNG_BYTES = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};

    private static final List<SeatMapSeat> SEATS = List.of(
            new SeatMapSeat("s0001", 1, 1, 70, 40, 18, 18), new SeatMapSeat("s0002", 1, 2, 90, 40, 18, 18));

    private static MockMultipartFile png() {
        return new MockMultipartFile("file", "a.png", "image/png", PNG_BYTES);
    }

    private void stubHappyPath() {
        when(venueRepository.findById(10L)).thenReturn(Optional.of(venue(10L, "KSPO DOME")));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, "nick")));
        when(client.recognize(any(), anyString(), anyString())).thenAnswer(inv -> {
            // 인식 호출(최대 60초)은 DB 트랜잭션 밖에서 해야 한다
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new SeatMapRecognitionClient.Recognition(
                    new SeatMapRecognitionClient.Recognition.Image(700, 400), SEATS);
        });
        when(layoutRepository.saveAndFlush(any(SeatMapLayout.class))).thenAnswer(inv -> {
            // 저장은 트랜잭션 안 (위 단언이 의미 있도록 매니저가 실제로 동기화를 켠다는 증거)
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            SeatMapLayout l = inv.getArgument(0);
            ReflectionTestUtils.setField(l, "id", 55L);
            return l;
        });
    }

    @Test
    void createDraftSavesRecognizedSeatsAsDraft() throws Exception {
        stubHappyPath();

        SeatMapResponse response = service.createDraft(10L, 1L, false, png(), "  A구역 ", null);

        assertThat(response.id()).isEqualTo(55L);
        assertThat(response.venueId()).isEqualTo(10L);
        assertThat(response.status()).isEqualTo(SeatMapStatus.DRAFT);
        assertThat(response.version()).isEqualTo(1);
        assertThat(response.zoneName()).isEqualTo("A구역");
        assertThat(response.imageWidth()).isEqualTo(700);
        assertThat(response.seats()).isEqualTo(SEATS);
        assertThat(response.canDelete()).isTrue();
        // aisleMode 기본값 continue, 업로드 content-type 그대로 중계
        verify(client).recognize(eq(PNG_BYTES), eq("image/png"), eq("continue"));

        ArgumentCaptor<SeatMapLayout> captor = ArgumentCaptor.forClass(SeatMapLayout.class);
        verify(layoutRepository).saveAndFlush(captor.capture());
        SeatMapLayout saved = captor.getValue();
        assertThat(saved.getCreatedBy().getId()).isEqualTo(1L);
        assertThat(saved.getImageHeight()).isEqualTo(400);
        assertThat(objectMapper.readTree(saved.getSeatJson()).get(0).fieldNames())
                .toIterable().containsExactlyInAnyOrder("uid", "row", "col", "x", "y", "w", "h", "section");
    }

    @Test
    void createDraftStoresSeatCountAndRecognizedRevision() {
        stubHappyPath();

        service.createDraft(10L, 1L, false, png(), "A구역", null);

        ArgumentCaptor<SeatMapLayout> layoutCaptor = ArgumentCaptor.forClass(SeatMapLayout.class);
        verify(layoutRepository).saveAndFlush(layoutCaptor.capture());
        assertThat(layoutCaptor.getValue().getSeatCount()).isEqualTo(2);

        ArgumentCaptor<SeatMapRevision> revisionCaptor = ArgumentCaptor.forClass(SeatMapRevision.class);
        verify(revisionRepository).save(revisionCaptor.capture());
        SeatMapRevision revision = revisionCaptor.getValue();
        assertThat(revision.getActionType()).isEqualTo(SeatMapRevisionAction.RECOGNIZED);
        assertThat(revision.getRevisionNo()).isEqualTo(1);
        assertThat(revision.getLayoutStatus()).isEqualTo(SeatMapStatus.DRAFT);
        assertThat(revision.getActor().getId()).isEqualTo(1L);
        assertThat(revision.getReason()).isEqualTo("최초 인식");
        // 좌석 JSON 전체는 로그에 복사하지 않는다
        assertThat(revision.getBeforeJson()).isNull();
        assertThat(revision.getAfterJson()).isNull();
    }

    @Test
    void createDraftWithoutZoneChecksNullZoneDraft() {
        stubHappyPath();
        service.createDraft(10L, 1L, false, png(), "   ", "skip");

        verify(layoutRepository, atLeastOnce()).findDraftIdWithoutZone(10L);
        verify(layoutRepository, never()).findDraftIdByZone(any(), any());
        verify(client).recognize(any(), anyString(), eq("skip"));
    }

    @Test
    void duplicateDraftIs409WithExistingIdAndSkipsRecognition() {
        when(venueRepository.findById(10L)).thenReturn(Optional.of(venue(10L, "KSPO DOME")));
        when(layoutRepository.findDraftIdByZone(10L, "A구역")).thenReturn(Optional.of(77L));

        assertThatThrownBy(() -> service.createDraft(10L, 1L, false, png(), "A구역", null))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.getDetails()).containsEntry("seatMapId", 77L);
                    assertThat(e.getMessage()).isEqualTo(SeatMapService.DUPLICATE_DRAFT_MESSAGE);
                });
        verify(client, never()).recognize(any(), anyString(), anyString());
        verify(layoutRepository, never()).saveAndFlush(any());
    }

    @Test
    void draftRaceIs409WithWinnerId() {
        stubHappyPath();
        when(layoutRepository.findDraftIdByZone(10L, "A구역"))
                .thenReturn(Optional.empty())    // 사전 확인
                .thenReturn(Optional.empty())    // insert 트랜잭션 안 확인
                .thenReturn(Optional.of(88L));   // 레이스 후 재조회
        when(layoutRepository.saveAndFlush(any(SeatMapLayout.class)))
                .thenThrow(uniqueViolation("seat_map_layout", "uk_seat_map_layout_draft_key"));

        assertThatThrownBy(() -> service.createDraft(10L, 1L, false, png(), "A구역", null))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getDetails()).containsEntry("seatMapId", 88L));
    }

    @Test
    void draftRaceWithVanishedWinnerIs409WithoutSeatMapId() {
        stubHappyPath();
        when(layoutRepository.findDraftIdByZone(10L, "A구역")).thenReturn(Optional.empty());
        when(layoutRepository.saveAndFlush(any(SeatMapLayout.class)))
                .thenThrow(uniqueViolation("seat_map_layout", "uk_seat_map_layout_draft_key"));

        assertThatThrownBy(() -> service.createDraft(10L, 1L, false, png(), "A구역", null))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo(GlobalExceptionHandler.DATA_CONFLICT_MESSAGE);
                    assertThat(e.getDetails()).isEmpty();
                });
    }

    @Test
    void signatureMismatchIs415BeforeAnyCall() {
        MockMultipartFile fake = new MockMultipartFile("file", "a.png", "image/png", new byte[]{1, 2, 3, 4});
        assertThatThrownBy(() -> service.createDraft(10L, 1L, false, fake, null, null))
                .isInstanceOfSatisfying(SeatMapException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
                    assertThat(e.getCode()).isEqualTo("UNSUPPORTED_IMAGE");
                });
        verify(client, never()).recognize(any(), anyString(), anyString());
        verify(venueRepository, never()).findById(any());
    }

    @Test
    void imageSignaturesAreDetected() {
        assertThat(SeatMapService.detectImageType(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0})).isEqualTo("image/jpeg");
        byte[] webp = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
        assertThat(SeatMapService.detectImageType(webp)).isEqualTo("image/webp");
        assertThat(SeatMapService.detectImageType(new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'})).isNull();
        assertThat(SeatMapService.detectImageType(new byte[0])).isNull();
    }

    @Test
    void sameUserCannotRecognizeTwiceConcurrently() {
        stubHappyPath();
        when(client.recognize(any(), anyString(), anyString())).thenAnswer(inv -> {
            // 인식 중에 같은 사용자가 다시 요청
            assertThatThrownBy(() -> service.createDraft(10L, 1L, false, png(), "B구역", null))
                    .isInstanceOfSatisfying(SeatMapException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                        assertThat(e.getCode()).isEqualTo("RATE_LIMITED");
                    });
            return new SeatMapRecognitionClient.Recognition(
                    new SeatMapRecognitionClient.Recognition.Image(700, 400), SEATS);
        });

        assertThat(service.createDraft(10L, 1L, false, png(), "A구역", null).id()).isEqualTo(55L);
        // 끝난 뒤에는 같은 사용자가 다시 할 수 있다
        assertThat(service.createDraft(10L, 1L, false, png(), "C구역", null)).isNotNull();
    }

    @Test
    void globalLimitReturns503BusyAndReleasesPermitsEvenOnFailure() {
        SeatMapService limited = newService(1);
        stubHappyPath();
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, "other")));
        when(client.recognize(any(), anyString(), anyString())).thenAnswer(inv -> {
            // 유일한 자리를 다른 사용자가 쓰는 중 -> 대기(20ms) 후 BUSY
            assertThatThrownBy(() -> limited.createDraft(10L, 2L, false, png(), "B구역", null))
                    .isInstanceOfSatisfying(SeatMapException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                        assertThat(e.getCode()).isEqualTo("BUSY");
                    });
            throw new IllegalStateException("boom");
        }).thenReturn(new SeatMapRecognitionClient.Recognition(
                new SeatMapRecognitionClient.Recognition.Image(700, 400), SEATS));

        assertThatThrownBy(() -> limited.createDraft(10L, 1L, false, png(), "A구역", null))
                .isInstanceOf(IllegalStateException.class);
        // 예외가 났어도 자리와 사용자 표시가 풀려 있어야 한다
        assertThat(limited.createDraft(10L, 1L, false, png(), "A구역", null)).isNotNull();
    }

    @Test
    void unrelatedIntegrityViolationIsRethrown() {
        stubHappyPath();
        var other = uniqueViolation("seat_map_layout", "something_else");
        when(layoutRepository.saveAndFlush(any(SeatMapLayout.class))).thenThrow(other);

        assertThatThrownBy(() -> service.createDraft(10L, 1L, false, png(), null, null)).isSameAs(other);
    }

    @Test
    void unknownVenueIs404() {
        when(venueRepository.findById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createDraft(10L, 1L, false, png(), null, null))
                .isInstanceOf(NotFoundException.class);
        verify(client, never()).recognize(any(), anyString(), anyString());
    }

    @Test
    void missingUserIs401() {
        stubHappyPath();
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createDraft(10L, 1L, false, png(), null, null))
                .isInstanceOf(InsufficientAuthenticationException.class);
    }

    @Test
    void fileValidation() {
        assertThatThrownBy(() -> service.createDraft(10L, 1L, false,
                new MockMultipartFile("file", "a.png", "image/png", new byte[0]), null, null))
                .isInstanceOfSatisfying(SeatMapException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.createDraft(10L, 1L, false,
                new MockMultipartFile("file", "a.txt", "text/plain", new byte[]{1}), null, null))
                .isInstanceOfSatisfying(SeatMapException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
                    assertThat(e.getCode()).isEqualTo("UNSUPPORTED_IMAGE");
                });
        assertThatThrownBy(() -> service.createDraft(10L, 1L, false,
                new MockMultipartFile("file", "a.png", "image/png", new byte[(int) SeatMapService.MAX_IMAGE_BYTES + 1]),
                null, null))
                .isInstanceOfSatisfying(SeatMapException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
                    assertThat(e.getCode()).isEqualTo("IMAGE_TOO_LARGE");
                });
        verify(client, never()).recognize(any(), anyString(), anyString());
    }

    @Test
    void invalidAisleModeAndLongZoneNameAre400() {
        assertThatThrownBy(() -> service.createDraft(10L, 1L, false, png(), null, "weird"))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getField()).isEqualTo("aisleMode"));
        assertThatThrownBy(() -> service.createDraft(10L, 1L, false, png(), "가".repeat(101), null))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getField()).isEqualTo("zoneName"));
    }

    @Test
    void recognitionErrorsPropagateUnchanged() {
        when(venueRepository.findById(10L)).thenReturn(Optional.of(venue(10L, "KSPO DOME")));
        SeatMapException upstream = new SeatMapException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_SEATS_DETECTED", "없음");
        when(client.recognize(any(), anyString(), anyString())).thenThrow(upstream);

        assertThatThrownBy(() -> service.createDraft(10L, 1L, false, png(), null, null)).isSameAs(upstream);
        verify(layoutRepository, never()).saveAndFlush(any());
    }

    @Test
    void getParsesSeatJson() {
        SeatMapLayout layout = layout("[{\"uid\":\"s0001\",\"row\":1,\"col\":2,\"x\":3,\"y\":4,\"w\":5,\"h\":6}]");
        when(layoutRepository.findDetailById(55L)).thenReturn(Optional.of(layout));

        SeatMapResponse response = service.get(55L, 1L, false);

        assertThat(response.seats()).containsExactly(new SeatMapSeat("s0001", 1, 2, 3, 4, 5, 6));
        assertThat(response.venueName()).isEqualTo("KSPO DOME");
    }

    @Test
    void getKeepsSectionAndFillsOneForLegacySeatJson() {
        SeatMapLayout layout = layout("[{\"uid\":\"a\",\"row\":1,\"col\":1,\"x\":1,\"y\":1,\"w\":1,\"h\":1,\"section\":2},"
                + "{\"uid\":\"b\",\"row\":1,\"col\":1,\"x\":1,\"y\":9,\"w\":1,\"h\":1}]");
        when(layoutRepository.findDetailById(55L)).thenReturn(Optional.of(layout));

        SeatMapResponse response = service.get(55L, 1L, false);

        // 같은 (row, col)이어도 section이 다르면 구분된다. 옛 데이터(section 없음)는 1
        assertThat(response.seats()).extracting(SeatMapSeat::section).containsExactly(2, 1);
    }

    @Test
    void getUnknownIs404() {
        when(layoutRepository.findDetailById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(1L, 1L, false)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void getCorruptedJsonIsGenericErrorWithoutLeakingContent() {
        SeatMapLayout layout = layout("{broken SECRET-CONTENT");
        when(layoutRepository.findDetailById(55L)).thenReturn(Optional.of(layout));

        assertThatThrownBy(() -> service.get(55L, 1L, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(SeatMapService.STORED_DATA_ERROR_MESSAGE);
    }

    @Test
    void listUnknownVenueIs404AndEmptyIsEmptyList() {
        when(venueRepository.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.listByVenue(9L)).isInstanceOf(NotFoundException.class);

        when(venueRepository.findById(10L)).thenReturn(Optional.of(venue(10L, "KSPO DOME")));
        when(layoutRepository.findSummariesByVenueId(10L)).thenReturn(List.of());
        assertThat(service.listByVenue(10L)).isEmpty();
    }

    private static SeatMapLayout layout(String seatJson) {
        SeatMapLayout layout = SeatMapLayout.createDraft(venue(10L, "KSPO DOME"), null, seatJson, "OCR_DONE",
                700, 400, user(1L, "nick"));
        ReflectionTestUtils.setField(layout, "id", 55L);
        return layout;
    }

    // ---- 등록 제한 ----

    @Test
    void zoneLimitIs422AndSkipsRecognition() {
        when(venueRepository.findById(10L)).thenReturn(Optional.of(venue(10L, "KSPO DOME")));
        when(layoutRepository.countByVenue_Id(10L)).thenReturn(20L);

        assertThatThrownBy(() -> service.createDraft(10L, 1L, false, png(), "A구역", null))
                .isInstanceOfSatisfying(SeatMapException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getCode()).isEqualTo("ZONE_LIMIT_REACHED");
                    assertThat(e.getMessage()).isEqualTo("이 공연장에 등록할 수 있는 구역 수를 넘었어요.");
                });
        verify(client, never()).recognize(any(), anyString(), anyString());
        // 상한 직전(19)이면 통과
        when(layoutRepository.countByVenue_Id(10L)).thenReturn(19L);
        stubHappyPath();
        assertThat(service.createDraft(10L, 1L, false, png(), "A구역", null)).isNotNull();
    }

    @Test
    void zoneLimitAlsoAppliesToAdmin() {
        when(venueRepository.findById(10L)).thenReturn(Optional.of(venue(10L, "KSPO DOME")));
        when(layoutRepository.countByVenue_Id(10L)).thenReturn(20L);

        assertThatThrownBy(() -> service.createDraft(10L, 1L, true, png(), "A구역", null))
                .isInstanceOfSatisfying(SeatMapException.class, e -> assertThat(e.getCode()).isEqualTo("ZONE_LIMIT_REACHED"));
    }

    @Test
    void dailyLimitIs429ForUserButNotAdmin() {
        when(venueRepository.findById(10L)).thenReturn(Optional.of(venue(10L, "KSPO DOME")));
        when(layoutRepository.countByCreatedBy_IdAndCreatedAtAfter(eq(1L), any())).thenReturn(10L);

        assertThatThrownBy(() -> service.createDraft(10L, 1L, false, png(), "A구역", null))
                .isInstanceOfSatisfying(SeatMapException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(e.getCode()).isEqualTo("DAILY_LIMIT_REACHED");
                    assertThat(e.getMessage()).isEqualTo("하루에 등록할 수 있는 좌석표 수를 넘었어요. 내일 다시 시도해주세요.");
                });
        verify(client, never()).recognize(any(), anyString(), anyString());

        // ADMIN은 일일 제한을 세지도 않는다
        stubHappyPath();
        assertThat(service.createDraft(10L, 1L, true, png(), "A구역", null)).isNotNull();
        verify(layoutRepository, org.mockito.Mockito.times(1)).countByCreatedBy_IdAndCreatedAtAfter(any(), any());
    }

    @Test
    void dailyLimitWindowIsLast24HoursInKst() {
        stubHappyPath();
        when(layoutRepository.countByCreatedBy_IdAndCreatedAtAfter(eq(1L), any())).thenReturn(9L);

        service.createDraft(10L, 1L, false, png(), "A구역", null);

        // 고정 시각 2026-10-07 12:00 KST -> 기준은 24시간 전(초과)
        verify(layoutRepository).countByCreatedBy_IdAndCreatedAtAfter(1L, LocalDateTime.of(2026, 10, 6, 12, 0));
    }

    @Test
    void duplicateDraft409ComesBeforeLimits() {
        when(venueRepository.findById(10L)).thenReturn(Optional.of(venue(10L, "KSPO DOME")));
        when(layoutRepository.findDraftIdByZone(10L, "A구역")).thenReturn(Optional.of(77L));
        when(layoutRepository.countByVenue_Id(10L)).thenReturn(99L);
        when(layoutRepository.countByCreatedBy_IdAndCreatedAtAfter(eq(1L), any())).thenReturn(99L);

        assertThatThrownBy(() -> service.createDraft(10L, 1L, false, png(), "A구역", null))
                .isInstanceOf(ConflictException.class);
        verify(layoutRepository, never()).countByVenue_Id(any());
    }

    @Test
    void zoneLimit422ComesBeforeDailyLimit429() {
        when(venueRepository.findById(10L)).thenReturn(Optional.of(venue(10L, "KSPO DOME")));
        when(layoutRepository.countByVenue_Id(10L)).thenReturn(20L);
        when(layoutRepository.countByCreatedBy_IdAndCreatedAtAfter(eq(1L), any())).thenReturn(99L);

        assertThatThrownBy(() -> service.createDraft(10L, 1L, false, png(), "A구역", null))
                .isInstanceOfSatisfying(SeatMapException.class, e -> assertThat(e.getCode()).isEqualTo("ZONE_LIMIT_REACHED"));
        verify(layoutRepository, never()).countByCreatedBy_IdAndCreatedAtAfter(any(), any());
    }

    // ---- 삭제 권한 / canDelete ----

    private void stubOwner(SeatMapStatus status, Long creatorId) {
        when(layoutRepository.lockById(55L)).thenReturn(Optional.of(55L));
        when(layoutRepository.findOwnerViewById(55L)).thenReturn(Optional.of(new SeatMapLayoutRepository.OwnerView() {
            @Override
            public SeatMapStatus getStatus() {
                return status;
            }

            @Override
            public Long getCreatorId() {
                return creatorId;
            }
        }));
    }

    /** 요청자 1L. 작성자: SELF(1L) / OTHER(2L) / NULL, 역할: admin 여부, 플래그 on/off -> 허용 여부. */
    @ParameterizedTest
    @CsvSource({
            // owner, admin, flag, allowed
            "SELF,  false, false, true",
            "OTHER, false, false, false",
            "OTHER, true,  false, true",
            "NULL,  false, false, false",
            "NULL,  true,  false, true",
            "SELF,  false, true,  true",
            "OTHER, false, true,  true",
            "NULL,  false, true,  true",
    })
    void deletePermissionMatrixAndCanDeleteAgree(String owner, boolean admin, boolean flag, boolean allowed) {
        ReflectionTestUtils.setField(service, "devDraftDeleteEnabled", flag);
        Long creatorId = switch (owner) {
            case "SELF" -> 1L;
            case "OTHER" -> 2L;
            default -> null;
        };
        stubOwner(SeatMapStatus.DRAFT, creatorId);
        when(layoutRepository.deleteDraftById(55L)).thenReturn(1);

        if (allowed) {
            service.deleteDraft(55L, 1L, admin);
            verify(layoutRepository).deleteDraftById(55L);
        } else {
            assertThatThrownBy(() -> service.deleteDraft(55L, 1L, admin))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            e -> assertThat(e.getMessage()).isEqualTo("작성자 또는 관리자만 삭제할 수 있어요."));
            verify(layoutRepository, never()).deleteDraftById(any());
        }

        // GET 응답의 canDelete는 같은 규칙
        SeatMapLayout layout = layout("[]");
        if (creatorId == null) {
            ReflectionTestUtils.setField(layout, "createdBy", null);
        } else {
            ReflectionTestUtils.setField(layout, "createdBy", user(creatorId, "n"));
        }
        when(layoutRepository.findDetailById(55L)).thenReturn(Optional.of(layout));
        assertThat(service.get(55L, 1L, admin).canDelete()).isEqualTo(allowed);
    }

    @Test
    void canDeleteIsFalseForOfficialEvenForAdminOrFlag() {
        SeatMapLayout layout = layout("[]");
        ReflectionTestUtils.setField(layout, "status", SeatMapStatus.OFFICIAL);
        when(layoutRepository.findDetailById(55L)).thenReturn(Optional.of(layout));

        assertThat(service.get(55L, 1L, true).canDelete()).isFalse();
    }

    @Test
    void deleteRejectsOfficialWith409EvenForAdmin() {
        stubOwner(SeatMapStatus.OFFICIAL, 1L);

        assertThatThrownBy(() -> service.deleteDraft(55L, 1L, true))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getMessage()).isEqualTo(SeatMapService.DELETE_OFFICIAL_MESSAGE));
        verify(layoutRepository, never()).deleteDraftById(any());
    }

    @Test
    void deleteRejectsWhenTicketOrCorrectionReferences() {
        stubOwner(SeatMapStatus.DRAFT, 1L);
        when(ticketRepository.countBySeatMapLayout_Id(55L)).thenReturn(1L);
        assertThatThrownBy(() -> service.deleteDraft(55L, 1L, false)).isInstanceOf(ConflictException.class);

        when(ticketRepository.countBySeatMapLayout_Id(55L)).thenReturn(0L);
        when(correctionRepository.countBySeatMapLayout_Id(55L)).thenReturn(2L);
        assertThatThrownBy(() -> service.deleteDraft(55L, 1L, false))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getMessage()).isEqualTo(SeatMapService.DELETE_REFERENCED_MESSAGE));
        verify(layoutRepository, never()).deleteDraftById(any());
    }

    @Test
    void deleteRejectsEditedDraftAndOnlyCleansInitialRecognizedRevision() {
        stubOwner(SeatMapStatus.DRAFT, 1L);
        when(layoutRepository.deleteDraftById(55L)).thenReturn(1);

        // 수정·정정 로그가 있으면 로그(append-only)를 지우지 않으므로 삭제 거부
        when(revisionRepository.countBySeatMapLayout_IdAndActionTypeNot(55L, SeatMapRevisionAction.RECOGNIZED))
                .thenReturn(1L);
        assertThatThrownBy(() -> service.deleteDraft(55L, 1L, false))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getMessage()).isEqualTo(SeatMapService.DELETE_EDITED_MESSAGE));
        verify(revisionRepository, never()).deleteAllBySeatMapId(any());
        verify(layoutRepository, never()).deleteDraftById(any());

        // 최초 인식 로그만 있으면 그것만 정리하고 삭제
        when(revisionRepository.countBySeatMapLayout_IdAndActionTypeNot(55L, SeatMapRevisionAction.RECOGNIZED))
                .thenReturn(0L);
        service.deleteDraft(55L, 1L, false);
        verify(revisionRepository).deleteAllBySeatMapId(55L);
        verify(layoutRepository).deleteDraftById(55L);
    }

    @Test
    void deleteLocksRowFirstAndUnknownIs404WithoutFurtherChecks() {
        when(layoutRepository.lockById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteDraft(1L, 1L, true)).isInstanceOf(NotFoundException.class);
        verify(layoutRepository, never()).findOwnerViewById(any());

        stubOwner(SeatMapStatus.DRAFT, 1L);
        when(layoutRepository.deleteDraftById(55L)).thenReturn(1);
        service.deleteDraft(55L, 1L, false);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(layoutRepository);
        order.verify(layoutRepository).lockById(55L);
        order.verify(layoutRepository).findOwnerViewById(55L);
    }

    @Test
    void deleteUnknownOrAlreadyDeletedIs404() {
        when(layoutRepository.findOwnerViewById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deleteDraft(1L, 1L, false)).isInstanceOf(NotFoundException.class);

        // 조회 직후 다른 요청이 먼저 지운 경우
        stubOwner(SeatMapStatus.DRAFT, 1L);
        when(layoutRepository.deleteDraftById(55L)).thenReturn(0);
        assertThatThrownBy(() -> service.deleteDraft(55L, 1L, false)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void flagOffNoLongerHides404ButAppliesOwnerRule() {
        ReflectionTestUtils.setField(service, "devDraftDeleteEnabled", false);
        stubOwner(SeatMapStatus.DRAFT, 2L);

        assertThatThrownBy(() -> service.deleteDraft(55L, 1L, false)).isInstanceOf(ForbiddenException.class);
    }
}
