package me.flexov.anonymouschat.repository;

import me.flexov.anonymouschat.model.MessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface MessageRepository extends JpaRepository<MessageEntity, Long> {

    List<MessageEntity> findByRoomIdOrderByTimestampDesc(String roomId);

    void deleteByRoomId(String roomId);

    @Modifying
    @Transactional
    @Query("DELETE FROM MessageEntity m WHERE m.timestamp < :cutoff")
    void deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
