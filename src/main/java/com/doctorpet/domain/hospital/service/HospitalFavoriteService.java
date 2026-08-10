package com.doctorpet.domain.hospital.service;

import com.doctorpet.domain.hospital.dto.response.FavoriteHospitalPageResponse;
import com.doctorpet.domain.hospital.dto.response.FavoriteHospitalResponse;
import com.doctorpet.domain.hospital.exception.HospitalErrorCode;
import com.doctorpet.domain.hospital.repository.HospitalFavoriteRepository;
import com.doctorpet.domain.hospital.repository.HospitalRepository;
import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.global.exception.ServiceException;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class HospitalFavoriteService {

    private final HospitalRepository hospitalRepository;
    private final HospitalFavoriteRepository hospitalFavoriteRepository;
    private final MemberService memberService;

    @Transactional
    public void addFavorite(Long memberId, Long hospitalId) {
        memberService.lockActiveMember(memberId);

        if (!hospitalRepository.existsById(hospitalId)) {
            throw new ServiceException(HospitalErrorCode.HOSPITAL_NOT_FOUND);
        }

        hospitalFavoriteRepository.insertIfAbsent(memberId, hospitalId);
    }

    @Transactional
    public void removeFavorite(Long memberId, Long hospitalId) {
        hospitalFavoriteRepository.deleteByMemberIdAndHospitalId(
                memberId,
                hospitalId
        );
    }

    @Transactional(readOnly = true)
    public FavoriteHospitalPageResponse getMyFavorites(
            Long memberId,
            int page,
            int size
    ) {
        Page<FavoriteHospitalResponse> favorites =
                hospitalFavoriteRepository.findByMemberId(
                        memberId,
                        PageRequest.of(
                                page - 1,
                                size,
                                Sort.by(
                                        Sort.Order.desc("createdAt"),
                                        Sort.Order.desc("id")
                                )
                        )
                ).map(FavoriteHospitalResponse::from);

        return FavoriteHospitalPageResponse.from(favorites);
    }

    @Transactional(readOnly = true)
    public Set<Long> findFavoriteHospitalIds(
            Long memberId,
            Collection<Long> hospitalIds
    ) {
        if (memberId == null || hospitalIds.isEmpty()) {
            return Set.of();
        }

        return hospitalFavoriteRepository
                .findHospitalIdsByMemberIdAndHospitalIdIn(
                        memberId,
                        hospitalIds
                ).stream()
                .collect(Collectors.toUnmodifiableSet());
    }

    @Transactional
    public void deleteAllByMemberId(Long memberId) {
        hospitalFavoriteRepository.deleteAllByMemberId(memberId);
    }
}
