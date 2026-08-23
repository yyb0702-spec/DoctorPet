// 병원 스태프의 회원 진료·결제 이력 조회(QueryDSL). 자병원에서의 그 회원 예약을 활성 결제와 함께
// 페이지로 돌려준다(SA §8-6). 결제는 LEFT JOIN이라 결제 없는 예약도 포함한다.
package com.doctorpet.domain.reservation.repository;

import static com.doctorpet.domain.payment.entity.QPayment.payment;
import static com.doctorpet.domain.reservation.entity.QReservation.reservation;
import static com.doctorpet.domain.reservation.entity.QReservationSlot.reservationSlot;

import com.doctorpet.domain.reservation.dto.response.HospitalMemberHistoryItemResponse;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
public class HospitalMemberHistoryQueryRepository {

    private final JPAQueryFactory queryFactory;

    public HospitalMemberHistoryQueryRepository(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    /**
     * 병원에서의 그 회원 예약을 슬롯 시작 시각 최근순으로 페이지 반환한다. 활성 결제(superseded_at
     * IS NULL)를 LEFT JOIN해 결제 없는 예약도 포함하며, 대체된 과거 결제는 붙이지 않는다.
     */
    public Page<HospitalMemberHistoryItemResponse> findMemberHistory(
            Long hospitalId,
            Long memberId,
            Pageable pageable
    ) {
        List<HospitalMemberHistoryItemResponse> content = queryFactory
                .select(Projections.constructor(
                        HospitalMemberHistoryItemResponse.class,
                        reservation.id,
                        reservationSlot.startAt,
                        reservation.petNameSnapshot,
                        reservation.petSpeciesSnapshot,
                        reservation.status,
                        payment.id,
                        payment.status,
                        payment.amount
                ))
                .from(reservation)
                .join(reservationSlot).on(reservation.slotId.eq(reservationSlot.id))
                .leftJoin(payment).on(
                        payment.reservationId.eq(reservation.id),
                        payment.supersededAt.isNull()
                )
                .where(
                        reservation.hospitalId.eq(hospitalId),
                        reservation.memberId.eq(memberId)
                )
                .orderBy(reservationSlot.startAt.desc(), reservation.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        // count는 content와 동일한 조인 집합(reservationSlot 포함)을 태워 페이지 정합을 보장한다.
        // 지금은 slot_id가 NOT NULL·슬롯 하드삭제가 없어 결과가 같지만, 슬롯이 optional로 바뀌어도
        // content(INNER join)에서 빠지는 예약이 totalElements에만 잡히는 불일치를 막는다.
        Long total = queryFactory
                .select(reservation.count())
                .from(reservation)
                .join(reservationSlot).on(reservation.slotId.eq(reservationSlot.id))
                .where(
                        reservation.hospitalId.eq(hospitalId),
                        reservation.memberId.eq(memberId)
                )
                .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0L : total);
    }
}
