package stream.api.adapter.log.dao.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "err_log")
public class LogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String timestamp;
    private String threadId;
    private String description;
}
