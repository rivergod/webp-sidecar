// libwebp 정지 이미지 디코딩 시간 측정(사진 alpha 격차 조사, docs/research/0013-alpha-decode-gap.md).
//
// 사용: alpha_decode <반복 수> <모드> <스레드 0|1> <출력 into|alloc> <file.webp>...
//   모드: rgba | bgra | argb | rgbA(premultiplied) | yuv
//   into: 미리 할당한 출력 버퍼에 디코딩한다(서버의 버퍼 재사용 경로와 같다). alloc: libwebp가 매번 할당한다.
//
// 반복 수의 1/5을 먼저 버리고(워밍업) 나머지의 중앙값·최솟값·90백분위를 ms로 출력한다(server/examples/decode_time.rs와
// 같은 방식). 한 스레드에서 돈다(threads=1이면 libwebp가 필터 스레드를 하나 더 쓴다).
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "webp/decode.h"

static double now_ms(void) {
  struct timespec t;
  clock_gettime(CLOCK_MONOTONIC, &t);
  return t.tv_sec * 1e3 + t.tv_nsec / 1e6;
}

static int cmp(const void* a, const void* b) {
  double x = *(const double*)a, y = *(const double*)b;
  return (x > y) - (x < y);
}

static uint8_t* read_file(const char* path, size_t* size) {
  FILE* f = fopen(path, "rb");
  if (f == NULL) return NULL;
  fseek(f, 0, SEEK_END);
  long n = ftell(f);
  fseek(f, 0, SEEK_SET);
  uint8_t* data = malloc((size_t)n);
  if (data == NULL || fread(data, 1, (size_t)n, f) != (size_t)n) {
    fclose(f);
    free(data);
    return NULL;
  }
  fclose(f);
  *size = (size_t)n;
  return data;
}

static int parse_mode(const char* s, WEBP_CSP_MODE* mode) {
  if (strcmp(s, "rgba") == 0) *mode = MODE_RGBA;
  else if (strcmp(s, "bgra") == 0) *mode = MODE_BGRA;
  else if (strcmp(s, "argb") == 0) *mode = MODE_ARGB;
  else if (strcmp(s, "rgbA") == 0) *mode = MODE_rgbA;
  else if (strcmp(s, "yuv") == 0) *mode = MODE_YUVA;
  else return 0;
  return 1;
}

// 한 번 디코딩한다. 성공하면 1.
static int decode_once(const uint8_t* data, size_t size, WEBP_CSP_MODE mode, int threads, uint8_t* out,
                       size_t out_size, int width) {
  WebPDecoderConfig config;
  if (!WebPInitDecoderConfig(&config)) return 0;
  config.options.use_threads = threads;
  config.output.colorspace = mode;
  if (out != NULL) {
    // 외부 버퍼(서버 decode_still_rgba_into와 같다). YUV는 alloc만 쓴다.
    config.output.is_external_memory = 1;
    config.output.u.RGBA.rgba = out;
    config.output.u.RGBA.stride = width * 4;
    config.output.u.RGBA.size = out_size;
  }
  int ok = WebPDecode(data, size, &config) == VP8_STATUS_OK;
  WebPFreeDecBuffer(&config.output);
  return ok;
}

int main(int argc, char** argv) {
  if (argc < 6) {
    fprintf(stderr, "usage: alpha_decode <iterations> <rgba|bgra|argb|rgbA|yuv> <threads 0|1> <into|alloc> <file>...\n");
    return 2;
  }
  int iterations = atoi(argv[1]);
  WEBP_CSP_MODE mode;
  if (iterations <= 0 || !parse_mode(argv[2], &mode)) {
    fprintf(stderr, "bad arguments\n");
    return 2;
  }
  int threads = atoi(argv[3]);
  int into = strcmp(argv[4], "into") == 0;
  if (into && mode == MODE_YUVA) {
    fprintf(stderr, "into is for RGB modes only\n");
    return 2;
  }
  int v = WebPGetDecoderVersion();
  printf("libwebp\tmode\tthreads\toutput\tfile\tmedian_ms\tmin_ms\tp90_ms\titerations\n");
  for (int i = 5; i < argc; i++) {
    size_t size;
    uint8_t* data = read_file(argv[i], &size);
    int width, height;
    if (data == NULL || !WebPGetInfo(data, size, &width, &height)) {
      fprintf(stderr, "cannot read %s\n", argv[i]);
      return 1;
    }
    size_t out_size = (size_t)width * height * 4;
    uint8_t* out = into ? malloc(out_size) : NULL;
    int warmup = iterations / 5 > 0 ? iterations / 5 : 1;
    for (int k = 0; k < warmup; k++) {
      if (!decode_once(data, size, mode, threads, out, out_size, width)) {
        fprintf(stderr, "decode failed: %s\n", argv[i]);
        return 1;
      }
    }
    double* t = malloc(sizeof(double) * (size_t)iterations);
    for (int k = 0; k < iterations; k++) {
      double s = now_ms();
      decode_once(data, size, mode, threads, out, out_size, width);
      t[k] = now_ms() - s;
    }
    qsort(t, (size_t)iterations, sizeof(double), cmp);
    const char* name = strrchr(argv[i], '/');
    printf("%d.%d.%d\t%s\t%d\t%s\t%s\t%.3f\t%.3f\t%.3f\t%d\n", (v >> 16) & 0xff, (v >> 8) & 0xff, v & 0xff, argv[2],
           threads, argv[4], name ? name + 1 : argv[i], t[iterations / 2], t[0], t[iterations * 9 / 10], iterations);
    free(t);
    free(out);
    free(data);
  }
  return 0;
}
