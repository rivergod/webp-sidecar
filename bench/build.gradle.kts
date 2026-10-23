// Phase 4 JMH 벤치마크(ADR-0011 판정 기준, ADR-0015 측정 방법). 제품 모듈이 아니다(배포하지 않는다).
// 한 명령 재현: bench/run.sh (서버 컨테이너, 입력 생성, 검증, JMH). 자세한 내용은 bench/README.md.
plugins {
    java
}

java {
    toolchain {
        // 벤치 실행 JDK는 25다(ADR-0015). 플러그인은 client-jvm이 --release 17로 컴파일한 그대로 쓴다.
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

val chicoryCli: Configuration by configurations.creating

dependencies {
    implementation(project(":client-jvm"))
    // 원시 gRPC 측정(TransportBenchmark)과 입력 생성은 생성 stub을 직접 쓴다.
    implementation(libs.grpc.netty)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation(libs.protobuf.java)
    implementation(libs.gson)

    implementation(libs.jmh.core)
    annotationProcessor(libs.jmh.annprocess)

    // 비교 대상(ADR-0011): 순수 Java, JNI, 프로세스 안 Wasm.
    implementation(libs.twelvemonkeys.webp)
    implementation(libs.darkxanter.webp)
    implementation(libs.chicory.runtime)
    implementation(libs.chicory.wasm)
    // WasmWebPDecoder가 RUNTIME_COMPILED 모드에서 직접 참조한다(bench/wasm/README.md).
    implementation(libs.chicory.compiler)
    // Wasm → JVM 바이트코드 빌드타임 컴파일러. uber jar라 전이 의존성이 필요 없다.
    chicoryCli(libs.chicory.compiler.cli) { isTransitive = false }

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
    // JMH 생성 코드와 Chicory 생성 코드도 같이 컴파일하므로 -Werror는 쓰지 않는다.
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing"))
}

// --- libwebp Wasm 모듈과 Chicory 빌드타임 컴파일 (bench/wasm/README.md) ---
val wasmDir = layout.projectDirectory.dir("wasm")
val wasmFile = wasmDir.file("build/libwebp.wasm")
val chicoryGen = layout.buildDirectory.dir("generated/chicory")
val wasmResourceDir = layout.buildDirectory.dir("generated/wasm-resource")
val wasmPackagePath = "net/rivergod/webp/imageio/bench/wasm"

val buildWasm = tasks.register<Exec>("buildWasm") {
    description = "libwebp v1.6.0을 wasi-sdk로 Wasm 빌드한다(docker)."
    inputs.files(wasmDir.file("Dockerfile"), wasmDir.file("shim.c"))
    outputs.file(wasmFile)
    commandLine(
        "docker", "build", "--quiet", "--target", "artifact",
        "--output", "type=local,dest=${wasmDir.dir("build").asFile.path}",
        wasmDir.asFile.path,
    )
}

val chicoryCompile = tasks.register<JavaExec>("chicoryCompile") {
    description = "libwebp.wasm을 Chicory 빌드타임 컴파일러로 JVM 클래스로 바꾼다."
    dependsOn(buildWasm)
    classpath = chicoryCli
    mainClass.set("com.dylibso.chicory.experimental.compiler.cli.Cli")
    inputs.file(wasmFile)
    outputs.dir(chicoryGen)
    doFirst { delete(chicoryGen) }
    args(
        "--prefix", "net.rivergod.webp.imageio.bench.wasm.LibWebPModule",
        "--source-dir", chicoryGen.get().dir("src").asFile.path,
        "--class-dir", chicoryGen.get().dir("classes").asFile.path,
        "--wasm-dir", chicoryGen.get().dir("resources").asFile.path,
        "--interpreter-fallback", "FAIL",
        wasmFile.asFile.path,
    )
}

// RUNTIME_COMPILED/INTERPRETER 모드는 원본 모듈을 클래스패스 리소스로 읽는다.
val wasmResource = tasks.register<Copy>("wasmResource") {
    dependsOn(buildWasm)
    from(wasmFile)
    into(wasmResourceDir.map { it.dir(wasmPackagePath) })
}

// LibWebPModule.java가 미리 컴파일된 LibWebPModuleMachine을 참조하므로 생성 클래스는 컴파일·실행 클래스패스 둘 다에 둔다.
val chicoryClasses = files(chicoryGen.map { it.dir("classes") }).builtBy(chicoryCompile)
dependencies {
    implementation(chicoryClasses)
}

sourceSets {
    main {
        java.srcDir(chicoryGen.map { it.dir("src") })
        resources.srcDir(chicoryGen.map { it.dir("resources") })
        resources.srcDir(wasmResourceDir)
    }
}
tasks.compileJava { dependsOn(chicoryCompile) }
tasks.processResources { dependsOn(chicoryCompile, wasmResource) }
tasks.jar { from(chicoryGen.map { it.dir("classes") }) }

// --- 실행 묶음: bench/run.sh가 이 디렉터리의 jar로 JMH와 도구를 실행한다 ---
val installBench = tasks.register<Sync>("installBench") {
    description = "벤치 jar와 실행 클래스패스를 build/install/bench/lib에 모은다."
    from(tasks.jar)
    from(configurations.runtimeClasspath) { exclude { it.file.isDirectory } }
    into(layout.buildDirectory.dir("install/bench/lib"))
    val launcher = javaToolchains.launcherFor(java.toolchain)
    val javaFile = layout.buildDirectory.file("install/bench/java-executable.txt")
    outputs.file(javaFile)
    doLast {
        javaFile.get().asFile.writeText(launcher.get().executablePath.asFile.absolutePath + "\n")
    }
}

// 서버가 있어야 하는 검증 테스트(비교 대상 간 픽셀 일치, 표준 메타데이터 트리 비교). bench/run.sh가 엔드포인트를 넘긴다.
tasks.test {
    useJUnitPlatform()
    dependsOn(installBench)
    val props = listOf("webp.bench.endpoint", "webp.bench.inputs", "webp.bench.samples", "webp.bench.report")
    props.forEach { key -> providers.gradleProperty(key).orNull?.let { systemProperty(key, it) } }
    systemProperty("webp.bench.samples", providers.gradleProperty("webp.bench.samples")
        .getOrElse(rootProject.layout.projectDirectory.dir("samples").asFile.path))
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    maxHeapSize = "4g"
    // 결과가 서버 상태에 따라 달라지므로 캐시하지 않는다.
    outputs.upToDateWhen { false }
}
