package org.lab.gpuqueue.account;

import java.util.Locale;
import java.util.UUID;

public record Account(UUID id, String loginName, String displayName, String passwordHash, Role role) {
    public enum Role {
        ADMIN, MEMBER
    }

    public static String normalizeLoginName(String loginName) {
        return loginName.strip().toLowerCase(Locale.ROOT);
    }
}
