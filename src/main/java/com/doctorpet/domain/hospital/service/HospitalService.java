package com.doctorpet.domain.hospital.service;

import com.doctorpet.domain.hospital.dto.response.HospitalDetailResponse;
import com.doctorpet.domain.hospital.dto.response.HospitalSearchPageResponse;
import com.doctorpet.domain.hospital.dto.response.HospitalSearchResponse;
import com.doctorpet.domain.hospital.dto.query.HospitalSearchCachedPage;
import com.doctorpet.domain.hospital.dto.query.HospitalSearchCacheLookupResult;
import com.doctorpet.domain.hospital.dto.query.HospitalSearchResult;
import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.CapabilityType;
import com.doctorpet.domain.hospital.entity.CapabilityValue;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.entity.HospitalCapability;
import com.doctorpet.domain.hospital.entity.HospitalDetail;
import com.doctorpet.domain.hospital.entity.PartnershipStatus;
import com.doctorpet.domain.hospital.exception.HospitalErrorCode;
import com.doctorpet.domain.hospital.model.DailyOperatingHours;
import com.doctorpet.domain.hospital.repository.HospitalCapabilityRepository;
import com.doctorpet.domain.hospital.repository.HospitalDetailRepository;
import com.doctorpet.domain.hospital.repository.HospitalRepository;
import com.doctorpet.domain.hospital.repository.HospitalSearchCacheRepository;
import com.doctorpet.domain.hospital.dto.query.HospitalSearchCandidate;
import com.doctorpet.domain.hospital.dto.query.HospitalSearchCondition;
import com.doctorpet.global.exception.CommonErrorCode;
import com.doctorpet.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class HospitalService {

    private static final ZoneId SEOUL_ZONE_ID = ZoneId.of("Asia/Seoul");

    private final HospitalRepository hospitalRepository;
    private final HospitalDetailRepository hospitalDetailRepository;
    private final HospitalCapabilityRepository hospitalCapabilityRepository;
    private final HospitalSearchCacheRepository hospitalSearchCacheRepository;

    @Transactional(readOnly = true)
    public HospitalDetailResponse getHospitalDetail(Long hospitalId) {
        Hospital hospital = hospitalRepository.findById(hospitalId)
                .orElseThrow(() -> new ServiceException(HospitalErrorCode.HOSPITAL_NOT_FOUND));

        if (hospital.getPartnershipStatus() != PartnershipStatus.PARTNER) {
            return HospitalDetailResponse.from(
                    hospital,
                    null,
                    null,
                    null
            );
        }

        HospitalDetail detail = hospitalDetailRepository.findByHospital(hospital)
                .orElseThrow(() -> {
                    log.error(
                            "제휴 병원의 상세정보가 없습니다. hospitalId={}",
                            hospitalId
                    );
                    return new ServiceException(
                            HospitalErrorCode.HOSPITAL_DETAIL_NOT_FOUND
                    );
                });

        List<HospitalCapability> capabilities = hospitalCapabilityRepository.findAllByHospital(hospital);

        return HospitalDetailResponse.from(
                hospital,
                detail,
                capabilities,
                calculateOpenNow(
                        hospital.getBusinessStatus(),
                        detail.getOpenHours()
                )
        );
    }

    @Transactional(readOnly = true)
    public HospitalSearchPageResponse hospitalSearch(
            String keyword,
            String region,
            BigDecimal latitude,
            BigDecimal longitude,
            BigDecimal radiusKm,
            List<String> requiredCapabilities,
            List<String> supportedSpecies,
            Boolean surgery,
            Boolean hospitalization,
            Boolean nightCare,
            Boolean emergency,
            boolean partnerOnly,
            boolean openNowOnly,
            int page,
            int size,
            String sort
    ) {
        // 위치 조건은 위도·경도가 함께 있어야 하며, 반경은 좌표가 있을 때만 사용할 수 있습니다.
        validateLocationCondition(latitude, longitude, radiusKm);

        // 정렬값을 소문자로 통일하고 name·distance 이외의 값은 거부합니다.
        String normalizedSort = normalizeSort(sort, latitude, longitude);

        // Service에서 검증·변환한 검색 조건만 Repository에 전달합니다.
        HospitalSearchCondition condition = new HospitalSearchCondition(
                keyword,
                region,
                latitude,
                longitude,
                radiusKm,
                parseCapabilities(requiredCapabilities),
                parseSupportedSpecies(supportedSpecies),
                surgery,
                hospitalization,
                nightCare,
                emergency,
                partnerOnly
        );

        boolean requiresPostProcessing = radiusKm != null
                || openNowOnly
                || normalizedSort.equals("distance");
        boolean initialListing = isInitialListing(
                condition,
                openNowOnly,
                size,
                normalizedSort
        );

        if (requiresPostProcessing) {
            return searchWithPostProcessing(
                    condition,
                    latitude,
                    longitude,
                    radiusKm,
                    openNowOnly,
                    page,
                    size,
                    normalizedSort
            );
        }

        return searchWithDatabasePaging(
                condition,
                latitude,
                longitude,
                page,
                size,
                initialListing,
                initialListing && page == 1 && size == 20
        );
    }

    private HospitalSearchPageResponse searchWithDatabasePaging(
            HospitalSearchCondition condition,
            BigDecimal latitude,
            BigDecimal longitude,
            int page,
            int size,
            boolean partnerFirst,
            boolean cacheTarget
    ) {
        if (cacheTarget) {
            HospitalSearchCacheLookupResult cacheLookup =
                    hospitalSearchCacheRepository.findInitialPage();

            return cacheLookup.cachedPageOptional()
                    .map(cachedPage -> toPageResponse(
                            cachedPage,
                            latitude,
                            longitude,
                            page,
                            size
                    ))
                    .orElseGet(() -> searchAndCacheInitialPage(
                            condition,
                            latitude,
                            longitude,
                            page,
                            size,
                            partnerFirst,
                            cacheLookup.canWrite()
                    ));
        }

        long totalElements = hospitalRepository.count(condition);
        int totalPages = calculateTotalPages(totalElements, size);
        validatePageRange(page, totalPages);

        long offset = (long) (page - 1) * size;
        List<HospitalSearchResponse> content = totalElements == 0
                ? List.of()
                : searchPageCandidates(
                                condition,
                                offset,
                                size,
                                partnerFirst
                        ).stream()
                        .map(candidate -> toSearchResult(
                                candidate,
                                latitude,
                                longitude
                        ))
                        .map(this::toSearchResponse)
                        .toList();

        return HospitalSearchPageResponse.of(
                content,
                page,
                size,
                totalElements,
                totalPages
        );
    }

    private List<HospitalSearchCandidate> searchPageCandidates(
            HospitalSearchCondition condition,
            long offset,
            int size,
            boolean partnerFirst
    ) {
        if (partnerFirst) {
            return hospitalRepository.searchPartnerFirstPage(
                    condition,
                    offset,
                    size
            );
        }

        return hospitalRepository.searchPage(condition, offset, size);
    }

    private HospitalSearchPageResponse searchAndCacheInitialPage(
            HospitalSearchCondition condition,
            BigDecimal latitude,
            BigDecimal longitude,
            int page,
            int size,
            boolean partnerFirst,
            boolean cacheWritable
    ) {
        long totalElements = hospitalRepository.count(condition);
        List<HospitalSearchCandidate> candidates = totalElements == 0
                ? List.of()
                : searchPageCandidates(
                        condition,
                        0,
                        size,
                        partnerFirst
                );
        HospitalSearchCachedPage cachedPage = new HospitalSearchCachedPage(
                candidates,
                totalElements
        );

        if (cacheWritable) {
            hospitalSearchCacheRepository.saveInitialPage(cachedPage);
        }

        return toPageResponse(
                cachedPage,
                latitude,
                longitude,
                page,
                size
        );
    }

    private HospitalSearchPageResponse toPageResponse(
            HospitalSearchCachedPage cachedPage,
            BigDecimal latitude,
            BigDecimal longitude,
            int page,
            int size
    ) {
        long totalElements = cachedPage.totalElements();
        int totalPages = calculateTotalPages(totalElements, size);
        validatePageRange(page, totalPages);
        List<HospitalSearchResponse> content = cachedPage.content().stream()
                .map(candidate -> toSearchResult(
                        candidate,
                        latitude,
                        longitude
                ))
                .map(this::toSearchResponse)
                .toList();

        return HospitalSearchPageResponse.of(
                content,
                page,
                size,
                totalElements,
                totalPages
        );
    }

    private boolean isInitialListing(
            HospitalSearchCondition condition,
            boolean openNowOnly,
            int size,
            String sort
    ) {
        return condition.keyword() == null
                && condition.region() == null
                && condition.latitude() == null
                && condition.longitude() == null
                && condition.radiusKm() == null
                && condition.requiredCapabilities().isEmpty()
                && condition.supportedSpecies().isEmpty()
                && condition.surgery() == null
                && condition.hospitalization() == null
                && condition.nightCare() == null
                && condition.emergency() == null
                && !condition.partnerOnly()
                && !openNowOnly
                && size == 20
                && sort.equals("name");
    }

    private HospitalSearchPageResponse searchWithPostProcessing(
            HospitalSearchCondition condition,
            BigDecimal latitude,
            BigDecimal longitude,
            BigDecimal radiusKm,
            boolean openNowOnly,
            int page,
            int size,
            String sort
    ) {
        List<HospitalSearchResponse> searchedHospitals =
                hospitalRepository.searchAll(condition).stream()
                        .map(candidate -> toSearchResult(
                                candidate,
                                latitude,
                                longitude
                        ))
                        .filter(result ->
                                isWithinRadius(
                                        result.preciseDistanceKm(),
                                        radiusKm
                                ))
                        .filter(result ->
                                !openNowOnly || Boolean.TRUE.equals(
                                        result.openNow()
                                ))
                        .sorted(resolveSearchResultComparator(sort))
                        .map(this::toSearchResponse)
                        .toList();

        long totalElements = searchedHospitals.size();
        int totalPages = calculateTotalPages(totalElements, size);
        validatePageRange(page, totalPages);

        int fromIndex = (page - 1) * size;
        int toIndex = Math.min(fromIndex + size, searchedHospitals.size());

        return HospitalSearchPageResponse.of(
                searchedHospitals.subList(fromIndex, toIndex),
                page,
                size,
                totalElements,
                totalPages
        );
    }

    private int calculateTotalPages(
            long totalElements,
            int size
    ) {
        // 데이터가 한 건이라도 남으면 별도 페이지가 필요하므로 나눗셈 결과를 올림합니다.
        return totalElements == 0
                ? 0
                : (int) Math.ceil((double) totalElements / size);
    }

    private void validatePageRange(
            int page,
            int totalPages
    ) {
        // 결과가 없는 1페이지는 정상 빈 응답이고, 2페이지부터는 범위를 벗어난 요청입니다.
        boolean pageOutOfRange = page > 1
                && (totalPages == 0 || page > totalPages);

        if (pageOutOfRange) {
            throw new ServiceException(
                    CommonErrorCode.VALIDATION_FAILED
            );
        }
    }

    private List<CapabilityValue> parseCapabilities(
            List<String> capabilities
    ) {
        if (capabilities == null || capabilities.isEmpty()) {
            return List.of();
        }

        try {
            // 대소문자와 앞뒤 공백을 정리한 뒤 문자열을 CapabilityValue enum으로 변환합니다.
            return capabilities.stream()
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .map(value -> CapabilityValue.valueOf(
                            value.toUpperCase(Locale.ROOT)
                    ))
                    .distinct()
                    .toList();
        } catch (IllegalArgumentException exception) {
            // enum에 존재하지 않는 역량 문자열은 잘못된 검색 조건으로 처리합니다.
            throw new ServiceException(
                    CommonErrorCode.VALIDATION_FAILED
            );
        }
    }

    private List<CapabilityValue> parseSupportedSpecies(
            List<String> supportedSpecies
    ) {
        if (supportedSpecies == null || supportedSpecies.isEmpty()) {
            return List.of();
        }

        try {
            return supportedSpecies.stream()
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .map(value -> CapabilityValue.valueOf(
                            value.toUpperCase(Locale.ROOT)
                    ))
                    .peek(value -> {
                        if (value.getType() != CapabilityType.SPECIES) {
                            throw new IllegalArgumentException();
                        }
                    })
                    .distinct()
                    .toList();
        } catch (IllegalArgumentException exception) {
            throw new ServiceException(
                    CommonErrorCode.VALIDATION_FAILED
            );
        }
    }

    private HospitalSearchResult toSearchResult(
            HospitalSearchCandidate candidate,
            BigDecimal latitude,
            BigDecimal longitude
    ) {
        BigDecimal preciseDistanceKm = calculatePreciseDistanceKm(
                latitude,
                longitude,
                candidate.latitude(),
                candidate.longitude()
        );

        return new HospitalSearchResult(
                candidate,
                preciseDistanceKm,
                resolveOpenNow(candidate)
        );
    }

    private HospitalSearchResponse toSearchResponse(
            HospitalSearchResult result
    ) {
        return HospitalSearchResponse.from(
                result.candidate(),
                roundDistanceKm(result.preciseDistanceKm()),
                result.openNow()
        );
    }

    private Boolean resolveOpenNow(
            HospitalSearchCandidate candidate
    ) {
        // 비제휴 병원은 운영시간 데이터가 없으므로 현재 영업 여부를 null로 반환합니다.
        if (candidate.partnershipStatus() != PartnershipStatus.PARTNER) {
            return null;
        }

        return candidate.openHours() != null
                && calculateOpenNow(
                        candidate.businessStatus(),
                        candidate.openHours()
                );
    }

    private void validateLocationCondition(
            BigDecimal latitude,
            BigDecimal longitude,
            BigDecimal radiusKm
    ) {
        boolean hasLatitude = latitude != null;
        boolean hasLongitude = longitude != null;

        // 위도·경도 중 하나만 있거나, 기준 좌표 없이 반경만 있으면 거리를 계산할 수 없습니다.
        if (hasLatitude != hasLongitude
                || (radiusKm != null && !hasLatitude)) {
            throw new ServiceException(
                    CommonErrorCode.VALIDATION_FAILED
            );
        }
    }

    private String normalizeSort(
            String sort,
            BigDecimal latitude,
            BigDecimal longitude
    ) {
        String normalizedSort = sort == null
                ? "name"
                : sort.trim().toLowerCase(Locale.ROOT);

        // 검색 API가 지원하는 정렬 기준만 허용합니다.
        if (!normalizedSort.equals("name")
                && !normalizedSort.equals("distance")) {
            throw new ServiceException(
                    CommonErrorCode.VALIDATION_FAILED
            );
        }

        // 거리순 정렬은 사용자 위치가 있어야 병원별 거리를 계산할 수 있습니다.
        if (normalizedSort.equals("distance")
                && (latitude == null || longitude == null)) {
            throw new ServiceException(
                    CommonErrorCode.VALIDATION_FAILED
            );
        }

        return normalizedSort;
    }

    private boolean isWithinRadius(
            BigDecimal preciseDistanceKm,
            BigDecimal radiusKm
    ) {
        // 반경 조건이 없으면 거리와 관계없이 모든 병원을 통과시킵니다.
        if (radiusKm == null) {
            return true;
        }

        // 응답용 반올림 전 정밀 거리를 비교해 반경 밖 후보가 포함되지 않게 합니다.
        return preciseDistanceKm != null
                && preciseDistanceKm.compareTo(radiusKm) <= 0;
    }

    private Comparator<HospitalSearchResult> resolveSearchResultComparator(
            String sort
    ) {
        // 같은 이름의 병원도 항상 일정한 순서로 나오도록 hospitalId를 보조 정렬 기준으로 사용합니다.
        Comparator<HospitalSearchResult> nameComparator =
                Comparator.comparing(
                                (HospitalSearchResult result) ->
                                        result.candidate().name(),
                                Comparator.nullsLast(
                                        String.CASE_INSENSITIVE_ORDER
                                )
                        )
                        .thenComparing(
                                result -> result.candidate().hospitalId()
                        );

        if (sort.equals("distance")) {
            // 반올림 전 거리가 같으면 이름과 ID 순서로 결과를 고정합니다.
            return Comparator.comparing(
                            HospitalSearchResult::preciseDistanceKm,
                            Comparator.nullsLast(
                                    Comparator.naturalOrder()
                            )
                    )
                    .thenComparing(nameComparator);
        }

        return nameComparator;
    }

    private BigDecimal calculatePreciseDistanceKm(
            BigDecimal userLatitude,
            BigDecimal userLongitude,
            BigDecimal hospitalLatitude,
            BigDecimal hospitalLongitude
    ) {
        if (userLatitude == null
                || userLongitude == null
                || hospitalLatitude == null
                || hospitalLongitude == null) {
            return null;
        }

        // 하버사인 공식으로 지구 표면을 따른 두 좌표 사이의 직선거리를 계산합니다.
        final double earthRadiusKm = 6371.0088;
        double latitudeDistance = Math.toRadians(
                hospitalLatitude.doubleValue()
                        - userLatitude.doubleValue()
        );
        double longitudeDistance = Math.toRadians(
                hospitalLongitude.doubleValue()
                        - userLongitude.doubleValue()
        );
        double userLatitudeRadians = Math.toRadians(
                userLatitude.doubleValue()
        );
        double hospitalLatitudeRadians = Math.toRadians(
                hospitalLatitude.doubleValue()
        );

        double haversine =
                Math.sin(latitudeDistance / 2)
                        * Math.sin(latitudeDistance / 2)
                        + Math.cos(userLatitudeRadians)
                        * Math.cos(hospitalLatitudeRadians)
                        * Math.sin(longitudeDistance / 2)
                        * Math.sin(longitudeDistance / 2);

        double distance = earthRadiusKm
                * 2
                * Math.atan2(
                        Math.sqrt(haversine),
                        Math.sqrt(1 - haversine)
                );

        return BigDecimal.valueOf(distance);
    }

    private BigDecimal roundDistanceKm(BigDecimal preciseDistanceKm) {
        return preciseDistanceKm == null
                ? null
                : preciseDistanceKm.setScale(1, RoundingMode.HALF_UP);
    }

    private boolean calculateOpenNow(
            BusinessStatus businessStatus,
            Map<DayOfWeek, DailyOperatingHours> openHours
    ) {
        if (businessStatus != BusinessStatus.OPEN) {
            return false;
        }

        LocalDateTime now = LocalDateTime.now(SEOUL_ZONE_ID);
        DayOfWeek today = now.getDayOfWeek();
        LocalTime currentTime = now.toLocalTime();
        var todayHours = openHours.get(today);

        // 오늘 일정은 당일 시작 시각 이후 구간만 판단합니다.
        // 예: 화요일 20:00~02:00은 화요일 01:00이 아니라 20:00부터 적용됩니다.
        if (isOpenDuringTodayHours(todayHours, currentTime)) {
            return true;
        }

        // 자정 이후에는 전날 시작한 심야영업이 이어질 수 있으므로 전날 일정도 확인합니다.
        // 예: 월요일 20:00~02:00이면 화요일 01:00에도 영업 중입니다.
        DayOfWeek yesterday = today.minus(1);
        var yesterdayHours = openHours.get(yesterday);

        return isOpenAfterMidnight(yesterdayHours, currentTime);
    }

    private boolean isOpenDuringTodayHours(
            DailyOperatingHours operatingHours,
            LocalTime currentTime
    ) {
        if (operatingHours == null) {
            return false;
        }

        LocalTime openTime = operatingHours.openTime();
        LocalTime closeTime = operatingHours.closeTime();

        if (openTime.equals(closeTime)) {
            return false;
        }

        // 일반 영업은 시작 시각을 포함하고 종료 시각은 포함하지 않습니다.
        if (openTime.isBefore(closeTime)) {
            return !currentTime.isBefore(openTime)
                    && currentTime.isBefore(closeTime);
        }

        // 자정을 넘는 오늘 영업은 오늘 시작 시각 이후 구간만 여기서 판단합니다.
        // 자정 이후 종료 시각 이전 구간은 isOpenAfterMidnight에서 전날 일정으로 판단합니다.
        return !currentTime.isBefore(openTime);
    }

    private boolean isOpenAfterMidnight(
            DailyOperatingHours operatingHours,
            LocalTime currentTime
    ) {
        if (operatingHours == null) {
            return false;
        }

        LocalTime openTime = operatingHours.openTime();
        LocalTime closeTime = operatingHours.closeTime();

        // 시작 시각이 종료 시각보다 늦은 일정만 자정을 넘는 영업입니다.
        // 종료 시각 정각부터는 영업이 끝난 것으로 처리합니다.
        return openTime.isAfter(closeTime)
                && currentTime.isBefore(closeTime);
    }
}
