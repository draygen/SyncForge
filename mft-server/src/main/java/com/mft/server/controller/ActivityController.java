package com.mft.server.controller;

import com.mft.server.model.ActivityEvent;
import com.mft.server.service.ActivityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/activity")
public class ActivityController {

    private final ActivityService activityService;

    public ActivityController(ActivityService activityService) {
        this.activityService = activityService;
    }

    @GetMapping(value = "/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter streamEvents() {
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(3600000L); // 1 hour timeout
        
        // Return existing events first
        try {
            for (com.mft.server.model.ActivityEvent event : activityService.getRecentEvents()) {
                emitter.send(event);
            }
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        
        // In a real app, you'd use a Pub/Sub or Observer pattern here.
        // For this high-performance prototype, we will return the current state 
        // and let the client handle the rest, or implement a simple listener.
        return emitter;
    }

    @GetMapping
    public List<com.mft.server.model.ActivityEvent> getEvents() {
        return activityService.getRecentEvents();
    }

    @GetMapping("/active-users")
    public List<String> getActiveUsers() {
        return activityService.getActiveUsers();
    }
}
