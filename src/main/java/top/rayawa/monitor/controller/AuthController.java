package top.rayawa.monitor.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.rayawa.monitor.dto.AuthResponse;
import top.rayawa.monitor.dto.LoginRequest;
import top.rayawa.monitor.service.AuthService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final String shareToken;

    public AuthController(AuthService authService, @Value("${auth.share-token}") String shareToken) {
        this.authService = authService;
        this.shareToken = shareToken;
    }

    @GetMapping("/session")
    public AuthResponse session(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        String username = session == null ? null : (String) session.getAttribute(AuthService.SESSION_USER);
        return new AuthResponse(username != null, username);
    }

    @PostMapping("/login")
    public AuthResponse login(@RequestBody LoginRequest loginRequest, HttpServletRequest request) {
        if (loginRequest == null || !authService.authenticate(loginRequest.username(), loginRequest.password())) {
            throw new SecurityException("用户名或密码错误");
        }

        HttpSession previousSession = request.getSession(false);
        if (previousSession != null) {
            previousSession.invalidate();
        }
        HttpSession session = request.getSession(true);
        String username = loginRequest.username().trim();
        session.setAttribute(AuthService.SESSION_USER, username);
        return new AuthResponse(true, username);
    }

    @GetMapping("/share")
    public void loginFromShare(@RequestParam String token,
                               HttpServletRequest request,
                               HttpServletResponse response) throws IOException {
        if (shareToken.isBlank() || !MessageDigest.isEqual(
                shareToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityException("分享链接无效");
        }

        HttpSession previousSession = request.getSession(false);
        if (previousSession != null) {
            previousSession.invalidate();
        }
        request.getSession(true).setAttribute(AuthService.SESSION_USER, "monitor");
        response.sendRedirect(request.getContextPath() + "/");
    }

    @PostMapping("/logout")
    public AuthResponse logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return new AuthResponse(false, null);
    }
}
