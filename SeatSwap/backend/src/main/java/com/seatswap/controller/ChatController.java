package com.seatswap.controller;

import com.seatswap.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

/** STOMP destination: /topic/chat/{matchId} (spring-boot-conventions 스킬 참고) */
@Controller
@RequiredArgsConstructor
public class ChatController {
    private final ChatService chatService;
    // TODO: @MessageMapping("/chat/{matchId}") 메시지 수신 처리
}
