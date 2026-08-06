package com.doctorpet.domain.notification.push;

// 수신자(memberId)별 SSE 연결(SseEmitter)을 보관·전송·정리하는 인메모리 레지스트리(SA §9-8).
// 단일 인스턴스 기준이며, 다중 인스턴스 팬아웃(Redis pub/sub)은 수평 확장 시 후속으로 둔다.
// 저장이 알림의 원본이고 이 전송은 부가 채널이므로, 전송 실패는 조용히 연결을 정리할 뿐 상위 흐름에 전파하지 않는다.

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Component
public class SseEmitterRegistry {

    // 프록시·게이트웨이가 유휴 연결을 끊기 전에 heartbeat로 살려두므로 타임아웃은 넉넉히 잡는다(30분).
    private static final long EMITTER_TIMEOUT_MS = 30 * 60 * 1000L;
    private static final long HEARTBEAT_INTERVAL_SEC = 15L;

    private final Map<Long, Set<SseEmitter>> emittersByMember = new ConcurrentHashMap<>();

    // heartbeat는 죽은 연결로의 blocking send를 포함할 수 있으므로, 결제 정산·승인 타임아웃 등 시간에 민감한
    // @Scheduled 배치와 스레드를 공유하지 않도록 전용 단일 스레드 executor로 격리한다(공용 스케줄러 풀 크기=1).
    private ScheduledExecutorService heartbeatScheduler;

    @PostConstruct
    void startHeartbeat() {
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeatScheduler.scheduleWithFixedDelay(
                this::heartbeat, HEARTBEAT_INTERVAL_SEC, HEARTBEAT_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stopHeartbeat() {
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
        }
    }

    /**
     * 회원당 동시 연결 상한을 적용해 구독을 등록한다. 상한을 넘으면 {@code null}을 반환하고 호출자가 429로 거절한다.
     *
     * <p>"현재 개수 검사 + 추가"를 {@code compute}(키 단위 원자 연산) 안에서 함께 수행한다 — 두 단계로 나누면
     * 동시 구독 요청이 모두 같은 개수를 읽고 상한을 통과해 리소스 보호가 우회된다(PR #106 리뷰 P1).
     */
    public SseEmitter tryRegister(Long memberId, int maxConnections) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        boolean[] registered = {false};
        emittersByMember.compute(memberId, (key, existing) -> {
            Set<SseEmitter> emitters = existing == null ? ConcurrentHashMap.newKeySet() : existing;
            if (emitters.size() >= maxConnections) {
                // 상한 초과 — 등록하지 않는다. 새로 만든 빈 집합은 맵에 남기지 않는다(existing == null이면 키 미생성).
                return existing;
            }
            emitters.add(emitter);
            registered[0] = true;
            return emitters;
        });
        if (!registered[0]) {
            return null;
        }

        emitter.onCompletion(() -> remove(memberId, emitter));
        emitter.onTimeout(() -> {
            remove(memberId, emitter);
            emitter.complete();
        });
        emitter.onError(e -> remove(memberId, emitter));

        // 최초 이벤트를 흘려 응답 헤더를 flush한다 — 클라이언트 EventSource의 onopen이 즉시 뜨게 한다.
        sendOrDrop(memberId, emitter, SseEmitter.event().comment("connected"));
        return emitter;
    }

    // 특정 수신자의 모든 연결로 이벤트를 전송한다. 죽은 연결은 전송 실패 시 정리한다.
    public void send(Long memberId, String eventName, Object data) {
        Set<SseEmitter> emitters = emittersByMember.get(memberId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        SseEmitter.SseEventBuilder event = SseEmitter.event()
                .name(eventName)
                .data(data, MediaType.APPLICATION_JSON);
        for (SseEmitter emitter : emitters) {
            sendOrDrop(memberId, emitter, event);
        }
    }

    // 유휴 연결 유지용 heartbeat. 전송이 실패하는 죽은 연결은 이 주기에 함께 정리된다.
    private void heartbeat() {
        emittersByMember.forEach((memberId, emitters) -> {
            for (SseEmitter emitter : emitters) {
                sendOrDrop(memberId, emitter, SseEmitter.event().comment("ping"));
            }
        });
    }

    private void sendOrDrop(Long memberId, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            // 클라이언트가 이미 끊었거나 완료된 연결이다. 조용히 제거한다(저장이 원본, 전송은 부가 채널).
            remove(memberId, emitter);
        }
    }

    // 제거와 빈 키 정리를 compute(키 단위 원자 연산) 안에서 함께 처리한다 — isEmpty() 확인과 키 삭제를 나누면
    // 그 사이 같은 집합에 등록된 새 연결이 키와 함께 사라져 알림·heartbeat를 못 받는 고아가 된다(PR #106 리뷰 P2).
    // 실제 호출자는 emitter 완료·타임아웃·에러 콜백이며, package-private으로 둬 동시성 회귀 테스트가 직접 호출한다.
    void remove(Long memberId, SseEmitter emitter) {
        emittersByMember.compute(memberId, (key, emitters) -> {
            if (emitters == null) {
                return null;
            }
            emitters.remove(emitter);
            // null을 반환하면 키가 제거된다. 아직 남은 연결이 있으면 집합을 그대로 유지한다.
            return emitters.isEmpty() ? null : emitters;
        });
    }

    // 테스트·모니터링용: 현재 특정 수신자에게 열려 있는 연결 수.
    public int connectionCount(Long memberId) {
        Set<SseEmitter> emitters = emittersByMember.get(memberId);
        return emitters == null ? 0 : emitters.size();
    }
}
