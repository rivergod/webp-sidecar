// webp_probe: 샘플 기대값을 만들 때 libwebp API를 서버와 같은 방식으로 직접 호출하는 도구.
//
// 왜 필요한가: anim_dump는 합성 프레임을 저장하기 전에 alpha가 0인 픽셀의 RGB를 0으로
// 지운다(libwebp 1.6.0 examples/anim_util.c CleanupTransparentPixels). 서버는
// WebPAnimDecoder 출력을 그대로 보내므로(ADR-0004 RGBA8), 기대값도 가공 없는 출력에서
// 만들어야 한다. 정지 이미지는 dwebp -pam 결과와 이 도구의 결과가 같은지 교차 확인한다.
//
// 사용법 (결과 JSON 한 줄을 stdout에 쓴다. 실패하면 "ok":false 와 종료 코드 1):
//   webp_probe features FILE            WebPGetFeatures
//   webp_probe still FILE OUT.rgba      WebPDecode(MODE_RGBA) -> 원시 RGBA (stride = w*4)
//   webp_probe anim FILE OUT.rgba|-     WebPAnimDecoder(MODE_RGBA) -> 프레임 RGBA를 이어 붙여 저장
//   webp_probe demux FILE [PREFIX]      WebPDemux -> 캔버스/프레임/청크 정보,
//                                       PREFIX가 있으면 ICCP/EXIF/XMP 페이로드를
//                                       PREFIX.<ICCP|EXIF|XMP>.<n> 파일로 저장
//
// 이 파일은 테스트 데이터 생성용이다. 서버 구현과 무관하다(ADR-0006).

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "webp/decode.h"
#include "webp/demux.h"

static const char* StatusName(VP8StatusCode status) {
  switch (status) {
    case VP8_STATUS_OK: return "VP8_STATUS_OK";
    case VP8_STATUS_OUT_OF_MEMORY: return "VP8_STATUS_OUT_OF_MEMORY";
    case VP8_STATUS_INVALID_PARAM: return "VP8_STATUS_INVALID_PARAM";
    case VP8_STATUS_BITSTREAM_ERROR: return "VP8_STATUS_BITSTREAM_ERROR";
    case VP8_STATUS_UNSUPPORTED_FEATURE: return "VP8_STATUS_UNSUPPORTED_FEATURE";
    case VP8_STATUS_SUSPENDED: return "VP8_STATUS_SUSPENDED";
    case VP8_STATUS_USER_ABORT: return "VP8_STATUS_USER_ABORT";
    case VP8_STATUS_NOT_ENOUGH_DATA: return "VP8_STATUS_NOT_ENOUGH_DATA";
  }
  return "VP8_STATUS_UNKNOWN";
}

static const char* DemuxStateName(WebPDemuxState state) {
  switch (state) {
    case WEBP_DEMUX_PARSE_ERROR: return "WEBP_DEMUX_PARSE_ERROR";
    case WEBP_DEMUX_PARSING_HEADER: return "WEBP_DEMUX_PARSING_HEADER";
    case WEBP_DEMUX_PARSED_HEADER: return "WEBP_DEMUX_PARSED_HEADER";
    case WEBP_DEMUX_DONE: return "WEBP_DEMUX_DONE";
  }
  return "WEBP_DEMUX_UNKNOWN";
}

// 파일 전체를 메모리로 읽는다. 샘플은 작으므로 스트리밍하지 않는다.
static int ReadWholeFile(const char* path, uint8_t** data, size_t* size) {
  FILE* f = fopen(path, "rb");
  long len;
  if (f == NULL) return 0;
  if (fseek(f, 0, SEEK_END) != 0 || (len = ftell(f)) < 0 || fseek(f, 0, SEEK_SET) != 0) {
    fclose(f);
    return 0;
  }
  // 0바이트 파일도 다루기 위해 최소 1바이트를 할당한다.
  *data = (uint8_t*)malloc((size_t)len + 1);
  if (*data == NULL) {
    fclose(f);
    return 0;
  }
  if (len > 0 && fread(*data, 1, (size_t)len, f) != (size_t)len) {
    free(*data);
    fclose(f);
    return 0;
  }
  fclose(f);
  *size = (size_t)len;
  return 1;
}

static int CmdFeatures(const uint8_t* data, size_t size) {
  WebPBitstreamFeatures features;
  const VP8StatusCode status = WebPGetFeatures(data, size, &features);
  if (status != VP8_STATUS_OK) {
    printf("{\"ok\":false,\"status\":\"%s\"}\n", StatusName(status));
    return 1;
  }
  // format: 0 = 정의 안 됨/혼합(애니메이션), 1 = lossy, 2 = lossless (decode.h WebPBitstreamFeatures)
  printf("{\"ok\":true,\"width\":%d,\"height\":%d,\"has_alpha\":%s,\"has_animation\":%s,"
         "\"format\":%d}\n",
         features.width, features.height, features.has_alpha ? "true" : "false",
         features.has_animation ? "true" : "false", features.format);
  return 0;
}

// 서버의 정지 이미지 경로와 같은 호출: 기본 옵션(fancy upsampling 켬) + MODE_RGBA.
static int CmdStill(const uint8_t* data, size_t size, const char* out_path) {
  WebPDecoderConfig config;
  VP8StatusCode status;
  FILE* out;
  int y, ok = 1;
  if (!WebPInitDecoderConfig(&config)) {
    printf("{\"ok\":false,\"status\":\"WebPInitDecoderConfig failed\"}\n");
    return 1;
  }
  config.output.colorspace = MODE_RGBA;
  status = WebPDecode(data, size, &config);
  if (status != VP8_STATUS_OK) {
    printf("{\"ok\":false,\"status\":\"%s\"}\n", StatusName(status));
    return 1;
  }
  out = fopen(out_path, "wb");
  if (out == NULL) {
    WebPFreeDecBuffer(&config.output);
    printf("{\"ok\":false,\"status\":\"cannot open output\"}\n");
    return 1;
  }
  // stride가 w*4보다 클 수 있으므로 행 단위로 패딩 없이 쓴다(ADR-0004).
  for (y = 0; y < config.output.height && ok; ++y) {
    const uint8_t* row = config.output.u.RGBA.rgba + (size_t)y * (size_t)config.output.u.RGBA.stride;
    const size_t row_bytes = (size_t)config.output.width * 4;
    ok = (fwrite(row, 1, row_bytes, out) == row_bytes);
  }
  ok = (fclose(out) == 0) && ok;
  printf("{\"ok\":%s,\"width\":%d,\"height\":%d}\n", ok ? "true" : "false",
         config.output.width, config.output.height);
  WebPFreeDecBuffer(&config.output);
  return ok ? 0 : 1;
}

static void PrintBgcolor(uint32_t bgcolor) {
  // WebPAnimInfo.bgcolor는 ANIM 청크의 [B,G,R,A] 바이트를 little-endian uint32로 읽은 값이다
  // (RFC 9649 §2.7.1.1). 사람이 읽기 쉽게 채널별로 내보낸다.
  printf("\"bgcolor\":{\"b\":%u,\"g\":%u,\"r\":%u,\"a\":%u}", bgcolor & 0xffu,
         (bgcolor >> 8) & 0xffu, (bgcolor >> 16) & 0xffu, (bgcolor >> 24) & 0xffu);
}

// 서버의 애니메이션 경로와 같은 호출: WebPAnimDecoder, MODE_RGBA, 스레드 없음.
static int CmdAnim(const uint8_t* data, size_t size, const char* out_path) {
  WebPData webp_data;
  WebPAnimDecoderOptions options;
  WebPAnimDecoder* dec;
  WebPAnimInfo info;
  FILE* out = NULL;
  int* timestamps;
  uint32_t n = 0, i;
  int ok = 1;
  const char* error = NULL;

  webp_data.bytes = data;
  webp_data.size = size;
  if (!WebPAnimDecoderOptionsInit(&options)) {
    printf("{\"ok\":false,\"stage\":\"options\"}\n");
    return 1;
  }
  options.color_mode = MODE_RGBA;
  options.use_threads = 0;
  dec = WebPAnimDecoderNew(&webp_data, &options);
  if (dec == NULL) {
    printf("{\"ok\":false,\"stage\":\"WebPAnimDecoderNew\"}\n");
    return 1;
  }
  if (!WebPAnimDecoderGetInfo(dec, &info)) {
    WebPAnimDecoderDelete(dec);
    printf("{\"ok\":false,\"stage\":\"WebPAnimDecoderGetInfo\"}\n");
    return 1;
  }
  timestamps = (int*)calloc(info.frame_count ? info.frame_count : 1, sizeof(*timestamps));
  if (timestamps == NULL) {
    WebPAnimDecoderDelete(dec);
    printf("{\"ok\":false,\"stage\":\"calloc\"}\n");
    return 1;
  }
  if (strcmp(out_path, "-") != 0) {
    out = fopen(out_path, "wb");
    if (out == NULL) {
      free(timestamps);
      WebPAnimDecoderDelete(dec);
      printf("{\"ok\":false,\"stage\":\"open output\"}\n");
      return 1;
    }
  }
  // 프레임을 순서대로 합성한다. 출력 버퍼는 캔버스 전체(stride = canvas_width*4)다.
  while (ok && WebPAnimDecoderHasMoreFrames(dec)) {
    uint8_t* rgba;
    int timestamp;
    if (!WebPAnimDecoderGetNext(dec, &rgba, &timestamp)) {
      ok = 0;
      error = "WebPAnimDecoderGetNext";
      break;
    }
    if (n < info.frame_count) timestamps[n] = timestamp;
    ++n;
    if (out != NULL) {
      const size_t frame_bytes = (size_t)info.canvas_width * info.canvas_height * 4;
      if (fwrite(rgba, 1, frame_bytes, out) != frame_bytes) {
        ok = 0;
        error = "write";
      }
    }
  }
  if (out != NULL && fclose(out) != 0) {
    ok = 0;
    error = "close";
  }
  printf("{\"ok\":%s,", ok ? "true" : "false");
  if (error != NULL) printf("\"stage\":\"%s\",", error);
  printf("\"canvas_width\":%u,\"canvas_height\":%u,\"loop_count\":%u,\"frame_count\":%u,",
         info.canvas_width, info.canvas_height, info.loop_count, info.frame_count);
  PrintBgcolor(info.bgcolor);
  printf(",\"decoded_frames\":%u,\"timestamps\":[", n);
  for (i = 0; i < n && i < info.frame_count; ++i) printf(i ? ",%d" : "%d", timestamps[i]);
  printf("]}\n");
  free(timestamps);
  WebPAnimDecoderDelete(dec);
  return ok ? 0 : 1;
}

// 청크 페이로드를 PREFIX.<이름>.<n> 파일로 저장한다. n은 1부터 센다.
static int DumpChunks(const WebPDemuxer* demux, const char* fourcc, const char* name,
                      const char* prefix, int* count) {
  WebPChunkIterator iter;
  int ok = 1;
  *count = 0;
  if (WebPDemuxGetChunk(demux, fourcc, 1, &iter)) {
    *count = iter.num_chunks;
    do {
      if (prefix != NULL) {
        char path[4096];
        FILE* f;
        snprintf(path, sizeof(path), "%s.%s.%d", prefix, name, iter.chunk_num);
        f = fopen(path, "wb");
        if (f == NULL) {
          ok = 0;
        } else {
          if (iter.chunk.size > 0 &&
              fwrite(iter.chunk.bytes, 1, iter.chunk.size, f) != iter.chunk.size) {
            ok = 0;
          }
          if (fclose(f) != 0) ok = 0;
        }
      }
    } while (WebPDemuxNextChunk(&iter));
    WebPDemuxReleaseChunkIterator(&iter);
  }
  return ok;
}

// 서버가 디코딩 전에 상한을 검사할 때 쓰는 정보(grpc-api.md §3): 캔버스, 프레임 수, 청크.
static int CmdDemux(const uint8_t* data, size_t size, const char* prefix) {
  WebPData webp_data;
  WebPDemuxState state;
  WebPDemuxer* demux;
  WebPIterator iter;
  uint32_t frame_count, flags;
  int icc = 0, exif = 0, xmp = 0, ok = 1;

  webp_data.bytes = data;
  webp_data.size = size;
  // WebPAnimDecoderNew와 같은 WebPDemux()(완전한 파일만 받음)로 판정한다.
  // 실패하면 사유를 알기 위해 WebPDemuxPartial로 파싱 상태만 다시 얻는다.
  demux = WebPDemux(&webp_data);
  if (demux == NULL) {
    WebPDemuxer* partial = WebPDemuxPartial(&webp_data, &state);
    printf("{\"ok\":false,\"state\":\"%s\"}\n",
           partial == NULL ? "WEBP_DEMUX_PARSE_ERROR" : DemuxStateName(state));
    WebPDemuxDelete(partial);
    return 1;
  }
  flags = WebPDemuxGetI(demux, WEBP_FF_FORMAT_FLAGS);
  frame_count = WebPDemuxGetI(demux, WEBP_FF_FRAME_COUNT);
  ok = DumpChunks(demux, "ICCP", "ICCP", prefix, &icc) && ok;
  ok = DumpChunks(demux, "EXIF", "EXIF", prefix, &exif) && ok;
  ok = DumpChunks(demux, "XMP ", "XMP", prefix, &xmp) && ok;
  printf("{\"ok\":%s,\"canvas_width\":%u,\"canvas_height\":%u,\"flags\":%u,"
         "\"frame_count\":%u,\"loop_count\":%u,",
         ok ? "true" : "false", WebPDemuxGetI(demux, WEBP_FF_CANVAS_WIDTH),
         WebPDemuxGetI(demux, WEBP_FF_CANVAS_HEIGHT), flags, frame_count,
         WebPDemuxGetI(demux, WEBP_FF_LOOP_COUNT));
  PrintBgcolor(WebPDemuxGetI(demux, WEBP_FF_BACKGROUND_COLOR));
  printf(",\"chunks\":{\"ICCP\":%d,\"EXIF\":%d,\"XMP\":%d},\"frames\":[", icc, exif, xmp);
  if (WebPDemuxGetFrame(demux, 1, &iter)) {
    int first = 1;
    do {
      printf("%s{\"x_offset\":%d,\"y_offset\":%d,\"width\":%d,\"height\":%d,\"duration\":%d,"
             "\"dispose\":\"%s\",\"blend\":\"%s\",\"has_alpha\":%s,\"complete\":%s}",
             first ? "" : ",", iter.x_offset, iter.y_offset, iter.width, iter.height,
             iter.duration, iter.dispose_method == WEBP_MUX_DISPOSE_BACKGROUND ? "background" : "none",
             iter.blend_method == WEBP_MUX_BLEND ? "blend" : "no_blend",
             iter.has_alpha ? "true" : "false", iter.complete ? "true" : "false");
      first = 0;
    } while (WebPDemuxNextFrame(&iter));
    WebPDemuxReleaseIterator(&iter);
  }
  printf("]}\n");
  WebPDemuxDelete(demux);
  return ok ? 0 : 1;
}

int main(int argc, char* argv[]) {
  uint8_t* data = NULL;
  size_t size = 0;
  int rc;
  if (argc < 3) {
    fprintf(stderr, "usage: webp_probe features|still|anim|demux FILE [OUT]\n");
    return 2;
  }
  if (!ReadWholeFile(argv[2], &data, &size)) {
    fprintf(stderr, "cannot read %s\n", argv[2]);
    return 2;
  }
  if (!strcmp(argv[1], "features")) {
    rc = CmdFeatures(data, size);
  } else if (!strcmp(argv[1], "still") && argc == 4) {
    rc = CmdStill(data, size, argv[3]);
  } else if (!strcmp(argv[1], "anim") && argc == 4) {
    rc = CmdAnim(data, size, argv[3]);
  } else if (!strcmp(argv[1], "demux") && (argc == 3 || argc == 4)) {
    rc = CmdDemux(data, size, argc == 4 ? argv[3] : NULL);
  } else {
    fprintf(stderr, "unknown command or wrong argument count\n");
    rc = 2;
  }
  free(data);
  return rc;
}
