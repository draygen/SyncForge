package com.mft.server.model;

import java.time.LocalDateTime;

public class ActivityEvent {
    private String type; // LOGIN, UPLOAD_INIT, UPLOAD_CHUNK, UPLOAD_COMPLETE, CONFIG_CHANGE
    private String user;
    private String detail;
    private LocalDateTime timestamp = LocalDateTime.now();

    public ActivityEvent(String type, String user, String detail) {
        this.type = type;
        this.user = user;
        this.detail = detail;
    }

    public String getType() { return type; }
    public String getUser() { return user; }
    public String getDetail() { return detail; }
    public LocalDateTime getTimestamp() { return timestamp; }
}
