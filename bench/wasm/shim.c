// libwebp Wasm 빌드의 최소 ABI(벤치마크 전용).
//
// JVM(Chicory)에서 호출할 함수만 내보낸다. 모든 포인터는 Wasm 선형 메모리 안의
// 32비트 오프셋이고, 크기 단위는 바이트다. 호스트 import가 하나도 없도록
// libwebp 디코더 API와 malloc/free만 쓴다(WASI 호출 없음 → 호스트 권한 없음).
//
// 반환 규약: 성공 1, 실패 0. 할당 실패는 0(NULL) 포인터로 알린다.
// 스레드: 이 모듈은 스레드 없이 빌드한다. 한 인스턴스는 한 스레드에서만 호출한다.

#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>

#include "webp/decode.h"

#define WP_EXPORT(name) __attribute__((export_name(name)))

// 입력·출력 버퍼용 할당. 실패하면(선형 메모리 최대 크기 도달 등) 0을 돌려준다.
WP_EXPORT("wp_alloc")
void* wp_alloc(size_t size) {
  return malloc(size);
}

// wp_alloc으로 받은 버퍼를 돌려준다. 0은 무시한다.
// Wasm 선형 메모리는 줄어들지 않으므로 해제한 영역은 다음 할당에 재사용된다.
WP_EXPORT("wp_free")
void wp_free(void* ptr) {
  free(ptr);
}

// 링크된 libwebp 디코더 버전. 0xMMmmrr 형식(예: 1.6.0 → 0x010600).
WP_EXPORT("wp_decoder_version")
int wp_decoder_version(void) {
  return WebPGetDecoderVersion();
}

// 헤더만 읽어 너비·높이(픽셀)를 width_out/height_out(각 int32 4바이트)에 쓴다.
// WebP가 아니거나 헤더가 깨졌으면 0. 픽셀 버퍼는 할당하지 않는다.
WP_EXPORT("wp_get_info")
int wp_get_info(const uint8_t* data, size_t data_size, int* width_out,
                int* height_out) {
  return WebPGetInfo(data, data_size, width_out, height_out);
}

// RGBA8(비-premultiplied, 바이트 순서 R,G,B,A — ADR-0004)로 out에 디코딩한다.
// out_size는 out 버퍼 바이트 수, stride는 한 행의 바이트 수다(패딩 없이 width × 4).
// 입력이 깨졌거나 out이 작거나 내부 할당이 실패하면 0.
WP_EXPORT("wp_decode_rgba_into")
int wp_decode_rgba_into(const uint8_t* data, size_t data_size, uint8_t* out,
                        size_t out_size, int stride) {
  return WebPDecodeRGBAInto(data, data_size, out, out_size, stride) != NULL;
}
