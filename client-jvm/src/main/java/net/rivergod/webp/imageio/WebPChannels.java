package net.rivergod.webp.imageio;

import io.grpc.ManagedChannel;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDomainSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.net.UnixDomainSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 엔드포인트별 공유 gRPC 채널. 처음 쓸 때 만들고 JVM이 끝날 때까지 쓴다.
 *
 * <p>채널과 이벤트 루프는 데몬 스레드라 JVM 종료를 막지 않는다. 정리를 {@code onDeregistration}에 맡기지 않는다
 * (JDK 26부터 레지스트리 GC 시 호출되지 않는다 — docs/research/0004). 스레드 안전하다.
 */
final class WebPChannels {

    /** gRPC 기본 최대 메시지 크기. 서버와 같은 값이다(ADR-0003: 어떤 메시지도 이 한도를 넘지 않는다). */
    static final int MAX_MESSAGE_BYTES = 4 * 1024 * 1024;

    private static final ConcurrentMap<String, ManagedChannel> CHANNELS = new ConcurrentHashMap<>();

    /** UDS용 NIO 이벤트 루프. 지연 초기화한다. */
    private static final class UdsEventLoop {
        static final EventLoopGroup GROUP =
                new MultiThreadIoEventLoopGroup(2, new DefaultThreadFactory("webp-imageio-uds", true), NioIoHandler.newFactory());
    }

    private WebPChannels() {}

    /** 엔드포인트의 공유 채널을 돌려준다. */
    static ManagedChannel forEndpoint(String endpoint) {
        return CHANNELS.computeIfAbsent(endpoint, WebPChannels::create);
    }

    private static ManagedChannel create(String endpoint) {
        NettyChannelBuilder builder;
        if (endpoint.startsWith("unix:")) {
            // ADR-0003 / research/0008: JNI 없는 NIO UDS 경로. native epoll을 쓰지 않는다.
            builder = NettyChannelBuilder.forAddress(UnixDomainSocketAddress.of(unixPath(endpoint)))
                    .channelType(NioDomainSocketChannel.class, UnixDomainSocketAddress.class)
                    .eventLoopGroup(UdsEventLoop.GROUP)
                    // research/0008: 소켓 경로가 :authority로 가면 tonic(h2) 서버가 PROTOCOL_ERROR로 거부한다.
                    .overrideAuthority("localhost");
        } else {
            builder = NettyChannelBuilder.forTarget(endpoint);
        }
        return builder.usePlaintext().maxInboundMessageSize(MAX_MESSAGE_BYTES).build();
    }

    /**
     * {@code unix:///abs/path} 또는 {@code unix:path}에서 소켓 경로를 꺼낸다.
     *
     * @throws IllegalArgumentException 경로가 비어 있으면
     */
    static Path unixPath(String endpoint) {
        String rest = endpoint.startsWith("unix://") ? endpoint.substring("unix://".length()) : endpoint.substring("unix:".length());
        if (rest.isEmpty()) {
            throw new IllegalArgumentException("unix endpoint without a path: " + endpoint);
        }
        return Path.of(rest);
    }
}
