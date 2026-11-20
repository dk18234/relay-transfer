package dev.dineshrama.relay;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class SecurityFilter extends OncePerRequestFilter {
  private final byte[] token;

  public SecurityFilter(RelayProperties p) {
    token = p.adminToken().getBytes(StandardCharsets.UTF_8);
  }

  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    res.setHeader("X-Content-Type-Options", "nosniff");
    res.setHeader("Referrer-Policy", "no-referrer");
    res.setHeader("X-Frame-Options", "DENY");
    res.setHeader(
        "Content-Security-Policy",
        "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; worker-src"
            + " 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors"
            + " 'none'");
    String path = req.getRequestURI();
    if (path.startsWith("/api/")
        || (path.startsWith("/actuator/") && !path.equals("/actuator/health"))) {
      res.setHeader("Cache-Control", "no-store");
      String supplied = req.getHeader("X-Admin-Token");
      if (supplied == null
          || !MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8))) {
        res.setStatus(401);
        res.setContentType("application/json");
        res.getWriter()
            .write(
                "{\"code\":\"UNAUTHORIZED\",\"message\":\"A valid operator token is required\"}");
        return;
      }
    }
    chain.doFilter(req, res);
  }
}
