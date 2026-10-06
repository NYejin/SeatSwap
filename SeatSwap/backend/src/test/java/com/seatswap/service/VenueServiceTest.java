package com.seatswap.service;

import com.seatswap.domain.Venue;
import com.seatswap.dto.request.VenueCreateRequest;
import com.seatswap.exception.FieldValidationException;
import com.seatswap.repository.VenueRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static com.seatswap.service.PerformanceFixtures.uniqueViolation;
import static com.seatswap.service.PerformanceFixtures.venue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VenueServiceTest {

    private final VenueRepository venueRepository = mock(VenueRepository.class);
    private final VenueService service = new VenueService(venueRepository);

    @Test
    void searchUsesEscapedRawAndNormalizedPatterns() {
        when(venueRepository.search(any(), any(), any(Pageable.class))).thenReturn(List.of(venue(1L, "KSPO DOME")));

        assertThat(service.search("  KSPO  dome ")).extracting("name").containsExactly("KSPO DOME");
        // 대소문자 무시는 DB collation에 맡기므로 원문 패턴은 소문자로 바꾸지 않는다
        verify(venueRepository).search(eq("%KSPO dome%"), eq("%kspodome%"), any(Pageable.class));

        service.search("100%_홀");
        // 원문 패턴은 %,_ 를 이스케이프, 정규화 패턴은 구두점(%,_)이 제거된 값
        verify(venueRepository).search(eq("%100!%!_홀%"), eq("%100홀%"), any(Pageable.class));
    }

    @Test
    void searchReturnsEmptyListWhenNothingMatches() {
        when(venueRepository.search(any(), any(), any(Pageable.class))).thenReturn(List.of());
        assertThat(service.search("없는공연장")).isEmpty();
    }

    @Test
    void searchRejectsTooLongQuery() {
        assertThatThrownBy(() -> service.search("가".repeat(101)))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getField()).isEqualTo("query"));
    }

    @Test
    void createReturnsExistingWhenNormalizedNameMatches() {
        when(venueRepository.findByNormalizedName("kspodome")).thenReturn(Optional.of(venue(1L, "KSPO DOME")));

        VenueService.VenueCreateResult result = service.create(new VenueCreateRequest("kspo-dome", null));

        assertThat(result.created()).isFalse();
        assertThat(result.venue().id()).isEqualTo(1L);
        verify(venueRepository, never()).saveAndFlush(any());
    }

    @Test
    void createInsertsNewVenue() {
        when(venueRepository.findByNormalizedName("올림픽홀")).thenReturn(Optional.empty());
        when(venueRepository.saveAndFlush(any(Venue.class))).thenAnswer(inv -> {
            Venue v = inv.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(v, "id", 5L);
            return v;
        });

        VenueService.VenueCreateResult result = service.create(new VenueCreateRequest(" 올림픽  홀 ", "  서울 송파구 "));

        assertThat(result.created()).isTrue();
        assertThat(result.venue().id()).isEqualTo(5L);
        assertThat(result.venue().name()).isEqualTo("올림픽 홀");
        assertThat(result.venue().address()).isEqualTo("서울 송파구");
    }

    @Test
    void createRaceReturnsWinnerRecord() {
        when(venueRepository.findByNormalizedName("올림픽홀"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(venue(9L, "올림픽홀")));
        when(venueRepository.saveAndFlush(any(Venue.class)))
                .thenThrow(uniqueViolation("venue", "uk_venue_normalized_name"));

        VenueService.VenueCreateResult result = service.create(new VenueCreateRequest("올림픽홀", null));

        assertThat(result.created()).isFalse();
        assertThat(result.venue().id()).isEqualTo(9L);
    }

    @Test
    void createRethrowsOtherIntegrityViolations() {
        DataIntegrityViolationException other = uniqueViolation("venue", "uk_something_else");
        when(venueRepository.findByNormalizedName(any())).thenReturn(Optional.empty());
        when(venueRepository.saveAndFlush(any(Venue.class))).thenThrow(other);

        assertThatThrownBy(() -> service.create(new VenueCreateRequest("올림픽홀", null))).isSameAs(other);
    }

    @Test
    void createValidatesNameAndAddress() {
        assertThatThrownBy(() -> service.create(new VenueCreateRequest("   ", null)))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getField()).isEqualTo("name"));
        assertThatThrownBy(() -> service.create(new VenueCreateRequest("가".repeat(101), null)))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getField()).isEqualTo("name"));
        assertThatThrownBy(() -> service.create(new VenueCreateRequest("(-)", null)))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getField()).isEqualTo("name"));
        assertThatThrownBy(() -> service.create(new VenueCreateRequest("올림픽홀", "가".repeat(256))))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getField()).isEqualTo("address"));
    }
}
