package me.flexov.anonymouschat.service;

import me.flexov.anonymouschat.model.MessageEntity;
import me.flexov.anonymouschat.model.RoomSettings;
import me.flexov.anonymouschat.model.UserSession;
import me.flexov.anonymouschat.repository.MessageRepository;
import me.flexov.anonymouschat.repository.RoomSettingsRepository;
import me.flexov.anonymouschat.repository.UserSessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class ChatService {

    private static final Pattern ROOM_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{12,64}$");
    private static final Pattern CLIENT_MESSAGE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{16,64}$");
    private static final String EVENT_PARTICIPANT_JOINED = "participant_joined";
    private static final String EVENT_PARTICIPANT_LEFT = "participant_left";
    private static final String EVENT_INVITE_COPIED = "invite_copied";
    private static final String EVENT_INVITE_SHARED = "invite_shared";
    private static final String[] ALIAS_PREFIXES = {
            "Silent", "Velvet", "Polar", "Mellow", "Silver", "Lunar", "Cloud", "Icy",
            "Quartz", "Neon", "Soft", "Nova", "Ivory", "Midnight", "Aero", "Hidden"
    };
    private static final String[] ALIAS_SUFFIXES = {
            "Fox", "Wave", "Pine", "Echo", "Otter", "Falcon", "Cloud", "Comet",
            "Pebble", "Sparrow", "River", "Orbit", "Cedar", "Finch", "Bloom", "Drift"
    };

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private UserSessionRepository userSessionRepository;

    @Autowired
    private RoomSettingsRepository roomSettingsRepository;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Value("${app.chat.persist-history:false}")
    private boolean persistHistory;

    @Value("${app.chat.session-lifetime-minutes:30}")
    private long sessionLifetimeMinutes;

    @Value("${app.chat.max-message-length:4096}")
    private int maxMessageLength;

    private final Map<String, Set<String>> activeSessions = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();

    public String createSecureRoomId() {
        byte[] bytes = new byte[18];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public boolean isValidRoomId(String roomId) {
        return roomId != null && ROOM_ID_PATTERN.matcher(roomId.trim()).matches();
    }

    public String normalizeRoomId(String roomId) {
        if (roomId == null) {
            throw new IllegalArgumentException("Room ID is required");
        }

        String normalized = roomId.trim();
        if (!isValidRoomId(normalized)) {
            throw new IllegalArgumentException("Room ID must be 12-64 characters and use only letters, digits, '-' or '_'");
        }

        return normalized;
    }

    public boolean isValidClientMessageId(String clientMessageId) {
        return clientMessageId != null && CLIENT_MESSAGE_ID_PATTERN.matcher(clientMessageId).matches();
    }

    public int getMaxMessageLength() {
        return maxMessageLength;
    }

    @Transactional
    public MessageEntity saveMessage(String roomId, String content) {
        String normalizedRoomId = normalizeRoomId(roomId);
        MessageEntity message = new MessageEntity(normalizedRoomId, null, content, LocalDateTime.now());
        message.setExpiresAt(LocalDateTime.now().plusMinutes(sessionLifetimeMinutes));

        if (!persistHistory) {
            return message;
        }

        return messageRepository.save(message);
    }

    public List<MessageEntity> getRoomHistory(String roomId, int limit) {
        if (!persistHistory) {
            return Collections.emptyList();
        }

        String normalizedRoomId = normalizeRoomId(roomId);
        List<MessageEntity> messages = messageRepository.findByRoomIdOrderByTimestampDesc(normalizedRoomId);
        if (messages.size() > limit) {
            return messages.subList(0, limit);
        }
        return messages;
    }

    @Transactional
    public UserSession createSession(String roomId) {
        String normalizedRoomId = normalizeRoomId(roomId);
        Optional<RoomSettings> settingsOpt = roomSettingsRepository.findByRoomId(normalizedRoomId);
        if (settingsOpt.isPresent()) {
            RoomSettings settings = settingsOpt.get();
            Set<String> active = activeSessions.getOrDefault(normalizedRoomId, Collections.emptySet());
            if (!settings.isAllowOthers() && !active.isEmpty()) {
                throw new IllegalStateException("Room is closed for new participants");
            }
        }

        UserSession session = new UserSession();
        session.setSessionId(UUID.randomUUID().toString());
        session.setRoomId(normalizedRoomId);
        session.setLastActivity(LocalDateTime.now());
        session.setDisplayAlias(generateUniqueAlias(normalizedRoomId));
        session.setExpiresAt(LocalDateTime.now().plusMinutes(sessionLifetimeMinutes));
        userSessionRepository.save(session);

        if (roomSettingsRepository.findByRoomId(normalizedRoomId).isEmpty()) {
            RoomSettings settings = new RoomSettings(normalizedRoomId, session.getSessionId());
            roomSettingsRepository.save(settings);
        }

        activeSessions.computeIfAbsent(normalizedRoomId, key -> ConcurrentHashMap.newKeySet()).add(session.getSessionId());
        sendSystemMessage(normalizedRoomId, session.getDisplayAlias() + " joined the room", EVENT_PARTICIPANT_JOINED);
        return session;
    }

    public boolean canJoinRoom(String roomId) {
        String normalizedRoomId = normalizeRoomId(roomId);
        Optional<RoomSettings> settingsOpt = roomSettingsRepository.findByRoomId(normalizedRoomId);
        if (settingsOpt.isEmpty()) {
            return true;
        }

        RoomSettings settings = settingsOpt.get();
        if (!settings.isAllowOthers()) {
            Set<String> active = activeSessions.getOrDefault(normalizedRoomId, Collections.emptySet());
            return active.isEmpty();
        }

        return true;
    }

    public RoomSettings getRoomSettings(String roomId) {
        return roomSettingsRepository.findByRoomId(normalizeRoomId(roomId)).orElse(null);
    }

    public boolean isRoomAdmin(String sessionId, String roomId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }

        RoomSettings settings = getRoomSettings(roomId);
        return settings != null
                && settings.getAdminSessionId() != null
                && settings.getAdminSessionId().equals(sessionId)
                && isSessionValidForRoom(sessionId, roomId);
    }

    @Transactional
    public RoomSettings updateRoomSettings(String roomId, String adminSessionId, boolean allowOthers, boolean autoCleanup) {
        String normalizedRoomId = normalizeRoomId(roomId);
        RoomSettings settings = roomSettingsRepository.findByRoomId(normalizedRoomId)
                .orElseThrow(() -> new IllegalArgumentException("Room not found"));
        if (!isSessionValidForRoom(adminSessionId, normalizedRoomId) || !settings.getAdminSessionId().equals(adminSessionId)) {
            throw new SecurityException("Only the room owner can change settings");
        }
        settings.setAllowOthers(allowOthers);
        settings.setAutoCleanup(autoCleanup);
        return roomSettingsRepository.save(settings);
    }

    @Transactional
    public void removeSession(String sessionId) {
        Optional<UserSession> sessionOpt = userSessionRepository.findById(sessionId);
        if (sessionOpt.isEmpty()) {
            return;
        }

        UserSession session = sessionOpt.get();
        String roomId = session.getRoomId();
        userSessionRepository.delete(session);

        Set<String> active = activeSessions.get(roomId);
        if (active != null) {
            active.remove(sessionId);
            if (active.isEmpty()) {
                activeSessions.remove(roomId);
            }
        }

        sendSystemMessage(roomId, session.getDisplayAlias() + " left the room", EVENT_PARTICIPANT_LEFT);
        deleteRoomArtifactsIfEmpty(roomId);
    }

    @Transactional
    public void notifyRoomActivity(String roomId, String sessionId, String action) {
        String normalizedRoomId = normalizeRoomId(roomId);
        if (!isSessionValidForRoom(sessionId, normalizedRoomId)) {
            throw new IllegalArgumentException("Invalid session");
        }

        String alias = getSessionAlias(sessionId, normalizedRoomId);

        updateSessionActivity(sessionId);

        if ("copied_invite_link".equals(action)) {
            sendSystemMessage(normalizedRoomId, alias + " copied the invite link", EVENT_INVITE_COPIED);
            return;
        }

        if ("shared_invite_link".equals(action)) {
            sendSystemMessage(normalizedRoomId, alias + " shared the invite link", EVENT_INVITE_SHARED);
            return;
        }

        throw new IllegalArgumentException("Unsupported activity");
    }

    @Transactional
    public void updateSessionActivity(String sessionId) {
        userSessionRepository.findById(sessionId).ifPresent(session -> {
            session.setLastActivity(LocalDateTime.now());
            session.setExpiresAt(LocalDateTime.now().plusMinutes(sessionLifetimeMinutes));
            userSessionRepository.save(session);
        });
    }

    public boolean isSessionValid(String sessionId) {
        Optional<UserSession> session = userSessionRepository.findById(sessionId);
        return session.isPresent() && session.get().getExpiresAt().isAfter(LocalDateTime.now());
    }

    public boolean isSessionValidForRoom(String sessionId, String roomId) {
        if (sessionId == null || roomId == null) {
            return false;
        }

        Optional<UserSession> sessionOpt = userSessionRepository.findById(sessionId);
        if (sessionOpt.isEmpty()) {
            return false;
        }

        UserSession session = sessionOpt.get();
        return session.getExpiresAt() != null
                && session.getExpiresAt().isAfter(LocalDateTime.now())
                && normalizeRoomId(roomId).equals(session.getRoomId());
    }

    public String getSessionAlias(String sessionId, String roomId) {
        String normalizedRoomId = normalizeRoomId(roomId);
        return userSessionRepository.findById(sessionId)
                .filter(session -> normalizedRoomId.equals(session.getRoomId()))
                .map(UserSession::getDisplayAlias)
                .orElseThrow(() -> new IllegalArgumentException("Invalid session"));
    }

    private void sendSystemMessage(String roomId, String text) {
        sendSystemMessage(roomId, text, null);
    }

    private void sendSystemMessage(String roomId, String text, String event) {
        Map<String, Object> systemMsg = new HashMap<>();
        systemMsg.put("type", "system");
        systemMsg.put("content", text);
        systemMsg.put("timestamp", LocalDateTime.now().toString());
        if (event != null && !event.isBlank()) {
            systemMsg.put("event", event);
        }
        messagingTemplate.convertAndSend("/topic/room/" + roomId, systemMsg);
    }

    private String generateUniqueAlias(String roomId) {
        List<UserSession> existingSessions = userSessionRepository.findByRoomId(roomId);
        for (int attempt = 0; attempt < 32; attempt++) {
            String alias = ALIAS_PREFIXES[secureRandom.nextInt(ALIAS_PREFIXES.length)]
                    + " "
                    + ALIAS_SUFFIXES[secureRandom.nextInt(ALIAS_SUFFIXES.length)]
                    + " "
                    + (100 + secureRandom.nextInt(900));

            boolean taken = existingSessions.stream()
                    .map(UserSession::getDisplayAlias)
                    .anyMatch(alias::equals);
            if (!taken) {
                return alias;
            }
        }

        return "Guest " + (1000 + secureRandom.nextInt(9000));
    }

    private void deleteRoomArtifactsIfEmpty(String roomId) {
        Set<String> active = activeSessions.getOrDefault(roomId, Collections.emptySet());
        if (!active.isEmpty()) {
            return;
        }

        roomSettingsRepository.deleteByRoomId(roomId);
        messageRepository.deleteByRoomId(roomId);
    }

    @Transactional
    public void cleanOldMessages() {
        if (!persistHistory) {
            return;
        }

        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(sessionLifetimeMinutes);
        messageRepository.deleteOlderThan(cutoff);
    }

    @Transactional
    public void cleanInactiveSessions() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(sessionLifetimeMinutes);
        List<UserSession> staleSessions = userSessionRepository.findByLastActivityBefore(cutoff);
        for (UserSession session : staleSessions) {
            removeSession(session.getSessionId());
        }
    }

    @Transactional
    public void scheduledAutoCleanup() {
        if (!persistHistory) {
            return;
        }

        List<RoomSettings> allSettings = roomSettingsRepository.findAll();
        for (RoomSettings settings : allSettings) {
            if (settings.isAutoCleanup()) {
                messageRepository.deleteByRoomId(settings.getRoomId());
            }
        }
    }
}
