package stream.api.adapter.log.model.request;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LogRequest {

    private String timestamp;
    private String threadId;
}
