package net.rivergod.webp.imageio;

import com.github.dockerjava.api.model.Capability;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * 통합 테스트용 서버 컨테이너. deploy/README.md의 격리 옵션(read-only, cap-drop ALL, no-new-privileges, seccomp 프로파일,
 * 메모리 1 GiB, CPU 2)으로 띄우고 TCP와 UDS(호스트 디렉터리 바인드)를 둘 다 연다. 이미지는 Gradle buildServerImage가 만든다.
 * seccomp 프로파일 경로는 시스템 프로퍼티 {@code webp.test.seccompProfile}(Gradle이 넘긴다)다.
 */
final class ServerContainer extends GenericContainer<ServerContainer> {

    private final Path socketDir;

    private ServerContainer(Path socketDir, String uidGid) {
        super(System.getProperty("webp.test.serverImage", "webp-imageio-server:dev"));
        this.socketDir = socketDir;
        withEnv("WEBP_SERVER_TCP_ADDR", "0.0.0.0:50051");
        withExposedPorts(50051);
        withFileSystemBind(socketDir.toString(), "/run/webp-imageio", BindMode.READ_WRITE);
        withCreateContainerCmdModifier(cmd -> {
            cmd.withUser(uidGid);
            cmd.getHostConfig()
                    .withReadonlyRootfs(true)
                    .withCapDrop(Capability.ALL)
                    .withSecurityOpts(securityOpts())
                    .withMemory(1024L * 1024 * 1024)
                    .withMemorySwap(1024L * 1024 * 1024)
                    .withNanoCPUs(2_000_000_000L);
        });
        waitingFor(Wait.forLogMessage(".*starting.*\\n", 1).withStartupTimeout(Duration.ofSeconds(60)));
    }

    /** no-new-privileges와 seccomp. Docker API는 seccomp 값으로 파일 경로가 아니라 프로파일 JSON 내용을 받는다. */
    private static java.util.List<String> securityOpts() {
        String profile = System.getProperty("webp.test.seccompProfile");
        if (profile == null || profile.isBlank()) {
            return java.util.List.of("no-new-privileges");
        }
        try {
            return java.util.List.of("no-new-privileges", "seccomp=" + Files.readString(Path.of(profile)));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("cannot read seccomp profile " + profile, e);
        }
    }

    /** 짧은 /tmp 경로(AF_UNIX 경로 길이 한도)에 소켓 디렉터리를 만들고, 현재 사용자 uid:gid로 서버를 띄운다. */
    static ServerContainer startNew() throws Exception {
        Path dir = Files.createTempDirectory(Path.of("/tmp"), "wpit");
        String uidGid = Files.getAttribute(dir, "unix:uid") + ":" + Files.getAttribute(dir, "unix:gid");
        ServerContainer c = new ServerContainer(dir, uidGid);
        c.start();
        Path socket = dir.resolve("webp.sock");
        for (int i = 0; i < 100 && !Files.exists(socket); i++) {
            Thread.sleep(50);
        }
        return c;
    }

    String tcpEndpoint() {
        return getHost() + ":" + getMappedPort(50051);
    }

    String udsEndpoint() {
        return "unix://" + socketDir.resolve("webp.sock");
    }

    /** 서버 프로세스를 SIGKILL로 죽인다(libwebp 크래시를 클라이언트 쪽에서 모사). */
    void kill() {
        getDockerClient().killContainerCmd(getContainerId()).exec();
    }
}
