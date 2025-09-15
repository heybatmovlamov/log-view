package stream.api.adapter.log.model.response;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class InfoResponse {

    private String timestamp;
    private String threadId;
}
