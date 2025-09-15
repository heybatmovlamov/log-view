package stream.api.adapter.log.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import stream.api.adapter.log.dao.entity.LogEntity;
import stream.api.adapter.log.service.LogReaderService;
import stream.api.adapter.log.service.LogSaverService;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class LogController {

    private final LogReaderService service;

    @GetMapping
    public void getLogSaverService() {
        service.findByOrdinatorOrReference("C:/Users/user/Desktop/stream.log", "4A10B6F7EEFD4C72A0ABF19FAD6870F3");

    }


}
