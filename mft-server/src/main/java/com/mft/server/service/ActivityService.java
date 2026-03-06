package com.mft.server.service;

import com.mft.server.model.ActivityEvent;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class ActivityService {
    private final List<ActivityEvent> events = Collections.synchronizedList(new LinkedList<>());
    private static final int MAX_EVENTS = 50;
    
    private final Map<String, LocalDateTime> activeUsers = new ConcurrentHashMap<>();
    private final List<org.springframework.web.servlet.mvc.method.annotation.SseEmitter> emitters = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void registerEmitter(org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter) {
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
    }

    public void log(String type, String user, String detail) {
        ActivityEvent event = new ActivityEvent(type, user, detail);
        events.add(0, event);
        if (events.size() > MAX_EVENTS) {
            events.remove(events.size() - 1);
        }
        
        // Notify emitters
        for (org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter : emitters) {
            try {
                emitter.send(event);
            } catch (Exception e) {
                emitters.remove(emitter);
            }
        }
    }

    public List<ActivityEvent> getRecentEvents() {
        return events;
    }
    
    public void pingUser(String username, String ip) {
        activeUsers.put(username + " (" + ip + ")", LocalDateTime.now());
    }

    public void purge() {
        events.clear();
        activeUsers.clear();
    }
    
    public List<String> getActiveUsers() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(10); // 10 sec timeout
        return activeUsers.entrySet().stream()
                .filter(e -> e.getValue().isAfter(cutoff))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }
}
