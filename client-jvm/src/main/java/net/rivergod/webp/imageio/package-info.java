/**
 * WebP ImageIO 플러그인. libwebp를 별도 프로세스(gRPC 사이드카 서버)에 두고 그 서버를 호출해 WebP를 읽고 쓴다
 * (ADR-0001). 사용자는 {@code ImageIO.read} / {@code ImageIO.write}로 쓰며, 이 패키지의 공개 타입은 ImageIO가 찾는
 * SPI 두 개뿐이다.
 */
package net.rivergod.webp.imageio;
