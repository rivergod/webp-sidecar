package net.rivergod.webp.sidecar;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * 코드로 넘기는 플러그인 설정(ADR-0020). 불변이고 스레드 안전하다. {@link #builder()}로 만든다.
 *
 * <p>설정하지 않은 항목은 비어 있고, 다음 단계 값을 쓴다. 항목마다 아래 순서로 처음 정해진 값을 쓴다.
 *
 * <ol>
 *   <li>리더·writer 단위: {@link javax.imageio.spi.ImageReaderSpi#createReaderInstance(Object)}에 넘긴 설정
 *   <li>등록 단위: {@link WebPImageReaderSpi#WebPImageReaderSpi(WebPClientConfig)}로 만든 SPI의 설정
 *   <li>시스템 프로퍼티 {@code webp.sidecar.*}
 *   <li>환경변수 {@code WEBP_SIDECAR_*}
 *   <li>기본값
 * </ol>
 *
 * <p>사용 예(등록 단위):
 *
 * <pre>{@code
 * WebPClientConfig config = WebPClientConfig.builder()
 *         .endpoint("unix:///var/run/webp/webp.sock")
 *         .busyRetries(3)
 *         .build();
 * IIORegistry.getDefaultInstance().registerServiceProvider(new WebPImageReaderSpi(config));
 * }</pre>
 */
public final class WebPClientConfig {

    /** {@link #busyRetries()}의 최댓값. */
    public static final int MAX_BUSY_RETRIES = 10;

    /** 아무 항목도 정하지 않은 설정(모든 항목이 다음 단계로 내려간다). */
    static final WebPClientConfig EMPTY = new Builder().build();

    private final String endpoint;
    private final Long deadlineMs;
    private final Long maxPixels;
    private final Integer busyRetries;

    private WebPClientConfig(String endpoint, Long deadlineMs, Long maxPixels, Integer busyRetries) {
        this.endpoint = endpoint;
        this.deadlineMs = deadlineMs;
        this.maxPixels = maxPixels;
        this.busyRetries = busyRetries;
    }

    /**
     * 빈 빌더를 만든다.
     *
     * @return 새 빌더. 스레드 안전하지 않다
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 서버 엔드포인트(gRPC target).
     *
     * @return {@code unix:///절대/경로}(UDS) 또는 {@code host:port}(TCP). 정하지 않았으면 비어 있다
     */
    public Optional<String> endpoint() {
        return Optional.ofNullable(endpoint);
    }

    /**
     * 호출 하나의 데드라인. 혼잡 재시도와 물러섬을 포함한다.
     *
     * @return 단위: ms. 정하지 않았으면 비어 있다
     */
    public OptionalLong deadlineMs() {
        return deadlineMs == null ? OptionalLong.empty() : OptionalLong.of(deadlineMs);
    }

    /**
     * 클라이언트 쪽 픽셀 수 상한(width × height).
     *
     * @return 0이면 서버 상한만 적용한다. 정하지 않았으면 비어 있다
     */
    public OptionalLong maxPixels() {
        return maxPixels == null ? OptionalLong.empty() : OptionalLong.of(maxPixels);
    }

    /**
     * 서버가 일을 시작하기 전에 혼잡("server busy", ADR-0017)으로 거부했을 때 다시 시도하는 횟수.
     *
     * @return 0이면 다시 시도하지 않는다. 정하지 않았으면 비어 있다
     */
    public OptionalInt busyRetries() {
        return busyRetries == null ? OptionalInt.empty() : OptionalInt.of(busyRetries);
    }

    /** 이 설정의 항목을 먼저 쓰고, 비운 항목은 {@code base}에서 채운 설정. */
    WebPClientConfig orElse(WebPClientConfig base) {
        return new WebPClientConfig(
                endpoint != null ? endpoint : base.endpoint,
                deadlineMs != null ? deadlineMs : base.deadlineMs,
                maxPixels != null ? maxPixels : base.maxPixels,
                busyRetries != null ? busyRetries : base.busyRetries);
    }

    /**
     * 항목별로 같으면 같다.
     *
     * @param o 비교할 객체
     * @return 같은 항목을 같은 값으로 정했으면 true
     */
    @Override
    public boolean equals(Object o) {
        return o instanceof WebPClientConfig c
                && Objects.equals(endpoint, c.endpoint)
                && Objects.equals(deadlineMs, c.deadlineMs)
                && Objects.equals(maxPixels, c.maxPixels)
                && Objects.equals(busyRetries, c.busyRetries);
    }

    /**
     * {@link #equals}와 맞는 해시.
     *
     * @return 해시 값
     */
    @Override
    public int hashCode() {
        return Objects.hash(endpoint, deadlineMs, maxPixels, busyRetries);
    }

    /**
     * 정한 항목만 보여 준다.
     *
     * @return 사람이 읽는 표현
     */
    @Override
    public String toString() {
        StringBuilder s = new StringBuilder("WebPClientConfig[");
        String sep = "";
        if (endpoint != null) {
            s.append("endpoint=").append(endpoint);
            sep = ", ";
        }
        if (deadlineMs != null) {
            s.append(sep).append("deadlineMs=").append(deadlineMs);
            sep = ", ";
        }
        if (maxPixels != null) {
            s.append(sep).append("maxPixels=").append(maxPixels);
            sep = ", ";
        }
        if (busyRetries != null) {
            s.append(sep).append("busyRetries=").append(busyRetries);
        }
        return s.append(']').toString();
    }

    /** {@link WebPClientConfig} 빌더. 스레드 안전하지 않다. */
    public static final class Builder {
        private String endpoint;
        private Long deadlineMs;
        private Long maxPixels;
        private Integer busyRetries;

        private Builder() {}

        /**
         * 서버 엔드포인트를 정한다.
         *
         * @param endpoint gRPC target. {@code unix:///절대/경로} 또는 {@code host:port}. 앞뒤 공백은 지운다
         * @return 이 빌더
         * @throws IllegalArgumentException 비었거나 공백뿐이면
         */
        public Builder endpoint(String endpoint) {
            Objects.requireNonNull(endpoint, "endpoint");
            if (endpoint.isBlank()) {
                throw new IllegalArgumentException("endpoint must not be empty");
            }
            this.endpoint = endpoint.trim();
            return this;
        }

        /**
         * 호출 하나의 데드라인을 정한다.
         *
         * @param deadlineMs 단위: ms. 1 이상
         * @return 이 빌더
         * @throws IllegalArgumentException 1보다 작으면
         */
        public Builder deadlineMs(long deadlineMs) {
            if (deadlineMs <= 0) {
                throw new IllegalArgumentException("deadlineMs must be > 0, got " + deadlineMs);
            }
            this.deadlineMs = deadlineMs;
            return this;
        }

        /**
         * 클라이언트 쪽 픽셀 수 상한을 정한다.
         *
         * @param maxPixels 0 이상. 0이면 서버 상한만 적용한다
         * @return 이 빌더
         * @throws IllegalArgumentException 음수면
         */
        public Builder maxPixels(long maxPixels) {
            if (maxPixels < 0) {
                throw new IllegalArgumentException("maxPixels must be >= 0, got " + maxPixels);
            }
            this.maxPixels = maxPixels;
            return this;
        }

        /**
         * 혼잡("server busy") 재시도 횟수를 정한다.
         *
         * @param busyRetries 0–{@value WebPClientConfig#MAX_BUSY_RETRIES}. 0이면 다시 시도하지 않는다
         * @return 이 빌더
         * @throws IllegalArgumentException 범위를 벗어나면
         */
        public Builder busyRetries(int busyRetries) {
            if (busyRetries < 0 || busyRetries > MAX_BUSY_RETRIES) {
                throw new IllegalArgumentException(
                        "busyRetries must be 0.." + MAX_BUSY_RETRIES + ", got " + busyRetries);
            }
            this.busyRetries = busyRetries;
            return this;
        }

        /**
         * 설정을 만든다. 빌더는 계속 쓸 수 있다.
         *
         * @return 불변 설정
         */
        public WebPClientConfig build() {
            return new WebPClientConfig(endpoint, deadlineMs, maxPixels, busyRetries);
        }
    }
}
