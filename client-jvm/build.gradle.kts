// WebP ImageIO SPI 플러그인(gRPC 클라이언트). ADR-0005: Java, --release 17, Gradle. ADR-0010/0013: 좌표와 패키지.
import com.google.protobuf.gradle.id

plugins {
    `java-library`
    alias(libs.plugins.protobuf)
}

group = "net.rivergod"
version = "0.1.0-SNAPSHOT"

base {
    // ADR-0010: 배포 좌표의 artifactId.
    archivesName.set("webp-imageio-external-server-proto")
}

java {
    toolchain {
        // 빌드는 JDK 25로 하고, 산출물은 --release 17로 만든다(ADR-0005).
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    // 비셰이드 grpc-netty: JNI 없는 NIO UDS 경로를 쓰려면 Netty 클래스를 직접 다뤄야 한다(ADR-0003).
    implementation(libs.grpc.netty)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation(libs.protobuf.java)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.params)
    // 테스트에서 samples/manifest.json을 읽는다.
    testImplementation(libs.gson)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    // UDS PoC 전용: grpc-java 공식 UDS 경로(native epoll)와 비교한다. 제품 코드는 쓰지 않는다.
    testImplementation(variantOf(libs.netty.epoll) { classifier("linux-x86_64") })
}

// --- proto 코드 생성 ---
// proto 원본에는 언어별 옵션을 두지 않는다(docs/design/grpc-api.md §6). 생성 직전에 사본에 java 옵션을 넣는다.
val generatedProtoSrc = layout.buildDirectory.dir("proto-with-java-options")
val prepareProto = tasks.register<Sync>("prepareProto") {
    from(rootProject.layout.projectDirectory.dir("proto"))
    include("**/*.proto")
    into(generatedProtoSrc)
    filter { line ->
        if (line.trim() == "package webp.v1;") {
            line + "\n" +
                "option java_package = \"net.rivergod.webp.imageio.proto.v1\";\n" +
                "option java_multiple_files = true;"
        } else {
            line
        }
    }
}

sourceSets {
    main {
        proto {
            srcDir(generatedProtoSrc)
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.get()}"
        }
    }
    generateProtoTasks {
        all().configureEach {
            dependsOn(prepareProto)
            plugins {
                id("grpc")
            }
        }
    }
}

tasks.matching { it.name == "extractIncludeProto" || it.name == "extractProto" || it.name == "processResources" }
    .configureEach { dependsOn(prepareProto) }

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

tasks.named<JavaCompile>("compileJava") {
    // 공개 패키지의 문서 주석 누락을 컴파일 오류로 만든다(docs/conventions/code-documentation.md §5). 생성 코드는 제외한다.
    // requires-automatic: grpc-java와 protobuf-java에 module-info가 없어 자동 모듈을 requires할 수밖에 없다(research/0008).
    options.compilerArgs.addAll(
        listOf("-Xlint:all,-processing,-requires-automatic", "-Xdoclint:all/protected", "-Xdoclint/package:net.rivergod.webp.imageio", "-Werror"),
    )
}

tasks.withType<Javadoc>().configureEach {
    exclude("net/rivergod/webp/imageio/proto/**")
}

// --- 테스트 ---
// test: 단위 테스트(빠름, Docker 없음). integrationTest: 실제 서버 컨테이너(Docker 필요).
// module path 등록 테스트(SpiRegistrationTest)가 쓸 jar와 의존성 경로.
val modulePath = files(tasks.jar, configurations.runtimeClasspath)

tasks.withType<Test>().configureEach {
    dependsOn(tasks.jar)
    systemProperty("webp.test.modulePath", modulePath.asPath)
}

tasks.test {
    useJUnitPlatform { excludeTags("integration", "poc", "poc-deny") }
}

val serverImage = providers.gradleProperty("webpServerImage").orElse("webp-imageio-server:dev")

// 통합 테스트가 쓸 서버 이미지를 docker CLI로 만든다(.dockerignore 적용, 빌드 캐시 사용).
val buildServerImage = tasks.register<Exec>("buildServerImage") {
    workingDir = rootProject.projectDir
    commandLine("docker", "build", "-q", "-f", "deploy/Dockerfile", "-t", serverImage.get(), ".")
}

fun Test.useServerImage() {
    dependsOn(buildServerImage)
    systemProperty("webp.test.serverImage", serverImage.get())
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "실제 서버 컨테이너(Testcontainers)로 통합 테스트를 실행한다."
    group = "verification"
    useServerImage()
    useJUnitPlatform { includeTags("integration") }
    shouldRunAfter(tasks.test)
}

val udsPoc = tasks.register<Test>("udsPoc") {
    description = "Phase 2 UDS PoC: JNI 없는 NIO 경로와 grpc-java 공식 epoll 경로를 비교한다(ADR-0003)."
    group = "verification"
    useServerImage()
    useJUnitPlatform { includeTags("poc") }
    testLogging { showStandardStreams = true }
}

val udsPocDeny = tasks.register<Test>("udsPocDeny") {
    description = "Phase 2 UDS PoC: --illegal-native-access=deny에서 JNI 없는 경로만 동작하는지 본다(ADR-0003)."
    group = "verification"
    useServerImage()
    useJUnitPlatform { includeTags("poc-deny") }
    jvmArgs("--illegal-native-access=deny")
    testLogging { showStandardStreams = true }
}

// JDK 매트릭스(ADR-0005 확인 방법): 같은 테스트를 각 toolchain에서 돌린다.
val matrix = listOf(17, 21, 25, 27)
val matrixTasks = matrix.flatMap { jdk ->
    val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(jdk)) }
    listOf(
        tasks.register<Test>("testJdk$jdk") {
            description = "단위 테스트를 JDK $jdk 에서 실행한다."
            group = "verification"
            javaLauncher.set(launcher)
            testClassesDirs = sourceSets.test.get().output.classesDirs
            classpath = sourceSets.test.get().runtimeClasspath
            useJUnitPlatform { excludeTags("integration", "poc", "poc-deny") }
        },
        tasks.register<Test>("integrationTestJdk$jdk") {
            description = "통합 테스트를 JDK $jdk 에서 실행한다."
            group = "verification"
            javaLauncher.set(launcher)
            useServerImage()
            useJUnitPlatform { includeTags("integration") }
        },
    )
}

tasks.register("testMatrix") {
    description = "단위·통합 테스트를 JDK ${matrix.joinToString("/")}에서 모두 실행한다."
    group = "verification"
    dependsOn(matrixTasks)
}

// ADR-0005 확인 방법: Spring Boot 4.1이 관리하는 grpc-java(1.84.1)·protobuf-java(4.36.2)로 테스트한다.
// 실행: ./gradlew :client-jvm:test :client-jvm:integrationTest -PbootCompat
if (providers.gradleProperty("bootCompat").isPresent) {
    configurations.matching { it.name.startsWith("test") }.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "io.grpc") {
                useVersion("1.84.1")
            }
            if (requested.group == "com.google.protobuf" && requested.name == "protobuf-java") {
                useVersion("4.36.2")
            }
        }
    }
}
