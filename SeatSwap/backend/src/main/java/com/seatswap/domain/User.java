package com.seatswap.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private String nickname;

    // 신뢰도 점수 — 거래 완료 후 Review 누적 기반 (요구사항정의서 FR-13)
    private Double trustScore = 0.0;

    /** 저장 시 JPA Auditing이 KST(Clock) 기준으로 채운다 — JpaAuditingConfig. */
    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    /**
     * 회원가입 시 사용하는 생성 팩토리. password는 호출 전에 반드시 암호화되어 있어야 한다
     * (AuthService에서 PasswordEncoder.encode() 거친 값만 전달).
     */
    public static User create(String email, String encodedPassword, String nickname) {
        User user = new User();
        user.email = email;
        user.password = encodedPassword;
        user.nickname = nickname;
        user.trustScore = 0.0;
        return user;
    }
}
