// 버릴 코드(스파이크). ADR-0002 결정 후 삭제한다. 결과는 docs/research/0003에 기록.
//
// 외부 Go 바인딩 없이 cgo로 libwebp(소스에서 정적 빌드, 버전 고정)를 직접 호출해 다음만 확인한다.
//  1. WebPGetFeatures로 크기를 먼저 읽고 상한을 검사한 뒤 디코딩한다(decompression bomb 방어 흉내).
//  2. 정지 이미지 디코딩 결과(크기, 알파 여부, 첫 픽셀 RGBA).
//  3. WebPMux로 ICCP/EXIF/XMP 청크를 읽을 수 있는지.
//  4. WebPAnimDecoder로 애니메이션 프레임 수와 합성된 프레임을 얻을 수 있는지.
//  5. cgo 포인터 규칙: Go 메모리를 가리키는 WebPData(Go 구조체)를 넘기면 런타임 검사에 걸리는지.
//
// 오류 처리와 안전한 래퍼 설계는 하지 않는다.
package main

/*
#cgo LDFLAGS: -lwebpmux -lwebpdemux -lwebp -lsharpyuv -lm -lpthread
#include <stdlib.h>
#include <webp/decode.h>
#include <webp/encode.h>
#include <webp/demux.h>
#include <webp/mux.h>
*/
import "C"

import (
	"fmt"
	"os"
	"runtime"
	"unsafe"
)

// 스파이크용 임의 상한(픽셀 수). 실제 값은 설계 단계에서 정한다.
const maxPixels = 100_000_000

func ver(v C.int) string {
	return fmt.Sprintf("%d.%d.%d", (v>>16)&0xff, (v>>8)&0xff, v&0xff)
}

func features(b []byte) (C.WebPBitstreamFeatures, error) {
	var f C.WebPBitstreamFeatures
	// &b[0]: 포인터를 담지 않은 Go 메모리라서 호출 동안 직접 넘겨도 된다.
	st := C.WebPGetFeatures((*C.uint8_t)(unsafe.Pointer(&b[0])), C.size_t(len(b)), &f)
	if st != C.VP8_STATUS_OK {
		return f, fmt.Errorf("WebPGetFeatures failed: %d", st)
	}
	return f, nil
}

func guard(w, h C.int) error {
	px := uint64(w) * uint64(h)
	if px > maxPixels {
		return fmt.Errorf("too large: %dx%d = %d px > %d", w, h, px, maxPixels)
	}
	return nil
}

func decodeStill(b []byte) error {
	f, err := features(b)
	if err != nil {
		return err
	}
	fmt.Printf("features: width=%d height=%d has_alpha=%d has_animation=%d format=%d\n",
		f.width, f.height, f.has_alpha, f.has_animation, f.format)
	if err := guard(f.width, f.height); err != nil {
		return err
	}
	fmt.Printf("guard: ok (<= %d px)\n", maxPixels)

	var w, h C.int
	p := C.WebPDecodeRGBA((*C.uint8_t)(unsafe.Pointer(&b[0])), C.size_t(len(b)), &w, &h)
	if p == nil {
		return fmt.Errorf("WebPDecodeRGBA failed")
	}
	defer C.WebPFree(unsafe.Pointer(p))
	n := int(w) * int(h) * 4
	// C 메모리를 복사 없이 Go 슬라이스로 본다. WebPFree 이후에는 쓰면 안 된다.
	px := unsafe.Slice((*byte)(unsafe.Pointer(p)), n)
	fmt.Printf("decoded: %dx%d first_pixel_rgba=(%d, %d, %d, %d) bytes=%d\n",
		w, h, px[0], px[1], px[2], px[3], n)
	return nil
}

// cData는 입력을 C 힙으로 복사한다. 디코더/먹서가 호출 이후에도 포인터를 보관하므로
// Go 메모리를 그대로 넘기면 cgo 규칙 위반이다(또는 runtime.Pinner로 고정해야 한다).
func cData(b []byte) (C.WebPData, func()) {
	p := C.CBytes(b)
	var d C.WebPData
	d.bytes = (*C.uint8_t)(p)
	d.size = C.size_t(len(b))
	return d, func() { C.free(p) }
}

func readChunks(b []byte) error {
	in, free := cData(b)
	defer free()
	mux := C.WebPMuxCreate(&in, 1) // static inline 함수지만 cgo가 래퍼를 만들어 호출된다.
	if mux == nil {
		return fmt.Errorf("WebPMuxCreate failed")
	}
	defer C.WebPMuxDelete(mux)
	for _, cc := range []string{"ICCP", "EXIF", "XMP "} {
		fourcc := C.CString(cc)
		var chunk C.WebPData
		e := C.WebPMuxGetChunk(mux, fourcc, &chunk)
		C.free(unsafe.Pointer(fourcc))
		if e == C.WEBP_MUX_OK {
			fmt.Printf("mux chunk %q: %d bytes\n", cc, chunk.size)
		} else {
			fmt.Printf("mux chunk %q: error %d\n", cc, e)
		}
	}
	return nil
}

func decodeAnim(b []byte) error {
	f, err := features(b)
	if err != nil {
		return err
	}
	fmt.Printf("features: width=%d height=%d has_alpha=%d has_animation=%d\n",
		f.width, f.height, f.has_alpha, f.has_animation)
	if err := guard(f.width, f.height); err != nil {
		return err
	}
	in, free := cData(b)
	defer free()

	var opts C.WebPAnimDecoderOptions
	if C.WebPAnimDecoderOptionsInit(&opts) == 0 {
		return fmt.Errorf("WebPAnimDecoderOptionsInit failed")
	}
	opts.color_mode = C.MODE_RGBA
	opts.use_threads = 0
	dec := C.WebPAnimDecoderNew(&in, &opts)
	if dec == nil {
		return fmt.Errorf("WebPAnimDecoderNew failed")
	}
	defer C.WebPAnimDecoderDelete(dec)
	var info C.WebPAnimInfo
	C.WebPAnimDecoderGetInfo(dec, &info)
	fmt.Printf("anim: canvas=%dx%d frame_count=%d loop_count=%d bgcolor=0x%08X\n",
		info.canvas_width, info.canvas_height, info.frame_count, info.loop_count, uint32(info.bgcolor))
	n := 0
	for C.WebPAnimDecoderHasMoreFrames(dec) != 0 {
		var buf *C.uint8_t
		var ts C.int
		if C.WebPAnimDecoderGetNext(dec, &buf, &ts) == 0 {
			return fmt.Errorf("WebPAnimDecoderGetNext failed")
		}
		px := unsafe.Slice((*byte)(unsafe.Pointer(buf)), 4)
		n++
		fmt.Printf("  frame %d: timestamp_ms=%d first_pixel_rgba=(%d, %d, %d, %d)\n",
			n, ts, px[0], px[1], px[2], px[3])
	}
	fmt.Printf("anim: decoded_frames=%d\n", n)
	return nil
}

// cgoRuleDemo는 Go 메모리를 가리키는 포인터를 담은 Go 구조체를 C에 넘길 때의 동작을 보여 준다.
func cgoRuleDemo(b []byte) {
	fmt.Println("== cgo pointer rule demo")
	func() {
		defer func() {
			if r := recover(); r != nil {
				fmt.Printf("unpinned: panic: %v\n", r)
			}
		}()
		var d C.WebPData
		d.bytes = (*C.uint8_t)(unsafe.Pointer(&b[0])) // Go 포인터를 Go 구조체 안에 넣음
		d.size = C.size_t(len(b))
		mux := C.WebPMuxCreate(&d, 1)
		if mux != nil {
			C.WebPMuxDelete(mux)
		}
		fmt.Println("unpinned: no panic")
	}()
	var pin runtime.Pinner
	pin.Pin(&b[0])
	var d C.WebPData
	d.bytes = (*C.uint8_t)(unsafe.Pointer(&b[0]))
	d.size = C.size_t(len(b))
	mux := C.WebPMuxCreate(&d, 1) // copy_data=1 이라 호출 후 Go 메모리를 보관하지 않는다.
	pin.Unpin()
	if mux != nil {
		C.WebPMuxDelete(mux)
		fmt.Println("pinned (runtime.Pinner): ok")
	}
}

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintf(os.Stderr, "usage: %s <still.webp> [anim.webp]\n", os.Args[0])
		os.Exit(2)
	}
	fmt.Printf("libwebp linked: decoder=%s encoder=%s demux=%s mux=%s\n",
		ver(C.WebPGetDecoderVersion()), ver(C.WebPGetEncoderVersion()),
		ver(C.WebPGetDemuxVersion()), ver(C.WebPGetMuxVersion()))
	fmt.Printf("go: %s GOMAXPROCS=%d\n", runtime.Version(), runtime.GOMAXPROCS(0))
	run := func() error {
		fmt.Printf("== still: %s\n", os.Args[1])
		still, err := os.ReadFile(os.Args[1])
		if err != nil {
			return err
		}
		if err := decodeStill(still); err != nil {
			return err
		}
		if err := readChunks(still); err != nil {
			return err
		}
		if len(os.Args) > 2 {
			fmt.Printf("== anim: %s\n", os.Args[2])
			anim, err := os.ReadFile(os.Args[2])
			if err != nil {
				return err
			}
			if err := decodeAnim(anim); err != nil {
				return err
			}
		}
		cgoRuleDemo(still)
		return nil
	}
	if err := run(); err != nil {
		fmt.Fprintf(os.Stderr, "error: %v\n", err)
		os.Exit(1)
	}
}
