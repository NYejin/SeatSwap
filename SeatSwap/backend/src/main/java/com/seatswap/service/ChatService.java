package com.seatswap.service;

import com.seatswap.repository.ChatRoomRepository;
import com.seatswap.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 매칭 성립 시 채팅방 생성, 메시지 저장 (FR-11) */
@Service
@RequiredArgsConstructor
public class ChatService {
    private final ChatRoomRepository chatRoomRepository;
    private final MessageRepository messageRepository;
    // TODO: createRoomForMatch(), saveMessage()
}
