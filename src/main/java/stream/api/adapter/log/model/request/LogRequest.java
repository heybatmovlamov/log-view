package stream.api.adapter.log.model.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.Objects;

@Data
@Builder
public class LogRequest {
    private String timestamp;
    private String threadId;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LogRequest)) return false;
        LogRequest that = (LogRequest) o;
        return Objects.equals(threadId, that.threadId); // yalnız threadId yoxlanır
    }

    @Override
    public int hashCode() {
        return Objects.hash(threadId);
    }
}
