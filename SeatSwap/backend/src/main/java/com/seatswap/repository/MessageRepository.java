package com.seatswap.repository;

import com.seatswap.domain.Message;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepository extends JpaRepository<Message, Long> {
    // TODO: 도메인별 조회 메서드 추가
}
