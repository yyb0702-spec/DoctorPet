package com.doctorpet.domain.hospital.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.hospital.exception.HospitalErrorCode;
import com.doctorpet.domain.hospital.repository.HospitalFavoriteRepository;
import com.doctorpet.domain.hospital.repository.HospitalRepository;
import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.global.exception.ServiceException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HospitalFavoriteServiceTest {

    @Mock
    private HospitalRepository hospitalRepository;

    @Mock
    private HospitalFavoriteRepository hospitalFavoriteRepository;

    @Mock
    private MemberService memberService;

    @InjectMocks
    private HospitalFavoriteService hospitalFavoriteService;

    @Test
    void 존재하는_병원은_멱등_삽입으로_찜한다() {
        given(hospitalRepository.existsById(10L)).willReturn(true);

        hospitalFavoriteService.addFavorite(1L, 10L);

        verify(memberService).lockActiveMember(1L);
        verify(hospitalFavoriteRepository).insertIfAbsent(1L, 10L);
    }

    @Test
    void 존재하지_않는_병원은_찜할_수_없다() {
        given(hospitalRepository.existsById(10L)).willReturn(false);

        assertThatThrownBy(() ->
                hospitalFavoriteService.addFavorite(1L, 10L)
        )
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> assertThat(
                        ((ServiceException) exception).getErrorCode()
                ).isEqualTo(HospitalErrorCode.HOSPITAL_NOT_FOUND));

        verify(hospitalFavoriteRepository, never())
                .insertIfAbsent(1L, 10L);
    }

    @Test
    void 찜_해제는_대상_존재_여부와_무관하게_삭제를_요청한다() {
        hospitalFavoriteService.removeFavorite(1L, 10L);

        verify(hospitalFavoriteRepository)
                .deleteByMemberIdAndHospitalId(1L, 10L);
    }

    @Test
    void 페이지의_병원_ID에_해당하는_내_찜만_집합으로_반환한다() {
        given(hospitalFavoriteRepository
                .findHospitalIdsByMemberIdAndHospitalIdIn(
                        1L,
                        List.of(10L, 20L)
                )).willReturn(List.of(20L));

        Set<Long> result = hospitalFavoriteService
                .findFavoriteHospitalIds(1L, List.of(10L, 20L));

        assertThat(result).containsExactly(20L);
    }

    @Test
    void 비로그인_또는_빈_페이지는_저장소를_조회하지_않는다() {
        assertThat(hospitalFavoriteService.findFavoriteHospitalIds(
                null,
                List.of(10L)
        )).isEmpty();
        assertThat(hospitalFavoriteService.findFavoriteHospitalIds(
                1L,
                List.of()
        )).isEmpty();

        verify(hospitalFavoriteRepository, never())
                .findHospitalIdsByMemberIdAndHospitalIdIn(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyCollection()
                );
    }
}
