package com.seatswap.service;

import com.seatswap.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 내 티켓 등록 (FR-08) */
@Service
@RequiredArgsConstructor
public class TicketService {
    private final TicketRepository ticketRepository;
    // TODO: registerTicket()
}
