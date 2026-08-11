package com.doctorpet.domain.hospital.service;

import com.doctorpet.domain.hospital.dto.request.DailyOperatingHoursRequest;
import com.doctorpet.domain.hospital.dto.request.OperatingHoursUpdateRequest;
import com.doctorpet.domain.hospital.dto.request.OperatingPeriodRequest;
import com.doctorpet.domain.hospital.dto.request.TemporaryClosureCreateRequest;
import com.doctorpet.domain.hospital.dto.response.OperatingHoursResponse;
import com.doctorpet.domain.hospital.dto.response.TemporaryClosureResponse;
import com.doctorpet.domain.hospital.entity.Hospital;
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
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
        LocalDate effectiveFrom = resolveEffectiveFrom(
                hospitalId,
                today,
                request.desiredEffectiveFrom()
        );
        Hospital hospital = lockHospital(hospitalId);

        HospitalOperatingSchedule schedule = scheduleRepository
                .findSchedule(hospitalId, effectiveFrom)
                .map(existing -> {
                    existing.changeOperatingHours(operatingHours);
                    return existing;
                })
                .orElseGet(() -> createSchedule(
                        hospital,
                        effectiveFrom,
                        operatingHours
                ));

        HospitalOperatingSchedule savedSchedule = scheduleRepository.save(schedule);
        replacePublishedSlots(hospitalId, effectiveFrom, today, savedSchedule);
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
        lockHospital(hospitalId);

        HospitalTemporaryClosure closure = closureRepository
                .findClosure(hospitalId, businessDate)
                .orElseThrow(() -> new ServiceException(
                        HospitalErrorCode.TEMPORARY_CLOSURE_NOT_FOUND
                ));
        closureRepository.delete(closure);
        closureRepository.flush();

        if (!businessDate.isAfter(today.plusDays(13))) {
            createSlotsAfterHospitalLock(hospitalId, businessDate);
        }
    }

    @Transactional
    public int createSlots(Long hospitalId, LocalDate businessDate) {
        lockHospital(hospitalId);
        return createSlotsAfterHospitalLock(hospitalId, businessDate);
    }

    private int createSlotsAfterHospitalLock(
            Long hospitalId,
            LocalDate businessDate
    ) {
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
            Long hospitalId,
            LocalDate effectiveFrom,
            LocalDate today,
            HospitalOperatingSchedule schedule
    ) {
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

    private Long getHospitalId(Long memberId) {
        MemberResponse member = memberService.getMyInfo(memberId);
        if (member.role() != MemberRole.HOSPITAL_STAFF || member.hospitalId() == null) {
            throw new ServiceException(HospitalErrorCode.NOT_OWN_HOSPITAL);
        }
        return member.hospitalId();
    }
}
