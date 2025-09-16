package stream.api.adapter.log.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import stream.api.adapter.log.service.ExceptionMonitorService;
import stream.api.adapter.log.service.LogReaderService;

@RestController
@RequiredArgsConstructor
public class LogController {

    private final LogReaderService service;
    private final ExceptionMonitorService exceptionMonitorService;

    @GetMapping("/test/monitor")
    public String triggerMonitorOnce() {
        exceptionMonitorService.printNowForTesting();
        return "Triggered manual exception scan. Check application console logs.";
    }

    @GetMapping
    public void getLogSaverService() {
        service.findByOrdinatorOrReference("C:/Users/user/Desktop/stream.log", "D7D78073B73640D2BE663406CD85B60F");
    }
}
