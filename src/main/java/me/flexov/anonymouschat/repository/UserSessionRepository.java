package me.flexov.anonymouschat.repository;

import me.flexov.anonymouschat.model.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserSessionRepository extends JpaRepository<UserSession, String> {

    Optional<UserSession> findBySessionId(String sessionId);

    List<UserSession> findByRoomId(String roomId);

    List<UserSession> findByLastActivityBefore(LocalDateTime cutoff);

    @Modifying
    @Transactional
    @Query("DELETE FROM UserSession u WHERE u.lastActivity < :cutoff")
    void deleteInactiveSessions(@Param("cutoff") LocalDateTime cutoff);
}
