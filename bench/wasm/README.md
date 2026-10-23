# bench/wasm — libwebp Wasm + Chicory 비교 구현

**벤치마크 전용이다. 제품 코드가 아니다.** libwebp v1.6.0을 WebAssembly로 빌드하고 순수 Java Wasm 런타임 [Chicory](https://github.com/dylibso/chicory)로 JVM 안에서 실행한다. 사이드카(gRPC 서버)와 경쟁하는 대안([ADR-0001](../../docs/adr/0001-isolate-libwebp-in-grpc-sidecar.md) 선택지 5)을 Phase 4에서 재기 위한 것이다. 측정 방법은 [ADR-0015](../../docs/adr/0015-benchmark-method.md)를 따른다: **판정용 수치는 Chicory 빌드타임 컴파일러 모드, 인터프리터는 참고값**.

모든 사실은 2026-10-23에 이 저장소의 개발 호스트에서 확인했다. 확인하지 못한 것은 "미확인"으로 적었다.

## 구성

| 경로 | 내용 |
|---|---|
| `Dockerfile` | wasi-sdk로 libwebp 1.6.0 디코더 + `shim.c`를 reactor 모듈 `libwebp.wasm`으로 빌드한다 |
| `shim.c` | JVM에서 부르는 최소 ABI(아래 표) |
| `../src/main/java/net/rivergod/webp/imageio/bench/wasm/WasmWebPDecoder.java` | Chicory로 모듈을 띄워 `info(byte[])` / `decodeRgba(byte[])` / `decode(byte[])`를 제공한다 |
| `../src/main/java/net/rivergod/webp/imageio/bench/wasm/WasmVerifyMain.java` | 동작·정확성·대략 시간을 확인하는 명령줄 도구(JMH 아님) |
| `build/` | 빌드 산출물(`libwebp.wasm`). 커밋하지 않는다(`.gitignore`) |

Java 소스는 Gradle `bench` 모듈(`bench/src/main/java`)에 있다. Wasm 빌드와 Chicory 코드 생성은 `bench/build.gradle.kts`의 `buildWasm`(docker), `chicoryCompile` 태스크가 한다(아래 "Gradle 연결").

## Wasm 빌드

저장소 루트에서:

```sh
docker build --target artifact --output type=local,dest=bench/wasm/build bench/wasm
# → bench/wasm/build/libwebp.wasm
```

고정한 입력:

| 항목 | 값 |
|---|---|
| 기준 이미지 | `debian:trixie-20261020-slim@sha256:62bd423aeee912639ca8ee681c2b762cb3e25137e0a92b20be455898a9f034ad` (index digest) |
| wasi-sdk | `wasi-sdk-34` (34.0, 2026-08-25 릴리스, 이 날짜 기준 최신 정식판). `VERSION`: LLVM 23.1.0, wasi-libc `2e6fb9d8ee0c` |
| wasi-sdk x86_64 tarball sha256 | `b761e3a0721dbae9c09a0059e5fdb2bf917d1b4a8a7b430fb3b5aafb0984b2c4` (GitHub 릴리스 자산 digest와 내려받은 파일 모두 확인) |
| wasi-sdk arm64 tarball sha256 | `f7e243dff54d60bcc576e94d6166b69f410f2500ae4a9ceef34315be10e77971` (GitHub 자산 digest. arm64로 빌드해 보지 않음 — 미확인) |
| libwebp | 1.6.0 공식 tarball, sha256 `e4ab7009bf0629fd11982d4c2aa83964cf244cffba7347ecd39019a9e38c4564` |
| apt 패키지 | `ca-certificates curl cmake make` — 버전 고정 안 함. 기준 이미지 digest로만 고정 |

산출물 (같은 호스트에서 `--no-cache` 빌드 두 번, 같은 해시):

| 항목 | 값 |
|---|---|
| `libwebp.wasm` 크기 | **148,779 바이트** |
| sha256 | `ed9d02b61066dd26ceccb08f1cd7e6bc0d1f7ad718c2126e7120ae4e3a44dd06` |
| import | **0개** (WASI 포함 호스트 함수 없음 → 모듈에 호스트 권한이 전혀 없다) |
| export | `memory`, `_initialize`, `wp_alloc`, `wp_free`, `wp_decoder_version`, `wp_get_info`, `wp_decode_rgba_into` |
| 정의된 함수 | 274개 |
| 선형 메모리 | 초기 17 페이지(1.06 MiB), 최대 16384 페이지(1 GiB) |
| target_features | `bulk-memory bulk-memory-opt call-indirect-overlong extended-const multivalue mutable-globals nontrapping-fptoint reference-types sign-ext` (simd128 없음) |

### ABI (`shim.c`)

포인터는 선형 메모리 안의 i32 오프셋, 크기는 바이트다. 성공 1, 실패 0.

| 함수 | 설명 |
|---|---|
| `wp_alloc(size) → ptr` | `malloc`. 실패(최대 메모리 도달)면 0 |
| `wp_free(ptr)` | `free` |
| `wp_decoder_version() → int` | `WebPGetDecoderVersion()` (1.6.0 → `0x010600`) |
| `wp_get_info(data, size, *w, *h) → int` | `WebPGetInfo`. 헤더만 읽는다 |
| `wp_decode_rgba_into(data, size, out, out_size, stride) → int` | `WebPDecodeRGBAInto`. RGBA8, stride = `w × 4`([ADR-0004](../../docs/adr/0004-pixel-transfer-format.md)) |

호출마다 입력 복사(Java → Wasm 메모리)와 출력 복사(Wasm 메모리 → Java `byte[]`)가 한 번씩 있다. 사이드카의 IPC 복사에 해당하는 비용이고 측정값에 들어간다.

### 빌드 결정

- **SIMD 끔** (`-mno-simd128`, `WEBP_ENABLE_SIMD=OFF`). 근거:
  - Chicory 1.7.5 문서: "SIMD support is available only for Java 21+ and interpreter mode" ([docs/usage/simd.md @1.7.5](https://github.com/dylibso/chicory/blob/1.7.5/docs/docs/usage/simd.md)). 판정용인 컴파일 모드에서 쓸 수 없다. SIMD는 별도 `com.dylibso.chicory:simd` 모듈의 `SimdInterpreterMachine`(인터프리터)으로만 돈다.
  - libwebp 1.6.0에는 Wasm SIMD128 전용 코드가 없다. Wasm용 SIMD 경로는 Emscripten의 SSE2 에뮬레이션 헤더를 전제로 한 `src/dsp/cpu.c`의 `EMSCRIPTEN` 분기뿐이고 wasi-sdk에는 그 헤더가 없다. 그래서 wasi-sdk 빌드에서 `-msimd128`을 켜도 얻는 것은 LLVM 자동 벡터화뿐이다(추정).
  - 따라서 ADR-0015의 "기본(SSE2) / SSE4.1·AVX2 두 빌드" 구분은 이 구현에 해당하지 않는다. Wasm 빌드는 일반 C 경로 하나다. CMake 로그에서 AVX2/SSE41/SSE2/NEON/MSA/MIPS 모두 "Disabling"을 확인했다.
- **스레드 끔** (`WEBP_USE_THREAD=OFF`, 타깃 `wasm32-wasip1`). 단일 스레드 디코딩만 잰다. 병렬 처리는 디코더 객체를 스레드마다 하나씩 만드는 방식이다. (wasi-sdk 34는 `wasm32-wasi`/`wasm32-wasi-threads` 타깃 이름을 없앴다 — 릴리스 노트 PR #637.)
- **reactor 모듈** (`-mexec-model=reactor`): `main` 없이 `_initialize`와 `wp_*`만 내보낸다. Chicory는 `_start`만 자동으로 부르므로 `_initialize`는 Java에서 직접 한 번 부른다.
- **디코더 전용**: CMake 타깃 `webpdecoder`(`libwebpdecoder.a`)만 링크한다. 인코더, demux(애니메이션), mux(메타데이터)는 넣지 않았다.
- `-O3 -DNDEBUG`(CMake Release와 같은 수준), 섀도 스택 1 MiB(`-z stack-size`, wasm-ld 기본 64 KiB보다 여유), `--strip-debug`(name 섹션은 남겨 trap 스택이 읽힌다).
- **최대 선형 메모리 1 GiB**: 아래 "메모리"의 측정값 근거.

## Chicory

버전 **1.7.5** — Maven Central `com.dylibso.chicory:runtime` `maven-metadata.xml`의 `latest`/`release`(lastUpdated 2026-03-24). 라이선스 Apache-2.0.

`WasmWebPDecoder.Mode`:

| 모드 | 내용 | 용도 |
|---|---|---|
| `BUILD_TIME_COMPILED` | 빌드타임 컴파일러가 미리 만든 바이트코드(`LibWebPModule`) | **ADR-0015 판정용** |
| `RUNTIME_COMPILED` | 같은 컴파일러를 실행 시점에 돌림(`MachineFactoryCompiler.compile(module)`, 첫 객체 생성 때 한 번) | 참고값. 빌드타임 경로를 못 쓸 때의 대안 |
| `INTERPRETER` | Chicory 기본 `InterpreterMachine` | ADR-0015 참고값 |

선형 메모리는 세 모드 모두 `ByteArrayMemory`로 만든다(Chicory 기본값은 `ByteBufferMemory`). Chicory 문서가 최신 OpenJDK에서 권장하고([docs/advanced/memory.md @1.7.5](https://github.com/dylibso/chicory/blob/1.7.5/docs/docs/advanced/memory.md)), 이 호스트에서 `ByteBufferMemory`로 바꾸면 1920×1080 lossy 디코딩이 약 90 ms → 150 ms로 느려졌다(아래 같은 방식의 대략 측정).

### 빌드타임 컴파일을 한 방법

Chicory 문서는 Maven 플러그인, Gradle 플러그인(서드파티), CLI를 안내한다([docs/usage/build-time-compiler.md @1.7.5](https://github.com/dylibso/chicory/blob/1.7.5/docs/docs/usage/build-time-compiler.md)). **CLI를 썼다.**

- 이유: Maven 플러그인(`com.dylibso.chicory:chicory-compiler-maven-plugin`)은 Maven이 필요하다. Gradle 공식 플러그인은 없고, 문서가 소개하는 서드파티 플러그인 `at.released.wasm2class.plugin`(Maven Central `at.released.wasm2class:plugin` 최신 0.5.0, 2025-07-15)은 Chicory 1.7.5와의 호환이 미확인이다. CLI(`Cli.java`)와 Maven 플러그인(`ChicoryCompilerGenMojo.java`)은 둘 다 같은 `com.dylibso.chicory.build.time.compiler.Generator`의 `generateResources` → `generateMetaWasm` → `generateSources`를 호출한다(1.7.5 소스 확인). 플러그인만 쓰는 `moduleInterface` 옵션은 CLI에 없지만 이 구현은 쓰지 않는다.
- 아티팩트: `com.dylibso.chicory:build-time-compiler-cli-experimental:1.7.5`. 이름에 `experimental`이 붙어 있다. **uber jar**다(Chicory, ASM, javaparser, picocli를 포함, `Main-Class: com.dylibso.chicory.experimental.compiler.cli.Cli`). 이 jar 하나로 실행된다.
- 명령(검증에 쓴 그대로, 경로만 바꿈):

  ```sh
  java -jar build-time-compiler-cli-experimental-1.7.5.jar \
    --prefix net.rivergod.webp.imageio.bench.wasm.LibWebPModule \
    --source-dir <gen>/src --class-dir <gen>/classes --wasm-dir <gen>/resources \
    --interpreter-fallback FAIL \
    bench/wasm/build/libwebp.wasm
  ```

- 산출물: `<gen>/src/.../LibWebPModule.java`(소스, `CompiledModule` 구현, `load()`/`create(Instance)`), `<gen>/classes/.../LibWebPModuleMachine*.class` 4개(약 316 KiB, class 파일 버전 55 = Java 11), `<gen>/resources/.../LibWebPModule.meta`(함수 본문을 비운 모듈, 약 12 KiB). 실행 시간 약 0.5 초.
- `--interpreter-fallback FAIL`로 성공했다 → 274개 함수 모두 바이트코드로 컴파일됐고 인터프리터로 떨어지는 함수가 없다. 생성된 가장 큰 메서드는 약 6.8 KB로 HotSpot `HugeMethodLimit`(8000 바이트) 아래라 `-XX:-DontCompileHugeMethods`가 필요 없다(`javap`로 확인).
- 생성 클래스는 `com.dylibso.chicory.runtime`과 `wasm` 패키지만 참조한다. `runtime`, `wasm` jar 두 개만 클래스패스에 두고 실행해 동작을 확인했다(컴파일러·ASM은 빌드 때만 필요).

### Gradle 연결

`bench/build.gradle.kts`에 아래 단계를 넣었고 `./gradlew :bench:installBench`로 동작을 확인했다(2026-10-23). 아래 초안과 다른 점: 태스크 등록을 `tasks.register<T>`로 썼고, `buildWasm`(Exec, docker)이 `libwebp.wasm`을 만든다. JMH는 플러그인 없이 어노테이션 프로세서로 붙였으므로 jar 하나(`bench.jar`)에 생성 클래스와 리소스가 함께 들어간다.

#### 처음 정리한 초안 (기록용)

Maven 좌표 (모두 Maven Central, sha1·sha256을 Central의 `.sha1`/`.sha256`과 대조함):

| 용도 | 좌표 | jar sha256 |
|---|---|---|
| 실행(모든 모드) | `com.dylibso.chicory:runtime:1.7.5` | `cdbb2bd4e353eacff78da5dd3454694316eaa6d2950aab3d1517dca41ae4a0b2` |
| 실행(모든 모드) | `com.dylibso.chicory:wasm:1.7.5` | `16558a66bac04f7e00ea06950f52d87ce2cfff535a54bac2115f2c13e0b3050b` |
| 컴파일 + `RUNTIME_COMPILED` 실행 | `com.dylibso.chicory:compiler:1.7.5` | `1a54cc0b3a79aa914f7f2fa5e7b7ed902702f3ce7aa155ec0cfc74b386a97377` |
| ↳ 전이 | `org.ow2.asm:asm:9.9.1` | `6f3828a215c920059a5efa2fb55c233d6c54ec5cadca99ce1b1bdd10077c7ddd` |
| ↳ 전이 | `org.ow2.asm:asm-commons:9.9.1` | `c2319e014ce7199f2b7f7d56d6bb991863168c3f4b6cd6c9f542a4937ef7ef88` |
| ↳ 전이 | `org.ow2.asm:asm-util:9.9.1` | `c5ebbbeaf68126af094b42fa4800f59bc4413abd02d95b9aefad722cd257e207` |
| ↳ 전이 | `org.ow2.asm:asm-tree:9.9.1` | `0f3555096b720b820bbacab0b515589bee0200bee099bda14c561738ae837ba1` |
| ↳ 전이 | `org.ow2.asm:asm-analysis:9.9.1` | `6260bffc8ec008dd1b713702c7994e2c94d188a3da5bef9e87278a16df6a7522` |
| 빌드 도구(코드 생성) | `com.dylibso.chicory:build-time-compiler-cli-experimental:1.7.5` (uber jar, 전이 의존성 불필요) | `344efd632d1f87939baa3fb59b3a4ac34af7382bcf563b913fd98a5962e27db3` |

`WasmWebPDecoder`는 `MachineFactoryCompiler`를 직접 참조하므로 **컴파일 클래스패스에는 `compiler`가 필요하다**. `RUNTIME_COMPILED`를 쓰지 않으면 실행 클래스패스에서는 빼도 된다(지연 초기화 홀더라 그 모드에서만 로드된다 — 확인함).

필요한 단계:

1. `docker build ...`로 `bench/wasm/build/libwebp.wasm`을 만든다(Gradle 밖, 또는 `Exec` 태스크).
2. CLI jar를 별도 configuration으로 받아 `JavaExec`로 위 명령을 실행한다(입력: `libwebp.wasm`, 출력: 생성 디렉터리).
3. 생성 소스 디렉터리를 소스 세트에 추가한다(`LibWebPModule.java`).
4. 생성 클래스 디렉터리를 **컴파일 클래스패스와 출력(jar)** 양쪽에 넣는다. `LibWebPModule.java`가 미리 컴파일된 `LibWebPModuleMachine`을 참조한다(빠뜨리면 `javac`가 `cannot find symbol`로 실패한다 — 겪었음).
5. 리소스로 `net/rivergod/webp/imageio/bench/wasm/LibWebPModule.meta`(빌드타임 모드)와 `net/rivergod/webp/imageio/bench/wasm/libwebp.wasm`(런타임 컴파일·인터프리터 모드)을 넣는다.

Kotlin DSL 초안 — 작성 당시에는 Gradle로 실행해 보지 않았다. 실제 연결은 `bench/build.gradle.kts`를 본다.

```kotlin
val chicoryVersion = "1.7.5"
val chicoryCli by configurations.creating

dependencies {
    chicoryCli("com.dylibso.chicory:build-time-compiler-cli-experimental:$chicoryVersion") { isTransitive = false }
    implementation("com.dylibso.chicory:runtime:$chicoryVersion")
    implementation("com.dylibso.chicory:wasm:$chicoryVersion")
    implementation("com.dylibso.chicory:compiler:$chicoryVersion")
}

val wasmFile = rootProject.layout.projectDirectory.file("bench/wasm/build/libwebp.wasm")
val genDir = layout.buildDirectory.dir("generated/chicory")
val pkgPath = "net/rivergod/webp/imageio/bench/wasm"

val chicoryCompile by tasks.registering(JavaExec::class) {
    classpath = chicoryCli
    mainClass.set("com.dylibso.chicory.experimental.compiler.cli.Cli")
    inputs.file(wasmFile)
    outputs.dir(genDir)
    args(
        "--prefix", "net.rivergod.webp.imageio.bench.wasm.LibWebPModule",
        "--source-dir", genDir.get().dir("src").asFile.path,
        "--class-dir", genDir.get().dir("classes").asFile.path,
        "--wasm-dir", genDir.get().dir("resources").asFile.path,
        "--interpreter-fallback", "FAIL",
        wasmFile.asFile.path,
    )
}

val wasmResource by tasks.registering(Copy::class) {
    from(wasmFile)
    into(layout.buildDirectory.dir("generated/wasm-resource/$pkgPath"))
}

val genClasses = files(genDir.map { it.dir("classes") }).builtBy(chicoryCompile)
dependencies { implementation(genClasses) }   // 컴파일 + 실행 클래스패스

sourceSets.main {
    java.srcDir(genDir.map { it.dir("src") })
    resources.srcDir(genDir.map { it.dir("resources") })
    resources.srcDir(layout.buildDirectory.dir("generated/wasm-resource"))
}
tasks.compileJava { dependsOn(chicoryCompile) }
tasks.processResources { dependsOn(chicoryCompile, wasmResource) }
tasks.jar { from(genDir.map { it.dir("classes") }) }   // jmh 플러그인을 쓰면 jmhJar에도 포함되는지 확인
```

## 검증 (Gradle 없이)

Gradle 모듈로 옮긴 뒤에는 `./gradlew :bench:installBench` 후 `java -cp "bench/build/install/bench/lib/*" net.rivergod.webp.imageio.bench.wasm.WasmVerifyMain ...`로 같은 도구를 실행한다. 아래는 옮기기 전에 한 기록이다.

환경: Intel Core i9-14900(P코어 CPU 0–15, E코어 16–31), 메모리 61 GB, Linux 7.0.0-38-generic, JDK Temurin 25.0.4.1. `javac --release 17 -Xlint:all -Werror`로 경고 없이 컴파일된다.

절차: Central에서 jar를 빈 디렉터리에 받아 해시 대조 → CLI로 코드 생성 → `javac` → `WasmVerifyMain` 실행.

```sh
CP=classes:resources:gen/classes:runtime-1.7.5.jar:wasm-1.7.5.jar   # BUILD_TIME_COMPILED
java -cp "$CP" net.rivergod.webp.imageio.bench.wasm.WasmVerifyMain BUILD_TIME_COMPILED 100 100 \
  photo_1920x1080_q75.webp=photo_1920x1080_q75.pam ...
```

입력(스크래치에서 생성, 커밋하지 않음): `spikes/testdata/still.webp`(4×3 lossless + 알파, VP8X·ICC·EXIF·XMP), 그리고 호스트 사진 `/usr/share/backgrounds/mendhak-Bluebells_Suspended_In_Time.jpg`(3840×2160)를 호스트 `cwebp` 1.5.0으로 크기 조정해 만든 파일: lossy `-q 75`, lossless `-lossless`, lossy + 알파(대각선 그라디언트 알파를 덧입힌 PAM을 `-q 75`, ALPH + VP8). 기준 출력은 호스트 `dwebp` 1.5.0 `-pam`(헤더를 떼고 RGBA 바이트만 비교).

### 정확성

| 입력 | 크기 | 결과 (BUILD_TIME_COMPILED) |
|---|---|---|
| still.webp (lossless + 알파) | 4×3 | 정확 일치 |
| photo lossless | 1920×1080 / 4000×3000 / 8192×8192 | 모두 정확 일치 |
| photo lossy q75 | 1920×1080 / 4000×3000 / 8192×8192 | 모두 정확 일치 |
| lossy + 알파 | 1920×1080 / 8192×8192 | 모두 정확 일치 |

`RUNTIME_COMPILED`(still, 1920×1080 3종)와 `INTERPRETER`(still, 1920×1080 3종)도 모두 정확 일치했다.

lossy도 1.5.0 `dwebp`와 바이트 단위로 같았다. 비교 대상은 libwebp 1.5.0의 x86 SIMD 경로이고 이쪽은 1.6.0의 일반 C 경로다. VP8 복원 자체는 스펙(RFC 6386)상 비트 단위로 정해져 있지만, YUV→RGB 변환과 업샘플링은 libwebp 구현 몫이라 버전·SIMD 경로가 다르면 달라질 수 있다. **이 입력들에서는 같았다는 것만 확인했고, 일반적으로 보장되는지는 미확인이다.** 그래서 벤치/테스트에서 lossy 비교는 [testing.md](../../docs/conventions/testing.md) 규칙대로 PSNR 임계값으로 한다. 같은 1.6.0 네이티브 `dwebp`와의 비교는 하지 않았다(미확인).

오류 입력(`decodeRgba`, 모두 같은 객체로 순서대로 실행한 뒤 정상 이미지가 다시 디코딩됨을 확인):

| 입력 | 결과 |
|---|---|
| `bomb_16384.webp` (VP8L 헤더만 16384²) | `info` = 16384×16384, `decodeRgba` → `IllegalArgumentException: too many pixels`(출력 할당 전 거부) |
| `anim.webp` | `info` = 캔버스 4×3, `decodeRgba` → `IllegalArgumentException`(libwebp `WebPDecode`는 애니메이션을 디코딩하지 않는다) |
| 1920×1080 lossy/lossless를 절반으로 자른 파일 | `info`는 성공, `decodeRgba` → `IllegalArgumentException` |
| 0x5A 1000바이트 | `info`, `decodeRgba` 모두 `IllegalArgumentException` |

### 대략적인 시간 (참고용)

JMH가 아니다. 단일 스레드, 같은 객체로 워밍업 후 반복, `taskset -c 2,4,6,8`(서로 다른 P코어 4개)로 고정. `dwebp`는 `taskset -c 2`, `-v`의 "Time to decode picture" 15회 중 최솟값(파일 I/O 제외, ms 해상도). Chicory 시간에는 입력·출력 복사가 들어간다.

| 1920×1080 | BUILD_TIME_COMPILED (워밍업 100, n=100) 평균 / 최소 | RUNTIME_COMPILED 평균 / 최소 | INTERPRETER (워밍업 3, n=5) 평균 / 최소 | dwebp 1.5.0 기본(SIMD) | dwebp 1.5.0 `-noasm` |
|---|---|---|---|---|---|
| lossy q75 (517 KB) | 100.8 / 89.5 ms | 103.3 / 92.4 ms | 6371 / 5903 ms | 25 ms | 41 ms |
| lossless (3.5 MB) | 93.3 / 87.6 ms | 94.9 / 90.8 ms | 10508 / 10044 ms | 34 ms | 40 ms |
| lossy + 알파 (506 KB) | 109.3 / 103.3 ms | 107.4 / 100.8 ms | 6894 / 6783 ms | 27 ms | 42 ms |

같은 조건으로 다시 돌린 실행 간 차이는 몇 % 안이었다(빌드타임 lossy 최소 89.5 → 91.5 ms 등).

읽는 법(이 측정 한정, 최솟값 기준): 컴파일 모드는 네이티브 SIMD `dwebp`보다 약 2.6–3.8배, SIMD를 끈 네이티브(`-noasm`)보다 약 2.2–2.5배 느리다. 빌드타임과 런타임 컴파일은 같은 컴파일러라 디코딩 속도가 같고 차이는 인스턴스 생성 시간뿐이다(첫 객체 생성: 빌드타임 약 60–150 ms, 런타임 컴파일 약 280–510 ms, 인터프리터 약 110 ms). 인터프리터는 컴파일 모드보다 약 65–115배 느리다. 평균과 최소의 차이는 GC·JIT 영향으로 보이며(추정) 판정 수치는 ADR-0015의 JMH 설정으로 다시 잰다.

## 메모리

### 한계

- Wasm32 스펙의 선형 메모리 상한은 65536 페이지(4 GiB)지만, **Chicory 런타임 상한은 32767 페이지(2 GiB − 64 KiB)**다. JVM 배열 크기 한도 때문이다(`Memory.RUNTIME_MAX_PAGES = 32767`, 1.7.5 `runtime/.../Memory.java`).
- `ByteArrayMemory`는 64 KiB `byte[]` 페이지를 `memory.grow` 때 지연 할당한다(1.7.5에서 페이지 배열 구조라 grow 때 기존 내용을 복사하지 않는다). **선형 메모리는 JVM 힙 안에 있어 `-Xmx`에 포함된다.** Wasm 메모리는 줄어들지 않으므로 디코더 객체가 살아 있는 동안 지금까지의 최고 수위를 유지한다(해제한 영역은 다음 디코딩에서 재사용된다 — 아홉 파일을 연속 디코딩해도 가장 큰 단일 디코딩 때 크기에서 더 늘지 않았다).
- 모듈 최대 메모리는 링크 때 `--max-memory=1073741824`(16384 페이지, 1 GiB)로 정했다. `Dockerfile`의 `WASM_MAX_MEMORY`로 바꿀 수 있다(64 KiB 배수, Chicory 상한 2 GiB − 64 KiB 이하).
- `WasmWebPDecoder.MAX_PIXELS` = 8192 × 8192 = 서버 기본 상한([ADR-0012](../../docs/adr/0012-server-runtime-config.md)). 넘으면 출력 할당 전에 거부한다.

### 측정한 최고 수위 (디코딩 한 번, 새 JVM, `linearMemoryBytes()`)

| 입력 | 입력 크기 | 선형 메모리 최고 수위 |
|---|---|---|
| 4000×3000 lossy q75 | 1.7 MB | 48 MiB |
| 4000×3000 lossless | 14.1 MB | 107 MiB |
| 8192×8192 lossy q75 | 4.9 MB | 262 MiB |
| 8192×8192 lossy + 알파 | 4.7 MB | 406 MiB |
| 8192×8192 lossless | 49.6 MB | 564 MiB |

대략 lossy ≈ 출력(w·h·4) + 입력, lossless ≈ 출력 × 2(libwebp VP8L의 ARGB 작업 버퍼) + 입력, lossy + 알파 ≈ 출력 + 알파 평면 작업 버퍼(w·h 바이트 × 2 정도) + 입력. 서버 기본 상한 조합(8192², 입력 64 MiB)의 lossless 최악값은 약 580 MiB(추정: 위 측정에 입력 차이를 더함)로 1 GiB 안이다. 1 GiB에서 lossless가 들어가는 최대 크기는 입력 64 MiB 기준 약 1.25억 픽셀(≈ 11000²)이다(추정). 실제로는 `MAX_PIXELS`(8192²)가 먼저 막는다.

`WEBP_MAX_DIMENSION`(16383)² 정지 이미지는 lossless가 2 GiB를 넘어 **Chicory에서는 어떤 설정으로도 디코딩할 수 없다**. lossy는 약 1.0 GiB + 입력이라 모듈 최대를 2 GiB 가까이 올리면 가능할 수 있다(미확인).

### JVM 힙과 실패 방식

- 디코딩 스레드 하나당 힙 ≈ 선형 메모리 최고 수위 + 결과 `byte[]` + 입력 `byte[]`. 8192² lossless는 약 564 + 256 + 47 ≈ 870 MiB다. JMH `-Xmx`는 (스레드 수 × 이 값) + 여유로 잡는다.
- Wasm 최대 메모리에 막히면 `malloc`이 0을 돌려준다 → `IllegalStateException`. libwebp 내부 할당이 막히면 디코딩 함수가 0을 돌려준다 → `IllegalArgumentException`(현재 ABI로는 손상 입력과 구분되지 않는다). 둘 다 상한을 160/200 페이지로 낮춘 인스턴스로 확인했다.
- **JVM 힙이 먼저 모자라면** `memory.grow` 안의 `ByteArrayMemory.growImpl`에서 `OutOfMemoryError`가 난다(`-Xmx300m`으로 8192² lossless를 디코딩해 확인). 이것은 JVM 전체의 오류라 다른 스레드에도 영향을 줄 수 있다. 사이드카는 컨테이너 메모리 제한이 서버 프로세스만 죽이는 것과 대비되는 점이다(ADR-0001의 "메모리 상한 관리" 비교 항목).

## 스레드 안전성과 수명

- `WasmWebPDecoder` 객체 하나 = Chicory `Instance` 하나(선형 메모리, 실행 스택). 스레드 안전하지 않다. **스레드마다 하나씩** 만든다(JMH `Scope.Thread`). Chicory 문서에 `Instance`의 스레드 안전성이 명시돼 있지는 않다(미확인) — 스레드당 하나 원칙은 그 가정 위의 보수적 선택이다.
- 파싱한 `WasmModule`과 런타임 컴파일 결과는 클래스 초기화로 한 번 만들어 객체 간에 공유한다. 빌드타임 생성 코드도 `WasmModule`을 정적 필드로 공유하는 구조다(`LibWebPModule.WasmModuleHolder`). 여러 스레드가 동시에 공유 모듈로 인스턴스를 만드는 경우는 시험하지 않았다(미확인).
- trap(`ChicoryException`)이나 `OutOfMemoryError` 뒤에는 그 객체를 버린다. 원래 예외가 가려지지 않게 그 경우에는 Wasm 쪽 버퍼를 해제하지 않는다.

## 주의와 미확인

- 수치는 한 호스트, 비 JMH 대략 측정이다. 판정은 ADR-0015 JMH 설정으로 다시 잰다.
- 네이티브 비교 기준이 호스트 `dwebp` 1.5.0이다(같은 1.6.0 네이티브 시간은 재지 않음 — 미확인). 서버 쪽 기본(SSE2) 빌드와의 비교는 Phase 4 본 측정에서 한다.
- 이 빌드는 정지 이미지 디코딩만 한다. 인코딩, 애니메이션(`WebPAnimDecoder`), 메타데이터(mux/demux)는 넣지 않았다. 필요하면 `shim.c`와 CMake 타깃을 늘린다.
- Chicory는 스레드 인터럽트로 실행을 멈출 수 있다고 문서화돼 있다([docs/usage/cpu.md @1.7.5](https://github.com/dylibso/chicory/blob/1.7.5/docs/docs/usage/cpu.md)). 디코딩 타임아웃으로 쓰는 것은 시험하지 않았다(미확인).
- 악성 입력이 실제로 선형 메모리 밖을 건드리려 할 때 trap으로 끝나는지는 Wasm 의미론과 Chicory 구현(스펙 테스트 통과)에 기댄 것이고 이 디렉터리에서 직접 재현하지 않았다(미확인).
- wasi-sdk arm64 체크섬, `at.released.wasm2class.plugin` 호환은 미확인이다.
- apt 패키지(cmake 등)는 버전을 고정하지 않았다. 같은 호스트에서 두 번의 `--no-cache` 빌드가 같은 해시를 냈지만, 다른 시점의 apt 미러에서도 같은지는 미확인이다.
