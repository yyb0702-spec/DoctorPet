package com.doctorpet.domain.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static com.doctorpet.domain.hospital.support.HospitalDetailTestFixture.partnerHospital;

import com.doctorpet.domain.chat.repository.ChatMessageRepository;
import com.doctorpet.domain.hospital.service.HospitalService;
import com.doctorpet.domain.chat.port.ChatMemberProfilePort;
import com.doctorpet.domain.member.entity.Member;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.repository.MemberRepository;
import com.doctorpet.domain.member.repository.RefreshTokenRepository;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import com.doctorpet.global.security.JwtTokenProvider;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.simp.SimpMessagingTemplate;
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
    @Autowired private MemberRepository memberRepository;
    @Autowired private SimpMessagingTemplate messagingTemplate;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @MockitoBean private HospitalService hospitalService;
    @MockitoBean private ChatMemberProfilePort memberProfilePort;

    private final List<WebSocket> sockets = new ArrayList<>();
    private final List<Long> reservationIds = new ArrayList<>();
    private final List<Long> slotIds = new ArrayList<>();
    private final List<Long> memberIds = new ArrayList<>();

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
        memberIds.forEach(memberRepository::deleteById);
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
                "destination:/app/chat/reservations/" + fixture.reservationId() + "/subscription-ready",
                "content-type:application/json"), ""));
        String ready = valid.awaitFrame();
        assertThat(ready).startsWith("MESSAGE");
        assertThat(ready).contains("SUBSCRIPTION_READY");

        valid.send(frame("SEND", List.of(
                "destination:/app/chat/reservations/" + fixture.reservationId() + "/messages",
                "content-type:application/json"), "{\"content\":\"실시간 메시지\",\"clientMessageId\":\"11111111-1111-4111-8111-111111111111\"}"));
        String delivered = valid.awaitFrame();
        assertThat(delivered).startsWith("MESSAGE");
        assertThat(delivered).contains("실시간 메시지");

        valid.send(frame("MESSAGE", List.of(
                "destination:/topic/chat/reservations/" + fixture.reservationId(),
                "content-type:application/json"), "{\"content\":\"위조 topic 주입\"}"));
        assertThat(valid.awaitFrame()).startsWith("ERROR");
        assertThat(chatMessageRepository.findByReservationIdOrderByCreatedAtAscIdAsc(
                fixture.reservationId(), org.springframework.data.domain.Pageable.unpaged()))
                .hasSize(1);

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

    @Test
    void loggedOutSession_cannotSendOrReceiveExistingChatMessages() throws Exception {
        ChatFixture fixture = saveWritableReservation();
        String accessToken = jwtTokenProvider.generateAccessToken(
                fixture.guardianId(), "guardian@example.com", MemberRole.GUARDIAN.name());
        StompFrames frames = connect(accessToken);
        frames.send(frame("SUBSCRIBE", List.of(
                "id:chat-logout",
                "destination:/topic/chat/reservations/" + fixture.reservationId()), ""));
        awaitActiveSubscription(frames, fixture.reservationId());

        refreshTokenRepository.blacklistAccessToken(
                jwtTokenProvider.getJti(accessToken), Duration.ofMinutes(30));
        messagingTemplate.convertAndSend(
                "/topic/chat/reservations/" + fixture.reservationId(),
                "logout-after-subscribe"
        );
        assertThat(frames.pollFrame()).isNull();

        frames.send(frame("SEND", List.of(
                "destination:/app/chat/reservations/" + fixture.reservationId() + "/messages",
                "content-type:application/json"), "{\"content\":\"무효화 후 전송\"}"));
        assertThat(frames.awaitFrame()).startsWith("ERROR");
        assertThat(chatMessageRepository.findByReservationIdOrderByCreatedAtAscIdAsc(
                fixture.reservationId(), org.springframework.data.domain.Pageable.unpaged())).isEmpty();
    }

    @Test
    void guardianAndHospitalStaff_exchangeMessagesAndReceiveOwnAcknowledgements() throws Exception {
        ChatFixture fixture = saveWritableReservation();
        String guardianToken = jwtTokenProvider.generateAccessToken(
                fixture.guardianId(), "guardian@example.com", MemberRole.GUARDIAN.name());
        String staffToken = jwtTokenProvider.generateAccessToken(
                fixture.staffId(), "staff@example.com", MemberRole.HOSPITAL_STAFF.name());

        StompFrames guardian = connect(guardianToken);
        guardian.send(frame("SUBSCRIBE", List.of(
                "id:guardian-ack", "destination:/user/queue/chat/send-acks"), ""));
        guardian.send(frame("SUBSCRIBE", List.of(
                "id:guardian-chat", "destination:/topic/chat/reservations/" + fixture.reservationId()), ""));

        StompFrames staff = connect(staffToken);
        staff.send(frame("SUBSCRIBE", List.of(
                "id:staff-ack", "destination:/user/queue/chat/send-acks"), ""));
        staff.send(frame("SUBSCRIBE", List.of(
                "id:staff-chat", "destination:/topic/chat/reservations/" + fixture.reservationId()), ""));

        guardian.send(frame("SEND", List.of(
                "destination:/app/chat/reservations/" + fixture.reservationId() + "/subscription-ready",
                "content-type:application/json"), ""));
        guardian.awaitFrameContaining("SUBSCRIPTION_READY");
        staff.send(frame("SEND", List.of(
                "destination:/app/chat/reservations/" + fixture.reservationId() + "/subscription-ready",
                "content-type:application/json"), ""));
        staff.awaitFrameContaining("SUBSCRIPTION_READY");

        guardian.send(frame("SEND", List.of(
                "destination:/app/chat/reservations/" + fixture.reservationId() + "/messages",
                "content-type:application/json"),
                "{\"content\":\"guardian message\",\"clientMessageId\":\"22222222-2222-4222-8222-222222222222\"}"));
        guardian.awaitFrameContaining("guardian message");
        guardian.awaitFrameContaining("\"clientMessageId\":\"22222222-2222-4222-8222-222222222222\"");
        staff.awaitFrameContaining("guardian message");

        staff.send(frame("SEND", List.of(
                "destination:/app/chat/reservations/" + fixture.reservationId() + "/messages",
                "content-type:application/json"),
                "{\"content\":\"staff reply\",\"clientMessageId\":\"33333333-3333-4333-8333-333333333333\"}"));
        staff.awaitFrameContaining("staff reply");
        staff.awaitFrameContaining("\"clientMessageId\":\"33333333-3333-4333-8333-333333333333\"");
        guardian.awaitFrameContaining("staff reply");

        assertThat(chatMessageRepository.findByReservationIdOrderByCreatedAtAscIdAsc(
                fixture.reservationId(), org.springframework.data.domain.Pageable.unpaged())).hasSize(2);
    }

    @Test
    void webSocketHandshake_allowsConfiguredCorsOrigin() throws Exception {
        assertThat(handshakeStatus("http://localhost:5173")).isEqualTo(101);
    }

    @Test
    void webSocketHandshake_rejectsUnconfiguredOrigin() throws Exception {
        assertThat(handshakeStatus("https://untrusted.example")).isEqualTo(403);
    }

    @Test
    void webSocketHandshake_allowsRequestsWithoutOrigin() throws Exception {
        assertThat(handshakeStatus(null)).isEqualTo(101);
    }

    private void awaitActiveSubscription(StompFrames frames, Long reservationId) throws InterruptedException {
        String delivered = null;
        for (int attempt = 1; attempt <= 5 && delivered == null; attempt++) {
            messagingTemplate.convertAndSend(
                    "/topic/chat/reservations/" + reservationId,
                    "before-logout-" + attempt
            );
            delivered = frames.pollFrame();
        }
        assertThat(delivered).as("활성화된 STOMP 구독의 사전 MESSAGE").startsWith("MESSAGE");
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

    private int handshakeStatus(String origin) throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5_000);
            String request = "GET /ws/chat HTTP/1.1\r\n"
                    + "Host: localhost:" + port + "\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                    + (origin == null ? "" : "Origin: " + origin + "\r\n")
                    + "\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            String statusLine = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                    .readLine();
            assertThat(statusLine).startsWith("HTTP/1.1 ");
            return Integer.parseInt(statusLine.substring("HTTP/1.1 ".length(), "HTTP/1.1 ".length() + 3));
        }
    }

    private ChatFixture saveWritableReservation() {
        return saveWritableReservation(ReservationStatus.REQUESTED);
    }

    private ChatFixture saveWritableReservation(ReservationStatus status) {
        long hospitalId = System.nanoTime();
        LocalDateTime now = LocalDateTime.now();
        Member guardian = memberRepository.saveAndFlush(Member.createGuardian(
                "chat-guardian-" + hospitalId + "@example.com", "encoded", "guardian"));
        memberIds.add(guardian.getId());
        Member staff = Member.createGuardian(
                "chat-staff-" + hospitalId + "@example.com", "encoded", "staff");
        ReflectionTestUtils.setField(staff, "role", MemberRole.HOSPITAL_STAFF);
        ReflectionTestUtils.setField(staff, "hospitalId", hospitalId);
        staff = memberRepository.saveAndFlush(staff);
        memberIds.add(staff.getId());
        ReservationSlot slot = reservationSlotRepository.saveAndFlush(ReservationSlot.create(
                hospitalId, now.plusDays(2), now.plusDays(2).plusMinutes(30)));
        slotIds.add(slot.getId());
        Reservation reservation = Reservation.request(
                guardian.getId(), 1L, hospitalId, slot.getId(), 1L,
                "초코", "DOG", now, slot.getStartAt());
        ReflectionTestUtils.setField(reservation, "status", status);
        reservation = reservationRepository.saveAndFlush(reservation);
        reservationIds.add(reservation.getId());
        org.mockito.BDDMockito.given(hospitalService.getHospitalDetail(hospitalId))
                .willReturn(partnerHospital(hospitalId, "테스트동물병원"));
        org.mockito.BDDMockito.given(memberProfilePort.getGuardianNickname(guardian.getId()))
                .willReturn("테스트보호자");
        return new ChatFixture(reservation.getId(), guardian.getId(), staff.getId());
    }

    private String frame(String command, List<String> headers, String body) {
        return command + "\n" + String.join("\n", headers) + "\n\n" + body + "\u0000";
    }

    private record ChatFixture(Long reservationId, Long guardianId, Long staffId) {
    }

    private record StompFrames(WebSocket socket, BlockingDeque<String> frames) {

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

        /**
         * 기대 문구가 담긴 프레임이 올 때까지 기다리되, 지나친 프레임은 버리지 않고 큐 앞으로 되돌린다.
         * 한 번의 전송으로 보낸 쪽은 topic 브로드캐스트(내용+clientMessageId)와 개인 ACK(clientMessageId)를
         * 각각 받는데 둘의 도착 순서가 보장되지 않는다. 버리면 ACK이 먼저 온 경우 내용 대기가 ACK을 삼켜,
         * 뒤따르는 clientMessageId 대기가 빈 큐에서 타임아웃한다(간헐 실패의 원인).
         */
        String awaitFrameContaining(String expected) throws InterruptedException {
            List<String> skipped = new ArrayList<>();
            try {
                for (int attempt = 0; attempt < 5; attempt++) {
                    String received = awaitFrame();
                    if (received.contains(expected)) {
                        return received;
                    }
                    skipped.add(received);
                }
                throw new AssertionError("STOMP frame does not contain: " + expected);
            } finally {
                // 다른 검증이 기다리는 프레임일 수 있으므로 원래 순서대로 되돌린다.
                for (int i = skipped.size() - 1; i >= 0; i--) {
                    frames.addFirst(skipped.get(i));
                }
            }
        }
    }

    private static final class CapturingWebSocketListener implements WebSocket.Listener {

        private final BlockingDeque<String> frames = new LinkedBlockingDeque<>();
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                int frameEnd;
                while ((frameEnd = buffer.indexOf("\u0000")) >= 0) {
                    frames.add(buffer.substring(0, frameEnd));
                    buffer.delete(0, frameEnd + 1);
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        private void attach(WebSocket webSocket) {
            webSocket.request(1);
        }

        private BlockingDeque<String> frames() {
            return frames;
        }
    }
}
