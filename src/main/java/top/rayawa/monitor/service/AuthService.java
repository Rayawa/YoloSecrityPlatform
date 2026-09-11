package top.rayawa.monitor.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@Service
public class AuthService {

    public static final String SESSION_USER = "authenticatedUser";

    private static final Map<String, String> USERS = Map.of(
            "admin", "admin123",
            "monitor", "monitor123"
    );

    public boolean authenticate(String username, String password) {
        if (username == null || password == null) {
            return false;
        }
        String normalizedUsername = username.trim();
        String expectedPassword = USERS.get(normalizedUsername);
        return expectedPassword != null && MessageDigest.isEqual(
                expectedPassword.getBytes(StandardCharsets.UTF_8),
                password.getBytes(StandardCharsets.UTF_8)
        );
    }
}
