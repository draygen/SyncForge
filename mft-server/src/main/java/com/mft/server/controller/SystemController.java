package com.mft.server.controller;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/system")
public class SystemController {
    public static boolean maintenanceMode = false;
    private final com.mft.server.service.ActivityService activityService;
    private final com.mft.server.service.FileStorageService fileStorageService;
    
    public SystemController(com.mft.server.service.ActivityService activityService, 
                            com.mft.server.service.FileStorageService fileStorageService) {
        this.activityService = activityService;
        this.fileStorageService = fileStorageService;
    }

    @PostMapping("/purge")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public Map<String, String> purgeData() {
        fileStorageService.purgeAllData();
        activityService.purge();
        return Map.of("status", "SUCCESS", "message", "Temple Cleansed. All artifacts and karma records extinguished.");
    }

    @GetMapping("/public/health")
    public Map<String, Object> health() {
        long totalFiles = 0;
        long totalBytes = 0;
        try {
            var files = fileStorageService.getAllFiles();
            totalFiles = files.stream().filter(f -> "COMPLETED".equals(f.getStatus())).count();
            totalBytes = files.stream().filter(f -> "COMPLETED".equals(f.getStatus()))
                    .mapToLong(f -> f.getTotalSize() != null ? f.getTotalSize() : 0L).sum();
        } catch (Exception ignored) {}
        return Map.of(
            "status", "UP",
            "maintenance", maintenanceMode,
            "totalFiles", totalFiles,
            "totalStorageBytes", totalBytes,
            "version", "1.0.5"
        );
    }

    @GetMapping("/ping")
    public Map<String, Object> ping(org.springframework.security.core.Authentication auth, jakarta.servlet.http.HttpServletRequest request) {
        boolean isAdmin = false;
        if (auth != null) {
            String clientIp = resolveClientIp(request);
            activityService.pingUser(auth.getName(), clientIp);
            isAdmin = auth.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        }
        return Map.of("maintenance", maintenanceMode, "isAdmin", isAdmin);
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
