package me.flexov.anonymouschat.controller;

import me.flexov.anonymouschat.model.MessageDto;
import me.flexov.anonymouschat.service.ChatService;
import me.flexov.anonymouschat.service.RateLimitService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebSocketControllerTest {

    @Mock
    private ChatService chatService;

    @Mock
    private RateLimitService rateLimitService;

    @InjectMocks
    private WebSocketController webSocketController;

    @Test
    void sendMessageAllowsExistingParticipantWhenRoomIsClosedForNewJoins() {
        String roomId = "secure_room_01";
        String sessionId = "session-001";
        String encryptedPayload = "encrypted-payload";
        String clientMessageId = "client_message_001";

        MessageDto messageDto = new MessageDto();
        messageDto.setSenderId(sessionId);
        messageDto.setContent(encryptedPayload);
        messageDto.setClientMessageId(clientMessageId);

        when(chatService.normalizeRoomId(roomId)).thenReturn(roomId);
        when(chatService.isSessionValidForRoom(sessionId, roomId)).thenReturn(true);
        when(chatService.getMaxMessageLength()).thenReturn(4096);
        when(chatService.isValidClientMessageId(clientMessageId)).thenReturn(true);
        doNothing().when(rateLimitService).assertAllowed(eq("send-message"), eq(sessionId), eq(30), any(), eq("Too many messages. Please slow down."));
        when(chatService.getSessionAlias(sessionId, roomId)).thenReturn("Silent Fox 101");

        Object response = webSocketController.sendMessage(roomId, messageDto);

        Map<?, ?> payload = assertInstanceOf(Map.class, response);
        assertEquals("message", payload.get("type"));
        assertEquals(encryptedPayload, payload.get("content"));
        assertEquals(clientMessageId, payload.get("clientMessageId"));
        assertEquals("Silent Fox 101", payload.get("alias"));
        verify(chatService).saveMessage(roomId, encryptedPayload);
        verify(chatService).updateSessionActivity(sessionId);
    }
}
