package com.doctorpet.domain.member.repository;

import com.doctorpet.global.security.AccessTokenBlacklistPort;
import com.doctorpet.global.security.MemberBlacklistPort;
import com.doctorpet.global.security.PasswordChangeInvalidationPort;
import com.doctorpet.global.security.TokenHasher;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/**
 * Refresh Token을 Redis에 저장한다. 키는 회원당 {@code refresh:{memberId}} 단일 키다(SA §6-1) —
 * 즉 회원당 세션이 하나뿐이며, 저장(덮어쓰기) 자체가 곧 회전(rotate)이다: 같은 키에 새 값을 쓰면
 * 이전 토큰은 더 이상 저장된 값과 일치하지 않으므로 자동으로 무효화된다([[A 도메인]] #6).
 *
 * 값은 토큰 원문이 아니라 SHA-256 해시를 저장한다(백로그 #123). Redis 값 자체가 "현재 유효한
 * 세션"의 증거이자 그대로 재발급에 쓸 수 있는 자격증명이므로, 원문을 저장하면 Redis가 노출되는
 * 사고(백업 유출, 오설정으로 인한 외부 접근, 관리자 콘솔 오남용 등)만으로 서명 검증 없이 곧바로
 * 세션을 탈취할 수 있다. 해시를 저장하면 그 사고가 나도 원문을 복원할 방법이 없다. salt/pepper를
 * 쓰지 않는 이유: 비밀번호와 달리 Refresh Token은 서버가 임의로 생성하는 고엔트로피 값이라
 * 레인보우테이블·사전 대입 공격 표면이 없고, 애초에 오프라인으로 원문을 추측해 시도할 수 있는
 * 통로도 없다(재발급은 항상 JWT 서명 검증을 먼저 통과해야 한다).
 *
 * MemberBlacklistPort·AccessTokenBlacklistPort도 함께 구현한다 — 탈퇴 회원(회원 단위)·로그아웃한
 * 특정 토큰(토큰 단위)을 인증 단계(global.security.JwtAuthenticationFilter)에서 걸러내려면 그
 * 필터가 이 저장소를 참조해야 하는데, global 패키지가 domain을 직접 참조하면 안 되므로 인터페이스는
 * global.security에 두고 여기서 구현만 제공한다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class RefreshTokenRepository implements MemberBlacklistPort, AccessTokenBlacklistPort, PasswordChangeInvalidationPort {

    private static final String KEY_PREFIX = "refresh:";
    private static final String LOCK_KEY_PREFIX = "refresh-lock:";
    private static final String FENCE_KEY_PREFIX = "refresh-fence:";
    private static final String WITHDRAWN_KEY_PREFIX = "withdrawn:";
    private static final String LOGOUT_BLACKLIST_KEY_PREFIX = "at-blacklist:";
    private static final String PASSWORD_CHANGED_KEY_PREFIX = "pwd-changed-at:";

    // 회원당 재발급 요청을 직렬화하는 락의 TTL. 크래시 등으로 unlock()이 못 불려도 이 시간 뒤엔
    // 자동으로 풀린다 — 정상 처리(회원 조회 + JWT 2개 생성 + Redis CAS 1회)는 이보다 훨씬 빨리 끝난다.
    //
    // 이 TTL을 넘겨 락이 자동으로 풀리면(GC 정지, 스레드 기아 등으로 원래 요청이 3초 안에 못
    // 끝내는 드문 경우), 뒤이은 동시 요청이 새 락을 얻어 먼저 회전을 끝낼 수 있다. 그 뒤 뒤늦게
    // 도착한 원래 요청의 CAS 시도가 "저장된 값과 다르다"는 이유만으로 무조건 재사용(탈취)으로
    // 판단해 세션을 삭제해버리면, 방금 성공한 요청의 새 세션까지 함께 지워진다(리뷰 지적,
    // 이슈 #100 P1). ROTATE_IF_MATCHES_SCRIPT의 펜싱 토큰 검사가 이 상황을 구분한다.
    private static final Duration LOCK_TTL = Duration.ofSeconds(3);

    /*
     * 락을 얻을 때마다(tryLock) 단조 증가하는 정수 하나를 새로 발급해 "펜싱 토큰"으로 쓴다
     * (KEYS[2] 카운터를 INCR). 락 TTL이 만료돼 다른 요청이 새 락 + 더 큰 펜싱 토큰을 얻으면,
     * 그 요청이 곧 "이 순간 가장 최신인 재발급 시도"가 된다 — 락은 한 번에 한 요청만 쥘 수
     * 있으므로(SET NX), 더 큰 펜싱 토큰을 가진 요청은 항상 더 이전 요청의 락 TTL이 만료된
     * 뒤에야 락을 얻을 수 있기 때문이다. ROTATE_IF_MATCHES_SCRIPT가 이 토큰으로 "지금 회전을
     * 시도하는 내가, 이 세션에 마지막으로 성공한 회전보다 최신인가"를 판단한다.
     */
    private static final RedisScript<Long> TRY_LOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('exists', KEYS[1]) == 1 then "
                    + "return -1 "
                    + "end "
                    + "local fence = redis.call('incr', KEYS[2]) "
                    + "redis.call('set', KEYS[1], ARGV[1], 'PX', ARGV[2]) "
                    + "return fence",
            Long.class
    );

    /*
     * 현재 저장된 값이 oldToken과 일치할 때만 newToken으로 교체한다(compare-and-set).
     * "조회 후 저장"을 두 단계로 나누면 같은 Refresh Token으로 동시에 들어온 재발급 요청이
     * 둘 다 비교를 통과해 각자 새 토큰을 저장하는 경쟁 상태가 생긴다 — Redis에게 GET과 SET을
     * 하나의 원자 연산(Lua)으로 실행시켜, 동시 요청 중 정확히 하나만 성공하도록 만든다.
     *
     * (이전에는 CAS 실패 시 "동시 중복 요청"과 "진짜 재사용"을 5초 유예 창으로 구분했는데, 그
     * 창 안에서는 실제 탈취 토큰 재사용도 눈감아주는 셈이라 리뷰에서 보안 약화로 지적됐다.
     * 그다음엔 AuthService.reissue()가 CAS를 시도하기 전에 tryLock/unlock으로 회원당 재발급을
     * 아예 직렬화해, 두 요청이 "동시에" CAS를 다투는 상황 자체를 없앴다 — 하지만 락 TTL(3초)이
     * 만료되면 세 번째 요청이 락을 새로 얻을 수 있어 그 전제가 깨진다. 지금은 ARGV[1]의 펜싱
     * 토큰으로 "내가 이 세션에 대해 가장 최신 락 소유자인가"까지 함께 확인한다.
     *
     * 이 비교는 두 값 중 더 큰 쪽(KEYS[2]=refresh-fence:{memberId}의 현재 값과, 저장된 값에
     * 기록된 마지막 성공 회전의 펜싱 토큰) 기준으로 해야 한다(2차 리뷰 지적) — 저장된 값의
     * 펜싱 토큰만 보면, "더 최신 락은 이미 발급됐지만(tryLock으로 refresh-fence 카운터는
     * 증가했지만) 그 락 소유자가 아직 회전을 실행하지 못한" 사이 시점에 뒤늦게 도착한 예전
     * 락 소유자의 회전이 통과해버린다 — 예전 락 소유자의 펜싱 토큰이 "아직 아무도 회전하지
     * 않은" 옛 저장값의 펜싱 토큰보다는 크기 때문이다. refresh-fence 카운터(지금까지 발급된
     * 가장 최신 펜싱 토큰)까지 함께 봐야 그 순간에도 정확히 걸러낼 수 있다. 내 펜싱 토큰이 이
     * 최댓값보다 작으면(STALE, -1 반환) — 이미 더 최신 요청이 락을 새로 얻었거나 회전까지
     * 끝냈다는 뜻이므로, 값을 비교하거나 건드리지 않고 그대로 물러난다. 이 검사를 통과한
     * 뒤에야(내가 최신이거나 아직 아무도 회전하지 않은 상태) 값 비교로 넘어가고, 거기서도
     * 일치하지 않으면 그건 시간 경쟁이 아니라 진짜 재사용이므로 세션을 삭제한다(REUSED, 0 반환).
     *
     * ARGV[2](해시)뿐 아니라 ARGV[3](원문)과도 비교하는 이유(#123 배포 마이그레이션, 리뷰 지적) —
     * 배포 직전까지는 이 키에 원문 Refresh Token이 그대로 저장돼 있었다. 해시만 비교하면 배포
     * 전에 로그인해 원문을 들고 있는 모든 사용자의 "첫" 재발급이 무조건 실패하고, reissue()는
     * 그걸 재사용(탈취 의심)으로 오판해 세션까지 삭제해버려 활성 사용자 전원이 강제 로그아웃된다.
     * 원문 비교 분기를 임시로 열어 배포 전 세션도 정상적으로 회전시키되, 새로 저장하는 값은
     * (ARGV[4]) 항상 해시다 — 그래서 한 번 회전을 거치면 그 회원은 자동으로 해시 저장으로
     * 넘어간다(lazy migration). Refresh Token 최대 TTL(14일)이 지나면 원문 값은 자연 소멸하므로
     * 배포일로부터 14일 뒤에는 ARGV[3] 비교 분기와 그 인자를 제거해도 안전하다.
     *
     * 저장 값의 형식은 "{펜싱 토큰}:{해시}"다. 콜론이 없는 값(펜싱 토큰이 아직 한 번도 기록되지
     * 않은 로그인 직후 save(), 또는 #123 배포 전 원문/해시)은 펜싱 토큰을 0으로 간주한다 — 어떤
     * 펜싱 토큰(항상 1 이상)이 와도 그보다 크므로 정상적으로 다음 단계(값 비교)로 넘어간다.
     * 해시(64자리 hex)와 원문(UUID, 하이픈만 사용)은 원래도 콜론을 포함하지 않으므로 형식 판별에
     * 모호함이 없다.
     */
    private static final RedisScript<Long> ROTATE_IF_MATCHES_SCRIPT = new DefaultRedisScript<>(
            "local stored = redis.call('get', KEYS[1]) "
                    + "local storedFence = 0 "
                    + "local storedValue = stored "
                    + "if stored then "
                    + "local sep = string.find(stored, ':', 1, true) "
                    + "if sep then "
                    + "storedFence = tonumber(string.sub(stored, 1, sep - 1)) "
                    + "storedValue = string.sub(stored, sep + 1) "
                    + "end "
                    + "end "
                    + "local currentFence = tonumber(redis.call('get', KEYS[2])) or 0 "
                    + "local maxFence = storedFence "
                    + "if currentFence > maxFence then "
                    + "maxFence = currentFence "
                    + "end "
                    + "if tonumber(ARGV[1]) < maxFence then "
                    + "return -1 "
                    + "end "
                    + "if storedValue == ARGV[2] or storedValue == ARGV[3] then "
                    + "redis.call('set', KEYS[1], ARGV[1] .. ':' .. ARGV[4], 'PX', ARGV[5]) "
                    + "return 1 "
                    + "end "
                    + "redis.call('del', KEYS[1]) "
                    + "return 0",
            Long.class
    );

    // 락을 쥔 요청만 스스로 풀 수 있게 한다(get-then-del을 원자적으로) — 그렇지 않으면 TTL 만료 후
    // 다른 요청이 새로 잡은 락을, 뒤늦게 unlock()을 호출한 원래 요청이 실수로 풀어버릴 수 있다.
    private static final RedisScript<Long> RELEASE_LOCK_IF_OWNED_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "redis.call('del', KEYS[1]) "
                    + "return 1 "
                    + "else "
                    + "return 0 "
                    + "end",
            Long.class
    );

    private final StringRedisTemplate redisTemplate;

    /** 토큰 원문이 아니라 그 해시를 저장한다(#123 — 클래스 Javadoc 참고). */
    public void save(Long memberId, String refreshToken, Duration ttl) {
        redisTemplate.opsForValue().set(key(memberId), TokenHasher.hash(refreshToken), ttl);
    }

    /**
     * 저장된 값에서 펜싱 토큰 접두사(있다면)를 뗀 순수 해시를 반환한다 — 토큰 원문을 저장하지
     * 않으므로 이 값과 원문을 직접 비교할 수는 없다. 원문 토큰 하나가 유효한지 확인하려면
     * {@link #matches(Long, String)}를 쓴다.
     */
    public Optional<String> findByMemberId(Long memberId) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(key(memberId)))
                .map(RefreshTokenRepository::stripFencingPrefix);
    }

    /** 제시된 원문 토큰을 해시해, 현재 저장된 값과 일치하는지 확인한다. */
    public boolean matches(Long memberId, String rawToken) {
        return findByMemberId(memberId)
                .map(storedHash -> storedHash.equals(TokenHasher.hash(rawToken)))
                .orElse(false);
    }

    /**
     * 현재 저장된 값(해시)이 {@code oldToken}의 해시와 정확히 일치할 때만 {@code newToken}의
     * 해시로 원자적으로 교체한다. {@code fencingToken}은 {@link #tryLock(Long, String)}이
     * 발급한 값을 그대로 넘겨야 한다 — 락 TTL이 만료돼 더 최신 펜싱 토큰을 가진 요청이 이미
     * 락을 얻었거나 회전에 성공했다면, 값을 비교·수정하지 않고 {@link RotateResult#STALE}을
     * 반환한다(이슈 #100). "이미 회전까지 끝났는지"(저장된 값의 펜싱 토큰)뿐 아니라 "더 최신
     * 락이 이미 발급됐는지"(refresh-fence 카운터의 현재 값)까지 함께 확인한다 — 후자만 놓치면,
     * 더 최신 락 소유자가 아직 회전을 실행하지 못한 사이 뒤늦게 도착한 예전 락 소유자의 회전이
     * 통과해버리는 레이스가 남는다(2차 리뷰 지적). 펜싱 검사를 통과했는데도 값이 일치하지
     * 않으면 진짜 재사용이므로 세션을 삭제하고 {@link RotateResult#REUSED}를 반환한다.
     */
    public RotateResult rotateIfMatches(Long memberId, long fencingToken, String oldToken, String newToken, Duration ttl) {
        Long result = redisTemplate.execute(
                ROTATE_IF_MATCHES_SCRIPT,
                List.of(key(memberId), fenceKey(memberId)),
                String.valueOf(fencingToken), TokenHasher.hash(oldToken), oldToken, TokenHasher.hash(newToken),
                String.valueOf(ttl.toMillis())
        );
        if (result == null) {
            return RotateResult.STALE;
        }
        if (result == 1L) {
            return RotateResult.SUCCESS;
        }
        return result == -1L ? RotateResult.STALE : RotateResult.REUSED;
    }

    /** 재사용 감지 시, 또는 로그아웃 시 세션을 완전히 무효화하기 위해 호출한다. */
    public void deleteByMemberId(Long memberId) {
        redisTemplate.delete(key(memberId));
    }

    /**
     * 회원당 재발급 요청을 직렬화하는 락을 시도한다(SET NX PX). 성공하면 그 회원에 대한 다른
     * 재발급 요청은 이 락이 풀릴 때까지(명시적 unlock 또는 TTL 만료) 즉시 실패해야 한다 — 그래야
     * 같은 토큰으로 동시에 들어온 요청들이 서로 CAS를 다투는 상황 자체가 생기지 않는다.
     * {@code lockToken}은 호출자를 식별하는 임의의 값(예: UUID)으로, unlock 시 본인 락인지
     * 확인하는 데 쓴다.
     *
     * 락을 얻으면 그 획득에 대응하는 펜싱 토큰(단조 증가, 1 이상)을 함께 발급한다 —
     * {@link #rotateIfMatches(Long, long, String, String, Duration)} 호출 시 반드시 이 값을
     * 그대로 넘겨야 한다(이슈 #100). 락을 얻지 못하면 빈 값을 반환한다.
     */
    public Optional<Long> tryLock(Long memberId, String lockToken) {
        Long fencingToken = redisTemplate.execute(
                TRY_LOCK_SCRIPT,
                List.of(lockKey(memberId), fenceKey(memberId)),
                lockToken, String.valueOf(LOCK_TTL.toMillis())
        );
        return (fencingToken != null && fencingToken > 0) ? Optional.of(fencingToken) : Optional.empty();
    }

    /** tryLock으로 얻은 락을 해제한다. lockToken이 일치할 때만(즉 내가 쥔 락일 때만) 지운다. */
    public void unlock(Long memberId, String lockToken) {
        redisTemplate.execute(RELEASE_LOCK_IF_OWNED_SCRIPT, List.of(lockKey(memberId)), lockToken);
    }

    /*
     * 탈퇴 회원 Access Token 블랙리스트(리뷰 지적 P1 대응) — JWT는 무상태라 탈퇴 시점에 이미
     * 발급된 Access Token을 서버가 즉시 폐기할 수단이 없다. Refresh Token 삭제(재발급 차단)만으로는
     * 만료 전까지 남은 Access Token으로 다른 도메인의 쓰기 API를 호출하는 것까지는 막지 못한다.
     *
     * TTL을 Access Token 만료 시간과 정확히 맞춰서(JwtProperties#getAccessTokenExpiration()),
     * 그 시점 이후엔 어차피 자연 만료라 블랙리스트 항목도 자동으로 사라지게 한다 — 별도의 정리
     * 배치가 필요 없고, 항목이 무한히 쌓이지도 않는다. JwtAuthenticationFilter가 매 요청마다
     * MySQL 조회 대신 이 키의 존재만 빠르게 확인한다(EXISTS, O(1)).
     */
    public void blacklistMember(Long memberId, Duration ttl) {
        redisTemplate.opsForValue().set(withdrawnKey(memberId), "1", ttl);
    }

    /** JwtAuthenticationFilter가 Access Token 인증 직전에 호출해, 탈퇴한 회원인지 확인한다. */
    @Override
    public boolean isBlacklisted(Long memberId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(withdrawnKey(memberId)));
    }

    /*
     * 로그아웃 시 Access Token 블랙리스트(#124) — JWT는 무상태라 로그아웃 시점에 이미 발급된
     * Access Token을 서버가 즉시 폐기할 수단이 없다. Refresh Token 삭제(재발급 차단)만으로는
     * 만료 전까지 남은 Access Token으로 API를 계속 호출하는 것까지는 막지 못한다.
     *
     * 탈퇴(blacklistMember)와 달리 회원 단위가 아니라 jti(토큰 고유 ID) 단위로 막는다 — 단일
     * 세션 정책상 회원 단위로 막으면, 로그아웃 직후 재로그인해서 받은 새 Access Token까지
     * 같은 memberId라는 이유로 함께 막혀버린다. jti는 토큰마다 유일하므로 "로그아웃한 바로 그
     * 토큰"만 정확히 걸러낸다.
     *
     * TTL은 호출자(AuthService)가 그 토큰의 실제 남은 수명(JwtTokenProvider#getRemainingTtl)을
     * 넘겨준다 — 그 시점 이후엔 어차피 자연 만료라 블랙리스트 항목도 자동으로 사라진다. ttl이
     * 0 이하(경쟁 상태로 이미 만료된 토큰)면 Redis에 쓰지 않는다 — 어차피 서명 검증에서 걸러지고,
     * PX에 0/음수를 넘기면 Redis가 에러를 낸다.
     */
    public void blacklistAccessToken(String jti, Duration ttl) {
        if (ttl.isZero() || ttl.isNegative()) {
            return;
        }
        redisTemplate.opsForValue().set(logoutBlacklistKey(jti), "1", ttl);
    }

    /** JwtAuthenticationFilter가 Access Token 인증 직전에 호출해, 로그아웃된 토큰인지 확인한다. */
    @Override
    public boolean isBlacklisted(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(logoutBlacklistKey(jti)));
    }

    /*
     * 비밀번호 재설정 시 기존 Access Token 무효화(기능 구멍 점검 대응, PasswordChangeInvalidationPort
     * 참고) — 이 시각을 "이 회원의 마지막 비밀번호 재설정 시각"으로 기록해둔다. blacklistMember()
     * (탈퇴)와 달리 memberId를 통째로 막지 않는 이유: 비밀번호 재설정 직후 사용자가 새 비밀번호로
     * 곧바로 재로그인하는 것이 정상 흐름인데, memberId 단위로 막으면 그때 발급되는 새 Access
     * Token까지 최대 TTL만큼 같이 막혀버린다. 대신 "이 시각 이전에 발급된 토큰"만 걸러내도록
     * 시각 자체를 저장해두고, isTokenInvalidatedByPasswordChange()가 토큰의 iat과 비교한다.
     *
     * TTL을 Access Token 만료 시간과 맞추는 이유는 blacklistMember()·blacklistAccessToken()과
     * 동일하다 — 그 시점 이후엔 재설정 이전에 발급된 토큰도 어차피 자연 만료라, 이 키가 사라져도
     * 안전하다.
     */
    public void invalidateTokensIssuedBeforeNow(Long memberId, Duration ttl) {
        redisTemplate.opsForValue().set(passwordChangedKey(memberId), String.valueOf(System.currentTimeMillis()), ttl);
    }

    /**
     * JwtAuthenticationFilter가 Access Token 인증 직전에 호출해, 이 토큰이 마지막 비밀번호
     * 재설정보다 먼저 발급됐는지 확인한다. 재설정 이력이 없으면(키 자체가 없거나 TTL 만료)
     * false — 아무 토큰도 걸러내지 않는다.
     *
     * (리뷰 지적 P2) tokenIssuedAt이 null이거나 Redis에 저장된 값이 파싱 불가능한 경우, 예외를
     * 그대로 던져 인증 필터 체인을 깨뜨리는 대신 fail-closed로 무효화 처리한다(true 반환) — 이
     * 메서드는 보안 판단(이 토큰을 계속 신뢰해도 되는가)이라, "비교할 수 없음"을 "통과시킴"으로
     * 해석하면 안 된다. tokenIssuedAt은 JwtTokenProvider.getIssuedAt()이 항상 채워 넣는 값이라
     * 정상 흐름에서 null일 일은 없지만(레거시 iat 폴백까지 포함), 호출부 계약이 바뀌거나 다른
     * 경로에서 호출될 가능성에 대비한 방어 코드다. Redis 값 파싱 실패는 이 키가 이 메서드
     * 외에는 아무도 쓰지 않는데도(invalidateTokensIssuedBeforeNow만 씀) 데이터 손상 시나리오에
     * 대비해 넣었다.
     */
    @Override
    public boolean isTokenInvalidatedByPasswordChange(Long memberId, Date tokenIssuedAt) {
        String value = redisTemplate.opsForValue().get(passwordChangedKey(memberId));
        if (value == null) {
            return false;
        }
        if (tokenIssuedAt == null) {
            log.warn(
                    "tokenIssuedAt이 없어 비밀번호 재설정 시각과 비교할 수 없습니다. "
                            + "안전하게 무효화 처리합니다. memberId={}",
                    memberId
            );
            return true;
        }
        try {
            return tokenIssuedAt.getTime() < Long.parseLong(value);
        } catch (NumberFormatException exception) {
            log.warn(
                    "비밀번호 재설정 시각 값이 손상되어 파싱할 수 없습니다. "
                            + "안전하게 무효화 처리합니다. memberId={}, value={}",
                    memberId,
                    value
            );
            return true;
        }
    }

    /**
     * 저장된 값(예: {@code "5:a3f5..."})에서 콜론 앞 펜싱 토큰을 떼고 순수 해시만 반환한다.
     * 콜론이 없으면(펜싱 토큰이 아직 한 번도 기록되지 않음 — 로그인 직후 save(), 또는 #123
     * 배포 전 원문/해시) 값 전체를 그대로 반환한다.
     */
    private static String stripFencingPrefix(String stored) {
        int sep = stored.indexOf(':');
        return sep >= 0 ? stored.substring(sep + 1) : stored;
    }

    private String key(Long memberId) {
        return KEY_PREFIX + memberId;
    }

    private String lockKey(Long memberId) {
        return LOCK_KEY_PREFIX + memberId;
    }

    /** tryLock이 펜싱 토큰을 발급하는 데 쓰는 단조 증가 카운터(INCR). TTL을 두지 않는다 — 회원당
     * 정수 하나뿐이라 크기 부담이 없고, 값이 끊김 없이 계속 증가해야 펜싱 검사가 성립한다. */
    private String fenceKey(Long memberId) {
        return FENCE_KEY_PREFIX + memberId;
    }

    private String withdrawnKey(Long memberId) {
        return WITHDRAWN_KEY_PREFIX + memberId;
    }

    private String logoutBlacklistKey(String jti) {
        return LOGOUT_BLACKLIST_KEY_PREFIX + jti;
    }

    private String passwordChangedKey(Long memberId) {
        return PASSWORD_CHANGED_KEY_PREFIX + memberId;
    }
}
