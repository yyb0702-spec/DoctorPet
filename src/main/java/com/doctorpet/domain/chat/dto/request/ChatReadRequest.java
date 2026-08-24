package com.doctorpet.domain.chat.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/*
  채팅 읽음 처리 요청(SA §8-9). 클라이언트가 **실제로 화면에 병합한 마지막 메시지 id**를 보낸다.

  상한이 없으면 서버가 그 스레드의 상대 메시지를 전부 읽음 처리하므로, 최종 복구 직후 저장됐지만
  아직 STOMP로 도착하지 않은 메시지까지 읽음이 된다(PR #159 리뷰 P1). 서버는 이 값 이하만 처리한다.
 */
public record ChatReadRequest(
        @NotNull(message = "읽음 처리 기준 메시지 ID가 필요합니다.")
        @Positive(message = "읽음 처리 기준 메시지 ID는 양수여야 합니다.")
        Long throughMessageId
) {
}
