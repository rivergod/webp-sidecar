// 저장소 루트 Gradle 빌드(ADR-0005). 하위 프로젝트: client-jvm(ImageIO 플러그인), bench(JMH 벤치마크, Phase 4).
plugins {
    // 테스트 매트릭스용 JDK(17/21/25/27)를 toolchain으로 자동으로 받는다.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    // 모든 하위 프로젝트가 Maven Central만 쓴다.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "webp-sidecar"

include("client-jvm")
include("bench")
