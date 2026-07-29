package com.doctorpet.domain.reservation.repository;

import static com.doctorpet.domain.reservation.entity.QReservation.reservation;
import static com.doctorpet.domain.reservation.entity.QReservationSlot.reservationSlot;

import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public class ReservationQueryRepositoryImpl
        implements ReservationQueryRepository {

    private final JPAQueryFactory queryFactory;

    public ReservationQueryRepositoryImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public Page<Reservation> findMyReservations(
            Long memberId,
            ReservationStatus status,
            LocalDateTime fromAt,
            LocalDateTime toExclusive,
            Sort.Direction direction,
            Pageable pageable
    ) {
        List<Reservation> content = queryFactory
                .selectFrom(reservation)
                .join(reservationSlot)
                .on(reservation.slotId.eq(reservationSlot.id))
                .where(
                        reservation.memberId.eq(memberId),
                        statusEq(status),
                        startAtGoe(fromAt),
                        startAtLt(toExclusive)
                )
                .orderBy(
                        reservedAtOrder(direction),
                        reservationIdOrder(direction)
                )
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(reservation.count())
                .from(reservation)
                .join(reservationSlot)
                .on(reservation.slotId.eq(reservationSlot.id))
                .where(
                        reservation.memberId.eq(memberId),
                        statusEq(status),
                        startAtGoe(fromAt),
                        startAtLt(toExclusive)
                )
                .fetchOne();

        return new PageImpl<>(
                content,
                pageable,
                total == null ? 0L : total
        );
    }

    private BooleanExpression statusEq(ReservationStatus status) {
        return status == null ? null : reservation.status.eq(status);
    }

    private BooleanExpression startAtGoe(LocalDateTime fromAt) {
        return fromAt == null ? null : reservationSlot.startAt.goe(fromAt);
    }

    private BooleanExpression startAtLt(LocalDateTime toExclusive) {
        return toExclusive == null
                ? null
                : reservationSlot.startAt.lt(toExclusive);
    }

    private OrderSpecifier<?> reservedAtOrder(Sort.Direction direction) {
        return direction.isAscending()
                ? reservationSlot.startAt.asc()
                : reservationSlot.startAt.desc();
    }

    private OrderSpecifier<?> reservationIdOrder(Sort.Direction direction) {
        return direction.isAscending()
                ? reservation.id.asc()
                : reservation.id.desc();
    }
}
