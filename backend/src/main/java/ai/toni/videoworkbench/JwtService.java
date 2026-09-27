package ai.toni.videoworkbench;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
class JwtService {
  private final SecretKey key;
  private final Duration ttl;

  JwtService(
      @Value("${workbench.security.jwt-secret}") String secret,
      @Value("${workbench.security.token-ttl-hours:24}") long ttlHours) {
    byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < 32) throw new IllegalStateException("WORKBENCH_JWT_SECRET 至少需要 32 个字符");
    this.key = Keys.hmacShaKeyFor(bytes);
    this.ttl = Duration.ofHours(ttlHours);
  }

  String issue(AuthenticatedUser user) {
    Instant now = Instant.now();
    return Jwts.builder()
        .subject(user.id())
        .claim("username", user.username())
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(ttl)))
        .signWith(key)
        .compact();
  }

  Optional<AuthenticatedUser> parse(String token) {
    try {
      Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
      String id = claims.getSubject();
      String username = claims.get("username", String.class);
      return id == null || username == null
          ? Optional.empty()
          : Optional.of(new AuthenticatedUser(id, username));
    } catch (JwtException | IllegalArgumentException ex) {
      return Optional.empty();
    }
  }
}
