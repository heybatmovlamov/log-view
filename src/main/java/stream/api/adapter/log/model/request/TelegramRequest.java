package stream.api.adapter.log.model.request;

import org.springframework.web.multipart.MultipartFile;

public record TelegramRequest(String message, MultipartFile file) {

}
