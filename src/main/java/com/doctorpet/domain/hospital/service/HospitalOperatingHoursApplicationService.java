package com.doctorpet.domain.hospital.service;

import com.doctorpet.domain.hospital.dto.request.DailyOperatingHoursRequest;
import com.doctorpet.domain.hospital.dto.request.OperatingHoursSaveMode;
import com.doctorpet.domain.hospital.dto.request.OperatingHoursUpdateRequest;
import com.doctorpet.domain.hospital.dto.request.OperatingPeriodRequest;
import com.doctorpet.domain.hospital.dto.request.TemporaryClosureCreateRequest;
import com.doctorpet.domain.hospital.dto.response.OperatingHoursResponse;
import com.doctorpet.domain.hospital.dto.response.TemporaryClosureResponse;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.HospitalOperatingSchedule;
import com.doctorpet.domain.hospital.entity.HospitalTemporaryClosure;
import com.doctorpet.domain.hospital.exception.HospitalErrorCode;
import com.doctorpet.domain.hospital.model.DailyOperatingHours;
import com.doctorpet.domain.hospital.repository.HospitalRepository;
import com.doctorpet.domain.hospital.repository.HospitalOperatingScheduleRepository;
import com.doctorpet.domain.hospital.repository.HospitalTemporaryClosureRepository;
import com.doctorpet.domain.member.dto.response.MemberResponse;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.domain.reservation.dto.request.ReservationSlotCreateCommand;
import com.doctorpet.domain.reservation.service.ReservationService;
import com.doctorpet.global.exception.ServiceException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HospitalOperatingHoursApplicationService {

    private static final int SLOT_INTERVAL_MINUTES = 30;

    private final MemberService memberService;
    private final ReservationService reservationService;
    private final HospitalRepository hospitalRepository;
    private final HospitalOperatingScheduleRepository scheduleRepository;
    private final HospitalTemporaryClosureRepository closureRepository;
    private final Clock applicationClock;

    // flush 후 저장 시간표를 DB 절단값으로 재적재하기 위해 쓴다(updatedAt 토큰 왕복 일치).
    @PersistenceContext
    private EntityManager entityManager;

    public OperatingHoursResponse getOperatingHours(Long memberId) {
        Long hospitalId = getHospitalId(memberId);
        LocalDate today = LocalDate.now(applicationClock);
        HospitalOperatingSchedule schedule = scheduleRepository
                .findEffectiveSchedule(hospitalId, today)
                .orElseThrow(() -> new ServiceException(
                        HospitalErrorCode.OPERATING_SCHEDULE_NOT_FOUND
                ));

        return OperatingHoursResponse.from(schedule);
    }

    /**
     * 현재 시간표와 별도로, 아직 발효되지 않은 시간표 전체를 발효일 순으로 반환한다.
     * 현재 GET 응답에 미래 시간표를 섞으면 현재 적용 중인 정책으로 오인될 수 있으므로
     * 편집 화면은 이 조회 결과에서 대상 발효일을 명시적으로 선택해야 한다.
     */
    public List<OperatingHoursResponse> getScheduledOperatingHours(Long memberId) {
        Long hospitalId = getHospitalId(memberId);
        LocalDate today = LocalDate.now(applicationClock);

        return scheduleRepository.findScheduledSchedules(hospitalId, today)
                .stream()
                .map(OperatingHoursResponse::from)
                .toList();
    }

    @Transactional
    public OperatingHoursResponse updateOperatingHours(
            Long memberId,
            OperatingHoursUpdateRequest request
    ) {
        Long hospitalId = getHospitalId(memberId);
        LocalDate today = LocalDate.now(applicationClock);
        validateDesiredEffectiveFrom(request.desiredEffectiveFrom(), today);
        Map<DayOfWeek, List<DailyOperatingHours>> operatingHours =
                validateAndConvert(request.days());
        // 같은 병원의 시간표 생성·수정은 병원 행 잠금으로 먼저 직렬화한다. 이보다 앞서 일반
        // 조회를 하면 MySQL REPEATABLE_READ 스냅샷이 고정돼, 잠금을 기다린 뒤에도 다른 트랜잭션이
        // 방금 만든 같은 발효일의 시간표를 못 보고 UNIQUE 위반으로 끝날 수 있다.
        Hospital hospital = lockHospital(hospitalId);
        // CREATE는 발행창 안에 예약이 있으면 발효일을 마지막 예약일 다음 날로 밀어 예약된 슬롯을
        // 보존한다. UPDATE는 목록에서 선택한 기존 시간표의 발효일이 고정이므로 밀지 않는다 —
        // 밀면 findSchedule이 대상 시간표를 못 찾아 정상 편집이 HOSPITAL_016으로 오거부된다.
        LocalDate effectiveFrom = switch (request.saveMode()) {
            case CREATE -> resolveEffectiveFrom(
                    hospitalId,
                    today,
                    request.desiredEffectiveFrom()
            );
            case UPDATE -> request.desiredEffectiveFrom();
        };
        HospitalOperatingSchedule existingSchedule = scheduleRepository
                .findScheduleForUpdate(hospitalId, effectiveFrom)
                .map(this::refreshForCurrentRead)
                .orElse(null);
        HospitalOperatingSchedule schedule = switch (request.saveMode()) {
            case CREATE -> createScheduleForNewEffectiveDate(
                    existingSchedule,
                    hospital,
                    effectiveFrom,
                    operatingHours
            );
            case UPDATE -> updateSelectedSchedule(
                    existingSchedule,
                    request,
                    operatingHours
            );
        };

        HospitalOperatingSchedule savedSchedule = scheduleRepository.save(schedule);
        scheduleRepository.flush();
        entityManager.refresh(savedSchedule);
        if (request.saveMode() == OperatingHoursSaveMode.UPDATE) {
            advanceUpdateToken(savedSchedule);
        }
        // DB datetime(6)에 저장된 토큰을 먼저 읽은 뒤, UPDATE는 그보다 큰 마이크로초 토큰으로
        // 조건부 갱신한다. 고정 Clock 또는 매우 촘촘한 요청으로 @LastModifiedDate가 같은 값을
        // 만들어도 뒤늦은 요청이 같은 expectedUpdatedAt으로 통과하지 못하게 한다.
        replacePublishedSlots(
                hospital,
                hospitalId,
                effectiveFrom,
                today,
                savedSchedule
        );
        return OperatingHoursResponse.from(savedSchedule);
    }

    @Transactional
    public TemporaryClosureResponse createTemporaryClosure(
            Long memberId,
            TemporaryClosureCreateRequest request
    ) {
        Long hospitalId = getHospitalId(memberId);
        LocalDate businessDate = request.businessDate();
        validateTemporaryClosureDate(businessDate);
        Hospital hospital = lockHospital(hospitalId);
        if (closureRepository.findClosure(hospitalId, businessDate).isPresent()) {
            throw new ServiceException(
                    HospitalErrorCode.TEMPORARY_CLOSURE_ALREADY_EXISTS
            );
        }
        if (!reservationService.removeOpenSlotsIfNoReservation(
                hospitalId,
                businessDate
        )) {
            throw new ServiceException(
                    HospitalErrorCode.TEMPORARY_CLOSURE_HAS_RESERVATION
            );
        }

        HospitalTemporaryClosure closure = closureRepository.save(
                HospitalTemporaryClosure.create(hospital, businessDate)
        );
        return TemporaryClosureResponse.from(closure);
    }

    @Transactional
    public void cancelTemporaryClosure(Long memberId, LocalDate businessDate) {
        Long hospitalId = getHospitalId(memberId);
        LocalDate today = LocalDate.now(applicationClock);
        validateTemporaryClosureCancellationDate(businessDate, today);
        Hospital hospital = lockHospital(hospitalId);

        HospitalTemporaryClosure closure = closureRepository
                .findClosure(hospitalId, businessDate)
                .orElseThrow(() -> new ServiceException(
                        HospitalErrorCode.TEMPORARY_CLOSURE_NOT_FOUND
                ));
        closureRepository.delete(closure);
        closureRepository.flush();

        if (!businessDate.isAfter(today.plusDays(13))) {
            createSlotsAfterHospitalLock(hospital, hospitalId, businessDate);
        }
    }

    @Transactional
    public int createSlots(Long hospitalId, LocalDate businessDate) {
        Hospital hospital = lockHospital(hospitalId);
        if (hospital.getBusinessStatus() != BusinessStatus.OPEN) {
            return 0;
        }
        return createSlotsAfterHospitalLock(hospital, hospitalId, businessDate);
    }

    private int createSlotsAfterHospitalLock(
            Hospital hospital,
            Long hospitalId,
            LocalDate businessDate
    ) {
        if (hospital.getBusinessStatus() != BusinessStatus.OPEN) {
            return 0;
        }
        if (closureRepository.findClosure(hospitalId, businessDate).isPresent()) {
            return 0;
        }

        HospitalOperatingSchedule schedule = scheduleRepository
                .findEffectiveSchedule(hospitalId, businessDate)
                .orElseThrow(() -> new ServiceException(
                        HospitalErrorCode.OPERATING_SCHEDULE_NOT_FOUND
                ));
        List<ReservationSlotCreateCommand> commands = schedule
                .getOperatingHours()
                .getOrDefault(businessDate.getDayOfWeek(), List.of())
                .stream()
                .flatMap(hours -> createSlotCommands(businessDate, hours).stream())
                .toList();

        return reservationService.createOpenSlots(
                hospitalId,
                businessDate,
                commands
        );
    }

    private HospitalOperatingSchedule createSchedule(
            Hospital hospital,
            LocalDate effectiveFrom,
            Map<DayOfWeek, List<DailyOperatingHours>> operatingHours
    ) {
        return HospitalOperatingSchedule.create(hospital, effectiveFrom, operatingHours);
    }

    private HospitalOperatingSchedule createScheduleForNewEffectiveDate(
            HospitalOperatingSchedule existingSchedule,
            Hospital hospital,
            LocalDate effectiveFrom,
            Map<DayOfWeek, List<DailyOperatingHours>> operatingHours
    ) {
        if (existingSchedule != null) {
            throw operatingScheduleConflict();
        }
        return createSchedule(hospital, effectiveFrom, operatingHours);
    }

    private HospitalOperatingSchedule updateSelectedSchedule(
            HospitalOperatingSchedule existingSchedule,
            OperatingHoursUpdateRequest request,
            Map<DayOfWeek, List<DailyOperatingHours>> operatingHours
    ) {
        if (existingSchedule == null
                || request.targetScheduleId() == null
                || request.expectedUpdatedAt() == null
                || !existingSchedule.getId().equals(request.targetScheduleId())
                || !existingSchedule.getUpdatedAt().equals(request.expectedUpdatedAt())) {
            throw operatingScheduleConflict();
        }
        existingSchedule.changeOperatingHours(operatingHours);
        return existingSchedule;
    }

    private void advanceUpdateToken(HospitalOperatingSchedule schedule) {
        LocalDateTime currentUpdatedAt = schedule.getUpdatedAt();
        LocalDateTime nextUpdatedAt = nextUpdateToken(currentUpdatedAt);
        if (scheduleRepository.advanceUpdateToken(
                schedule.getId(),
                currentUpdatedAt,
                nextUpdatedAt
        ) != 1) {
            throw operatingScheduleConflict();
        }
        entityManager.refresh(schedule);
    }

    private HospitalOperatingSchedule refreshForCurrentRead(
            HospitalOperatingSchedule schedule
    ) {
        // 같은 영속성 컨텍스트에서 잠금 전 일반 조회가 이미 엔티티를 관리 중이면, PESSIMISTIC_WRITE
        // 조회만으로는 1차 캐시의 오래된 필드를 다시 쓸 수 있다. refresh + 잠금으로 DB current read를
        // 엔티티에 재적재해 CREATE 존재 확인과 UPDATE 토큰 검증 모두 최신 행으로 수행한다.
        entityManager.refresh(schedule, LockModeType.PESSIMISTIC_WRITE);
        return schedule;
    }

    private LocalDateTime nextUpdateToken(LocalDateTime currentUpdatedAt) {
        LocalDateTime now = LocalDateTime.now(applicationClock)
                .truncatedTo(ChronoUnit.MICROS);
        return now.isAfter(currentUpdatedAt)
                ? now
                : currentUpdatedAt.plus(1, ChronoUnit.MICROS);
    }

    private Hospital lockHospital(Long hospitalId) {
        return hospitalRepository.findByIdForUpdate(hospitalId)
                .orElseThrow(() -> new ServiceException(
                        HospitalErrorCode.HOSPITAL_NOT_FOUND
                ));
    }

    private List<ReservationSlotCreateCommand> createSlotCommands(
            LocalDate businessDate,
            DailyOperatingHours hours
    ) {
        LocalDateTime periodStart = businessDate.atTime(hours.openTime());
        LocalDateTime periodEnd = businessDate.atTime(hours.closeTime());
        if (!periodEnd.isAfter(periodStart)) {
            periodEnd = periodEnd.plusDays(1);
        }

        List<ReservationSlotCreateCommand> commands = new ArrayList<>();
        LocalDateTime slotStart = periodStart;
        while (!slotStart.plusMinutes(SLOT_INTERVAL_MINUTES).isAfter(periodEnd)) {
            LocalDateTime slotEnd = slotStart.plusMinutes(SLOT_INTERVAL_MINUTES);
            commands.add(new ReservationSlotCreateCommand(slotStart, slotEnd));
            slotStart = slotEnd;
        }
        return commands;
    }

    private void replacePublishedSlots(
            Hospital hospital,
            Long hospitalId,
            LocalDate effectiveFrom,
            LocalDate today,
            HospitalOperatingSchedule schedule
    ) {
        if (hospital.getBusinessStatus() != BusinessStatus.OPEN) {
            return;
        }
        LocalDate publishedUntil = today.plusDays(13);
        if (effectiveFrom.isAfter(publishedUntil)) {
            return;
        }

        reservationService.lockOpenSlotsForReplacement(
                hospitalId,
                effectiveFrom,
                publishedUntil
        );

        for (LocalDate businessDate = effectiveFrom;
             !businessDate.isAfter(publishedUntil);
             businessDate = businessDate.plusDays(1)) {
            List<ReservationSlotCreateCommand> commands =
                    createReplacementCommands(hospitalId, businessDate, schedule);
            reservationService.replaceOpenSlots(hospitalId, businessDate, commands);
        }
    }

    private List<ReservationSlotCreateCommand> createReplacementCommands(
            Long hospitalId,
            LocalDate businessDate,
            HospitalOperatingSchedule schedule
    ) {
        if (closureRepository.findClosure(hospitalId, businessDate).isPresent()) {
            return List.of();
        }
        return schedule.getOperatingHours()
                .getOrDefault(businessDate.getDayOfWeek(), List.of())
                .stream()
                .flatMap(hours -> createSlotCommands(businessDate, hours).stream())
                .toList();
    }

    private void validateDesiredEffectiveFrom(LocalDate desiredEffectiveFrom, LocalDate today) {
        if (!desiredEffectiveFrom.isAfter(today)) {
            throw new ServiceException(
                    HospitalErrorCode.INVALID_OPERATING_HOURS_EFFECTIVE_DATE
            );
        }
    }

    private void validateTemporaryClosureDate(LocalDate businessDate) {
        LocalDate today = LocalDate.now(applicationClock);
        if (!businessDate.isAfter(today)) {
            throw new ServiceException(
                    HospitalErrorCode.INVALID_TEMPORARY_CLOSURE_DATE
            );
        }
    }

    private void validateTemporaryClosureCancellationDate(
            LocalDate businessDate,
            LocalDate today
    ) {
        if (!businessDate.isAfter(today)) {
            throw new ServiceException(
                    HospitalErrorCode.TEMPORARY_CLOSURE_CANCEL_DEADLINE_PASSED
            );
        }
    }

    private Map<DayOfWeek, List<DailyOperatingHours>> validateAndConvert(
            List<DailyOperatingHoursRequest> days
    ) {
        Set<DayOfWeek> dayOfWeeks = new HashSet<>();
        EnumMap<DayOfWeek, List<DailyOperatingHours>> operatingHours =
                new EnumMap<>(DayOfWeek.class);

        for (DailyOperatingHoursRequest day : days) {
            if (!dayOfWeeks.add(day.dayOfWeek())) {
                throw invalidOperatingHours();
            }
            List<DailyOperatingHours> periods = day.periods().stream()
                    .map(this::toOperatingHours)
                    .sorted(Comparator.comparing(DailyOperatingHours::openTime))
                    .toList();
            validateNoOverlap(periods);
            operatingHours.put(day.dayOfWeek(), periods);
        }

        if (dayOfWeeks.size() != DayOfWeek.values().length) {
            throw invalidOperatingHours();
        }
        validateNoOverlapAcrossWeek(operatingHours);
        return operatingHours;
    }

    private DailyOperatingHours toOperatingHours(OperatingPeriodRequest period) {
        if (period.startTime().equals(period.endTime())) {
            throw invalidOperatingHours();
        }
        return new DailyOperatingHours(period.startTime(), period.endTime());
    }

    private void validateNoOverlap(List<DailyOperatingHours> periods) {
        LocalDate anchor = LocalDate.of(2000, 1, 1);
        LocalDateTime previousEnd = null;
        for (DailyOperatingHours period : periods) {
            LocalDateTime start = anchor.atTime(period.openTime());
            LocalDateTime end = anchor.atTime(period.closeTime());
            if (!end.isAfter(start)) {
                end = end.plusDays(1);
            }
            if (previousEnd != null && start.isBefore(previousEnd)) {
                throw invalidOperatingHours();
            }
            previousEnd = end;
        }
    }

    private void validateNoOverlapAcrossWeek(
            Map<DayOfWeek, List<DailyOperatingHours>> operatingHours
    ) {
        LocalDate monday = LocalDate.of(2000, 1, 3);
        List<LocalDateTime> starts = new ArrayList<>();
        List<LocalDateTime> ends = new ArrayList<>();

        for (DayOfWeek day : DayOfWeek.values()) {
            LocalDate businessDate = monday.plusDays(day.getValue() - 1L);
            for (DailyOperatingHours period : operatingHours.getOrDefault(day, List.of())) {
                LocalDateTime start = businessDate.atTime(period.openTime());
                LocalDateTime end = businessDate.atTime(period.closeTime());
                if (!end.isAfter(start)) {
                    end = end.plusDays(1);
                }
                starts.add(start);
                ends.add(end);
            }
        }

        for (int first = 0; first < starts.size(); first++) {
            for (int second = first + 1; second < starts.size(); second++) {
                if (overlaps(starts.get(first), ends.get(first),
                        starts.get(second), ends.get(second))) {
                    throw invalidOperatingHours();
                }
            }
        }

        for (int first = 0; first < starts.size(); first++) {
            for (int second = 0; second < starts.size(); second++) {
                LocalDateTime nextWeekStart = starts.get(second).plusWeeks(1);
                LocalDateTime nextWeekEnd = ends.get(second).plusWeeks(1);
                if (overlaps(starts.get(first), ends.get(first),
                        nextWeekStart, nextWeekEnd)) {
                    throw invalidOperatingHours();
                }
            }
        }
    }

    private boolean overlaps(
            LocalDateTime firstStart,
            LocalDateTime firstEnd,
            LocalDateTime secondStart,
            LocalDateTime secondEnd
    ) {
        return firstStart.isBefore(secondEnd) && secondStart.isBefore(firstEnd);
    }

    private LocalDate resolveEffectiveFrom(
            Long hospitalId,
            LocalDate today,
            LocalDate desiredEffectiveFrom
    ) {
        return reservationService.findLatestReservedBusinessDate(
                        hospitalId,
                        today,
                        today.plusDays(13)
                )
                .map(lastReservedDate -> max(
                        desiredEffectiveFrom,
                        lastReservedDate.plusDays(1)
                ))
                .orElse(desiredEffectiveFrom);
    }

    private LocalDate max(LocalDate first, LocalDate second) {
        return first.isAfter(second) ? first : second;
    }

    private ServiceException invalidOperatingHours() {
        return new ServiceException(HospitalErrorCode.INVALID_OPERATING_HOURS);
    }

    private ServiceException operatingScheduleConflict() {
        return new ServiceException(HospitalErrorCode.OPERATING_SCHEDULE_CONFLICT);
    }

    private Long getHospitalId(Long memberId) {
        MemberResponse member = memberService.getMyInfo(memberId);
        if (member.role() != MemberRole.HOSPITAL_STAFF || member.hospitalId() == null) {
            throw new ServiceException(HospitalErrorCode.NOT_OWN_HOSPITAL);
        }
        return member.hospitalId();
    }
}
