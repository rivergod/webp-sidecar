package net.rivergod.webp.sidecar;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 테스트용 HTTP CONNECT 프록시(127.0.0.1, 임의 포트). {@code CONNECT host:port}를 받으면 대상에 붙고 양방향으로 바이트를
 * 옮긴다. 인증과 HTTP/1 요청 전달은 하지 않는다. 받은 CONNECT 수를 센다.
 */
final class ConnectProxy implements AutoCloseable {

    final AtomicInteger connects = new AtomicInteger();
    private final ServerSocket server;

    private ConnectProxy() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread accept = new Thread(this::acceptLoop, "connect-proxy");
        accept.setDaemon(true);
        accept.start();
    }

    static ConnectProxy start() throws IOException {
        return new ConnectProxy();
    }

    int port() {
        return server.getLocalPort();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                Thread t = new Thread(() -> handle(client), "connect-proxy-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handle(Socket client) {
        try (client) {
            InputStream in = client.getInputStream();
            String head = readHead(in);
            String[] requestLine = head.split("\r\n", 2)[0].split(" ");
            if (requestLine.length < 2 || !requestLine[0].equals("CONNECT")) {
                client.getOutputStream().write("HTTP/1.1 405 Method Not Allowed\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                return;
            }
            String target = requestLine[1];
            int colon = target.lastIndexOf(':');
            try (Socket upstream = new Socket(target.substring(0, colon), Integer.parseInt(target.substring(colon + 1)))) {
                connects.incrementAndGet();
                OutputStream out = client.getOutputStream();
                out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                Thread up = new Thread(() -> pipe(in, upstreamOut(upstream)), "connect-proxy-up");
                up.setDaemon(true);
                up.start();
                pipe(upstream.getInputStream(), out);
                up.join(1_000);
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            // 연결이 끊기면 끝낸다.
        }
    }

    private static OutputStream upstreamOut(Socket s) {
        try {
            return s.getOutputStream();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** 빈 줄까지(요청 줄과 헤더) 읽는다. 그 뒤 바이트는 스트림에 남는다. */
    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        byte[] end = {'\r', '\n', '\r', '\n'};
        for (int b; (b = in.read()) >= 0; ) {
            head.write(b);
            matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
            if (matched == end.length) {
                break;
            }
        }
        return head.toString(StandardCharsets.US_ASCII);
    }

    private static void pipe(InputStream from, OutputStream to) {
        byte[] buf = new byte[16 * 1024];
        try {
            for (int r; (r = from.read(buf)) >= 0; ) {
                to.write(buf, 0, r);
                to.flush();
            }
        } catch (IOException e) {
            // 한쪽이 닫혔다.
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
