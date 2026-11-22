package org.lab.gpuqueue.account;

import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MemberAccountController {
    private final AccountRepository accounts;
    private final PasswordEncoder passwords;

    MemberAccountController(AccountRepository accounts, PasswordEncoder passwords) {
        this.accounts = accounts;
        this.passwords = passwords;
    }

    @PostMapping(path = "/api/admin/members", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<?> createMember(@RequestBody MultiValueMap<String, String> form) {
        String loginName = form.getFirst("loginName");
        String displayName = form.getFirst("displayName");
        String password = form.getFirst("password");
        String normalizedLoginName = loginName == null ? "" : Account.normalizeLoginName(loginName);
        String normalizedDisplayName = displayName == null ? "" : displayName.strip();
        if (normalizedLoginName.isEmpty() || normalizedLoginName.length() > 128) {
            return ResponseEntity.badRequest().body(new FieldError("loginName", "登录名须为 1 至 128 个字符。"));
        }
        if (normalizedDisplayName.isEmpty() || normalizedDisplayName.length() > 128) {
            return ResponseEntity.badRequest().body(new FieldError("displayName", "显示姓名须为 1 至 128 个字符。"));
        }
        if (password == null || password.isEmpty()) {
            return ResponseEntity.badRequest().body(new FieldError("password", "请输入初始口令。"));
        }

        UUID id = UUID.randomUUID();
        try {
            accounts.createMember(id, normalizedLoginName, normalizedDisplayName, passwords.encode(password));
        } catch (DuplicateKeyException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new FieldError("loginName", "该登录名已存在。"));
        } catch (DataAccessException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CreatedMember(id, normalizedLoginName, normalizedDisplayName, Account.Role.MEMBER));
    }

    record FieldError(String field, String message) {}

    record CreatedMember(UUID id, String loginName, String displayName, Account.Role role) {}
}
