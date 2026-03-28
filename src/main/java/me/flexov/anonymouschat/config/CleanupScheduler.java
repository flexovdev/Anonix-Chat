package me.flexov.anonymouschat.config;

import me.flexov.anonymouschat.service.ChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
public class CleanupScheduler {

    @Autowired
    private ChatService chatService;

    @Scheduled(fixedRateString = "${app.chat.cleanup-messages-ms:300000}")
    public void cleanOldMessages() {
        chatService.cleanOldMessages();
    }

    @Scheduled(fixedRateString = "${app.chat.cleanup-sessions-ms:300000}")
    public void cleanInactiveSessions() {
        chatService.cleanInactiveSessions();
    }

    @Scheduled(fixedRateString = "${app.chat.cleanup-auto-ms:300000}")
    public void scheduledAutoCleanup() {
        chatService.scheduledAutoCleanup();
    }
}
