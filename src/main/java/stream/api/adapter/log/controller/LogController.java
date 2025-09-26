package stream.api.adapter.log.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import stream.api.adapter.log.model.request.LogsRequest;
import stream.api.adapter.log.service.LogReaderService;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/filter")
public class LogController {

    private final LogReaderService service;

//    @GetMapping("/test/monitor")
//    public String triggerMonitorOnce() {
//        exceptionMonitorService.scanLastHourCustomerLogs();
//        return "Triggered manual exception scan. Check application console logs.";
//    }

//    @GetMapping("/{file}/{serial}")
//    public ResponseEntity<LogResponse> getLogByFileAndSerial(@PathVariable String file,
//                                                             @PathVariable String serial) {
//        String path = "C:/Users/user/Desktop/stream/" + file;
//        return ResponseEntity.ok(service.findByOrdinatorOrReference(path, serial));
//    }

    @PostMapping("/file-stream")
    public ResponseEntity<ResponseBodyEmitter> streamLogs(@RequestBody LogsRequest req) {
        ResponseBodyEmitter emitter =  new ResponseBodyEmitter();

        new Thread(() -> {
            try {
                service.streamLogs(req.getFile(), req.getUniqueData(), req.getPage(), req.getSize(), emitter);
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }).start();
        return ResponseEntity.ok(emitter);
    }

    @PostMapping("/file-live")
    public ResponseEntity<ResponseBodyEmitter> streamLiveLogs(@RequestBody LogsRequest req) {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(0L);
        new Thread(() -> {
            try {
                service.streamLiveLogs(req.getFile(), req.getUniqueData(), emitter);
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }).start();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/plain;charset=UTF-8") // 🔑 vacibdir
                .body(emitter);
    }

    @GetMapping("/files")
    public ResponseEntity<List<String>> getLogFiles() {
        return ResponseEntity.ok(service.loadFiles());
    }
}
