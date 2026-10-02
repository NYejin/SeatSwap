package com.seatswap.service;

import com.seatswap.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 거래완료 후 리뷰 작성, 신뢰도 점수 반영 (FR-12, FR-13) */
@Service
@RequiredArgsConstructor
public class ReviewService {
    private final ReviewRepository reviewRepository;
    // TODO: createReview(), recalculateTrustScore()
}
