package com.seatswap.service;

import com.seatswap.domain.PerformanceSession;
import com.seatswap.exception.FieldValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 회차 일시 규칙.
 * - 분 단위 절삭 (unique 제약 판정과 일치시키기 위해 PerformanceSession.truncate 사용)
 * - 지난 일시 거부: 지난 공연의 좌석 교환은 의미가 없으므로 생성·변경 시 현재(KST) 이전 시각은 400.
 *   이미 저장된 과거 회차는 그대로 두고 조회에도 포함한다.
 */
@Component
@RequiredArgsConstructor
public class SessionTimePolicy {

    static final String PAST_MESSAGE = "지난 일시는 등록할 수 없습니다.";

    private final Clock clock;

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** 단일 회차: 절삭 + 과거 거부. */
    public LocalDateTime normalize(LocalDateTime startsAt, String field) {
        if (startsAt == null) {
            throw new FieldValidationException(field, "회차 일시를 입력해주세요.");
        }
        LocalDateTime truncated = PerformanceSession.truncate(startsAt);
        if (truncated.isBefore(PerformanceSession.truncate(now()))) {
            throw new FieldValidationException(field, PAST_MESSAGE);
        }
        return truncated;
    }

    /** 여러 회차: 각각 절삭 + 과거 거부, 같은 시각(절삭 후)은 하나로 합치고 오름차순 정렬. */
    public List<LocalDateTime> normalizeAll(Collection<LocalDateTime> startsAts, String field) {
        if (startsAts == null || startsAts.isEmpty()) {
            throw new FieldValidationException(field, "회차를 1개 이상 입력해주세요.");
        }
        return startsAts.stream()
                .map(t -> normalize(t, field))
                .distinct()
                .sorted()
                .toList();
    }
}
