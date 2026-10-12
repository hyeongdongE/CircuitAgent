package com.circuitagent.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 인증 미구현 PoC의 로컬 접근 제한 필터. 기본 loopback/허용 Origin만 통과시키고 production은 거절한다. 이는 사내 SSO나 설계 소유권 권한이
 * 아니다. 공개 reverse proxy로 우회 노출하면 안 된다. JSON body는 실제 읽은 bytes로 제한하며 내부 원문 응답에는 no-store를 적용한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PocAccessBoundary extends OncePerRequestFilter {
  private static final int MAX_JSON = 512 * 1024;
  private final String mode;
  private final Environment environment;

  public PocAccessBoundary(
      @Value("${circuitagent.mode:local-poc}") String mode, Environment environment) {
    this.mode = mode;
    this.environment = environment;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    // context path를 제외한 실제 API 경로에 적용한다. 프록시가 모든 호출을 loopback으로 보이게 하면 인증 경계가 아니다.
    if (!request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/")) {
      chain.doFilter(request, response);
      return;
    }
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("X-Content-Type-Options", "nosniff");
    boolean production =
        Arrays.stream(environment.getActiveProfiles())
            .anyMatch(p -> p.equals("prod") || p.equals("production"));
    if (!"local-poc".equals(mode)
        || production
        || !Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(request.getRemoteAddr())
        || !originAllowed(request.getHeader("Origin"))) {
      reject(
          response,
          403,
          "POC_ACCESS_DENIED",
          "Only local isolated PoC access is configured; enterprise policy is unavailable");
      return;
    }
    if (request.getContentType() != null
        && request.getContentType().toLowerCase(Locale.ROOT).startsWith("application/json")) {
      // Content-Length만 신뢰하지 않는다. 실제 stream을 한도+1 byte까지 읽어 chunked 요청도 제한한다.
      byte[] body = request.getInputStream().readNBytes(MAX_JSON + 1);
      if (body.length > MAX_JSON) {
        reject(response, 413, "JSON_TOO_LARGE", "Audit JSON must be at most 512 KiB");
        return;
      }
      // 검사 과정에서 소비한 body를 Controller가 다시 읽을 수 있도록 요청 stream을 감싼다.
      var wrapped =
          new HttpServletRequestWrapper(request) {
            @Override
            public ServletInputStream getInputStream() {
              var bytes = new ByteArrayInputStream(body);
              return new ServletInputStream() {
                public int read() {
                  return bytes.read();
                }

                public boolean isFinished() {
                  return bytes.available() == 0;
                }

                public boolean isReady() {
                  return true;
                }

                public void setReadListener(ReadListener listener) {
                  throw new UnsupportedOperationException("Async JSON request unsupported");
                }
              };
            }

            @Override
            public BufferedReader getReader() {
              return new BufferedReader(
                  new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
            }
          };
      chain.doFilter(wrapped, response);
    } else chain.doFilter(request, response);
  }

  private static boolean originAllowed(String value) {
    if (value == null) return true;
    try {
      var uri = URI.create(value);
      return "http".equals(uri.getScheme())
          && Set.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost())
          && Set.of(8080, 5173, 4173).contains(uri.getPort());
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static void reject(HttpServletResponse response, int status, String code, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
  }
}
