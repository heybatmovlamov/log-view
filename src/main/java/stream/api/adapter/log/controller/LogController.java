package stream.api.adapter.log.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import stream.api.adapter.log.dao.entity.LogEntity;
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

    @GetMapping("/{serial}")
    public void getLogSaverService(@PathVariable String serial) {
        service.findByOrdinatorOrReference("C:/Users/user/Desktop/stream.log",serial);
    }
}
