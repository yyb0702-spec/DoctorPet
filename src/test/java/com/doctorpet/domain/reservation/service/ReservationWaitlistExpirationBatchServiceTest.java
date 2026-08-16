package com.doctorpet.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.reservation.config.ReservationWaitlistProperties;
import com.doctorpet.domain.reservation.entity.ReservationWaitlist;
import com.doctorpet.domain.reservation.entity.status.ReservationWaitlistStatus;
import com.doctorpet.domain.reservation.repository.ReservationWaitlistRepository;
import com.doctorpet.domain.reservation.scheduler.ReservationWaitlistExpirationLock;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReservationWaitlistExpirationBatchServiceTest {

    @Mock
    private ReservationWaitlistRepository reservationWaitlistRepository;

    @Mock
    private ReservationWaitlistService reservationWaitlistService;

    @Mock
    private ReservationWaitlistExpirationLock lock;

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
                lock,
                clock
        );
    }

    @Test
    @DisplayName("만료된 OFFERED 제안만 개별 처리하고 성공 건수를 반환한다")
    void expireOffers_processesExpiredOffers() {
        given(lock.executeIfAcquired(any(Integer.class), any())).willAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Supplier<Integer> action = invocation.getArgument(1);
            return java.util.Optional.of(action.get());
        });
        ReservationWaitlist first = offered(1L);
        ReservationWaitlist second = offered(2L);
        given(reservationWaitlistRepository
                .findByStatusAndOfferExpiresAtLessThanEqualOrderByOfferExpiresAtAscIdAsc(
                        ReservationWaitlistStatus.OFFERED,
                        LocalDateTime.of(2026, 8, 13, 10, 0),
                        PageRequest.of(0, 100)
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

    @Test
    @DisplayName("다른 인스턴스가 만료 배치 잠금을 보유하면 조회·상태 전이를 수행하지 않는다")
    void expireOffers_lockUnavailable_skipsBatch() {
        doReturn(java.util.Optional.empty()).when(lock)
                .executeIfAcquired(any(Integer.class), any());

        int expired = batchService.expireOffers();

        assertThat(expired).isZero();
        verify(reservationWaitlistRepository, never())
                .findByStatusAndOfferExpiresAtLessThanEqualOrderByOfferExpiresAtAscIdAsc(
                        any(), any(), any());
    }

    private ReservationWaitlist offered(Long id) {
        ReservationWaitlist waitlist = ReservationWaitlist.waiting(id, 10L);
        ReflectionTestUtils.setField(waitlist, "id", id);
        LocalDateTime now = LocalDateTime.of(2026, 8, 13, 9, 50);
        waitlist.offer(now, now.plusMinutes(10));
        return waitlist;
    }
}
