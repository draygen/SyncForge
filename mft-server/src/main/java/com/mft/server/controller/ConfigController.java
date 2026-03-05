package com.mft.server.controller;

import com.mft.server.model.SystemConfig;
import com.mft.server.repository.SystemConfigRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/config")
public class ConfigController {

    private final com.mft.server.repository.SystemConfigRepository configRepository;
    private final com.mft.server.service.ActivityService activityService;

    public ConfigController(com.mft.server.repository.SystemConfigRepository configRepository, 
                            com.mft.server.service.ActivityService activityService) {
        this.configRepository = configRepository;
        this.activityService = activityService;
    }

    @GetMapping
    public List<com.mft.server.model.SystemConfig> listConfig() {
        return configRepository.findAll();
    }

    @PostMapping
    public com.mft.server.model.SystemConfig saveConfig(@RequestBody com.mft.server.model.SystemConfig config, org.springframework.security.core.Authentication auth) {
        activityService.log("CONFIG_CHANGE", auth.getName(), "Updated key: " + config.getConfigKey());
        return configRepository.save(config);
    }

    @PostMapping("/maintenance")
    public java.util.Map<String, Object> toggleMaintenance(org.springframework.security.core.Authentication auth) {
        com.mft.server.controller.SystemController.maintenanceMode = !com.mft.server.controller.SystemController.maintenanceMode;
        activityService.log("MAINTENANCE", auth.getName(), "Maintenance toggled: " + com.mft.server.controller.SystemController.maintenanceMode);
        return java.util.Map.of("maintenance", com.mft.server.controller.SystemController.maintenanceMode);
    }
}
