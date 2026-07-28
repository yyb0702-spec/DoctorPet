package com.doctorpet.domain.payment.port;

import com.doctorpet.domain.payment.exception.PaymentErrorCode;
import com.doctorpet.global.exception.ServiceException;
import java.util.Optional;

/**
 * 예약 도메인(#27)이 아직 develop에 없어 실제 {@link ReservationLookupPort} 구현이 없는 동안의 임시 기본 구현.
 * 이 빈이 없으면 {@code PaymentChargeService}가 의존성을 해소하지 못해 전체 스프링 컨텍스트가 부팅되지 않는다.
 *
 * <p>청구를 시도하면 예약 연동 미배선임을 503으로 명확히 알린다(조용히 404로 오인시키지 않는다).
 * #27이 실제 어댑터({@code @Component})를 제공하면 {@code @ConditionalOnMissingBean}에 의해 이 기본 구현은
 * 등록되지 않으므로 결제 흐름 코드 변경 없이 대체된다({@link PaymentPortConfig}).
 */
public class UnwiredReservationLookupPort implements ReservationLookupPort {

    @Override
    public Optional<ReservationChargeView> findForCharge(Long reservationId) {
        throw new ServiceException(PaymentErrorCode.RESERVATION_LOOKUP_UNAVAILABLE);
    }
}
