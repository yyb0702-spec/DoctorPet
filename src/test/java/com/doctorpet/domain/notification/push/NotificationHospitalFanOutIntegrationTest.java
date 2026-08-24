package com.doctorpet.domain.notification.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.member.entity.Member;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.repository.MemberRepository;
import com.doctorpet.domain.notification.dto.response.NotificationResponse;
import com.doctorpet.domain.notification.entity.Notification;
import com.doctorpet.domain.notification.entity.status.NotificationRecipientType;
import com.doctorpet.domain.notification.entity.status.NotificationResourceType;
import com.doctorpet.domain.notification.entity.status.NotificationType;
import com.doctorpet.domain.notification.service.NotificationService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Level 3 — 병원(HOSPITAL) 수신 알림이 "발송 시점의 현재 소속 스태프"에게만 SSE로 fan-out되는지 실제 MySQL·
 * 실제 리스너 배선으로 검증한다(고도화 3.10 후속 — SSE 병원 라우팅). 저장은 병원 단위 1건 그대로이고, 여기서
 * 확인하는 것은 전달 대상뿐이다.
 *
 * <p>전송 대상 관찰은 {@link SseEmitterRegistry}를 스파이로 감싸 {@code send(memberId, ...)} 호출로 한다.
 * 다만 호출 검증만으로는 "한 회원의 첫 연결에만 보내는" 구현도 통과하므로(연결 수는 그대로 유지된다),
 * 한 스태프의 여러 연결(다중 탭)에 실제로 payload가 들어갔는지는 각 {@link SseEmitter}의 미전송 버퍼를 읽어
 * 확인한다 — 테스트 emitter에는 HTTP 핸들러가 붙지 않아 {@code send}가 버퍼에 쌓이므로 연결별 수신 내용을
 * 그대로 관찰할 수 있다({@link #deliveredData(SseEmitter)}).
 *
 * <p>전체 컨텍스트(MySQL·Redis·env)가 필요하다 — 없으면 BLOCKED.
 */
@SpringBootTest
class NotificationHospitalFanOutIntegrationTest {

    private static final String EVENT_NAME = "notification";
    private static final int CAP = 5;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    // 실제 레지스트리 동작(연결 보관·전송)을 그대로 두고 전송 대상만 관찰한다.
    @MockitoSpyBean
    private SseEmitterRegistry registry;

    private long hospitalId;
    private long otherHospitalId;
    private Long staffA;
    private Long staffB;
    private Long otherHospitalStaff;
    private Long withdrawnStaff;
    private Long unassignedStaff;

    private final List<Long> notificationIds = new ArrayList<>();
    private final List<Long> memberIds = new ArrayList<>();
    private final List<SseEmitter> openedEmitters = new ArrayList<>();

    @BeforeEach
    void setUpHospitalStaff() {
        long base = Math.abs(System.nanoTime());
        hospitalId = base;
        otherHospitalId = base + 1;

        staffA = saveStaff("a", hospitalId);
        staffB = saveStaff("b", hospitalId);
        otherHospitalStaff = saveStaff("other", otherHospitalId);

        // 탈퇴한 스태프 — deleted_at이 채워지면 Member의 @SQLRestriction으로 조회에서 빠진다.
        withdrawnStaff = saveStaff("withdrawn", hospitalId);
        jdbcTemplate.update("update members set deleted_at = ? where id = ?", LocalDateTime.now(), withdrawnStaff);

        // 병원 소속이 해제된 스태프 — 계정은 살아 있지만 이 병원의 수신자가 아니다.
        unassignedStaff = saveStaff("unassigned", hospitalId);
        jdbcTemplate.update("update members set hospital_id = null where id = ?", unassignedStaff);
    }

    @AfterEach
    void cleanUp() {
        for (SseEmitter emitter : openedEmitters) {
            for (Long memberId : memberIds) {
                registry.remove(memberId, emitter);
            }
        }
        openedEmitters.clear();
        for (Long id : notificationIds) {
            jdbcTemplate.update("delete from notifications where id = ?", id);
        }
        for (Long id : memberIds) {
            jdbcTemplate.update("delete from members where id = ?", id);
        }
    }

    @Test
    @DisplayName("병원 알림은 그 병원에 현재 소속된 연결 스태프 모두에게 전송되고, 타 병원·탈퇴·소속 해제 스태프에게는 가지 않는다")
    void hospitalNotification_fansOutToCurrentStaffOnly() {
        SseEmitter staffAtab1 = connect(staffA);
        SseEmitter staffAtab2 = connect(staffA); // 같은 스태프의 두 번째 탭
        SseEmitter staffBtab = connect(staffB);
        SseEmitter otherHospitalTab = connect(otherHospitalStaff);
        SseEmitter withdrawnTab = connect(withdrawnStaff);
        SseEmitter unassignedTab = connect(unassignedStaff);

        Long notificationId = createHospitalNotification();

        verify(registry).send(eq(staffA), eq(EVENT_NAME), any());
        verify(registry).send(eq(staffB), eq(EVENT_NAME), any());
        verify(registry, never()).send(eq(otherHospitalStaff), any(), any());
        verify(registry, never()).send(eq(withdrawnStaff), any(), any());
        verify(registry, never()).send(eq(unassignedStaff), any(), any());
        // 병원 자체를 연결 키로 쓰지 않는다 — 레지스트리 키는 계속 memberId다.
        verify(registry, never()).send(eq(hospitalId), any(), any());

        // 대상 확인만으로는 "회원의 첫 연결에만 보내는" 구현도 통과하므로, 연결마다 payload가 실제로 들어갔는지
        // 본다. 다중 탭(상한 5)은 정상 사용이라 두 번째 탭이 조용히 빠지면 스태프가 알림을 놓친다.
        assertDelivered(staffAtab1, notificationId);
        assertDelivered(staffAtab2, notificationId);
        assertDelivered(staffBtab, notificationId);
        // 수신 대상이 아닌 스태프의 연결에는 payload가 들어가지 않는다(연결 유지 여부와 별개).
        assertNotDelivered(otherHospitalTab);
        assertNotDelivered(withdrawnTab);
        assertNotDelivered(unassignedTab);

        // 다중 탭 연결이 fan-out 뒤에도 모두 살아 있다(전송 실패로 정리되지 않았다).
        assertThat(registry.connectionCount(staffA)).isEqualTo(2);
        assertThat(registry.connectionCount(staffB)).isEqualTo(1);
    }

    @Test
    @DisplayName("회원 수신 알림은 그 회원 연결로만 가고 병원 스태프에게 새지 않는다")
    void memberNotification_staysOnThatMember() {
        connect(staffA);
        Long guardianId = staffB + 1_000_000L; // 연결 없는 별개 수신자

        Notification saved = notificationService.create(
                guardianId,
                NotificationType.PAYMENT_RESULT,
                "진료비 결제가 완료되었습니다.",
                NotificationResourceType.PAYMENT,
                55L);
        notificationIds.add(saved.getId());

        verify(registry).send(eq(guardianId), eq(EVENT_NAME), any());
        verify(registry, never()).send(eq(staffA), any(), any());
    }

    @Test
    @DisplayName("연결된 스태프가 하나도 없어도 예외 없이 끝나고 알림은 저장된다")
    void noConnectedStaff_isSilentAndKeepsSavedNotification() {
        Long savedId = createHospitalNotification();

        // 소속 스태프는 있지만 아무도 접속해 있지 않다 — 전송 시도만 하고 조용히 끝난다.
        verify(registry).send(eq(staffA), eq(EVENT_NAME), any());
        assertThat(rowExists(savedId)).isTrue();
    }

    @Test
    @DisplayName("커밋 이후에만 fan-out되고, 롤백된 알림 생성은 전송되지 않는다")
    void fanOut_happensOnlyAfterCommit() {
        connect(staffA);
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        Long committedId = transactionTemplate.execute(status -> {
            Long id = createHospitalNotification();
            // 아직 커밋 전이다 — AFTER_COMMIT 리스너는 실행되지 않았어야 한다.
            verify(registry, never()).send(eq(staffA), any(), any());
            return id;
        });
        verify(registry, times(1)).send(eq(staffA), eq(EVENT_NAME), any());
        assertThat(rowExists(committedId)).isTrue();

        Long rolledBackId = transactionTemplate.execute(status -> {
            Long id = createHospitalNotification();
            status.setRollbackOnly();
            return id;
        });
        // 롤백된 생성은 전송되지 않는다(커밋된 앞 건의 1회 그대로).
        verify(registry, times(1)).send(eq(staffA), eq(EVENT_NAME), any());
        assertThat(rowExists(rolledBackId)).isFalse();
    }

    @Test
    @DisplayName("fan-out 전송이 실패해도 저장된 알림과 상위 트랜잭션은 롤백되지 않는다")
    void fanOutFailure_doesNotRollbackSavedNotification() {
        connect(staffA);
        doThrow(new IllegalStateException("전송 채널 장애 시뮬레이션"))
                .when(registry).send(anyLong(), eq(EVENT_NAME), any());

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        Long savedId = transactionTemplate.execute(status -> createHospitalNotification());

        // 전송 예외는 SseNotificationPusher가 대상별로 삼키고(그 밖의 누락은 NotificationPushListener가 받는다)
        // 호출자에게 전파되지 않으며, 저장은 이미 커밋을 마쳤다.
        verify(registry).send(eq(staffA), eq(EVENT_NAME), any());
        assertThat(rowExists(savedId)).isTrue();
    }

    @Test
    @DisplayName("한 스태프 연결 전송이 실패해도 같은 병원 나머지 스태프는 알림을 받는다")
    void fanOutFailure_isIsolatedPerStaff() {
        SseEmitter staffAtab = connect(staffA);
        SseEmitter staffBtab = connect(staffB);
        // 레지스트리가 흡수하지 않는 런타임 예외를 staffA 전송에서만 터뜨린다. 전송 순서는 쿼리 결과 순서라
        // 보장하지 않지만, 대상별 격리가 되어 있으면 순서와 무관하게 staffB는 받아야 한다.
        doThrow(new IllegalStateException("스태프 A 전송 채널 장애"))
                .when(registry).send(eq(staffA), eq(EVENT_NAME), any());

        Long savedId = createHospitalNotification();

        verify(registry).send(eq(staffB), eq(EVENT_NAME), any());
        assertDelivered(staffBtab, savedId);
        assertNotDelivered(staffAtab);
        assertThat(rowExists(savedId)).isTrue();
    }

    private Long saveStaff(String suffix, Long hospitalId) {
        Member staff = Member.createGuardian(
                "hospital-fanout-" + suffix + "-" + System.nanoTime() + "@example.com", "encoded", "스태프" + suffix);
        // 병원 스태프는 회원가입 대상이 아니라 시드로 생성되므로(SA §6-2) 테스트에서는 필드를 직접 세팅한다.
        ReflectionTestUtils.setField(staff, "role", MemberRole.HOSPITAL_STAFF);
        ReflectionTestUtils.setField(staff, "hospitalId", hospitalId);
        Long id = memberRepository.saveAndFlush(staff).getId();
        memberIds.add(id);
        return id;
    }

    private SseEmitter connect(Long memberId) {
        SseEmitter emitter = registry.tryRegister(memberId, CAP);
        assertThat(emitter).isNotNull();
        openedEmitters.add(emitter);
        return emitter;
    }

    /*
      연결 1개가 실제로 받은 내용을 확인한다. 테스트 emitter에는 HTTP 응답 핸들러가 붙지 않으므로
      ResponseBodyEmitter.send는 전송 대신 미전송 버퍼(earlySendAttempts)에 쌓아 둔다 — 그래서 연결별 수신
      내용을 그대로 읽을 수 있다. 이벤트 이름은 문자열 프레임(event:notification)으로, payload는 버퍼에 담긴
      NotificationResponse 객체로 확인한다(직렬화는 실제 전송 시점에 일어난다).
     */
    private void assertDelivered(SseEmitter emitter, Long notificationId) {
        List<Object> delivered = deliveredData(emitter);
        String frames = delivered.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .collect(Collectors.joining());
        assertThat(frames).as("이벤트 이름 프레임").contains("event:" + EVENT_NAME);
        assertThat(delivered)
                .as("이 연결이 받은 알림 payload")
                .anySatisfy(data -> assertThat(data)
                        .isInstanceOfSatisfying(
                                NotificationResponse.class,
                                payload -> assertThat(payload.id()).isEqualTo(notificationId)));
    }

    private void assertNotDelivered(SseEmitter emitter) {
        assertThat(deliveredData(emitter))
                .as("수신 대상이 아닌 연결")
                .noneMatch(NotificationResponse.class::isInstance);
    }

    @SuppressWarnings("unchecked")
    private List<Object> deliveredData(SseEmitter emitter) {
        Set<ResponseBodyEmitter.DataWithMediaType> attempts =
                (Set<ResponseBodyEmitter.DataWithMediaType>)
                        ReflectionTestUtils.getField(emitter, "earlySendAttempts");
        if (attempts == null) {
            return List.of();
        }
        return attempts.stream()
                .map(ResponseBodyEmitter.DataWithMediaType::getData)
                .toList();
    }

    private Long createHospitalNotification() {
        Notification saved = notificationService.create(
                NotificationRecipientType.HOSPITAL,
                hospitalId,
                NotificationType.RESERVATION_CONFIRMED,
                "새 예약 요청이 접수되었습니다.",
                NotificationResourceType.RESERVATION,
                77L);
        notificationIds.add(saved.getId());
        return saved.getId();
    }

    private boolean rowExists(Long id) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from notifications where id = ?", Long.class, id);
        return count != null && count > 0;
    }
}
