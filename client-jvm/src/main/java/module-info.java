/**
 * WebP ImageIO 플러그인 모듈(ADR-0005, ADR-0013). module path에서는 {@code provides}로, classpath에서는
 * {@code META-INF/services}로 SPI를 등록한다. 생성된 gRPC/protobuf 코드는 export하지 않는다.
 */
module net.rivergod.webp.imageio {
    requires transitive java.desktop;
    requires com.google.common;
    requires com.google.protobuf;
    requires io.grpc;
    requires io.grpc.netty;
    requires io.grpc.protobuf;
    requires io.grpc.stub;
    requires io.netty.common;
    requires io.netty.transport;

    exports net.rivergod.webp.imageio;

    // protobuf 런타임이 생성 메시지를 리플렉션으로 다룰 때(예: toString)를 위해 연다. 사용자 코드에는 열지 않는다.
    opens net.rivergod.webp.imageio.proto.v1 to com.google.protobuf;

    provides javax.imageio.spi.ImageReaderSpi with net.rivergod.webp.imageio.WebPImageReaderSpi;
    provides javax.imageio.spi.ImageWriterSpi with net.rivergod.webp.imageio.WebPImageWriterSpi;
}
