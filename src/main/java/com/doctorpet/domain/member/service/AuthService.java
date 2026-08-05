package com.doctorpet.domain.member.service;

import com.doctorpet.domain.member.dto.request.LoginRequest;
import com.doctorpet.domain.member.dto.request.ReissueRequest;
import com.doctorpet.domain.member.dto.request.SignupRequest;
import com.doctorpet.domain.member.dto.response.LoginResponse;
import com.doctorpet.domain.member.dto.response.SignupResponse;
import com.doctorpet.domain.member.entity.Member;
import com.doctorpet.domain.member.event.MemberSignedUpEvent;
import com.doctorpet.domain.member.exception.MemberErrorCode;
import com.doctorpet.domain.member.repository.MemberRepository;
import com.doctorpet.domain.member.repository.RefreshTokenRepository;
import com.doctorpet.domain.member.repository.RotateResult;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.security.JwtProperties;
import com.doctorpet.global.security.JwtTokenProvider;
import com.doctorpet.global.security.MemberPrincipal;
import com.doctorpet.global.security.TokenType;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher eventPublisher;

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

        // 인증 메일 발송은 이 트랜잭션의 커밋 이후에만 실행돼야 한다(MemberSignedUpEvent 참고) —
        // 여기서 직접 호출하면 SMTP를 기다리는 동안 트랜잭션·커넥션을 붙들고, 커밋 전에 인증 링크가
        // 클릭되면 토큰만 소비된 채 회원을 못 찾아 링크가 영구 무효화되는 문제가 있었다(리뷰 지적).
        eventPublisher.publishEvent(new MemberSignedUpEvent(savedMember));

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

        // 이메일 인증 전이면 로그인을 차단한다(백로그 P2, 가입 시 이메일 인증 필수 — A 도메인 결정).
        // 비밀번호 확인 이후에 체크하는 이유: 잠금 판정과 마찬가지로 이 코드는 "계정이 존재하고
        // 비밀번호가 맞다"는 사실 자체는 이미 확정된 뒤이므로 계정 존재 여부를 추가로 노출하지
        // 않는다. recordLoginSuccess()를 먼저 호출해 정상적인 비밀번호 입력을 실패 횟수에 반영하지
        // 않는다 — 미인증 상태는 "틀린 로그인 시도"가 아니다.
        if (!member.isEmailVerified()) {
            throw new ServiceException(MemberErrorCode.EMAIL_NOT_VERIFIED);
        }

        return issueTokenPair(member);
    }

    /*
     * 토큰 재발급. SA §8-1 + A 도메인 결정 #6(Refresh 회전 — 화이트리스트 방식).
     *
     * 1) JWT 자체의 서명·만료와 tokenType(REFRESH)을 검증한다 — Access Token으로는 재발급할 수 없다.
     * 2) 회원당 재발급 요청을 락으로 직렬화한다(tryLock/unlock) — 같은 토큰으로 동시에 들어온
     *    재발급 요청들이 서로 CAS를 다투는 상황 자체를 없앤다. 락을 못 얻으면 다른 요청이 이미
     *    처리 중이라는 뜻이므로 REISSUE_IN_PROGRESS(409)로 응답한다. 락 획득에 성공하면 함께
     *    발급되는 펜싱 토큰(fencingToken)을 4)에 그대로 전달한다.
     * 3) 회원이 여전히 존재하는지 확인한다.
     * 4) Redis에 저장된 "현재 유효한" Refresh Token과 제시된 토큰을 비교(compare)하고 새 토큰으로
     *    교체(swap)하는 것을 하나의 원자 연산(Lua)으로 처리한다.
     *
     *    (이전 리뷰 대응에서는 CAS 실패 시 "동시 중복 요청"과 "진짜 재사용"을 5초 유예 창으로
     *    구분했는데, 그 창 안에서는 실제 탈취 토큰 재사용도 눈감아주는 셈이라 보안 약화로 다시
     *    지적됐다. 그다음엔 2)의 락이 "동시에 CAS를 다투는 상황" 자체를 원천 차단한다고 봤지만,
     *    락 TTL(3초)이 만료되면 세 번째 요청이 새 락을 얻어 그 전제가 깨질 수 있다는 지적을 다시
     *    받았다(이슈 #100 P1) — 락 TTL을 넘겨 뒤늦게 도착한 CAS 시도가 "저장된 값과 다르다"는
     *    이유만으로 무조건 재사용으로 판단하면, 그사이 새 락으로 먼저 성공한 요청의 새 세션까지
     *    지워버린다. 지금은 rotateIfMatches가 펜싱 토큰으로 "내가 이 세션에 대해 가장 최신
     *    락 소유자인가"까지 함께 확인해 STALE(시간 경쟁에서 진 것뿐, 세션 안 건드림)과
     *    REUSED(진짜 재사용, 세션 무효화)를 구분한다.)
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

        String lockToken = UUID.randomUUID().toString();
        long fencingToken = refreshTokenRepository.tryLock(memberId, lockToken)
                .orElseThrow(() -> new ServiceException(MemberErrorCode.REISSUE_IN_PROGRESS));

        try {
            Member member = memberRepository.findById(memberId)
                    .orElseThrow(() -> new ServiceException(MemberErrorCode.INVALID_REFRESH_TOKEN));

            String newAccessToken = jwtTokenProvider.generateAccessToken(memberId, member.getEmail(), member.getRole().name());
            String newRefreshToken = jwtTokenProvider.generateRefreshToken(memberId, member.getEmail(), member.getRole().name());
            Duration ttl = Duration.ofMillis(jwtProperties.getRefreshTokenExpiration());

            RotateResult result = refreshTokenRepository.rotateIfMatches(
                    memberId, fencingToken, presentedRefreshToken, newRefreshToken, ttl);

            return switch (result) {
                case SUCCESS -> LoginResponse.of(newAccessToken, newRefreshToken);
                // 락 TTL 만료로 더 최신 요청에게 추월당했을 뿐 — 세션은 그 요청이 이미 정상
                // 회전시켰으므로 건드리지 않는다. 이 요청 자신은 "다시 시도" 신호로 응답한다.
                case STALE -> throw new ServiceException(MemberErrorCode.REISSUE_IN_PROGRESS);
                case REUSED -> throw new ServiceException(MemberErrorCode.REFRESH_TOKEN_REUSED);
            };
        } finally {
            refreshTokenRepository.unlock(memberId, lockToken);
        }
    }

    /*
     * 로그아웃. SA §8-1 + A 도메인 결정 #3(단일 세션 유지) + 백로그 #124.
     * 세션이 회원당 하나뿐이므로, 저장된 Refresh Token(refresh:{memberId})을 삭제하면
     * 재발급(reissue)이 더 이상 불가능해진다. 그것만으로는 만료 전까지 남은 Access Token으로
     * 계속 API를 호출할 수 있었는데(#124 이전), 이제는 지금 로그아웃에 쓰인 그 Access Token도
     * jti 단위로 블랙리스트에 넣어 즉시 무효화한다.
     *
     * accessToken이 null일 수 있다(예: Authorization 헤더 없이 호출 — 이 엔드포인트는 인증이
     * 필요해 정상적으로는 발생하지 않지만, 방어적으로 처리) — 그 경우 Refresh Token 삭제까지만
     * 수행하고 블랙리스트는 건너뛴다.
     */
    public void logout(Long memberId, String accessToken) {
        refreshTokenRepository.deleteByMemberId(memberId);

        if (accessToken != null) {
            String jti = jwtTokenProvider.getJti(accessToken);
            Duration remainingTtl = jwtTokenProvider.getRemainingTtl(accessToken);
            refreshTokenRepository.blacklistAccessToken(jti, remainingTtl);
        }
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
