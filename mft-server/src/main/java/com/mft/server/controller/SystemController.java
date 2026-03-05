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

    @GetMapping("/ping")
    public Map<String, Object> ping(org.springframework.security.core.Authentication auth, jakarta.servlet.http.HttpServletRequest request) {
        if (auth != null) {
            String clientIp = request.getHeader("X-Forwarded-For");
            if (clientIp == null || clientIp.isEmpty()) {
                clientIp = request.getRemoteAddr();
            } else {
                clientIp = clientIp.split(",")[0].trim();
            }
            activityService.pingUser(auth.getName(), clientIp);
        }
        return Map.of("maintenance", maintenanceMode);
    }
}
