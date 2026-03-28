package me.flexov.anonymouschat.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class MessageDto {
    private String senderId;
    private String content;
    private LocalDateTime timestamp;
    private String clientMessageId;

    public MessageDto(String senderId, String content, LocalDateTime timestamp) {
        this.senderId = senderId;
        this.content = content;
        this.timestamp = timestamp;
    }
}
