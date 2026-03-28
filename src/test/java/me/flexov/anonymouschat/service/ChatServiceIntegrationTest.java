package me.flexov.anonymouschat.service;

import me.flexov.anonymouschat.model.UserSession;
import me.flexov.anonymouschat.repository.MessageRepository;
import me.flexov.anonymouschat.repository.RoomSettingsRepository;
import me.flexov.anonymouschat.repository.UserSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ChatServiceIntegrationTest {

    @Autowired
    private ChatService chatService;

    @Autowired
    private UserSessionRepository userSessionRepository;

    @Autowired
    private RoomSettingsRepository roomSettingsRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Test
    void createSessionAssignsUniqueAliasesAndMarksFirstUserAsAdmin() {
        String roomId = "secure_room_01";

        UserSession first = chatService.createSession(roomId);
        UserSession second = chatService.createSession(roomId);

        assertNotEquals(first.getDisplayAlias(), second.getDisplayAlias());
        assertTrue(chatService.isRoomAdmin(first.getSessionId(), roomId));
        assertFalse(chatService.isRoomAdmin(second.getSessionId(), roomId));

        messageRepository.deleteAll();
        userSessionRepository.deleteAll();
        roomSettingsRepository.deleteAll();
    }

    @Test
    void updateRoomSettingsRejectsNonAdminSession() {
        String roomId = "secure_room_02";

        UserSession admin = chatService.createSession(roomId);
        UserSession participant = chatService.createSession(roomId);

        assertThrows(SecurityException.class, () ->
                chatService.updateRoomSettings(roomId, participant.getSessionId(), false, true)
        );

        assertTrue(chatService.updateRoomSettings(roomId, admin.getSessionId(), false, true).isAutoCleanup());
    }
}
