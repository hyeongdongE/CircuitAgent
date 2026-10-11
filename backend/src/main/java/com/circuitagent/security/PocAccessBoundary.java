package com.circuitagent.security;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Local test boundary only. This is not SSO, a user identity or design ownership authorization. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE)
public class PocAccessBoundary extends OncePerRequestFilter {
    private static final int MAX_JSON=512*1024;
    private final String mode;private final Environment environment;
    public PocAccessBoundary(@Value("${circuitagent.mode:local-poc}") String mode,Environment environment){this.mode=mode;this.environment=environment;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        if(!request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/")){chain.doFilter(request,response);return;}
        response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");
        boolean production=Arrays.stream(environment.getActiveProfiles()).anyMatch(p->p.equals("prod") || p.equals("production"));
        if(!"local-poc".equals(mode) || production || !Set.of("127.0.0.1","::1","0:0:0:0:0:0:0:1").contains(request.getRemoteAddr()) || !originAllowed(request.getHeader("Origin"))) {
            reject(response,403,"POC_ACCESS_DENIED","Only local isolated PoC access is configured; enterprise policy is unavailable");return;
        }
        if(request.getContentType()!=null && request.getContentType().toLowerCase(Locale.ROOT).startsWith("application/json")) {
            byte[] body=request.getInputStream().readNBytes(MAX_JSON+1);
            if(body.length>MAX_JSON){reject(response,413,"JSON_TOO_LARGE","Audit JSON must be at most 512 KiB");return;}
            var wrapped=new HttpServletRequestWrapper(request){
                @Override public ServletInputStream getInputStream(){var bytes=new ByteArrayInputStream(body);return new ServletInputStream(){public int read(){return bytes.read();}public boolean isFinished(){return bytes.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener listener){throw new UnsupportedOperationException("Async JSON request unsupported");}};}
                @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
            };chain.doFilter(wrapped,response);
        } else chain.doFilter(request,response);
    }
    private static boolean originAllowed(String value){if(value==null)return true;try{var uri=URI.create(value);return "http".equals(uri.getScheme()) && Set.of("127.0.0.1","localhost","[::1]").contains(uri.getHost()) && Set.of(8080,5173,4173).contains(uri.getPort());}catch(RuntimeException e){return false;}}
    private static void reject(HttpServletResponse response,int status,String code,String message)throws IOException {response.setStatus(status);response.setContentType("application/json");response.getWriter().write("{\"code\":\""+code+"\",\"message\":\""+message+"\"}");}
}
