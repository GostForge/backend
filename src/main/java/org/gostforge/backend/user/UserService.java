package org.gostforge.backend.user;

import lombok.RequiredArgsConstructor;
import org.gostforge.backend.common.ApiException;
import org.gostforge.backend.user.dto.ChangePasswordRequest;
import org.gostforge.backend.user.dto.UpdateProfileRequest;
import org.gostforge.backend.user.dto.UserResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public UserResponse getProfile(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found"));
        return toResponse(user);
    }

    @Transactional
    public UserResponse updateProfile(UUID userId, UpdateProfileRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found"));

        if (req.getEmail() != null) {
            if (userRepository.existsByEmail(req.getEmail()) &&
                !user.getEmail().equals(req.getEmail())) {
                throw ApiException.conflict("EMAIL_TAKEN", "Email already exists");
            }
            user.setEmail(req.getEmail());
        }

        user = userRepository.save(user);
        return toResponse(user);
    }

    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found"));

        if (!passwordEncoder.matches(req.getCurrentPassword(), user.getPasswordHash())) {
            throw ApiException.badRequest("INVALID_PASSWORD", "Current password is incorrect");
        }

        user.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
    }

    private UserResponse toResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .storageQuotaMb(user.getStorageQuotaMb())
                .build();
    }
}
