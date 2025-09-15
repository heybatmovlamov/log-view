package stream.api.adapter.log.dao.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import stream.api.adapter.log.dao.entity.LogEntity;

public interface LogRepository extends JpaRepository<LogEntity,Integer> {
}
