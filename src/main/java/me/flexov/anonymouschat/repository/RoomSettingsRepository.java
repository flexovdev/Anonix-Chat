package me.flexov.anonymouschat.repository;

import me.flexov.anonymouschat.model.RoomSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoomSettingsRepository extends JpaRepository<RoomSettings, String> {

    Optional<RoomSettings> findByRoomId(String roomId);

    void deleteByRoomId(String roomId);
}
