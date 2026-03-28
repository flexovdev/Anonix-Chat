package me.flexov.anonymouschat.controller;

import me.flexov.anonymouschat.model.MessageDto;
import me.flexov.anonymouschat.service.ChatService;
import me.flexov.anonymouschat.service.RateLimitExceededException;
import me.flexov.anonymouschat.service.RateLimitService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

@Controller
public class WebSocketController {

    @Autowired
    private ChatService chatService;

    @Autowired
    private RateLimitService rateLimitService;

    @MessageMapping("/chat/{roomId}")
    @SendTo("/topic/room/{roomId}")
    public Object sendMessage(@DestinationVariable String roomId, MessageDto messageDto) {
        try {
            String normalizedRoomId = chatService.normalizeRoomId(roomId);
            String sessionId = messageDto.getSenderId();

            if (!chatService.isSessionValidForRoom(sessionId, normalizedRoomId)) {
                return error("Invalid session");
            }

            String encryptedPayload = messageDto.getContent();
            if (encryptedPayload == null || encryptedPayload.isBlank()) {
                return error("Message is empty");
            }

            if (encryptedPayload.length() > chatService.getMaxMessageLength()) {
                return error("Message is too large");
            }

            if (!chatService.isValidClientMessageId(messageDto.getClientMessageId())) {
                return error("Invalid message identifier");
            }

            rateLimitService.assertAllowed(
                    "send-message",
                    sessionId,
                    30,
                    Duration.ofMinutes(1),
                    "Too many messages. Please slow down."
            );

            chatService.saveMessage(normalizedRoomId, encryptedPayload);
            chatService.updateSessionActivity(sessionId);
            String alias = chatService.getSessionAlias(sessionId, normalizedRoomId);

            return Map.of(
                    "type", "message",
                    "content", encryptedPayload,
                    "timestamp", LocalDateTime.now().toString(),
                    "clientMessageId", messageDto.getClientMessageId(),
                    "alias", alias
            );
        } catch (RateLimitExceededException e) {
            return error(e.getMessage());
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    private Map<String, String> error(String message) {
        return Map.of(
                "type", "error",
                "content", message,
                "event", "error"
        );
    }
}
