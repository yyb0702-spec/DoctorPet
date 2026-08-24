package com.doctorpet.domain.member.repository;

/**
 * {@link RefreshTokenRepository#rotateIfMatches(Long, long, String, String, java.time.Duration)}의
 * 결과. 이슈 #100(재발급 락 TTL 레이스) 대응으로 STALE을 REUSED와 구분한다 — 둘 다 "저장된 값과
 * 일치하지 않음"이라는 점은 같지만, 의미와 안전한 대응이 완전히 다르다.
 */
public enum RotateResult {

    /** 제시된 토큰이 저장된 값과 일치해 정상적으로 회전(교체)됐다. */
    SUCCESS,

    /**
     * 펜싱 토큰이 이미 더 최신 회전보다 뒤처져 있다 — 락 TTL이 만료된 뒤 새 락(더 큰 펜싱 토큰)을
     * 얻은 다른 요청이 먼저 회전에 성공했다는 뜻이다. 이 요청은 시간 경쟁에서 진 것일 뿐 탈취
     * 정황이 아니므로, 값을 건드리지 않고(세션을 삭제하지 않고) 조용히 물러나야 한다.
     */
    STALE,

    /**
     * 펜싱 검사를 통과했는데도(즉 내가 이 세션에 대해 가장 최신 락 소유자인데도) 제시된 토큰이
     * 저장된 값과 일치하지 않는다 — 시간 경쟁으로 설명되지 않으므로 진짜 재사용(탈취 의심)으로
     * 간주한다. 저장소가 이미 세션을 삭제했다.
     */
    REUSED
}
