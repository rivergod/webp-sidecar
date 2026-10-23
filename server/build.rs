//! proto/webp/v1 정의로 gRPC 서버·클라이언트 코드를 생성한다. protoc가 PATH에 있어야 한다(server/README.md).

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let proto_root = "../proto";
    println!("cargo:rerun-if-changed={proto_root}");
    tonic_prost_build::configure()
        // 데이터 조각은 bytes::Bytes로 받아 수신 측 복사를 줄인다(ADR-0002).
        .bytes(".")
        .compile_protos(&["../proto/webp/v1/webp_service.proto"], &[proto_root])?;
    Ok(())
}
