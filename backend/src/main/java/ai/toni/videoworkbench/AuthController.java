package ai.toni.videoworkbench;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
class AuthController {
  private static final String USERNAME_PATTERN = "[A-Za-z0-9_]{3,32}";
  private static final int MIN_PASSWORD_LENGTH = 8;
  private static final int MAX_PASSWORD_LENGTH = 72;

  private final UserRepository users;
  private final PasswordEncoder encoder;
  private final JwtService jwt;

  AuthController(UserRepository users, PasswordEncoder encoder, JwtService jwt) {
    this.users = users;
    this.encoder = encoder;
    this.jwt = jwt;
  }

  @PostMapping("/register")
  AuthResponse register(@RequestBody Credentials credentials) {
    String username = username(credentials);
    String password = password(credentials);
    if (users.findByUsername(username).isPresent())
      throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在");
    User user = new User(UUID.randomUUID().toString(), username, encoder.encode(password));
    users.save(user);
    return response(user);
  }

  @PostMapping("/login")
  AuthResponse login(@RequestBody Credentials credentials) {
    String username = credentials.username() == null ? "" : credentials.username().trim();
    String password = credentials.password() == null ? "" : credentials.password();
    // 用户名不存在与密码错误返回同一结果，避免暴露账号是否存在。
    User user = users.findByUsername(username).orElseThrow(AuthController::rejected);
    if (!encoder.matches(password, user.passwordHash())) throw rejected();
    return response(user);
  }

  private AuthResponse response(User user) {
    return new AuthResponse(
        jwt.issue(new AuthenticatedUser(user.id(), user.username())), user.username());
  }

  private String username(Credentials credentials) {
    String value = credentials.username() == null ? "" : credentials.username().trim();
    if (!value.matches(USERNAME_PATTERN))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名需为 3-32 位字母、数字或下划线");
    return value;
  }

  private String password(Credentials credentials) {
    String value = credentials.password() == null ? "" : credentials.password();
    if (value.length() < MIN_PASSWORD_LENGTH || value.length() > MAX_PASSWORD_LENGTH)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码长度需为 8-72 位");
    return value;
  }

  private static ResponseStatusException rejected() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
  }

  record Credentials(String username, String password) {}

  record AuthResponse(String token, String username) {}
}
