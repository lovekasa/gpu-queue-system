package org.lab.gpuqueue.account;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {
    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Account> findByLoginName(String normalizedLoginName) {
        return jdbc.sql("""
                SELECT id, login_name, display_name, password_hash, role
                FROM accounts WHERE login_name = :loginName
                """)
                .param("loginName", normalizedLoginName)
                .query((rs, rowNum) -> new Account(
                        rs.getObject("id", UUID.class), rs.getString("login_name"), rs.getString("display_name"),
                        rs.getString("password_hash"), Account.Role.valueOf(rs.getString("role"))))
                .optional();
    }

    boolean initialAdministratorExists() {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM accounts WHERE role = 'ADMIN')")
                .query(Boolean.class)
                .single();
    }

    boolean createInitialAdministrator(String loginName, String displayName, String passwordHash) {
        return jdbc.sql("""
                INSERT INTO accounts (id, login_name, display_name, password_hash, role)
                SELECT :id, :loginName, :displayName, :passwordHash, 'ADMIN'
                WHERE NOT EXISTS (SELECT 1 FROM accounts WHERE role = 'ADMIN' OR login_name = :loginName)
                """)
                .param("id", UUID.randomUUID())
                .param("loginName", loginName)
                .param("displayName", displayName)
                .param("passwordHash", passwordHash)
                .update() == 1;
    }
}
