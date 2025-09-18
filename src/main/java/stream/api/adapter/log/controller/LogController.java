package stream.api.adapter.log.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import stream.api.adapter.log.dao.entity.LogEntity;
import stream.api.adapter.log.model.response.LogResponse;
import stream.api.adapter.log.service.LogReaderService;
import stream.api.adapter.log.service.LogSaverService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.springframework.boot.logging.LoggingSystemProperty.LOG_PATH;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/filter")
public class LogController {

    private final LogReaderService service;

    @GetMapping("/{file}/{serial}")
    public ResponseEntity<LogResponse> getLogByFileAndSerial(@PathVariable String file,
                                                             @PathVariable String serial) {
        String path = "C:/Users/user/Desktop/stream/" + file;
        return ResponseEntity.ok(service.findByOrdinatorOrReference(path, serial));
    }

    @GetMapping("/files")
    public ResponseEntity<List<String>> getLogFiles() {
        try {
            Path logDir = Paths.get("C:/Users/user/Desktop/stream");
            List<String> files = Files.list(logDir)
                    .filter(p -> p.toString().endsWith(".log"))
                    .map(p -> p.getFileName().toString())
                    .toList();
            return ResponseEntity.ok(files);
        } catch (IOException e) {
            return ResponseEntity.status(500).body(List.of());
        }
    }
}
