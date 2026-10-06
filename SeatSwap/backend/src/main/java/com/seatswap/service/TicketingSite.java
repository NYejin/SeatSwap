package com.seatswap.service;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 상품 ID를 뽑아 sourceKey를 "{site}:{productId}"로 만들 수 있는 티켓팅 사이트 목록.
 * 같은 공연을 가리키는 링크가 경로·쿼리·모바일 여부만 달라도 하나의 공연으로 판정하기 위함이다.
 *
 * 범위 원칙: 호스트는 티켓 서비스 호스트를 정확히 나열하고(접미사 일치 금지), 경로는 처음부터 끝까지
 * 앵커를 건다. 패턴 밖의 링크는 일반 URL 정규화로 폴백한다 — 서로 다른 공연이 합쳐지는 것보다
 * 같은 공연을 놓치는 쪽이 낫다.
 *
 * 패턴 근거 (확신도 표기):
 * - [확인] = 실제 공개 링크 형식으로 알려진 것
 * - [추정] = 형식이 알려진 것과 유사해 포함했으나 실제 링크로 확인하지 못한 것
 * 같은 사이트 안에서 상품 ID 체계가 다를 수 있는 경로(예: YES24 /Special/{id})는 넣지 않았다.
 */
enum TicketingSite {

    /**
     * 인터파크 티켓 (2025 NOL 티켓 리브랜딩 포함).
     * [확인] https://tickets.interpark.com/goods/24013928
     * [확인] http://ticket.interpark.com/Ticket/Goods/GoodsInfo.asp?GoodsCode=24013928 (구 형식 — 이 경로일 때만 GoodsCode 인정)
     * [추정] https://mobileticket.interpark.com/goods/24013928
     * [추정] https://nol.interpark.com/ticket/goods/24013928 (nol. 호스트는 /ticket/ 접두 경로만)
     */
    INTERPARK("interpark",
            Set.of("tickets.interpark.com", "ticket.interpark.com", "mobileticket.interpark.com",
                    "nol.interpark.com")) {
        private final Pattern goodsPath = Pattern.compile("^/goods/(\\d{1,20})/?$", Pattern.CASE_INSENSITIVE);
        private final Pattern nolGoodsPath =
                Pattern.compile("^/ticket/goods/(\\d{1,20})/?$", Pattern.CASE_INSENSITIVE);

        @Override
        Optional<String> extractProductId(String host, String path, Map<String, String> query) {
            if ("nol.interpark.com".equals(host)) {
                return group(nolGoodsPath, path);
            }
            Optional<String> fromPath = group(goodsPath, path);
            if (fromPath.isPresent()) {
                return fromPath;
            }
            if ("/Ticket/Goods/GoodsInfo.asp".equalsIgnoreCase(path)) {
                return numericParam(query, "GoodsCode");
            }
            return Optional.empty();
        }
    },

    /**
     * 멜론티켓.
     * [확인] https://ticket.melon.com/performance/index.htm?prodId=210813
     * 모바일(m.ticket.melon.com)은 경로를 확인하지 못해 제외 — 일반 정규화로 폴백.
     */
    MELON("melon", Set.of("ticket.melon.com")) {
        @Override
        Optional<String> extractProductId(String host, String path, Map<String, String> query) {
            if ("/performance/index.htm".equalsIgnoreCase(path)) {
                return numericParam(query, "prodId");
            }
            return Optional.empty();
        }
    },

    /**
     * YES24 티켓.
     * [확인] http://ticket.yes24.com/Perf/49876
     * [추정] https://m.ticket.yes24.com/Perf/Detail/PerfInfo.aspx?IdPerf=49876 (모바일 — 이 경로일 때만 IdPerf 인정)
     */
    YES24("yes24", Set.of("ticket.yes24.com", "m.ticket.yes24.com")) {
        private final Pattern perfPath = Pattern.compile("^/Perf/(\\d{1,20})/?$", Pattern.CASE_INSENSITIVE);

        @Override
        Optional<String> extractProductId(String host, String path, Map<String, String> query) {
            if ("ticket.yes24.com".equals(host)) {
                return group(perfPath, path);
            }
            if ("/Perf/Detail/PerfInfo.aspx".equalsIgnoreCase(path)) {
                return numericParam(query, "IdPerf");
            }
            return Optional.empty();
        }
    },

    /**
     * 티켓링크.
     * [확인] https://www.ticketlink.co.kr/product/45678
     * [추정] https://m.ticketlink.co.kr/product/45678 (모바일)
     */
    TICKETLINK("ticketlink", Set.of("ticketlink.co.kr", "www.ticketlink.co.kr", "m.ticketlink.co.kr")) {
        private final Pattern productPath = Pattern.compile("^/product/(\\d{1,20})/?$", Pattern.CASE_INSENSITIVE);

        @Override
        Optional<String> extractProductId(String host, String path, Map<String, String> query) {
            return group(productPath, path);
        }
    };

    /**
     * 좌석맵 이미지 수집 단계(서버가 외부 URL을 실제로 요청하는 단계)에서 요청을 허용할 호스트 (정확히 일치).
     * 현재(공연 등록 단계)는 서버가 URL을 요청하지 않으므로 형식 검증만 한다.
     *
     * SSRF 방어 규칙 — 수집 단계(2단계) 구현 시 반드시 지킬 것:
     * 1. 사용자가 입력한 sourceUrl을 그대로 요청하지 않는다. sourceKey의 {site, productId}로
     *    서버가 URL을 새로 조립하거나, 최소한 아래 검증을 모두 통과한 URL만 요청한다.
     * 2. https 스킴 + 443 포트만 허용.
     * 3. 호스트는 이 목록과 정확히 일치해야 한다 (좌석맵 이미지 CDN 호스트는 실제 확인 후 여기에 추가).
     * 4. DNS 해석 결과 IP가 사설(10/8, 172.16/12, 192.168/16, fc00::/7)·루프백(127/8, ::1)·
     *    링크로컬(169.254/16, fe80::/10, 클라우드 메타데이터 169.254.169.254 포함)·0.0.0.0이면 차단.
     *    검증한 IP로 접속해 DNS 재바인딩을 막는다.
     * 5. 리다이렉트는 자동 추종하지 않고, 매 홉마다 1~4를 재검증 (최대 홉 수 제한).
     * 6. 응답 크기 상한(예: 이미지 10MB)·연결/읽기 타임아웃·Content-Type(image/*) 검사.
     */
    static final Set<String> SEATMAP_FETCH_ALLOWED_HOSTS = Set.of(
            "tickets.interpark.com", "ticket.interpark.com", "mobileticket.interpark.com", "nol.interpark.com",
            "ticket.melon.com",
            "ticket.yes24.com", "m.ticket.yes24.com",
            "ticketlink.co.kr", "www.ticketlink.co.kr", "m.ticketlink.co.kr");

    private final String key;
    private final Set<String> hosts;

    TicketingSite(String key, Set<String> hosts) {
        this.key = key;
        this.hosts = hosts;
    }

    String key() {
        return key;
    }

    /**
     * 상품 ID 추출 (숫자만, 앞자리 0 제거).
     * @param host 소문자 ASCII 호스트 (www. 유지)
     * @param path 재인코딩된 경로
     * @param query 디코딩된 쿼리 파라미터
     */
    abstract Optional<String> extractProductId(String host, String path, Map<String, String> query);

    static Optional<TicketingSite> byHost(String host) {
        if (host == null) {
            return Optional.empty();
        }
        String h = host.toLowerCase(Locale.ROOT);
        for (TicketingSite site : values()) {
            if (site.hosts.contains(h)) {
                return Optional.of(site);
            }
        }
        return Optional.empty();
    }

    /** 수집 단계용 허용 호스트 판정 (정확히 일치). 스킴·포트·IP·리다이렉트 검증은 별도로 해야 한다. */
    static boolean isSeatMapFetchAllowed(String host) {
        return host != null && SEATMAP_FETCH_ALLOWED_HOSTS.contains(host.toLowerCase(Locale.ROOT));
    }

    private static Optional<String> group(Pattern pattern, String path) {
        if (path == null) {
            return Optional.empty();
        }
        Matcher m = pattern.matcher(path);
        return m.matches() ? Optional.of(stripLeadingZeros(m.group(1))) : Optional.empty();
    }

    private static Optional<String> numericParam(Map<String, String> query, String name) {
        for (Map.Entry<String, String> entry : query.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name) && entry.getValue() != null
                    && entry.getValue().matches("\\d{1,20}")) {
                return Optional.of(stripLeadingZeros(entry.getValue()));
            }
        }
        return Optional.empty();
    }

    private static String stripLeadingZeros(String digits) {
        String stripped = digits.replaceFirst("^0+(?=\\d)", "");
        return stripped.isEmpty() ? "0" : stripped;
    }
}
