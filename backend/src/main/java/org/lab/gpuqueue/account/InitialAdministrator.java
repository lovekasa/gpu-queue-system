package org.lab.gpuqueue.account;

import java.io.Console;
import java.nio.CharBuffer;
import java.util.Arrays;

import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class InitialAdministrator {
    private final AccountRepository accounts;
    private final PasswordEncoder passwordEncoder;

    public InitialAdministrator(AccountRepository accounts, PasswordEncoder passwordEncoder) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
    }

    public void initialize() {
        if (accounts.initialAdministratorExists()) {
            System.out.println("管理员已存在，账号保持原样。");
            return;
        }

        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException("请在可交互的本机终端初始化管理员。");
        }

        String loginName = Account.normalizeLoginName(readName(console, "管理员登录名："));
        String displayName = readName(console, "管理员显示姓名：");
        if (loginName.length() > 128) {
            throw new IllegalArgumentException("规范化后的登录名须在 128 个字符以内。");
        }

        char[] password = console.readPassword("管理员口令：");
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("须输入管理员口令。");
        }

        try {
            String passwordHash = passwordEncoder.encode(CharBuffer.wrap(password));
            if (!accounts.createInitialAdministrator(loginName, displayName, passwordHash)) {
                throw new IllegalStateException("管理员或同名账号已存在，账号保持原样。");
            }
            System.out.println("初始管理员已创建。");
        } catch (DataAccessException ex) {
            throw new IllegalStateException("无法写入管理员账号，请检查本地数据库。");
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static String readName(Console console, String prompt) {
        String value = console.readLine("%s", prompt);
        if (value == null || value.isBlank() || value.strip().length() > 128) {
            throw new IllegalArgumentException("登录名和显示姓名须为 1 至 128 个字符。");
        }
        return value.strip();
    }
}
