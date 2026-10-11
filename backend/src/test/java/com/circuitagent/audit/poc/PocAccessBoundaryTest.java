package com.circuitagent.audit.poc;
import com.circuitagent.security.PocAccessBoundary;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
class PocAccessBoundaryTest {
    @Test void loopbackAllowedButRemoteAndUnconfiguredProductionAreDenied() throws Exception {
        for(String remote:new String[]{"127.0.0.1","198.51.100.10"}){
            var request=new MockHttpServletRequest("GET","/api/audit-rules");request.setRemoteAddr(remote);var response=new MockHttpServletResponse();
            new PocAccessBoundary("local-poc",new MockEnvironment()).doFilter(request,response,new MockFilterChain());assertThat(response.getStatus()).isEqualTo(remote.equals("127.0.0.1")?200:403);
        }
        var prefixed=new MockHttpServletRequest("GET","/poc/api/audit-rules");prefixed.setContextPath("/poc");prefixed.setRemoteAddr("198.51.100.10");var blocked=new MockHttpServletResponse();new PocAccessBoundary("local-poc",new MockEnvironment()).doFilter(prefixed,blocked,new MockFilterChain());assertThat(blocked.getStatus()).isEqualTo(403);
        var env=new MockEnvironment();env.setActiveProfiles("production");var response=new MockHttpServletResponse();new PocAccessBoundary("local-poc",env).doFilter(new MockHttpServletRequest("GET","/api/audit-rules"),response,new MockFilterChain());assertThat(response.getStatus()).isEqualTo(403);
        response=new MockHttpServletResponse();new PocAccessBoundary("enterprise",new MockEnvironment()).doFilter(new MockHttpServletRequest("GET","/api/audit-rules"),response,new MockFilterChain());assertThat(response.getStatus()).isEqualTo(403);
    }
    @Test void crossSiteOriginBlockedAndLocalViteOriginHasNoStoreHeaders() throws Exception {
        for(String origin:new String[]{"https://untrusted.example","http://127.0.0.1:5173"}){
            var request=new MockHttpServletRequest("POST","/api/designs/x/audits");request.addHeader("Origin",origin);var response=new MockHttpServletResponse();new PocAccessBoundary("local-poc",new MockEnvironment()).doFilter(request,response,new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(origin.startsWith("https")?403:200);assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        }
    }
    @Test void oversizedJsonRejectedEvenWithoutTrustedContentLength() throws Exception {
        var request=new MockHttpServletRequest("POST","/api/designs/x/audits");request.setContentType("application/json");request.setContent(new byte[512*1024+1]);var response=new MockHttpServletResponse();new PocAccessBoundary("local-poc",new MockEnvironment()).doFilter(request,response,new MockFilterChain());assertThat(response.getStatus()).isEqualTo(413);assertThat(response.getContentAsString()).contains("JSON_TOO_LARGE");
    }
}
