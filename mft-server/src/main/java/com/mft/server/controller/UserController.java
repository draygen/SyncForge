package com.mft.server.controller;

import com.mft.server.model.User;
import com.mft.server.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/users")
public class UserController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserController(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    private record UserDTO(
            UUID id,
            String username,
            boolean enabled,
            Set<String> roles,
            java.time.LocalDateTime lastLoginAt,
            String lastLoginIp
    ) {
        static UserDTO from(User u) {
            return new UserDTO(
                    u.getId(),
                    u.getUsername(),
                    u.isEnabled(),
                    u.getRoles(),
                    u.getLastLoginAt(),
                    u.getLastLoginIp()
            );
        }
    }

    @GetMapping
    public List<UserDTO> listUsers() {
        return userRepository.findAll().stream()
                .map(UserDTO::from)
                .collect(Collectors.toList());
    }

    @PostMapping
    public ResponseEntity<UserDTO> createUser(@RequestBody User user) {
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        if (user.getRoles() == null || user.getRoles().isEmpty()) {
            user.setRoles(Set.of("USER"));
        }
        return ResponseEntity.ok(UserDTO.from(userRepository.save(user)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable java.util.UUID id) {
        userRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/vast-access")
    public ResponseEntity<Void> toggleVastAccess(@PathVariable UUID id, @RequestBody Map<String, Boolean> body,
                                                  org.springframework.security.core.Authentication auth) {
        if (auth == null || !"draygen".equals(auth.getName())) {
            return ResponseEntity.status(403).build();
        }
        User user = userRepository.findById(id).orElse(null);
        if (user == null) return ResponseEntity.notFound().build();
        Set<String> roles = new HashSet<>(user.getRoles() != null ? user.getRoles() : Set.of());
        if (Boolean.TRUE.equals(body.get("enabled"))) {
            roles.add("ROLE_VAST");
        } else {
            roles.remove("ROLE_VAST");
        }
        user.setRoles(roles);
        userRepository.save(user);
        return ResponseEntity.<Void>noContent().build();
    }
}
