package com.mft.server.controller;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/me")
public class ProfileController {

    private final com.mft.server.service.ActivityService activityService;

    public ProfileController(com.mft.server.service.ActivityService activityService) {
        this.activityService = activityService;
    }

    @GetMapping
    public Map<String, Object> getProfile(org.springframework.security.core.Authentication authentication, jakarta.servlet.http.HttpServletRequest request) {
        String clientIp = request.getHeader("X-Forwarded-For");
        if (clientIp == null || clientIp.isEmpty()) {
            clientIp = request.getRemoteAddr();
        } else {
            // X-Forwarded-For can contain a list of IPs, take the first one
            clientIp = clientIp.split(",")[0].trim();
        }
        
        activityService.log("LOGIN", authentication.getName(), "IP: " + clientIp);
        
        return Map.of(
            "username", authentication.getName(),
            "roles", authentication.getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                .collect(java.util.stream.Collectors.toList())
        );
    }
}
