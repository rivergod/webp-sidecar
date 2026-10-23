package net.rivergod.webp.imageio.poc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDomainSocketChannel;
import java.net.UnixDomainSocketAddress;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 UDS PoC(ADR-0003): {@code --illegal-native-access=deny}로 실행한다(Gradle 작업 udsPocDeny).
 * JNI 없는 경로는 동작하고, native epoll 경로는 쓸 수 없어야 한다.
 */
@Tag("poc-deny")
class UdsNoNativeAccessPocTest {

    @Test
    void nioUds_nativeAccessDenied_stillWorks() throws Exception {
        // given
        try (PocSupport.Server server = PocSupport.start(false)) {
            EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
            ManagedChannel nio = NettyChannelBuilder.forAddress(UnixDomainSocketAddress.of(server.socket()))
                    .channelType(NioDomainSocketChannel.class, UnixDomainSocketAddress.class)
                    .eventLoopGroup(group).overrideAuthority("localhost").usePlaintext().build();
            try {
                // when
                String version = PocSupport.libwebpVersion(nio);

                // then
                assertEquals("1.6.0", version);
                System.out.println("POC-DENY A nio-uds: ok, " + PocSupport.measure(nio, PocSupport.sample4k(), 2, 10));
            } finally {
                nio.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
                group.shutdownGracefully().sync();
            }
        }
    }

    @Test
    void epollUds_nativeAccessDenied_isUnavailable() throws Exception {
        // given
        try (PocSupport.Server server = PocSupport.start(false)) {
            // when / then: native 라이브러리를 못 올리므로 epoll이 비활성이고 unix: 대상은 쓸 수 없다.
            System.out.println("POC-DENY epoll available: " + io.netty.channel.epoll.Epoll.isAvailable()
                    + ", cause: " + io.netty.channel.epoll.Epoll.unavailabilityCause());
            Throwable error = assertThrows(Throwable.class, () -> {
                ManagedChannel ch = Grpc.newChannelBuilder("unix://" + server.socket(),
                        InsecureChannelCredentials.create()).overrideAuthority("localhost").build();
                try {
                    PocSupport.libwebpVersion(ch);
                } finally {
                    ch.shutdownNow();
                }
            });
            System.out.println("POC-DENY B epoll-uds: " + error);
        }
    }
}
