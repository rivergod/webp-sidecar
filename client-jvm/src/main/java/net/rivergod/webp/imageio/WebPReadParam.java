package net.rivergod.webp.imageio;

import javax.imageio.ImageReadParam;

/**
 * WebP 읽기 파라미터(ADR-0020). 표준 항목(영역, 서브샘플링, 대상 이미지 등)에 더해 이 읽기 한 번의 데드라인과
 * 클라이언트 픽셀 상한을 바꾼다. 리더의 {@code getDefaultReadParam()}이 이 타입을 돌려준다. 스레드 안전하지 않다.
 *
 * <pre>{@code
 * WebPReadParam param = (WebPReadParam) reader.getDefaultReadParam();
 * param.setDeadlineMs(5_000);
 * BufferedImage image = reader.read(0, param);
 * }</pre>
 *
 * <p>애니메이션을 0번부터 차례로 읽으면 리더가 스트림 하나를 열어 두고 이어 받는다(ADR-0014). 그 스트림은 처음 연
 * 읽기의 데드라인을 쓴다.
 */
public final class WebPReadParam extends ImageReadParam {

    private long deadlineMs;
    private long maxPixels;

    /** 데드라인과 픽셀 상한을 정하지 않은(리더 설정을 쓰는) 파라미터를 만든다. */
    public WebPReadParam() {
        super();
    }

    /**
     * 이 읽기 한 번의 데드라인을 정한다. 혼잡 재시도(ADR-0020)도 이 시간 안이다.
     *
     * @param deadlineMs 단위: ms. 0이면 리더 설정을 쓴다
     * @throws IllegalArgumentException 음수면
     */
    public void setDeadlineMs(long deadlineMs) {
        if (deadlineMs < 0) {
            throw new IllegalArgumentException("deadlineMs must be >= 0, got " + deadlineMs);
        }
        this.deadlineMs = deadlineMs;
    }

    /**
     * 이 읽기 한 번의 데드라인.
     *
     * @return 단위: ms. 0이면 리더 설정을 쓴다
     */
    public long getDeadlineMs() {
        return deadlineMs;
    }

    /**
     * 이 읽기 한 번의 클라이언트 픽셀 수 상한(width × height)을 정한다. 서버 상한은 항상 따로 적용된다.
     *
     * @param maxPixels 0이면 리더 설정을 쓴다
     * @throws IllegalArgumentException 음수면
     */
    public void setMaxPixels(long maxPixels) {
        if (maxPixels < 0) {
            throw new IllegalArgumentException("maxPixels must be >= 0, got " + maxPixels);
        }
        this.maxPixels = maxPixels;
    }

    /**
     * 이 읽기 한 번의 클라이언트 픽셀 수 상한.
     *
     * @return 0이면 리더 설정을 쓴다
     */
    public long getMaxPixels() {
        return maxPixels;
    }
}
