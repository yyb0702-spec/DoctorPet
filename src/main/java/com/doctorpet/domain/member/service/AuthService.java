package com.doctorpet.domain.member.service;

import com.doctorpet.domain.member.dto.request.LoginRequest;
import com.doctorpet.domain.member.dto.request.ReissueRequest;
import com.doctorpet.domain.member.dto.request.SignupRequest;
import com.doctorpet.domain.member.dto.response.LoginResponse;
import com.doctorpet.domain.member.dto.response.SignupResponse;
import com.doctorpet.domain.member.entity.Member;
import com.doctorpet.domain.member.exception.MemberErrorCode;
import com.doctorpet.domain.member.repository.MemberRepository;
import com.doctorpet.domain.member.repository.RefreshTokenRepository;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.security.JwtProperties;
import com.doctorpet.global.security.JwtTokenProvider;
import com.doctorpet.global.security.MemberPrincipal;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final MemberRepository memberRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;

    /*
      회원가입. SA §8-1: 활성 회원 기준 이메일 중복 시 409({@link MemberErrorCode#DUPLICATE_EMAIL}).
      가입은 항상 GUARDIAN이며, 병원 스태프는 시드로만 생성된다(SA §6-2).
     */
    @Transactional
    public SignupResponse signup(SignupRequest request) {
        if (memberRepository.existsByEmail(request.email())) {
            throw new ServiceException(MemberErrorCode.DUPLICATE_EMAIL);
        }

        String encodedPassword = passwordEncoder.encode(request.password());
        Member member = Member.createGuardian(request.email(), encodedPassword, request.nickname());
        Member savedMember = memberRepository.save(member);

        return SignupResponse.from(savedMember);
    }

    /*
     * 로그인. SA §8-1 + A 도메인 결정 #1(로그인 실패 잠금)·#3(단일 세션)·#6(Refresh 회전).
     *
     * noRollbackFor=ServiceException: 비밀번호 불일치로 실패 카운트를 올린 뒤 예외를 던지는데,
     * 기본 동작대로 두면 런타임 예외 발생 시 트랜잭션이 롤백되면서 방금 올린 실패 카운트·잠금 설정까지
     * 함께 사라져버린다. 실패 기록 자체는 반드시 커밋돼야 하므로 이 예외에 한해 롤백하지 않는다.
     */
    @Transactional(noRollbackFor = ServiceException.class)
    public LoginResponse login(LoginRequest request) {
        Member member = memberRepository.findByEmail(request.email())
                .orElseThrow(() -> new ServiceException(MemberErrorCode.INVALID_CREDENTIALS));

        LocalDateTime now = LocalDateTime.now();
        member.unlockIfExpired(now);
        if (member.isLocked()) {
            throw new ServiceException(MemberErrorCode.ACCOUNT_LOCKED);
        }

        if (!passwordEncoder.matches(request.password(), member.getPassword())) {
            member.recordLoginFailure(now);
            throw new ServiceException(MemberErrorCode.INVALID_CREDENTIALS);
        }

        member.recordLoginSuccess();

        return issueTokenPair(member);
    }

    /*
     * 토큰 재발급. SA §8-1 + A 도메인 결정 #6(Refresh 회전 — 화이트리스트 방식).
     *
     * 1) JWT 자체의 서명·만료를 검증한다.
     * 2) 그 안의 memberId로 Redis에 저장된 "현재 유효한" Refresh Token과 정확히 같은지 비교한다.
     *    다르면(이미 회전으로 폐기된 토큰이 재사용된 것) 재사용 감지로 보고 세션을 완전히 무효화한다.
     * 3) 일치하면 새 토큰 쌍을 발급하고 Redis 값을 덮어써 회전시킨다.
     */
    @Transactional(readOnly = true)
    public LoginResponse reissue(ReissueRequest request) {
        String presentedRefreshToken = request.refreshToken();

        if (!jwtTokenProvider.validateToken(presentedRefreshToken)) {
            throw new ServiceException(MemberErrorCode.INVALID_REFRESH_TOKEN);
        }

        MemberPrincipal principal = jwtTokenProvider.getMemberPrincipal(presentedRefreshToken);
        Long memberId = principal.memberId();

        String storedRefreshToken = refreshTokenRepository.findByMemberId(memberId)
                .orElse(null);

        if (storedRefreshToken == null || !storedRefreshToken.equals(presentedRefreshToken)) {
            refreshTokenRepository.deleteByMemberId(memberId);
            throw new ServiceException(MemberErrorCode.REFRESH_TOKEN_REUSED);
        }

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new ServiceException(MemberErrorCode.INVALID_REFRESH_TOKEN));

        return issueTokenPair(member);
    }

    /** Access/Refresh Token을 새로 발급하고, Refresh Token은 Redis에 덮어써 회전시킨다. */
    private LoginResponse issueTokenPair(Member member) {
        String role = member.getRole().name();
        String accessToken = jwtTokenProvider.generateAccessToken(member.getId(), member.getEmail(), role);
        String refreshToken = jwtTokenProvider.generateRefreshToken(member.getId(), member.getEmail(), role);

        refreshTokenRepository.save(
                member.getId(),
                refreshToken,
                Duration.ofMillis(jwtProperties.getRefreshTokenExpiration())
        );

        return LoginResponse.of(accessToken, refreshToken);
    }
}
