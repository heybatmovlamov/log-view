package stream.api.adapter.log.model.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
@Builder
public class LogResponse {

    private List<String> billList;
    private List<String> register;
    private List<String> pay;
}
