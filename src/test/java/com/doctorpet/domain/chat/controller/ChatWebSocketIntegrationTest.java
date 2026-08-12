package com.doctorpet.domain.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.chat.repository.ChatMessageRepository;
import com.doctorpet.domain.hospital.dto.response.HospitalDetailResponse;
import com.doctorpet.domain.hospital.service.HospitalService;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import com.doctorpet.global.security.JwtTokenProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "payment.gateway=fake",
        "payment.billing-key.enc-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "mail.provider=fake",
        "mail.verification.base-url=http://localhost/verify-email",
        "mail.password-reset.base-url=http://localhost/reset-password",
        "member.email-verified-backfill.enabled=false",
        "jwt.secret=doctorpet-chat-integration-test-secret-key-32-bytes-minimum",
        "jwt.access-token-expiration=3600000",
        "jwt.refresh-token-expiration=1209600000"
})
class ChatWebSocketIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private ChatMessageRepository chatMessageRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private ReservationSlotRepository reservationSlotRepository;
    @MockitoBean private HospitalService hospitalService;

    private final List<WebSocket> sockets = new ArrayList<>();
    private final List<Long> reservationIds = new ArrayList<>();
    private final List<Long> slotIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        sockets.forEach(socket -> {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").join();
            } catch (RuntimeException ignored) {
                // 인증 거부 경로는 서버가 먼저 세션을 닫을 수 있다.
            }
        });
        reservationIds.forEach(id -> {
            chatMessageRepository.deleteAll(chatMessageRepository
                    .findByReservationIdOrderByCreatedAtAscIdAsc(
                            id, org.springframework.data.domain.Pageable.unpaged()));
            reservationRepository.deleteById(id);
        });
        slotIds.forEach(reservationSlotRepository::deleteById);
    }

    @Test
    void rejectsQueryOnlyAndRefreshTokens_andDeliversOnlyAfterAuthorizedConnect() throws Exception {
        ChatFixture fixture = saveWritableReservation();
        ChatFixture otherGuardianFixture = saveWritableReservation();
        String accessToken = jwtTokenProvider.generateAccessToken(
                fixture.guardianId(), "guardian@example.com", MemberRole.GUARDIAN.name());
        String refreshToken = jwtTokenProvider.generateRefreshToken(
                fixture.guardianId(), "guardian@example.com", MemberRole.GUARDIAN.name());

        StompFrames queryOnly = open("?accessToken=" + accessToken);
        queryOnly.send(frame("CONNECT", List.of("accept-version:1.2"), ""));
        assertThat(queryOnly.awaitFrame()).startsWith("ERROR");

        StompFrames refresh = open("");
        refresh.send(frame("CONNECT", List.of(
                "accept-version:1.2", "Authorization:Bearer " + refreshToken), ""));
        assertThat(refresh.awaitFrame()).startsWith("ERROR");

        StompFrames valid = open("");
        valid.send(frame("CONNECT", List.of(
                "accept-version:1.2", "Authorization:Bearer " + accessToken), ""));
        assertThat(valid.awaitFrame()).startsWith("CONNECTED");

        valid.send(frame("SUBSCRIBE", List.of(
                "id:chat-1",
                "destination:/topic/chat/reservations/" + fixture.reservationId()), ""));

        valid.send(frame("SEND", List.of(
                "destination:/app/chat/reservations/" + fixture.reservationId() + "/messages",
                "content-type:application/json"), "{\"content\":\"실시간 메시지\"}"));
        String delivered = valid.awaitFrame();
        assertThat(delivered).startsWith("MESSAGE");
        assertThat(delivered).contains("실시간 메시지");

        StompFrames foreignSubscribe = connect(accessToken);
        foreignSubscribe.send(frame("SUBSCRIBE", List.of(
                "id:foreign-chat",
                "destination:/topic/chat/reservations/" + otherGuardianFixture.reservationId()), ""));
        assertThat(foreignSubscribe.awaitFrame()).startsWith("ERROR");

        StompFrames forgedSend = connect(accessToken);
        forgedSend.send(frame("SEND", List.of(
                "destination:/app/chat/reservations/" + otherGuardianFixture.reservationId() + "/messages",
                "content-type:application/json"), "{\"content\":\"위조 전송\"}"));
        assertThat(forgedSend.awaitFrame()).startsWith("ERROR");

        StompFrames reconnectedFrames = open("");
        reconnectedFrames.send(frame("CONNECT", List.of(
                "accept-version:1.2", "Authorization:Bearer " + accessToken), ""));
        assertThat(reconnectedFrames.awaitFrame()).startsWith("CONNECTED");

        var request = java.net.http.HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/api/reservations/" + fixture.reservationId()
                                + "/chat/messages?size=1"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .GET()
                .build();
        var response = HttpClient.newHttpClient().send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("실시간 메시지");
    }

    @Test
    void rejectsSendForHospitalCanceledReservation() throws Exception {
        ChatFixture fixture = saveWritableReservation(ReservationStatus.HOSPITAL_CANCELED);
        String accessToken = jwtTokenProvider.generateAccessToken(
                fixture.guardianId(), "guardian@example.com", MemberRole.GUARDIAN.name());

        StompFrames frames = connect(accessToken);
        frames.send(frame("SEND", List.of(
                "destination:/app/chat/reservations/" + fixture.reservationId() + "/messages",
                "content-type:application/json"), "{\"content\":\"병원 취소 후 전송\"}"));

        // MessageMapping의 상태 거부는 broker 단계의 destination 거부와 달리 STOMP ERROR 프레임을
        // 보장하지 않는다. 정책상 불변식은 종료 상태 메시지가 전달·저장되지 않는 것이다.
        assertThat(frames.pollFrame()).isNull();
        assertThat(chatMessageRepository.findByReservationIdOrderByCreatedAtAscIdAsc(
                fixture.reservationId(), org.springframework.data.domain.Pageable.unpaged())).isEmpty();
    }

    private StompFrames open(String query) throws Exception {
        CapturingWebSocketListener listener = new CapturingWebSocketListener();
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/chat" + query), listener)
                .get(5, TimeUnit.SECONDS);
        sockets.add(socket);
        listener.attach(socket);
        return new StompFrames(socket, listener.frames());
    }

    private StompFrames connect(String accessToken) throws Exception {
        StompFrames frames = open("");
        frames.send(frame("CONNECT", List.of(
                "accept-version:1.2", "Authorization:Bearer " + accessToken), ""));
        assertThat(frames.awaitFrame()).startsWith("CONNECTED");
        return frames;
    }

    private ChatFixture saveWritableReservation() {
        return saveWritableReservation(ReservationStatus.REQUESTED);
    }

    private ChatFixture saveWritableReservation(ReservationStatus status) {
        long hospitalId = System.nanoTime();
        long guardianId = hospitalId + 1;
        LocalDateTime now = LocalDateTime.now();
        ReservationSlot slot = reservationSlotRepository.saveAndFlush(ReservationSlot.create(
                hospitalId, now.plusDays(2), now.plusDays(2).plusMinutes(30)));
        slotIds.add(slot.getId());
        Reservation reservation = Reservation.request(
                guardianId, 1L, hospitalId, slot.getId(), 1L,
                "초코", "DOG", now, slot.getStartAt());
        ReflectionTestUtils.setField(reservation, "status", status);
        reservation = reservationRepository.saveAndFlush(reservation);
        reservationIds.add(reservation.getId());
        org.mockito.BDDMockito.given(hospitalService.getHospitalDetail(hospitalId))
                .willReturn(new HospitalDetailResponse(hospitalId, "테스트동물병원", null, null, null,
                        null, null, null, null, null, null, null, null, null, null, 0L, false));
        return new ChatFixture(reservation.getId(), guardianId);
    }

    private String frame(String command, List<String> headers, String body) {
        return command + "\n" + String.join("\n", headers) + "\n\n" + body + "\u0000";
    }

    private record ChatFixture(Long reservationId, Long guardianId) {
    }

    private record StompFrames(WebSocket socket, BlockingQueue<String> frames) {

        void send(String frame) {
            socket.sendText(frame, true).join();
        }

        String awaitFrame() throws InterruptedException {
            String received = frames.poll(5, TimeUnit.SECONDS);
            assertThat(received).as("STOMP frame within 5 seconds").isNotNull();
            return received;
        }

        String pollFrame() throws InterruptedException {
            return frames.poll(1, TimeUnit.SECONDS);
        }
    }

    private static final class CapturingWebSocketListener implements WebSocket.Listener {

        private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                frames.add(buffer.toString());
                buffer.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        private void attach(WebSocket webSocket) {
            webSocket.request(1);
        }

        private BlockingQueue<String> frames() {
            return frames;
        }
    }
}
