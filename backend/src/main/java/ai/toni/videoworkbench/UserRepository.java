package ai.toni.videoworkbench;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
class UserRepository {
  private final JdbcTemplate jdbc;
  private final RowMapper<User> mapper =
      (r, n) -> new User(r.getString("id"), r.getString("username"), r.getString("password_hash"));

  UserRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  Optional<User> findByUsername(String username) {
    return jdbc
        .query("select id,username,password_hash from users where username=?", mapper, username)
        .stream()
        .findFirst();
  }

  void save(User user) {
    jdbc.update(
        "insert into users(id,username,password_hash,created_at) values(?,?,?,?)",
        user.id(),
        user.username(),
        user.passwordHash(),
        Timestamp.from(Instant.now()));
  }
}
