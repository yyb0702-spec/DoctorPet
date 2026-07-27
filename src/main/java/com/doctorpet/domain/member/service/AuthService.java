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
import com.doctorpet.global.security.TokenType;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    // MySQL이 UNIQUE 제약 위반(ER_DUP_ENTRY)에 실제로 내려주는 SQLState·벤더 오류 코드.
    // GlobalExceptionHandler의 같은 이름 상수와 의미가 같다 — 여기서 다시 정의하는 이유는
    // global이 domain(MemberErrorCode)을 참조하면 안 되기 때문에, "제약 위반 → 도메인 에러코드"
    // 변환 자체를 이 서비스 계층에서 해야 하고, 그러려면 이 판별 로직도 여기 있어야 한다.
    private static final String MYSQL_INTEGRITY_CONSTRAINT_VIOLATION_SQL_STATE = "23000";
    private static final int MYSQL_DUPLICATE_ENTRY_ERROR_CODE = 1062;
    private static final String EMAIL_UNIQUE_CONSTRAINT_NAME = "uk_members_email";

    private final MemberRepository memberRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;

    /*
      회원가입. SA §8-1: 활성 회원 기준 이메일 중복 시 409({@link MemberErrorCode#DUPLICATE_EMAIL}).
      가입은 항상 GUARDIAN이며, 병원 스태프는 시드로만 생성된다(SA §6-2).

      existsByEmail 사전 체크와 실제 save() 사이에는 틈이 있어, 그 사이 동시에 같은 이메일로
      가입 요청이 들어오면 사전 체크는 둘 다 통과하고 DB의 UNIQUE 제약(uk_members_email)에서만
      걸린다. 이 경쟁 상태를 GlobalExceptionHandler의 공통 DataIntegrityViolationException
      처리기로 넘기면 COMMON_006(DUPLICATE_RESOURCE)으로 응답돼, 사전 체크로 걸렸을 때의
      MEMBER_001과 같은 상황인데도 타이밍에 따라 code·message가 달라진다(리뷰 지적).
      여기서 이메일 제약 위반임을 확인해 사전 체크와 같은 예외로 변환해 계약을 통일한다.
     */
    @Transactional
    public SignupResponse signup(SignupRequest request) {
        if (memberRepository.existsByEmail(request.email())) {
            throw new ServiceException(MemberErrorCode.DUPLICATE_EMAIL);
        }

        String encodedPassword = passwordEncoder.encode(request.password());
        Member member = Member.createGuardian(request.email(), encodedPassword, request.nickname());

        Member savedMember;
        try {
            savedMember = memberRepository.save(member);
        } catch (DataIntegrityViolationException exception) {
            if (isEmailUniqueViolation(exception)) {
                throw new ServiceException(MemberErrorCode.DUPLICATE_EMAIL);
            }
            throw exception;
        }

        return SignupResponse.from(savedMember);
    }

    private boolean isEmailUniqueViolation(DataIntegrityViolationException exception) {
        Throwable cause = exception.getMostSpecificCause();
        return cause instanceof SQLException sqlException
                && MYSQL_INTEGRITY_CONSTRAINT_VIOLATION_SQL_STATE.equals(sqlException.getSQLState())
                && sqlException.getErrorCode() == MYSQL_DUPLICATE_ENTRY_ERROR_CODE
                && sqlException.getMessage() != null
                && sqlException.getMessage().contains(EMAIL_UNIQUE_CONSTRAINT_NAME);
    }

    /*
     * 로그인. SA §8-1 + A 도메인 결정 #1(로그인 실패 잠금)·#3(단일 세션)·#6(Refresh 회전).
     *
     * noRollbackFor=ServiceException: 비밀번호 불일치로 실패 카운트를 올린 뒤 예외를 던지는데,
     * 기본 동작대로 두면 런타임 예외 발생 시 트랜잭션이 롤백되면서 방금 올린 실패 카운트·잠금 설정까지
     * 함께 사라져버린다. 실패 기록 자체는 반드시 커밋돼야 하므로 이 예외에 한해 롤백하지 않는다.
     *
     * findByEmailForUpdate(비관적 락): 같은 계정으로 동시에 여러 로그인 요청이 오면(예: 무차별 대입)
     * 행 잠금 없이는 모든 트랜잭션이 같은 실패 카운트를 읽어 각자 +1만 저장하는 lost update가
     * 생겨 5회 잠금 임계값을 우회할 수 있다. 잠금으로 요청을 한 트랜잭션씩 직렬화한다.
     */
    @Transactional(noRollbackFor = ServiceException.class)
    public LoginResponse login(LoginRequest request) {
        Member member = memberRepository.findByEmailForUpdate(request.email())
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
     * 1) JWT 자체의 서명·만료와 tokenType(REFRESH)을 검증한다 — Access Token으로는 재발급할 수 없다.
     * 2) 회원이 여전히 존재하는지 확인한다.
     * 3) Redis에 저장된 "현재 유효한" Refresh Token과 제시된 토큰을 비교(compare)하고 새 토큰으로
     *    교체(swap)하는 것을 하나의 원자 연산(Lua)으로 처리한다 — 조회 후 저장을 분리하면, 같은
     *    Refresh Token으로 동시에 재발급 요청이 오는 경우 둘 다 비교를 통과해 두 응답 모두 200을
     *    받는 경쟁 상태가 생긴다. 원자 연산이 실패하면(불일치) REFRESH_TOKEN_REUSED로 응답한다.
     *
     *    다만 CAS 실패에는 두 가지 서로 다른 상황이 섞여 있다 — (a) 같은 토큰으로 거의 동시에 들어온
     *    다른 요청이 이미 정상적으로 회전시켜버린 "동시 중복 요청", (b) 이미 여러 세대 전에 폐기된
     *    토큰이 다시 제시된 "진짜 재사용(탈취 의심)". 리뷰에서 지적된 대로, 이 둘을 구분하지 않고
     *    매번 세션을 통째로 삭제하면 (a)의 경우 방금 성공한 요청이 받아간 새 Refresh Token까지
     *    함께 지워버리게 된다. wasRecentlyRotatedFrom으로 "직전에 정상 회전되어 나간 토큰"인지
     *    확인해 (a)일 때는 세션을 지우지 않고, (b)일 때만 전체 무효화한다.
     */
    @Transactional(readOnly = true)
    public LoginResponse reissue(ReissueRequest request) {
        String presentedRefreshToken = request.refreshToken();

        if (!jwtTokenProvider.validateToken(presentedRefreshToken)
                || jwtTokenProvider.getTokenType(presentedRefreshToken) != TokenType.REFRESH) {
            throw new ServiceException(MemberErrorCode.INVALID_REFRESH_TOKEN);
        }

        MemberPrincipal principal = jwtTokenProvider.getMemberPrincipal(presentedRefreshToken);
        Long memberId = principal.memberId();

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new ServiceException(MemberErrorCode.INVALID_REFRESH_TOKEN));

        String newAccessToken = jwtTokenProvider.generateAccessToken(memberId, member.getEmail(), member.getRole().name());
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(memberId, member.getEmail(), member.getRole().name());
        Duration ttl = Duration.ofMillis(jwtProperties.getRefreshTokenExpiration());

        boolean rotated = refreshTokenRepository.rotateIfMatches(memberId, presentedRefreshToken, newRefreshToken, ttl);
        if (!rotated) {
            if (!refreshTokenRepository.wasRecentlyRotatedFrom(memberId, presentedRefreshToken)) {
                refreshTokenRepository.deleteByMemberId(memberId);
            }
            throw new ServiceException(MemberErrorCode.REFRESH_TOKEN_REUSED);
        }

        return LoginResponse.of(newAccessToken, newRefreshToken);
    }

    /*
     * 로그아웃. SA §8-1 + A 도메인 결정 #3(단일 세션 유지).
     * 세션이 회원당 하나뿐이므로, 저장된 Refresh Token(refresh:{memberId})만 삭제하면
     * 재발급(reissue)이 더 이상 불가능해져 로그아웃이 완성된다. Access Token은 만료까지
     * 유효하게 남지만(무상태 JWT라 서버 측 즉시 폐기 수단이 없음), 이는 SA에서 이미 감수한 트레이드오프다.
     */
    public void logout(Long memberId) {
        refreshTokenRepository.deleteByMemberId(memberId);
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
