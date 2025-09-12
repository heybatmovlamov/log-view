package stream.api.adapter.log.entity;

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
    private String transactionId;
    private String errorName;
    private String description;
    private String adapterName;
}
