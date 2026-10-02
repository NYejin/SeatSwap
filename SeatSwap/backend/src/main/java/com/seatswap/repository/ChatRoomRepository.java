package com.seatswap.repository;

import com.seatswap.domain.ChatRoom;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {
    // TODO: 도메인별 조회 메서드 추가
}
