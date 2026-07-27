package com.doctorpet.domain.hospital.partnership.service;

import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.CapabilityValue;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.entity.HospitalCapability;
import com.doctorpet.domain.hospital.entity.HospitalDetail;
import com.doctorpet.domain.hospital.entity.PartnershipStatus;
import com.doctorpet.domain.hospital.model.DailyOperatingHours;
import com.doctorpet.domain.hospital.partnership.dto.PartnerHospitalDetailSeedData;
import com.doctorpet.domain.hospital.partnership.dto.PartnerHospitalOperatingHoursSeedData;
import com.doctorpet.domain.hospital.partnership.dto.PartnerHospitalSeedData;
import com.doctorpet.domain.hospital.partnership.mapper.PartnerHospitalSeedMapper;
import com.doctorpet.domain.hospital.repository.HospitalCapabilityRepository;
import com.doctorpet.domain.hospital.repository.HospitalDetailRepository;
import com.doctorpet.domain.hospital.repository.HospitalRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Map;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class PartnerHospitalSeedServiceTest {

    @Mock
    private HospitalRepository hospitalRepository;

    @Mock
    private HospitalDetailRepository hospitalDetailRepository;

    @Mock
    private HospitalCapabilityRepository hospitalCapabilityRepository;

    @Test
    void 처음_적용하면_제휴_상태와_상세정보와_역량을_저장한다() {
        Hospital hospital = createHospital();
        given(hospitalRepository.findByLocalGovCodeAndMgmtNo(
                "3130000",
                "313000001020260004"
        )).willReturn(Optional.of(hospital));
        given(hospitalDetailRepository.findByHospital(hospital))
                .willReturn(Optional.empty());
        given(hospitalCapabilityRepository.findAllByHospital(hospital))
                .willReturn(List.of());
        PartnerHospitalSeedService service = createService();
        PartnerHospitalSeedData seedData = createSeedData();

        int appliedCount = service.applyPartnerships(List.of(seedData));

        // 처음 실행하면 병원을 제휴로 전환하고 상세정보와 역량을 함께 저장합니다.
        assertThat(appliedCount).isEqualTo(1);
        assertThat(hospital.getPartnershipStatus())
                .isEqualTo(PartnershipStatus.PARTNER);
        then(hospitalDetailRepository).should()
                .save(any(HospitalDetail.class));
        then(hospitalCapabilityRepository).should()
                .saveAll(any());
    }

    @Test
    void JSON과_같은_상세정보와_역량은_다시_저장하지_않는다() {
        Hospital hospital = createHospital();
        PartnerHospitalSeedData seedData = createSeedData();
        HospitalDetail detail = HospitalDetail.create(
                hospital,
                Map.of(
                        DayOfWeek.MONDAY,
                        new DailyOperatingHours(
                                LocalTime.of(9, 0),
                                LocalTime.of(20, 0)
                        )
                ),
                true,
                true,
                false,
                false
        );
        List<HospitalCapability> capabilities = List.of(
                HospitalCapability.create(
                        hospital,
                        CapabilityValue.DOG
                ),
                HospitalCapability.create(
                        hospital,
                        CapabilityValue.XRAY
                )
        );
        given(hospitalRepository.findByLocalGovCodeAndMgmtNo(
                seedData.localGovernmentCode(),
                seedData.managementNumber()
        )).willReturn(Optional.of(hospital));
        given(hospitalDetailRepository.findByHospital(hospital))
                .willReturn(Optional.of(detail));
        given(hospitalCapabilityRepository.findAllByHospital(hospital))
                .willReturn(capabilities);

        createService().applyPartnerships(List.of(seedData));

        // 변경사항이 없으면 INSERT와 DELETE를 추가로 실행하지 않습니다.
        then(hospitalDetailRepository).should(never())
                .save(any(HospitalDetail.class));
        then(hospitalCapabilityRepository).should(never())
                .saveAll(any());
        then(hospitalCapabilityRepository).should(never())
                .deleteAll(any());
    }

    @Test
    void JSON에서_변경된_상세정보와_역량만_반영한다() {
        Hospital hospital = createHospital();
        PartnerHospitalSeedData seedData = createSeedData();
        HospitalDetail detail = HospitalDetail.create(
                hospital,
                Map.of(
                        DayOfWeek.MONDAY,
                        new DailyOperatingHours(
                                LocalTime.of(9, 0),
                                LocalTime.of(18, 0)
                        )
                ),
                false,
                false,
                false,
                false
        );
        HospitalCapability dog = HospitalCapability.create(
                hospital,
                CapabilityValue.DOG
        );
        HospitalCapability cat = HospitalCapability.create(
                hospital,
                CapabilityValue.CAT
        );
        given(hospitalRepository.findByLocalGovCodeAndMgmtNo(
                seedData.localGovernmentCode(),
                seedData.managementNumber()
        )).willReturn(Optional.of(hospital));
        given(hospitalDetailRepository.findByHospital(hospital))
                .willReturn(Optional.of(detail));
        given(hospitalCapabilityRepository.findAllByHospital(hospital))
                .willReturn(List.of(dog, cat));

        createService().applyPartnerships(List.of(seedData));

        // 상세정보는 JSON 값으로 갱신하고, CAT은 제거하며 XRAY만 추가합니다.
        assertThat(detail.getOpenHours().get(DayOfWeek.MONDAY))
                .isEqualTo(new DailyOperatingHours(
                        LocalTime.of(9, 0),
                        LocalTime.of(20, 0)
                ));
        assertThat(detail.isSurgeryAvailable()).isTrue();
        then(hospitalCapabilityRepository).should()
                .deleteAll(List.of(cat));
        then(hospitalCapabilityRepository).should()
                .saveAll(org.mockito.ArgumentMatchers.argThat(saved ->
                        ((List<HospitalCapability>) saved).size() == 1
                                && ((List<HospitalCapability>) saved)
                                .get(0)
                                .getCapabilityValue()
                                == CapabilityValue.XRAY
                ));
    }

    private PartnerHospitalSeedService createService() {
        return new PartnerHospitalSeedService(
                hospitalRepository,
                hospitalDetailRepository,
                hospitalCapabilityRepository,
                new PartnerHospitalSeedMapper()
        );
    }

    private PartnerHospitalSeedData createSeedData() {
        return new PartnerHospitalSeedData(
                "3130000",
                "313000001020260004",
                "시그널 동물의료센터",
                new PartnerHospitalDetailSeedData(
                        Map.of(
                                DayOfWeek.MONDAY,
                                new PartnerHospitalOperatingHoursSeedData(
                                        "09:00",
                                        "20:00"
                                )
                        ),
                        true,
                        true,
                        false,
                        false
                ),
                List.of(
                        CapabilityValue.DOG,
                        CapabilityValue.XRAY
                )
        );
    }

    private Hospital createHospital() {
        return Hospital.createFromPublicData(
                "313000001020260004",
                "3130000",
                "시그널 동물의료센터",
                "02-2088-0922",
                "서울특별시 마포구 성산동",
                "서울특별시 마포구 모래내로1길 9",
                "03938",
                null,
                null,
                LocalDate.of(2026, 7, 24),
                BusinessStatus.OPEN,
                null,
                null,
                null
        );
    }
}
