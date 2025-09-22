package stream.api.adapter.log.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import stream.api.adapter.log.service.ExceptionMonitorService;
import stream.api.adapter.log.model.response.LogResponse;
import stream.api.adapter.log.service.LogReaderService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/filter")
public class LogController {

    private final LogReaderService service;
    private final ExceptionMonitorService exceptionMonitorService;

//    @GetMapping("/test/monitor")
//    public String triggerMonitorOnce() {
//        exceptionMonitorService.printTestLog();
//        return "Triggered manual exception scan. Check application console logs.";
//    }

//    @GetMapping("/{file}/{serial}")
//    public ResponseEntity<LogResponse> getLogByFileAndSerial(@PathVariable String file,
//                                                             @PathVariable String serial) {
//        String path = "C:/Users/user/Desktop/stream/" + file;
//        return ResponseEntity.ok(service.findByOrdinatorOrReference(path, serial));
//    }

    @GetMapping("/{file}/{uniqueData}")
    public ResponseEntity<List<String>> getByUniqueData(
            @PathVariable String file,
            @PathVariable String uniqueData,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {


        List<String> logs = service.extractFindPaged(file, uniqueData, page, size);
        return ResponseEntity.ok(logs);
    }

    @GetMapping("/files")
    public ResponseEntity<List<String>> getLogFiles() {
        return ResponseEntity.ok(service.loadFiles());
    }
}
