package net.rivergod.webp.imageio.bench;

import com.sun.management.ThreadMXBean;
import io.grpc.Status;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import net.rivergod.webp.imageio.WebPImageReaderSpi;
import net.rivergod.webp.imageio.WebPImageWriterSpi;
import net.rivergod.webp.imageio.WebPWriteParam;

/**
 * 섞인 작업 부하와 첫 호출 지연(검증 보강, docs/research/0014-load-soak.md). JMH 아님. 서버 엔드포인트와 플러그인 설정은
 * 시스템 프로퍼티({@code webp.imageio.*}), 입력은 {@code webp.bench.samples}의 사진 세트다.
 *
 * <ul>
 *   <li>{@code first-call}: 이 JVM에서 처음 하는 {@code ImageIO.read}(채널 연결, GetInfo, 클래스 로딩, JIT 전)와 두 번째, 열 번째
 *       읽기 시간을 ms로 한 줄 출력한다. 입력은 사진 M lossy.
 *   <li>{@code mixed <스레드 수> <초> <보고 간격 초>}: 스레드마다 아래 작업을 가중치대로 골라 반복한다. 간격마다 한 줄(누적 작업
 *       수, 구간 처리량, 오류)을 출력하고, 끝에 작업별 지연 백분위와 JVM GC 횟수·시간을 출력한다.
 *   <li>{@code alloc <반복 수>}: 작업마다 한 스레드로 반복해 1회당 JVM 전체 할당량(KiB)과 평균 시간을 출력한다. 결과 이미지는
 *       gRPC 수신 스레드에서 할당되므로 모든 스레드의 할당을 더한다. 다른 일이 없는 JVM에서 재야 한다.
 * </ul>
 *
 * <p>벤치 클래스패스에는 다른 WebP 플러그인(TwelveMonkeys, darkxanter)도 있어 {@code ImageIO.read}가 이 플러그인을 고른다는
 * 보장이 없다. 그래서 이 플러그인의 SPI로 리더·writer를 직접 만든다(설정은 시스템 프로퍼티).
 *
 * <p>작업과 가중치: 사진 M lossy 읽기 30, M alpha 읽기 20, M lossless 읽기 10, L lossy 읽기 10, M lossy 쓰기 15,
 * M lossless 쓰기 5, 애니메이션(1024×768, 사진) 모든 프레임 순차 읽기 10.
 */
public final class MixedLoad {

    private static final WebPImageReaderSpi READERS = new WebPImageReaderSpi();
    private static final WebPImageWriterSpi WRITERS = new WebPImageWriterSpi();

    private MixedLoad() {}

    /** 작업 하나. */
    private record Op(String name, int weight, Task task) {}

    @FunctionalInterface
    private interface Task {
        void run() throws Exception;
    }

    /** 작업별 통계. 지연은 ns. */
    private static final class Stats {
        final List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        final AtomicLong count = new AtomicLong();
    }

    /**
     * 명령줄 진입점.
     *
     * @param args 위 사용법
     * @throws Exception 입력을 읽지 못하면
     */
    public static void main(String[] args) throws Exception {
        switch (args.length > 0 ? args[0] : "") {
            case "first-call" -> firstCall();
            case "mixed" -> mixed(Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]));
            case "alloc" -> alloc(Integer.parseInt(args[1]));
            default -> throw new IllegalArgumentException(
                    "usage: MixedLoad first-call | mixed <threads> <seconds> <interval> | alloc <reps>");
        }
    }

    /** {@code ImageIO.read}와 같은 순서(입력 설정 → 기본 파라미터로 read(0) → dispose)로 이 플러그인 리더를 쓴다. */
    private static BufferedImage read(byte[] webp) throws Exception {
        ImageReader reader = READERS.createReaderInstance();
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(webp))) {
            reader.setInput(in, true, true);
            return reader.read(0, reader.getDefaultReadParam());
        } finally {
            reader.dispose();
        }
    }

    private static void firstCall() throws Exception {
        byte[] m = BenchInputs.webp(BenchInputs.Content.PHOTO, "M", BenchInputs.Mode.LOSSY);
        long t0 = System.nanoTime();
        read(m);
        long t1 = System.nanoTime();
        read(m);
        long t2 = System.nanoTime();
        for (int i = 0; i < 7; i++) {
            read(m);
        }
        long t9 = System.nanoTime();
        read(m);
        long t10 = System.nanoTime();
        System.out.printf(Locale.ROOT, "first_ms=%.1f second_ms=%.1f tenth_ms=%.2f%n",
                (t1 - t0) / 1e6, (t2 - t1) / 1e6, (t10 - t9) / 1e6);
    }

    private static void write(BufferedImage image, boolean lossless) throws Exception {
        ImageWriter writer = WRITERS.createWriterInstance();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            WebPWriteParam param = (WebPWriteParam) writer.getDefaultWriteParam();
            if (lossless) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionType(WebPWriteParam.LOSSLESS);
            }
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private static void readAllFrames(byte[] webp) throws Exception {
        ImageReader reader = READERS.createReaderInstance();
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(webp))) {
            reader.setInput(in);
            int n = reader.getNumImages(true);
            for (int i = 0; i < n; i++) {
                reader.read(i);
            }
        } finally {
            reader.dispose();
        }
    }

    private static List<Op> ops() {
        BenchInputs.Content photo = BenchInputs.Content.PHOTO;
        byte[] mLossy = BenchInputs.webp(photo, "M", BenchInputs.Mode.LOSSY);
        byte[] mAlpha = BenchInputs.webp(photo, "M", BenchInputs.Mode.ALPHA);
        byte[] mLossless = BenchInputs.webp(photo, "M", BenchInputs.Mode.LOSSLESS);
        byte[] lLossy = BenchInputs.webp(photo, "L", BenchInputs.Mode.LOSSY);
        byte[] anim = BenchInputs.animation();
        BufferedImage mSource = BenchInputs.source(photo, "M", BenchInputs.Mode.LOSSY);
        return List.of(
                new Op("read_M_lossy", 30, () -> read(mLossy)),
                new Op("read_M_alpha", 20, () -> read(mAlpha)),
                new Op("read_M_lossless", 10, () -> read(mLossless)),
                new Op("read_L_lossy", 10, () -> read(lLossy)),
                new Op("write_M_lossy", 15, () -> write(mSource, false)),
                new Op("write_M_lossless", 5, () -> write(mSource, true)),
                new Op("read_anim_all", 10, () -> readAllFrames(anim)));
    }

    private static String classify(Throwable e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.startsWith("WebP server busy")) {
            return "BUSY";
        }
        for (Throwable c = e; c != null; c = c.getCause()) {
            Status st = Status.fromThrowable(c);
            if (st.getCode() != Status.Code.UNKNOWN) {
                return st.getCode().name();
            }
        }
        return e.getClass().getSimpleName();
    }

    private static void mixed(int threads, int seconds, int interval) throws Exception {
        List<Op> ops = ops();
        int total = ops.stream().mapToInt(Op::weight).sum();
        Map<String, Stats> stats = new ConcurrentHashMap<>();
        ops.forEach(o -> stats.put(o.name(), new Stats()));
        Map<String, AtomicLong> errors = new ConcurrentHashMap<>();
        AtomicLong done = new AtomicLong();
        long start = System.nanoTime();
        long end = start + TimeUnit.SECONDS.toNanos(seconds);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            pool.execute(() -> {
                while (System.nanoTime() < end) {
                    int pick = ThreadLocalRandom.current().nextInt(total);
                    Op op = ops.get(0);
                    for (Op o : ops) {
                        if (pick < o.weight()) {
                            op = o;
                            break;
                        }
                        pick -= o.weight();
                    }
                    long s = System.nanoTime();
                    try {
                        op.task().run();
                        long elapsed = System.nanoTime() - s;
                        Stats st = stats.get(op.name());
                        st.latencies.add(elapsed);
                        st.count.incrementAndGet();
                        done.incrementAndGet();
                    } catch (Exception e) {
                        errors.computeIfAbsent(op.name() + ":" + classify(e), k -> new AtomicLong()).incrementAndGet();
                        // 혼잡 거부는 플러그인 재시도(webp.imageio.busy-retries) 뒤에도 남은 것이다. 잠시 물러선다.
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            });
        }
        pool.shutdown();

        // 구간 보고: 경과 초, 누적 작업 수, 구간 처리량, 누적 오류.
        long last = 0;
        long lastTime = start;
        while (!pool.awaitTermination(interval, TimeUnit.SECONDS)) {
            long now = System.nanoTime();
            long d = done.get();
            System.out.printf(Locale.ROOT, "interval t_s=%d ops=%d ops_per_s=%.2f errors=%s%n",
                    TimeUnit.NANOSECONDS.toSeconds(now - start), d, (d - last) / ((now - lastTime) / 1e9), errors);
            System.out.flush();
            last = d;
            lastTime = now;
        }
        double elapsed = (System.nanoTime() - start) / 1e9;

        // 작업별 요약: 횟수, 지연 백분위(ms).
        for (Op o : ops) {
            Stats st = stats.get(o.name());
            List<Long> l = new ArrayList<>(st.latencies);
            Collections.sort(l);
            long n = st.count.get();
            if (n == 0) {
                System.out.printf("op %s count=0%n", o.name());
                continue;
            }
            System.out.printf(Locale.ROOT, "op %s count=%d p50_ms=%.2f p99_ms=%.2f max_ms=%.2f%n",
                    o.name(), n, l.get(l.size() / 2) / 1e6, l.get((int) (l.size() * 0.99)) / 1e6,
                    l.get(l.size() - 1) / 1e6);
        }
        long gcCount = 0;
        long gcMs = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += gc.getCollectionCount();
            gcMs += gc.getCollectionTime();
        }
        System.out.printf(Locale.ROOT,
                "summary threads=%d elapsed_s=%.1f ops=%d ops_per_s=%.2f errors=%s gc_count=%d gc_ms=%d gc_share=%.4f%n",
                threads, elapsed, done.get(), done.get() / elapsed, errors, gcCount, gcMs, gcMs / 1000.0 / elapsed);
    }

    /** JVM의 살아 있는 모든 스레드가 지금까지 할당한 바이트 합계. */
    private static long totalAllocated(ThreadMXBean mx) {
        long sum = 0;
        for (long b : mx.getThreadAllocatedBytes(mx.getAllThreadIds())) {
            if (b > 0) {
                sum += b;
            }
        }
        return sum;
    }

    private static void alloc(int reps) throws Exception {
        ThreadMXBean mx = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        for (Op o : ops()) {
            for (int i = 0; i < 20; i++) {
                o.task().run();
            }
            long a0 = totalAllocated(mx);
            long t0 = System.nanoTime();
            for (int i = 0; i < reps; i++) {
                o.task().run();
            }
            long t1 = System.nanoTime();
            long a1 = totalAllocated(mx);
            System.out.printf(Locale.ROOT, "alloc %s reps=%d alloc_kib_per_op=%.0f mean_ms=%.2f%n",
                    o.name(), reps, (a1 - a0) / 1024.0 / reps, (t1 - t0) / 1e6 / reps);
        }
    }
}
