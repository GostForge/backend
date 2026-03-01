package org.gostforge.backend.auth;

import lombok.RequiredArgsConstructor;
import org.gostforge.backend.auth.dto.AuthResponse;
import org.gostforge.backend.auth.dto.LoginRequest;
import org.gostforge.backend.auth.dto.RegisterRequest;
import org.gostforge.backend.common.ApiException;
import org.gostforge.backend.security.JwtTokenProvider;
import org.gostforge.backend.user.User;
import org.gostforge.backend.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtProvider;

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        if (userRepository.existsByUsername(req.getUsername())) {
            throw ApiException.conflict("USERNAME_TAKEN", "Username already exists");
        }
        if (userRepository.existsByEmail(req.getEmail())) {
            throw ApiException.conflict("EMAIL_TAKEN", "Email already exists");
        }

        User user = User.builder()
                .username(req.getUsername())
                .email(req.getEmail())
                .passwordHash(passwordEncoder.encode(req.getPassword()))
                .displayName(req.getDisplayName())
                .build();
        user = userRepository.save(user);

        return buildAuthResponse(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        User user = userRepository.findByUsernameOrEmail(req.getLogin(), req.getLogin())
                .orElseThrow(() -> ApiException.unauthorized("Invalid credentials"));

        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw ApiException.unauthorized("Invalid credentials");
        }

        return buildAuthResponse(user);
    }

    public AuthResponse refresh(String refreshToken) {
        UUID userId = jwtProvider.consumeRefreshToken(refreshToken)
                .orElseThrow(() -> ApiException.unauthorized("Invalid or expired refresh token"));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized("User not found"));

        return buildAuthResponse(user);
    }

    public void logout(String refreshToken) {
        jwtProvider.revokeRefreshToken(refreshToken);
    }

    private AuthResponse buildAuthResponse(User user) {
        return buildAuthResponseForUser(user);
    }

    /**
     * Build auth response for a given user. Used by TelegramService for Mini App auth.
     */
    public AuthResponse buildAuthResponseForUser(User user) {
        String accessToken = jwtProvider.createAccessToken(user.getId(), user.getUsername());
        String refreshToken = jwtProvider.createRefreshToken(user.getId());

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .user(AuthResponse.UserInfo.builder()
                        .id(user.getId())
                        .username(user.getUsername())
                        .email(user.getEmail())
                        .displayName(user.getDisplayName())
                        .telegramLinked(user.getTelegramChatId() != null)
                        .build())
                .build();
    }
}
