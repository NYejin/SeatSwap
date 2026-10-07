package com.seatswap.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.unit.DataSize;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * application.yml 설정값이 실제로 들어 있는지 가볍게 확인한다 (컨텍스트 기동 없음).
 * multipart 상한을 빠뜨리면 Spring 기본 1MB라 좌석표 이미지 업로드가 전부 막힌다.
 */
class ApplicationConfigTest {

    private static Binder binder() throws IOException {
        PropertySource<?> source = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml")).get(0);
        return new Binder(ConfigurationPropertySources.from(source));
    }

    @Test
    void multipartLimitsAreConfiguredForSeatMapUpload() throws IOException {
        MultipartProperties props = binder().bind("spring.servlet.multipart", MultipartProperties.class).get();

        assertThat(props.getMaxFileSize()).isEqualTo(DataSize.ofMegabytes(10));
        assertThat(props.getMaxRequestSize()).isEqualTo(DataSize.ofMegabytes(11));
    }

    @Test
    void jsonResponseCompressionIsEnabled() throws IOException {
        Binder binder = binder();

        assertThat(binder.bind("server.compression.enabled", Boolean.class).get()).isTrue();
        assertThat(binder.bind("server.compression.min-response-size", DataSize.class).get()).isEqualTo(DataSize.ofKilobytes(1));
        assertThat(binder.bind("server.compression.mime-types", String[].class).get()).contains("application/json");
    }

    @Test
    void recognitionLimitsHaveSaneDefaults() throws IOException {
        Binder binder = binder();

        assertThat(binder.bind("seatmap-service.max-concurrent-recognitions", Integer.class).get()).isEqualTo(3);
        assertThat(binder.bind("seatmap-service.permit-wait-ms", Integer.class).get()).isEqualTo(5000);
        assertThat(binder.bind("seatmap-service.max-seats", Integer.class).get()).isEqualTo(6000);
        assertThat(binder.bind("seatmap-service.read-timeout-ms", Integer.class).get()).isGreaterThanOrEqualTo(60000);
    }
}
