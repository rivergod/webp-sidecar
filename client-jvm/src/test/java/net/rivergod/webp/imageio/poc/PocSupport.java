package net.rivergod.webp.imageio.poc;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.rivergod.webp.imageio.proto.v1.DecodeOptions;
import net.rivergod.webp.imageio.proto.v1.DecodeRequest;
import net.rivergod.webp.imageio.proto.v1.DecodeResponse;
import net.rivergod.webp.imageio.proto.v1.GetInfoRequest;
import net.rivergod.webp.imageio.proto.v1.PixelFormat;
import net.rivergod.webp.imageio.proto.v1.WebPServiceGrpc;

/** UDS PoC 공용 도구(버릴 코드가 아니라 PoC 기록용 테스트 코드). docker CLI로 서버 컨테이너를 띄운다. */
final class PocSupport {
    static final int CHUNK = 1024 * 1024;

    private PocSupport() {}

    /** 실행 중인 서버 컨테이너. */
    record Server(String containerId, Path socketDir, int tcpPort) implements AutoCloseable {
        Path socket() {
            return socketDir.resolve("webp.sock");
        }

        @Override
        public void close() throws Exception {
            run("docker", "rm", "-f", containerId);
        }
    }

    /** 짧은 /tmp 경로(AF_UNIX 경로 길이 한도)에 소켓 디렉터리를 만들고 서버를 띄운다. tcp면 TCP도 켠다. */
    static Server start(boolean tcp) throws Exception {
        Path dir = Files.createTempDirectory(Path.of("/tmp"), "wpoc");
        Object uid = Files.getAttribute(dir, "unix:uid");
        Object gid = Files.getAttribute(dir, "unix:gid");
        List<String> cmd = new ArrayList<>(List.of("docker", "run", "-d", "--rm", "--read-only", "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges", "--memory", "1g", "--cpus", "2", "--user", uid + ":" + gid,
                "-v", dir + ":/run/webp-imageio"));
        if (tcp) {
            cmd.addAll(List.of("-e", "WEBP_SERVER_TCP_ADDR=0.0.0.0:50051", "-p", "127.0.0.1::50051"));
        } else {
            cmd.addAll(List.of("--network", "none"));
        }
        cmd.add(System.getProperty("webp.test.serverImage", "webp-imageio-server:dev"));
        String id = run(cmd.toArray(String[]::new)).trim();
        int port = -1;
        if (tcp) {
            String mapping = run("docker", "port", id, "50051/tcp").trim().split("\n")[0];
            port = Integer.parseInt(mapping.substring(mapping.lastIndexOf(':') + 1));
        }
        Path socket = dir.resolve("webp.sock");
        for (int i = 0; i < 100 && !Files.exists(socket); i++) {
            Thread.sleep(50);
        }
        if (!Files.exists(socket)) {
            throw new IllegalStateException("server socket did not appear: " + run("docker", "logs", id));
        }
        return new Server(id, dir, port);
    }

    static String run(String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IOException(String.join(" ", cmd) + " failed: " + out);
        }
        return out;
    }

    static String libwebpVersion(ManagedChannel channel) {
        return WebPServiceGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS)
                .getInfo(GetInfoRequest.getDefaultInstance()).getLibwebp().getDecoder();
    }

    /** Decode를 한 번 호출하고 받은 픽셀 바이트 수를 돌려준다. */
    static long decodeOnce(ManagedChannel channel, byte[] webp) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicLong bytes = new AtomicLong();
        AtomicReference<Throwable> error = new AtomicReference<>();
        StreamObserver<DecodeRequest> requests = WebPServiceGrpc.newStub(channel).decode(new StreamObserver<>() {
            @Override
            public void onNext(DecodeResponse r) {
                if (r.hasPixels()) {
                    bytes.addAndGet(r.getPixels().size());
                }
            }

            @Override
            public void onError(Throwable t) {
                error.set(t);
                done.countDown();
            }

            @Override
            public void onCompleted() {
                done.countDown();
            }
        });
        requests.onNext(DecodeRequest.newBuilder().setOptions(DecodeOptions.newBuilder()
                .setInputBytes(webp.length).setPixelFormat(PixelFormat.PIXEL_FORMAT_RGBA8)).build());
        for (int off = 0; off < webp.length; off += CHUNK) {
            requests.onNext(DecodeRequest.newBuilder()
                    .setWebpData(ByteString.copyFrom(webp, off, Math.min(CHUNK, webp.length - off))).build());
        }
        requests.onCompleted();
        if (!done.await(60, TimeUnit.SECONDS)) {
            throw new IllegalStateException("decode timed out");
        }
        if (error.get() != null) {
            throw new IllegalStateException(error.get());
        }
        return bytes.get();
    }

    /** warmup 후 n번 디코딩한 평균 시간(ms)과 처리량(MiB/s)을 문자열로 돌려준다. */
    static String measure(ManagedChannel channel, byte[] webp, int warmup, int n) throws Exception {
        for (int i = 0; i < warmup; i++) {
            decodeOnce(channel, webp);
        }
        long start = System.nanoTime();
        long total = 0;
        for (int i = 0; i < n; i++) {
            total += decodeOnce(channel, webp);
        }
        double ms = (System.nanoTime() - start) / 1e6 / n;
        double mibPerSec = total / 1048576.0 / ((System.nanoTime() - start) / 1e9);
        return String.format("%.1f ms/decode, %.0f MiB/s (pixels %d bytes/decode)", ms, mibPerSec, total / n);
    }

    static byte[] sample4k() throws IOException {
        return Files.readAllBytes(Path.of(System.getProperty("user.dir"))
                .resolve("../samples/generated/still/lossless_3840x2160.webp"));
    }
}
