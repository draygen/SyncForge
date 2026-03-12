package com.mft.server.controller;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/me")
public class ProfileController {

    private final com.mft.server.service.ActivityService activityService;
    private final com.mft.server.repository.UserRepository userRepository;

    public ProfileController(
            com.mft.server.service.ActivityService activityService,
            com.mft.server.repository.UserRepository userRepository
    ) {
        this.activityService = activityService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public Map<String, Object> getProfile(org.springframework.security.core.Authentication authentication, jakarta.servlet.http.HttpServletRequest request) {
        String clientIp = resolveClientIp(request);

        userRepository.findByUsername(authentication.getName()).ifPresent(user -> {
            user.setLastLoginAt(LocalDateTime.now());
            user.setLastLoginIp(clientIp);
            userRepository.save(user);
        });

        activityService.log("LOGIN", authentication.getName(), "IP: " + clientIp);

        return Map.of(
            "username", authentication.getName(),
            "roles", authentication.getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                .collect(java.util.stream.Collectors.toList())
        );
    }

    private String resolveClientIp(jakarta.servlet.http.HttpServletRequest request) {
        String clientIp = firstIp(
                request.getHeader("CF-Connecting-IP"),
                request.getHeader("True-Client-IP"),
                request.getHeader("X-Forwarded-For"),
                request.getHeader("X-Real-IP")
        );
        if (clientIp == null || clientIp.isEmpty()) {
            return request.getRemoteAddr();
        }
        return clientIp;
    }

    private String firstIp(String... candidates) {
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            return candidate.split(",")[0].trim();
        }
        return null;
    }
}
