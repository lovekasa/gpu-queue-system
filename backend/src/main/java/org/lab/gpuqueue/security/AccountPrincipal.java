package org.lab.gpuqueue.security;

import java.util.List;

import org.lab.gpuqueue.account.Account;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

class AccountPrincipal extends User {
    private final String id;
    private final String displayName;
    private final Account.Role role;

    AccountPrincipal(Account account) {
        super(account.loginName(), account.passwordHash(),
                List.of(new SimpleGrantedAuthority("ROLE_" + account.role().name())));
        this.id = account.id().toString();
        this.displayName = account.displayName();
        this.role = account.role();
    }

    String id() {
        return id;
    }

    String displayName() {
        return displayName;
    }

    Account.Role role() {
        return role;
    }
}
