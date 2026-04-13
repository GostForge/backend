package org.gostforge.backend.pat;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.gostforge.backend.pat.dto.CreatePatRequest;
import org.gostforge.backend.pat.dto.PatResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users/me/tokens")
@RequiredArgsConstructor
public class PatController {

    private final PatService patService;

    @PostMapping
    public ResponseEntity<PatResponse> create(Authentication auth,
                              @Valid @RequestBody CreatePatRequest request) {
        UUID userId = (UUID) auth.getPrincipal();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(patService.create(userId, request));
    }

    @GetMapping
    public ResponseEntity<List<PatResponse>> list(Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        return ResponseEntity.ok(patService.listByUser(userId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(Authentication auth, @PathVariable UUID id) {
        UUID userId = (UUID) auth.getPrincipal();
        patService.revoke(userId, id);
        return ResponseEntity.noContent().build();
    }
}
