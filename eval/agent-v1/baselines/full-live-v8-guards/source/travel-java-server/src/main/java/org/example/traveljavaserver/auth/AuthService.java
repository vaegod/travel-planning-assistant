package org.example.traveljavaserver.auth;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.example.traveljavaserver.common.AppException;
import org.example.traveljavaserver.persistence.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {
  private final StoreMapper store;
  private final PasswordEncoder encoder;
  private static final String DUMMY_HASH =
      "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

  @Transactional
  public UserPrincipal register(String username, String password) {
    if (password.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "密码的UTF-8编码不能超过72字节");
    Rows.User row = new Rows.User();
    row.setUsername(username.toLowerCase(Locale.ROOT));
    row.setPasswordHash(encoder.encode(password));
    try {
      store.addUser(row);
    } catch (DuplicateKeyException e) {
      throw new AppException(HttpStatus.CONFLICT, "USERNAME_TAKEN", "用户名已被使用");
    }
    return new UserPrincipal(row.getId(), row.getUsername(), 0);
  }

  public UserPrincipal authenticate(String username, String password) {
    if (password.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new AppException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "用户名或密码不正确");
    Rows.User row = store.user(username.toLowerCase(Locale.ROOT));
    boolean valid = encoder.matches(password, row == null ? DUMMY_HASH : row.getPasswordHash());
    if (row == null || !valid)
      throw new AppException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "用户名或密码不正确");
    return new UserPrincipal(row.getId(), row.getUsername(), row.getCredentialVersion());
  }

  private Rows.User verifiedUser(UserPrincipal actor, String password) {
    var row = store.lockUser(actor.id());
    if (row == null || row.getCredentialVersion() != actor.credentialVersion())
      throw new AppException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "账号信息已变更，请重新登录");
    if (password.getBytes(StandardCharsets.UTF_8).length > 72
        || !encoder.matches(password, row.getPasswordHash()))
      throw new AppException(HttpStatus.BAD_REQUEST, "CURRENT_PASSWORD_INVALID", "当前密码不正确");
    return row;
  }

  @Transactional
  public void rename(UserPrincipal actor, String password, String username) {
    var row = verifiedUser(actor, password);
    String normalized = username.toLowerCase(Locale.ROOT);
    if (normalized.equals(row.getUsername()))
      throw new AppException(HttpStatus.BAD_REQUEST, "USERNAME_UNCHANGED", "新用户名与当前用户名相同");
    try {
      store.renameUser(actor.id(), normalized);
    } catch (DuplicateKeyException e) {
      throw new AppException(HttpStatus.CONFLICT, "USERNAME_TAKEN", "用户名已被使用");
    }
  }

  @Transactional
  public void changePassword(UserPrincipal actor, String current, String next, String confirm) {
    var row = verifiedUser(actor, current);
    if (!next.equals(confirm))
      throw new AppException(HttpStatus.BAD_REQUEST, "PASSWORD_MISMATCH", "两次输入的新密码不一致");
    if (next.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "密码的UTF-8编码不能超过72字节");
    if (encoder.matches(next, row.getPasswordHash()))
      throw new AppException(HttpStatus.BAD_REQUEST, "PASSWORD_UNCHANGED", "新密码不能与当前密码相同");
    store.changePassword(actor.id(), encoder.encode(next));
  }
}
