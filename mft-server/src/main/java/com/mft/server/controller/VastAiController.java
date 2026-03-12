package com.mft.server.controller;

import com.mft.server.model.SystemConfig;
import com.mft.server.repository.SystemConfigRepository;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/vast")
@PreAuthorize("hasAnyRole('ADMIN', 'VAST')")
public class VastAiController {

    private static final String VAST_API = "https://console.vast.ai/api/v0";

    private final SystemConfigRepository configRepository;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public VastAiController(SystemConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    private String apiKey() {
        return configRepository.findById("vast_api_key")
                .map(SystemConfig::getConfigValue)
                .filter(k -> k != null && !k.isBlank())
                .orElseThrow(() -> new IllegalStateException("vast_api_key not configured — set it in Core Settings"));
    }

    private HttpRequest.Builder vastReq(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(VAST_API + path))
                .header("Authorization", "Bearer " + apiKey())
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(20));
    }

    private ResponseEntity<String> proxy(HttpResponse<String> resp) {
        return ResponseEntity.status(resp.statusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(resp.body());
    }

    @GetMapping("/instances")
    public ResponseEntity<String> listInstances() throws Exception {
        var resp = http.send(vastReq("/instances/").GET().build(), HttpResponse.BodyHandlers.ofString());
        return proxy(resp);
    }

    @GetMapping("/offers")
    public ResponseEntity<String> searchOffers(
            @RequestParam(required = false) Double max_dph,
            @RequestParam(required = false) Double min_gpu_ram,
            @RequestParam(required = false) String gpu_name) throws Exception {

        StringBuilder q = new StringBuilder("{");
        q.append("\"type\":\"ondemand\",");
        q.append("\"verified\":{\"eq\":true},");
        q.append("\"rentable\":{\"eq\":true},");
        q.append("\"order\":[[\"dph_total\",\"asc\"]],");
        q.append("\"limit\":30");
        if (max_dph != null) q.append(",\"dph_total\":{\"lte\":").append(max_dph).append("}");
        if (min_gpu_ram != null) q.append(",\"gpu_ram\":{\"gte\":").append(min_gpu_ram * 1024).append("}");
        if (gpu_name != null && !gpu_name.isBlank()) q.append(",\"gpu_name\":{\"eq\":").append(jsonStr(gpu_name)).append("}");
        q.append("}");

        var resp = http.send(
                vastReq("/bundles/").POST(HttpRequest.BodyPublishers.ofString(q.toString())).build(),
                HttpResponse.BodyHandlers.ofString());
        return proxy(resp);
    }

    private static final String SSH_PUBKEY =
        "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAACAQCQm6lKaPtMgVp4EqOaZm8DDVVPamCAe65GTFFXIY3sC1PnfyLt8gLkaqXyLmkVe9WjJLm62hVZrUL2B+KfBGJANx3s5XfS8pk38h9NuSLcpr07bQEoZ8echNm6kEULfKMt2K28SjW8UXWXMMda/imDJOR0jrX/WM6LJpF/cS//nZdaF5IobFgYcMdvMMX4ZmnFPBGqSFJgi1AtXG28vu9so9KMmF2Q7skqbXMHrgvLtBRrQJeV/emJ6UQZop4A8H9dp529AGIJm1vyuMPVW3wcqqt3jBRwp3s7pOgrKiEPCjraDN7lOxiWm5fQvT+xYdyC79xEDFGjMos1xFn7qFV6OYkQJ12n+nwTO9lYhutUxSSF+4HHtUhmWKQLpHWAPL5hvwuZsuWrA8YxFUsYSgMfvg96CdiglIyJB5uQgIH6lYM0x4Ndet/KBPI2LJxpDz8jN7Xgogd+rno0uxEffkIF6BlhP7cMJXDxHP/E1kjA9lydDxnDRfc1cQB4RcwL+SCWVWhMjRnRBAwrHTWyV5Ri096O1pjpFrd/PGi8SZi3B5ZYxJU32zryJG2WtAFZd3PwexPg5Ptjsa2Z20zcgPC9YsmzzzYb1DcE/L341VJhbUK+skwwTj51utcYsVbBQxcq8XFVONXYeYzKfhk0Sut092XL8IQrsqumW1V1C6mdHw==";

    private static final String ONSTART_SCRIPT =
        "#!/bin/bash\n" +
        "mkdir -p /root/.ssh && chmod 700 /root/.ssh\n" +
        "echo '" + SSH_PUBKEY + "' >> /root/.ssh/authorized_keys\n" +
        "chmod 600 /root/.ssh/authorized_keys\n" +
        "echo '[syncforge] SSH key installed' >> /var/log/syncforge-setup.log\n";

    private ResponseEntity<String> draygenOnly(org.springframework.security.core.Authentication auth) {
        if (auth == null || !"draygen".equals(auth.getName())) {
            return ResponseEntity.status(403).body("{\"error\":\"Reserved for authorized operator only\"}");
        }
        return null;
    }

    @PostMapping("/deploy")
    public ResponseEntity<String> deploy(@RequestBody Map<String, Object> req,
                                         org.springframework.security.core.Authentication auth) throws Exception {
        ResponseEntity<String> denied = draygenOnly(auth);
        if (denied != null) return denied;
        Object offerId = req.get("offer_id");
        int diskGb = parseIntOrDefault(req.getOrDefault("disk_gb", 40), 40);
        // Build JSON manually to avoid pulling in Jackson for this one case
        String onstart = ONSTART_SCRIPT.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        String body = "{\"image\":\"ubuntu:22.04\",\"disk\":" + diskGb +
                ",\"runtype\":\"ssh_direct\",\"label\":\"syncforge\"" +
                ",\"onstart\":\"" + onstart + "\"}";
        var resp = http.send(
                vastReq("/asks/" + offerId + "/").PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        return proxy(resp);
    }

    @DeleteMapping("/instances/{id}")
    public ResponseEntity<String> destroyInstance(@PathVariable long id,
                                                   org.springframework.security.core.Authentication auth) throws Exception {
        ResponseEntity<String> denied = draygenOnly(auth);
        if (denied != null) return denied;
        var resp = http.send(vastReq("/instances/" + id + "/").DELETE().build(), HttpResponse.BodyHandlers.ofString());
        return proxy(resp);
    }

    @PostMapping("/instances/{id}/start")
    public ResponseEntity<String> startInstance(@PathVariable long id,
                                                 org.springframework.security.core.Authentication auth) throws Exception {
        ResponseEntity<String> denied = draygenOnly(auth);
        if (denied != null) return denied;
        var resp = http.send(
                vastReq("/instances/" + id + "/").PUT(HttpRequest.BodyPublishers.ofString("{\"state\":\"running\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        return proxy(resp);
    }

    @PostMapping("/instances/{id}/stop")
    public ResponseEntity<String> stopInstance(@PathVariable long id,
                                                org.springframework.security.core.Authentication auth) throws Exception {
        ResponseEntity<String> denied = draygenOnly(auth);
        if (denied != null) return denied;
        var resp = http.send(
                vastReq("/instances/" + id + "/").PUT(HttpRequest.BodyPublishers.ofString("{\"state\":\"stopped\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        return proxy(resp);
    }

    @PostMapping("/network/ping")
    public Map<String, Object> pingHost(@RequestBody Map<String, String> req) {
        String host = req.getOrDefault("host", "").trim();
        if (host.isBlank() || !host.matches("[a-zA-Z0-9._-]{1,253}")) {
            return Map.of("ok", false, "error", "Invalid host — only hostnames and IPs are accepted");
        }
        // Try native ping first (available in most Linux containers)
        try {
            ProcessBuilder pb = new ProcessBuilder("ping", "-c", "4", "-W", "2", host);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            byte[] out = p.getInputStream().readAllBytes();
            boolean done = p.waitFor(12, TimeUnit.SECONDS);
            if (!done) p.destroyForcibly();
            String output = new String(out);
            int exit = done ? p.exitValue() : -1;
            return Map.of("ok", exit == 0, "output", output);
        } catch (Exception pingEx) {
            // Fallback: Java InetAddress reachability
            try {
                InetAddress addr = InetAddress.getByName(host);
                long t0 = System.currentTimeMillis();
                boolean reachable = addr.isReachable(4000);
                long ms = System.currentTimeMillis() - t0;
                String msg = reachable
                        ? "Host " + addr.getHostAddress() + " is reachable (" + ms + " ms)"
                        : "Host " + host + " is not reachable (timeout after 4s)";
                return Map.of("ok", reachable, "output", msg);
            } catch (Exception ex) {
                return Map.of("ok", false, "error", "Cannot resolve " + host + ": " + ex.getMessage());
            }
        }
    }

    private String jsonStr(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private int parseIntOrDefault(Object val, int def) {
        try { return Integer.parseInt(String.valueOf(val)); } catch (Exception e) { return def; }
    }
}
