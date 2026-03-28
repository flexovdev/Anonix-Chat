package me.flexov.anonymouschat.model;  // или ваш пакет

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "messages")
@Data
@NoArgsConstructor
public class MessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String roomId;

    @Column
    private String senderId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    @Column
    private LocalDateTime expiresAt;

    public MessageEntity(String roomId, String senderId, String content, LocalDateTime timestamp) {
        this.roomId = roomId;
        this.senderId = senderId;
        this.content = content;
        this.timestamp = timestamp;
    }
}
