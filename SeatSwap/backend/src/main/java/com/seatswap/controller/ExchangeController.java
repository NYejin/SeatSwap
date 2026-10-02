package com.seatswap.controller;

import com.seatswap.service.ExchangeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/exchange")
@RequiredArgsConstructor
public class ExchangeController {
    private final ExchangeService exchangeService;
    // TODO: POST /requests, GET /requests, POST /requests/{id}/apply, POST /matches/{id}/accept
}
