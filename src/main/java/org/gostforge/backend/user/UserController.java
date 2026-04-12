package org.gostforge.backend.user;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.gostforge.backend.user.dto.ChangePasswordRequest;
import org.gostforge.backend.user.dto.UpdateProfileRequest;
import org.gostforge.backend.user.dto.UserResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getProfile(Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        return ResponseEntity.ok(userService.getProfile(userId));
    }

    @PutMapping("/me")
    public ResponseEntity<UserResponse> updateProfile(Authentication auth,
                                                      @RequestBody UpdateProfileRequest request) {
        UUID userId = (UUID) auth.getPrincipal();
        return ResponseEntity.ok(userService.updateProfile(userId, request));
    }

    @PutMapping("/me/password")
    public ResponseEntity<Void> changePassword(Authentication auth,
                                               @Valid @RequestBody ChangePasswordRequest request) {
        UUID userId = (UUID) auth.getPrincipal();
        userService.changePassword(userId, request);
        return ResponseEntity.ok().build();
    }
}
