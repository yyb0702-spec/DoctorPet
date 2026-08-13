package com.doctorpet.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.reservation.config.ReservationWaitlistProperties;
import com.doctorpet.domain.reservation.entity.ReservationWaitlist;
import com.doctorpet.domain.reservation.entity.status.ReservationWaitlistStatus;
import com.doctorpet.domain.reservation.repository.ReservationWaitlistRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReservationWaitlistExpirationBatchServiceTest {

    @Mock
    private ReservationWaitlistRepository reservationWaitlistRepository;

    @Mock
    private ReservationWaitlistService reservationWaitlistService;

    private ReservationWaitlistExpirationBatchService batchService;

    @BeforeEach
    void setUp() {
        ReservationWaitlistProperties properties = new ReservationWaitlistProperties();
        properties.setExpirationBatchSize(100);
        Clock clock = Clock.fixed(
                LocalDateTime.of(2026, 8, 13, 10, 0)
                        .atZone(ZoneId.of("Asia/Seoul")).toInstant(),
                ZoneId.of("Asia/Seoul")
        );
        batchService = new ReservationWaitlistExpirationBatchService(
                reservationWaitlistRepository,
                reservationWaitlistService,
                properties,
                clock
        );
    }

    @Test
    @DisplayName("만료된 OFFERED 제안만 개별 처리하고 성공 건수를 반환한다")
    void expireOffers_processesExpiredOffers() {
        ReservationWaitlist first = offered(1L);
        ReservationWaitlist second = offered(2L);
        given(reservationWaitlistRepository
                .findByStatusAndOfferExpiresAtLessThanEqualOrderByOfferExpiresAtAscIdAsc(
                        ReservationWaitlistStatus.OFFERED,
                        LocalDateTime.of(2026, 8, 13, 10, 0)
                )).willReturn(List.of(first, second));
        given(reservationWaitlistService.expire(any(), any())).willReturn(true, false);

        int expired = batchService.expireOffers();

        assertThat(expired).isEqualTo(1);
        verify(reservationWaitlistService).expire(
                1L,
                LocalDateTime.of(2026, 8, 13, 10, 0)
        );
        verify(reservationWaitlistService).expire(
                2L,
                LocalDateTime.of(2026, 8, 13, 10, 0)
        );
    }

    private ReservationWaitlist offered(Long id) {
        ReservationWaitlist waitlist = ReservationWaitlist.waiting(id, 10L);
        ReflectionTestUtils.setField(waitlist, "id", id);
        LocalDateTime now = LocalDateTime.of(2026, 8, 13, 9, 50);
        waitlist.offer(now, now.plusMinutes(10));
        return waitlist;
    }
}
