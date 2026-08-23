// 병원 스태프 결제 목록 조회(QueryDSL). 자병원 예약에 붙은 활성 결제만 페이지로 돌려준다(SA §8-7).
package com.doctorpet.domain.payment.repository;

import static com.doctorpet.domain.payment.entity.QPayment.payment;
import static com.doctorpet.domain.reservation.entity.QReservation.reservation;
import static com.doctorpet.domain.reservation.entity.QReservationSlot.reservationSlot;

import com.doctorpet.domain.payment.dto.response.HospitalPaymentListItemResponse;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
public class HospitalPaymentQueryRepository {

    private final JPAQueryFactory queryFactory;

    public HospitalPaymentQueryRepository(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    /**
     * 병원의 예약 중 활성 결제(superseded_at IS NULL)가 붙은 것을 결제 스냅샷과 함께 페이지로 돌려준다.
     * 결제 기준으로 조인하므로 결제 없는 예약은 빠지고, 대체된 과거 결제도 제외된다. 예약 슬롯 시작
     * 시각(진료 예약 시각) 최근순으로 정렬하며, 같은 시각은 결제 id 역순으로 안정 정렬한다.
     */
    public Page<HospitalPaymentListItemResponse> findActivePaymentsByHospitalId(
            Long hospitalId,
            Pageable pageable
    ) {
        List<HospitalPaymentListItemResponse> content = queryFactory
                .select(Projections.constructor(
                        HospitalPaymentListItemResponse.class,
                        reservation.id,
                        reservation.memberId,
                        reservation.petId,
                        reservation.petNameSnapshot,
                        reservationSlot.startAt,
                        payment.id,
                        payment.status,
                        payment.amount,
                        payment.paidAt,
                        payment.offlineSettledAt,
                        payment.refundedAt,
                        payment.offlineRequiredAt
                ))
                .from(payment)
                .join(reservation).on(payment.reservationId.eq(reservation.id))
                .join(reservationSlot).on(reservation.slotId.eq(reservationSlot.id))
                .where(
                        reservation.hospitalId.eq(hospitalId),
                        payment.supersededAt.isNull()
                )
                .orderBy(reservationSlot.startAt.desc(), payment.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(payment.count())
                .from(payment)
                .join(reservation).on(payment.reservationId.eq(reservation.id))
                .where(
                        reservation.hospitalId.eq(hospitalId),
                        payment.supersededAt.isNull()
                )
                .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0L : total);
    }
}
