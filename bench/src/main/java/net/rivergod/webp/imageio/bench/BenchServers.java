package net.rivergod.webp.imageio.bench;

import io.grpc.ManagedChannel;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDomainSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.net.UnixDomainSocketAddress;
import java.nio.file.Path;

/**
 * 벤치 서버 이름 → 엔드포인트. bench/run.sh가 컨테이너를 띄우고 {@code -Dwebp.bench.server.<이름>=<엔드포인트>}로 넘긴다.
 *
 * <p>이름 규칙: {@code <빌드>-<전송>[-<조각>]}. 예: {@code sse2-uds}(판정용 기본 빌드), {@code sse2-tcp}, {@code avx2-uds},
 * {@code sse2-uds-256k}. 각 컨테이너의 설정은 bench/run.sh에 있다.
 */
public final class BenchServers {

    private BenchServers() {}

    /**
     * 서버 이름의 엔드포인트.
     *
     * @param name 서버 이름
     * @return {@code unix:///...} 또는 {@code host:port}
     * @throws IllegalStateException 매핑이 없을 때
     */
    public static String endpoint(String name) {
        String v = System.getProperty("webp.bench.server." + name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("no endpoint for bench server '" + name
                    + "' (set -Dwebp.bench.server." + name + "=..., see bench/run.sh)");
        }
        return v.trim();
    }

    /**
     * 이 프로젝트 플러그인이 쓸 서버를 정한다. 플러그인은 리더를 만들 때마다 {@code webp.imageio.endpoint}를 읽는다.
     *
     * @param name 서버 이름
     */
    public static void use(String name) {
        System.setProperty("webp.imageio.endpoint", endpoint(name));
    }

    /**
     * 원시 gRPC 측정용 채널. 플러그인(WebPChannels)과 같은 설정이다: UDS는 JNI 없는 NIO 경로, 최대 메시지 4 MiB.
     *
     * @param endpoint 엔드포인트
     * @return 새 채널(호출한 쪽이 닫는다)
     */
    public static ManagedChannel channel(String endpoint) {
        NettyChannelBuilder b;
        if (endpoint.startsWith("unix://")) {
            b = NettyChannelBuilder.forAddress(UnixDomainSocketAddress.of(Path.of(endpoint.substring("unix://".length()))))
                    .channelType(NioDomainSocketChannel.class, UnixDomainSocketAddress.class)
                    .eventLoopGroup(UdsEventLoop.GROUP)
                    .overrideAuthority("localhost");
        } else {
            b = NettyChannelBuilder.forTarget(endpoint);
        }
        return b.usePlaintext().maxInboundMessageSize(4 * 1024 * 1024).build();
    }

    private static final class UdsEventLoop {
        static final EventLoopGroup GROUP = new MultiThreadIoEventLoopGroup(
                2, new DefaultThreadFactory("bench-uds", true), NioIoHandler.newFactory());
    }
}
