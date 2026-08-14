package com.doctorpet.domain.chat.scheduler;

import com.doctorpet.domain.chat.service.ChatMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMessageRetentionScheduler {

    private final ChatMessageService chatMessageService;

    // 공통 Clock으로 정확한 cutoff을 계산하는 서비스 메서드를 하루 한 번 호출한다.
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    public void deleteExpiredMessages() {
        long deleted = chatMessageService.deleteExpiredMessages();
        log.info("만료 채팅 메시지 삭제 완료 count={}", deleted);
    }
}
