package org.lab.gpuqueue.security;

import org.lab.gpuqueue.account.Account;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class SessionController {
    @GetMapping("/csrf")
    CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }

    @GetMapping("/me")
    Identity me(@AuthenticationPrincipal AccountPrincipal account) {
        return new Identity(account.id(), account.getUsername(), account.displayName(), account.role());
    }

    record CsrfResponse(String headerName, String token) {}

    record Identity(String id, String loginName, String displayName, Account.Role role) {}
}
