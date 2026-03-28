package me.flexov.anonymouschat.controller;

import me.flexov.anonymouschat.model.MessageEntity;
import me.flexov.anonymouschat.model.RoomSettings;
import me.flexov.anonymouschat.model.UserSession;
import me.flexov.anonymouschat.service.ChatService;
import me.flexov.anonymouschat.service.RateLimitExceededException;
import me.flexov.anonymouschat.service.RateLimitService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    @Autowired
    private ChatService chatService;

    @Autowired
    private RateLimitService rateLimitService;

    @PostMapping("/room")
    public ResponseEntity<?> createRoom(HttpServletRequest request) {
        rateLimitService.assertAllowed(
                "create-room",
                extractClientKey(request),
                10,
                Duration.ofMinutes(10),
                "Too many new rooms. Please try again later."
        );

        Map<String, String> response = new HashMap<>();
        response.put("roomId", chatService.createSecureRoomId());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/room/{roomId}/history")
    public ResponseEntity<?> getHistory(@PathVariable String roomId,
                                        @RequestParam String sessionId,
                                        @RequestParam(defaultValue = "50") int limit) {
        try {
            if (!chatService.isSessionValidForRoom(sessionId, roomId)) {
                return ResponseEntity.status(403).body("Forbidden");
            }

            List<MessageEntity> history = chatService.getRoomHistory(roomId, Math.max(0, limit));
            List<Map<String, Object>> response = history.stream()
                    .map(message -> Map.<String, Object>of(
                            "content", message.getContent(),
                            "timestamp", message.getTimestamp()
                    ))
                    .toList();
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/room/{roomId}/session")
    public ResponseEntity<?> createSession(@PathVariable String roomId, HttpServletRequest request) {
        try {
            rateLimitService.assertAllowed(
                    "create-session",
                    extractClientKey(request),
                    20,
                    Duration.ofMinutes(5),
                    "Too many join attempts. Please wait a moment."
            );

            UserSession session = chatService.createSession(roomId);
            Map<String, String> response = new HashMap<>();
            response.put("sessionId", session.getSessionId());
            response.put("alias", session.getDisplayAlias());
            response.put("isAdmin", Boolean.toString(chatService.isRoomAdmin(session.getSessionId(), roomId)));
            return ResponseEntity.ok(response);
        } catch (RateLimitExceededException e) {
            return ResponseEntity.status(429).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(403).body(e.getMessage());
        }
    }

    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable String sessionId) {
        chatService.removeSession(sessionId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/session/{sessionId}/validate")
    public ResponseEntity<Map<String, Boolean>> validateSession(@PathVariable String sessionId,
                                                                @RequestParam String roomId) {
        Map<String, Boolean> response = new HashMap<>();
        response.put("valid", chatService.isSessionValidForRoom(sessionId, roomId));
        return ResponseEntity.ok(response);
    }

    @PostMapping("/room/{roomId}/activity")
    public ResponseEntity<?> notifyRoomActivity(@PathVariable String roomId,
                                                @RequestBody Map<String, String> body) {
        String sessionId = body.get("sessionId");
        String action = body.get("action");
        if (sessionId == null || sessionId.isBlank() || action == null || action.isBlank()) {
            return ResponseEntity.badRequest().body("sessionId and action are required");
        }

        try {
            rateLimitService.assertAllowed(
                    "room-activity",
                    sessionId,
                    20,
                    Duration.ofMinutes(1),
                    "Too many room activity events."
            );
            chatService.notifyRoomActivity(roomId, sessionId, action);
            return ResponseEntity.accepted().build();
        } catch (RateLimitExceededException e) {
            return ResponseEntity.status(429).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/room/{roomId}/settings")
    public ResponseEntity<?> getRoomSettings(@PathVariable String roomId,
                                             @RequestParam(required = false) String sessionId) {
        try {
            RoomSettings settings = chatService.getRoomSettings(roomId);
            if (settings == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(maskSettings(settings, chatService.isRoomAdmin(sessionId, roomId)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PutMapping("/room/{roomId}/settings")
    public ResponseEntity<?> updateRoomSettings(@PathVariable String roomId,
                                                @RequestBody Map<String, Object> body) {
        String adminSessionId = (String) body.get("adminSessionId");
        boolean allowOthers = (boolean) body.getOrDefault("allowOthers", true);
        boolean autoCleanup = (boolean) body.getOrDefault("autoCleanup", false);
        try {
            rateLimitService.assertAllowed(
                    "update-settings",
                    adminSessionId,
                    15,
                    Duration.ofMinutes(2),
                    "Too many settings updates."
            );
            RoomSettings settings = chatService.updateRoomSettings(roomId, adminSessionId, allowOthers, autoCleanup);
            return ResponseEntity.ok(maskSettings(settings, true));
        } catch (RateLimitExceededException e) {
            return ResponseEntity.status(429).body(e.getMessage());
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body("Only the room owner can change settings");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    private Map<String, Object> maskSettings(RoomSettings settings, boolean isAdmin) {
        Map<String, Object> view = new HashMap<>();
        view.put("allowOthers", settings.isAllowOthers());
        view.put("autoCleanup", settings.isAutoCleanup());
        view.put("isAdmin", isAdmin);
        return view;
    }

    private String extractClientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
