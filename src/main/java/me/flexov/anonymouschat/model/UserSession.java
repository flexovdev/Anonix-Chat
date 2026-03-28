package me.flexov.anonymouschat.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_sessions")
@Data
@NoArgsConstructor
public class UserSession {

    @Id
    private String sessionId;

    @Column(nullable = false)
    private String roomId;

    @Column(nullable = false)
    private LocalDateTime lastActivity;

    @Column(nullable = false)
    private String displayAlias;

    private LocalDateTime expiresAt;
}
