package net.rivergod.webp.imageio.poc;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

/** Phase 2 UDS PoC(ADR-0003): 세 경로의 동작과 처리량을 같은 서버에서 비교한다. 결과는 docs/research/0008에 기록한다. */
@Tag("poc")
class UdsTransportPocTest {

    @Test
    void allThreePaths_sameServer_decode4kAndReportThroughput() throws Exception {
        // given
        byte[] webp = PocSupport.sample4k();
        try (PocSupport.Server server = PocSupport.start(true)) {
            EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
            ManagedChannel nio = NettyChannelBuilder.forAddress(UnixDomainSocketAddress.of(server.socket()))
                    .channelType(NioDomainSocketChannel.class, UnixDomainSocketAddress.class)
                    .eventLoopGroup(group).overrideAuthority("localhost").usePlaintext().build();
            ManagedChannel epoll = Grpc.newChannelBuilder("unix://" + server.socket(), InsecureChannelCredentials.create())
                    .overrideAuthority("localhost").build();
            ManagedChannel tcp = Grpc.newChannelBuilder("127.0.0.1:" + server.tcpPort(), InsecureChannelCredentials.create())
                    .build();
            try {
                // when / then
                for (var entry : new Object[][] {{"A nio-uds (no JNI)", nio}, {"B epoll-uds (grpc unix:)", epoll}, {"C tcp", tcp}}) {
                    ManagedChannel ch = (ManagedChannel) entry[1];
                    assertEquals("1.6.0", PocSupport.libwebpVersion(ch));
                    System.out.println("POC " + entry[0] + ": " + PocSupport.measure(ch, webp, 5, 30));
                }
                System.out.println("POC epoll available: " + io.netty.channel.epoll.Epoll.isAvailable());
            } finally {
                for (ManagedChannel ch : new ManagedChannel[] {nio, epoll, tcp}) {
                    ch.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
                }
                group.shutdownGracefully().sync();
            }
        }
    }
}
