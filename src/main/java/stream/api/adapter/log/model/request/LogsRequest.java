package stream.api.adapter.log.model.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Data
@AllArgsConstructor
@ToString
@EqualsAndHashCode
public class LogsRequest {

    String file;
    String uniqueData;
    int page;
    int size;
}
