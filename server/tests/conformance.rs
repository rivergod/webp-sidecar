//! 적합성: libwebp 공식 테스트 벡터(libwebp-test-data, 커밋 06ddd96e)의 디코딩 결과를 `.pam` md5와 비교한다.
//! 데이터를 받아야 하므로 기본 실행에서 제외한다.
//!
//! 준비: samples/README.md "적합성 데이터 받기". 실행: `cargo test --test conformance -- --ignored`
//! `.pgm` 줄은 미릴리스 libwebp 변경으로 갱신돼 있어 쓰지 않는다(docs/research/0005).

mod common;

use common::{TestServer, decode, decode_options};
use md5::{Digest, Md5};
use std::path::Path;

#[tokio::test]
#[ignore = "requires samples/conformance (samples/README.md)"]
async fn libwebp_test_vectors_match_pam_md5() {
    // given: libwebp_tests.md5의 `.pam` 줄 = dwebp -pam 출력의 md5
    let dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("../samples/conformance");
    let list = std::fs::read_to_string(dir.join("libwebp_tests.md5")).expect("samples/conformance/libwebp_tests.md5");
    let cases: Vec<(String, String)> = list
        .lines()
        .filter_map(|l| l.split_once("  "))
        .filter_map(|(md5, name)| {
            name.strip_suffix(".pam")
                .map(|webp| (md5.to_string(), webp.to_string()))
        })
        .collect();
    assert!(cases.len() >= 90, "expected ~96 .pam cases, found {}", cases.len());
    let server = TestServer::start().await;
    let mut client = server.client().await;

    // when / then: 서버 디코딩 결과로 dwebp와 같은 PAM 바이트를 만들어 md5를 비교한다.
    let mut failures = Vec::new();
    for (want, name) in &cases {
        let data = std::fs::read(dir.join(name)).unwrap();
        match decode(&mut client, &data, decode_options(&data)).await {
            Ok((header, pixels)) => {
                let mut pam = format!(
                    "P7\nWIDTH {}\nHEIGHT {}\nDEPTH 4\nMAXVAL 255\nTUPLTYPE RGB_ALPHA\nENDHDR\n",
                    header.width, header.height
                )
                .into_bytes();
                pam.extend_from_slice(&pixels);
                let got: String = Md5::digest(&pam).iter().map(|b| format!("{b:02x}")).collect();
                if &got != want {
                    failures.push(format!("{name}: md5 {got} != {want}"));
                }
            }
            Err(status) => failures.push(format!("{name}: {:?} {}", status.code(), status.message())),
        }
    }
    assert!(
        failures.is_empty(),
        "{} of {} failed:\n{}",
        failures.len(),
        cases.len(),
        failures.join("\n")
    );
}
