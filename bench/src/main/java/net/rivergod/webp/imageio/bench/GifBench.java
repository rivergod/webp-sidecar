package net.rivergod.webp.imageio.bench;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.IIOImage;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * GIF → WebP 변환 측정(벤치마크 전용, JMH 아님). 실제 앱 경로: JDK GIF 리더 → 합성({@link GifFrames}) → 이 플러그인의
 * {@code writeToSequence} → 서버 EncodeAnimation. 서버 엔드포인트는 시스템 프로퍼티 {@code webp.imageio.endpoint}.
 *
 * <ul>
 *   <li>{@code single <lossy|lossless> <gif>...}: 파일마다 합성 시간, 인코딩 시간, 출력 크기를 TSV로 출력한다. 결과를 이 플러그인
 *       리더로 다시 읽어 캔버스 크기와 프레임 수가 같은지 확인한다.
 *   <li>{@code load <lossy|lossless> <동시 수> <초> <gif>}: 합성은 한 번만 하고, 동시 수만큼의 스레드가 주어진 시간 동안 변환을
 *       반복한다. 변환 수, 오류(gRPC 상태별), 처리량, 지연 백분위를 출력한다. 시간이 끝날 때 진행 중인 변환은 끝까지 기다린다.
 * </ul>
 */
public final class GifBench {

    private GifBench() {}

    /**
     * 명령줄 진입점.
     *
     * @param args 위 사용법
     * @throws Exception 입출력 실패
     */
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "single" -> single(args[1], List.of(args).subList(2, args.length));
            case "load" -> load(args[1], Integer.parseInt(args[2]), Integer.parseInt(args[3]), args[4]);
            default -> throw new IllegalArgumentException("usage: GifBench single|load ...");
        }
    }

    private static void single(String mode, List<String> gifs) throws Exception {
        boolean lossless = "lossless".equals(mode);
        System.out.println("file\tmode\tcanvas\tframes\tgif_bytes\tcomposite_ms\tencode_ms\twebp_bytes\twebp_vs_gif");
        for (String g : gifs) {
            Path path = Path.of(g);
            long t0 = System.nanoTime();
            GifFrames.Animation a = GifFrames.read(path);
            long t1 = System.nanoTime();
            byte[] webp = encode(a, lossless);
            long t2 = System.nanoTime();
            verify(webp, a);
            long gifBytes = Files.size(path);
            System.out.printf(Locale.ROOT, "%s\t%s\t%dx%d\t%d\t%d\t%.0f\t%.0f\t%d\t%.2f%n", path.getFileName(), mode,
                    a.width(), a.height(), a.frames().size(), gifBytes, (t1 - t0) / 1e6, (t2 - t1) / 1e6, webp.length,
                    (double) webp.length / gifBytes);
        }
    }

    private static void load(String mode, int concurrency, int seconds, String gif) throws Exception {
        boolean lossless = "lossless".equals(mode);
        GifFrames.Animation a = GifFrames.read(Path.of(gif));
        encode(a, lossless); // 채널 연결과 JIT 워밍업
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        Map<String, AtomicLong> errors = new ConcurrentHashMap<>();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        long start = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        for (int t = 0; t < concurrency; t++) {
            pool.execute(() -> {
                while (System.nanoTime() < end) {
                    long s = System.nanoTime();
                    try {
                        encode(a, lossless);
                        latencies.add(System.nanoTime() - s);
                    } catch (Exception e) {
                        errors.computeIfAbsent(classify(e), k -> new AtomicLong()).incrementAndGet();
                    }
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.HOURS);
        double elapsed = (System.nanoTime() - start) / 1e9;
        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        Map<String, Long> err = new TreeMap<>();
        errors.forEach((k, v) -> err.put(k, v.get()));
        System.out.printf(Locale.ROOT,
                "mode=%s concurrency=%d elapsed_s=%.1f ok=%d errors=%s throughput_per_s=%.3f p50_ms=%.0f p90_ms=%.0f p99_ms=%.0f max_ms=%.0f%n",
                mode, concurrency, elapsed, sorted.size(), err, sorted.size() / elapsed, pct(sorted, 50), pct(sorted, 90),
                pct(sorted, 99), sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1) / 1e6);
    }

    private static double pct(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int i = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, i)) / 1e6;
    }

    /** 원인 사슬에서 gRPC 상태를 찾는다. 없으면 예외 클래스 이름. */
    private static String classify(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof StatusRuntimeException s) {
                return s.getStatus().getCode().name();
            }
            Status st = Status.fromThrowable(c);
            if (st.getCode() != Status.Code.UNKNOWN) {
                return st.getCode().name();
            }
        }
        return e.getClass().getSimpleName();
    }

    /** 이 플러그인 writer로 애니메이션 WebP를 만든다(ImageIO 표준 파라미터, 품질 0.75). */
    static byte[] encode(GifFrames.Animation a, boolean lossless) throws IOException {
        ImageWriter writer = new net.rivergod.webp.imageio.WebPImageWriterSpi().createWriterInstance();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 << 20);
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = EncodeBenchmark.param(writer, lossless);
            writer.prepareWriteSequence(writer.getDefaultStreamMetadata(param));
            for (int i = 0; i < a.frames().size(); i++) {
                BufferedImage frame = a.frames().get(i);
                IIOMetadata md = writer.getDefaultImageMetadata(null, param);
                IIOMetadataNode root = new IIOMetadataNode("net_rivergod_webp_imageio_1.0");
                IIOMetadataNode f = new IIOMetadataNode("Frame");
                f.setAttribute("durationMs", Integer.toString(a.delaysMs()[i]));
                root.appendChild(f);
                md.mergeTree("net_rivergod_webp_imageio_1.0", root);
                writer.writeToSequence(new IIOImage(frame, null, md), param);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    /** 결과를 이 플러그인 리더로 읽어 캔버스 크기와 프레임 수를 확인한다. */
    private static void verify(byte[] webp, GifFrames.Animation a) throws IOException {
        ImageReader reader = new net.rivergod.webp.imageio.WebPImageReaderSpi().createReaderInstance();
        try (MemoryCacheImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(webp))) {
            reader.setInput(in, false, true);
            int n = reader.getNumImages(true);
            if (n != a.frames().size() || reader.getWidth(0) != a.width() || reader.getHeight(0) != a.height()) {
                throw new IllegalStateException("converted WebP is " + reader.getWidth(0) + "x" + reader.getHeight(0)
                        + " x" + n + ", expected " + a.width() + "x" + a.height() + " x" + a.frames().size());
            }
        } finally {
            reader.dispose();
        }
    }
}
