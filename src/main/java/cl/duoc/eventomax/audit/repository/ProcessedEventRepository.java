package cl.duoc.eventomax.audit.repository;

import cl.duoc.eventomax.audit.model.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {
}
