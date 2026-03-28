package me.flexov.anonymouschat.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "room_settings")
@Data
@NoArgsConstructor
public class RoomSettings {

    @Id
    private String roomId;

    private boolean allowOthers = true;   // разрешать ли вход другим пользователям
    private boolean autoCleanup = false;  // автоочистка сообщений (каждый час)
    private String adminSessionId;        // sessionId создателя комнаты

    public RoomSettings(String roomId, String adminSessionId) {
        this.roomId = roomId;
        this.adminSessionId = adminSessionId;
        this.allowOthers = true;
        this.autoCleanup = false;
    }
}
