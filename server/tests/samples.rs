//! samples/manifest.json(ADR-0006)의 기대값과 서버 결과를 비교한다.
//! 기대값은 같은 libwebp 1.6.0으로 만든 것이다(samples/README.md). 실패하면 어긋난 파일을 모두 모아 보고한다.

mod common;

use common::{TestServer, decode, decode_animation, decode_options, get_features};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};
use tonic::Code;
use webp_sidecar::config::Limits;

fn samples_dir() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../samples")
}

fn manifest_files() -> Vec<Value> {
    let text = std::fs::read_to_string(samples_dir().join("manifest.json")).expect("read samples/manifest.json");
    let manifest: Value = serde_json::from_str(&text).expect("parse manifest.json");
    manifest["files"].as_array().expect("files[]").clone()
}

fn read_sample(entry: &Value) -> (String, Vec<u8>) {
    let path = entry["path"].as_str().unwrap().to_string();
    let data = std::fs::read(samples_dir().join(&path)).unwrap();
    (path, data)
}

fn sha256_hex(data: &[u8]) -> String {
    Sha256::digest(data).iter().map(|b| format!("{b:02x}")).collect()
}

fn is_webp(entry: &Value) -> bool {
    entry["path"].as_str().is_some_and(|p| p.ends_with(".webp"))
}

#[tokio::test]
async fn generated_still_images_decode_to_expected_rgba() {
    // given: 정지 이미지이고 디코딩 기대값이 있는 생성 파일
    let server = TestServer::start().await;
    let mut client = server.client().await;
    let entries: Vec<Value> = manifest_files()
        .into_iter()
        .filter(|e| {
            let p = e["path"].as_str().unwrap_or("");
            (p.starts_with("generated/still/") || p.starts_with("generated/meta/"))
                && is_webp(e)
                && e["expected"]["decode"].is_object()
        })
        .collect();
    assert!(
        entries.len() >= 20,
        "expected generated still samples, found {}",
        entries.len()
    );

    // when / then: 파일마다 디코딩해 크기와 RGBA8 SHA-256을 비교한다.
    let mut failures = Vec::new();
    for entry in &entries {
        let (path, data) = read_sample(entry);
        let want = &entry["expected"]["decode"];
        match decode(&mut client, &data, decode_options(&data)).await {
            Ok((header, pixels)) => {
                let got = (header.width as u64, header.height as u64, sha256_hex(&pixels));
                let expected = (
                    want["width"].as_u64().unwrap(),
                    want["height"].as_u64().unwrap(),
                    want["rgba_sha256"].as_str().unwrap().to_string(),
                );
                if got != expected {
                    failures.push(format!("{path}: got {got:?}, want {expected:?}"));
                }
            }
            Err(status) => failures.push(format!("{path}: {:?} {}", status.code(), status.message())),
        }
    }
    assert!(
        failures.is_empty(),
        "{} of {} mismatched:\n{}",
        failures.len(),
        entries.len(),
        failures.join("\n")
    );
}

#[tokio::test]
async fn generated_metadata_chunks_match_expected_payloads() {
    // given: 메타데이터 기대값이 있는 파일
    let server = TestServer::start().await;
    let mut client = server.client().await;
    let entries: Vec<Value> = manifest_files()
        .into_iter()
        .filter(|e| is_webp(e) && e["expected"]["metadata"].is_object())
        .collect();
    assert!(!entries.is_empty(), "expected metadata samples");

    // when / then: GetFeatures(include_metadata)의 청크 페이로드 크기와 해시(첫 청크)를 비교한다.
    let mut failures = Vec::new();
    for entry in &entries {
        let (path, data) = read_sample(entry);
        let want = &entry["expected"]["metadata"];
        let response = match get_features(&mut client, &data, true).await {
            Ok(r) => r,
            Err(status) => {
                failures.push(format!("{path}: {:?} {}", status.code(), status.message()));
                continue;
            }
        };
        let metadata = response.metadata.unwrap_or_default();
        for (key, got) in [
            ("icc", &metadata.icc_profile),
            ("exif", &metadata.exif),
            ("xmp", &metadata.xmp),
        ] {
            let expected = want
                .get(key)
                .filter(|v| v.is_object())
                .map(|v| v["sha256"].as_str().unwrap().to_string());
            let got = got.as_ref().map(|b| sha256_hex(b));
            if got != expected {
                failures.push(format!("{path} {key}: got {got:?}, want {expected:?}"));
            }
        }
    }
    assert!(
        failures.is_empty(),
        "{} mismatches:\n{}",
        failures.len(),
        failures.join("\n")
    );
}

#[tokio::test]
async fn malformed_still_files_follow_libwebp_outcome() {
    // given: 정지 이미지 디코더 대상의 손상·악성 입력
    let server = TestServer::start().await;
    let mut client = server.client().await;
    let entries: Vec<Value> = manifest_files()
        .into_iter()
        .filter(|e| {
            e["path"].as_str().is_some_and(|p| p.starts_with("malformed/")) && e["expected"]["decoder"] == "still"
        })
        .collect();
    assert!(
        entries.len() >= 40,
        "expected malformed still samples, found {}",
        entries.len()
    );

    // when / then: libwebp가 거부한 파일은 서버도 거부하고(상한 초과는 RESOURCE_EXHAUSTED),
    // libwebp가 받은 파일은 서버도 같은 픽셀로 디코딩한다. 어떤 경우에도 서버 내부 오류가 나면 안 된다.
    let mut failures = Vec::new();
    for entry in &entries {
        let (path, data) = read_sample(entry);
        let expected = &entry["expected"];
        let over_limit = expected["exceeds_default_limits"]
            .as_array()
            .is_some_and(|l| !l.is_empty());
        let result = if data.is_empty() {
            Err(tonic::Status::invalid_argument("empty file"))
        } else {
            decode(&mut client, &data, decode_options(&data)).await
        };
        match (expected["outcome"].as_str().unwrap(), result) {
            ("reject", Ok(_)) => failures.push(format!("{path}: accepted but libwebp rejects")),
            ("reject", Err(status)) => {
                let ok_codes: &[Code] = if over_limit {
                    &[Code::ResourceExhausted]
                } else {
                    &[Code::InvalidArgument, Code::ResourceExhausted]
                };
                if !ok_codes.contains(&status.code()) {
                    failures.push(format!(
                        "{path}: rejected with {:?} {}",
                        status.code(),
                        status.message()
                    ));
                }
            }
            ("accept", Ok((_, pixels))) => {
                if let Some(want) = expected["decode"]["rgba_sha256"].as_str()
                    && sha256_hex(&pixels) != want
                {
                    failures.push(format!("{path}: accepted but pixels differ from libwebp"));
                }
            }
            ("accept", Err(status)) => failures.push(format!(
                "{path}: rejected ({:?} {}) but libwebp accepts",
                status.code(),
                status.message()
            )),
            (other, _) => failures.push(format!("{path}: unknown outcome {other}")),
        }
    }
    assert!(
        failures.is_empty(),
        "{} of {} mismatched:\n{}",
        failures.len(),
        entries.len(),
        failures.join("\n")
    );
}

#[tokio::test]
async fn generated_animations_decode_to_expected_frames() {
    // given: 애니메이션 기대값(프레임별 합성 캔버스 RGBA8 SHA-256, WebPAnimDecoder MODE_RGBA)이 있는 파일
    let server = TestServer::start().await;
    let mut client = server.client().await;
    let entries: Vec<Value> = manifest_files()
        .into_iter()
        .filter(|e| is_webp(e) && e["expected"]["animation"].is_object())
        .collect();
    assert!(
        entries.len() >= 10,
        "expected animation samples, found {}",
        entries.len()
    );

    // when / then: 프레임 수, 프레임별 해시·표시 시간, 반복 횟수를 비교한다.
    let mut failures = Vec::new();
    for entry in &entries {
        let (path, data) = read_sample(entry);
        let want = &entry["expected"]["animation"];
        let (features, frames) = match decode_animation(&mut client, &data).await {
            Ok(r) => r,
            Err(status) => {
                failures.push(format!("{path}: {:?} {}", status.code(), status.message()));
                continue;
            }
        };
        let want_frames = want["frames"].as_array().unwrap();
        if frames.len() != want_frames.len() {
            failures.push(format!("{path}: {} frames, want {}", frames.len(), want_frames.len()));
            continue;
        }
        for ((header, pixels), w) in frames.iter().zip(want_frames) {
            if sha256_hex(pixels) != w["rgba_sha256"].as_str().unwrap()
                || u64::from(header.duration_ms) != w["duration_ms"].as_u64().unwrap()
            {
                failures.push(format!("{path}: frame {} differs", header.frame_index));
            }
        }
        let loop_count = features.animation.map(|a| a.loop_count);
        if loop_count.map(u64::from) != want["loop_count"].as_u64() && want.get("loop_count").is_some() {
            failures.push(format!(
                "{path}: loop_count {loop_count:?}, want {}",
                want["loop_count"]
            ));
        }
    }
    assert!(
        failures.is_empty(),
        "{} mismatches:\n{}",
        failures.len(),
        failures.join("\n")
    );
}

#[tokio::test]
async fn malformed_animation_files_follow_libwebp_outcome() {
    // given: 애니메이션 디코더 대상의 손상·악성 입력
    let server = TestServer::start().await;
    let mut client = server.client().await;
    let entries: Vec<Value> = manifest_files()
        .into_iter()
        .filter(|e| {
            e["path"].as_str().is_some_and(|p| p.starts_with("malformed/")) && e["expected"]["decoder"] == "anim"
        })
        .collect();
    assert!(!entries.is_empty());

    // when / then: libwebp(WebPAnimDecoder)가 거부한 파일은 서버도 거부한다. 받은 파일은 서버도 받는다.
    // 서버는 상한(max_frames 등)을 추가로 적용하므로, 상한을 넘는 파일은 libwebp가 받아도 RESOURCE_EXHAUSTED가 맞다.
    let mut failures = Vec::new();
    let mut bombs = Vec::new();
    for entry in &entries {
        let (path, data) = read_sample(entry);
        let expected = &entry["expected"];
        // 출력량 폭탄(예: 4096² 캔버스 × 1000프레임 ≈ 64 GiB)은 합성 픽셀 수 상한(ADR-0017)에 걸려 픽셀을 보내기 전에
        // RESOURCE_EXHAUSTED여야 한다. manifest의 exceeds_default_limits는 이 상한이 생기기 전에 기록한 값이라 여기서 더한다.
        let demux = &expected["observed"]["demux"];
        let composited = demux["canvas_width"].as_u64().unwrap_or(0)
            * demux["canvas_height"].as_u64().unwrap_or(0)
            * demux["frame_count"].as_u64().unwrap_or(0);
        let over_limit = expected["exceeds_default_limits"]
            .as_array()
            .is_some_and(|l| !l.is_empty())
            || composited > Limits::default().max_composited_pixels;
        if composited > Limits::default().max_composited_pixels {
            bombs.push(path.clone());
        }
        let result = decode_animation(&mut client, &data).await;
        match (expected["outcome"].as_str().unwrap(), over_limit, result) {
            (_, true, Err(s)) if s.code() == Code::ResourceExhausted => {}
            (_, true, other) => failures.push(format!(
                "{path}: over limit but got {:?}",
                other.err().map(|s| s.code())
            )),
            ("reject", false, Err(s)) if matches!(s.code(), Code::InvalidArgument | Code::ResourceExhausted) => {}
            ("accept", false, Ok(_)) => {}
            (outcome, false, other) => failures.push(format!(
                "{path}: libwebp {outcome}, server {:?}",
                other.err().map(|s| (s.code(), s.message().to_string()))
            )),
        }
    }
    assert!(!bombs.is_empty(), "expected at least one output-bomb sample");
    assert!(
        failures.is_empty(),
        "{} mismatches:\n{}",
        failures.len(),
        failures.join("\n")
    );
}
