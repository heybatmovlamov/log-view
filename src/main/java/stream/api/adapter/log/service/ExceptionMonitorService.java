package stream.api.adapter.log.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExceptionMonitorService {

    private final JavaMailSender mailSender;

    @Value("${log.monitor.enabled:true}")
    private boolean enabled;

    @Value("${log.monitor.file:src/main/resources/stream.log}")
    private String logFilePath;

    @Value("${log.monitor.recipients:}")
    private String recipients; // comma separated

    @Value("${log.monitor.context-lines:6}")
    private int contextLines; // number of context lines to include before exception start


    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final Pattern EXCEPTION_LINE = Pattern.compile(".*(Exception|Error)[: ].*", Pattern.CASE_INSENSITIVE);
    
    private static String signatureOf(String block) {
        // Normalize to dedupe: remove timestamps, thread ids, memory addresses, numbers that look like ids
        String s = block
                .replaceAll("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} \\[[^]]+] \\[[A-Z]{3}] - ", "")
                .replaceAll("@[0-9a-fA-F]{6,}", "@xxxx")
                .replaceAll("0x[0-9a-fA-F]+", "0xXXXX")
                .replaceAll("\\bT\\d+\\b", "Txxxx")
                .replaceAll("\\b[0-9]{6,}\\b", "<num>");
        // Only keep first line and stack frame class+method to cluster similar exceptions
        StringBuilder sb = new StringBuilder();
        String[] lines = s.split("\\R");
        for (String line : lines) {
            if (line.startsWith("\tat ")) {
                // keep class and method only
                String cleaned = line.replaceAll("\\(.*\\)", "()");
                sb.append(cleaned).append('\n');
            } else if (line.contains("Exception") || line.contains("Error")) {
                sb.append(line.toLowerCase()).append('\n');
            }
        }
        if (sb.length() == 0) {
            return s.toLowerCase();
        }
        return sb.toString().trim();
    }

    private static List<String> dedupeBlocks(List<String> blocks) {
        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();
        for (String b : blocks) {
            String key = signatureOf(b);
            map.putIfAbsent(key, b);
        }
        return new java.util.ArrayList<>(map.values());
    }

    // Run every hour at minute 0
    @Scheduled(cron = "0 0 * * * *")
    public void scanLastHourLogs() {
        if (!enabled) {
            return;
        }
        try {
            List<String> lines = readAllLines(Paths.get(logFilePath));
            if (lines.isEmpty()) return;

            LocalDateTime now = LocalDateTime.now();
            LocalDateTime from = now.minusHours(1);

            List<String> window = filterByLastHour(lines, from, now);
            List<String> blocks = findExceptionBlocks(window);
            List<String> devOnly = filterDeveloperExceptions(blocks);
            List<String> suspicious = dedupeBlocks(devOnly);

            if (!suspicious.isEmpty()) {
                sendEmail(now, suspicious);
            }
        } catch (Exception e) {
            log.error("Exception in ExceptionMonitorService:", e);
        }
    }

    private List<String> readAllLines(Path path) throws IOException {
        if (!Files.exists(path)) return List.of();
        return Files.readAllLines(path);
    }

    private List<String> filterByLastHour(List<String> lines, LocalDateTime from, LocalDateTime to) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            if (line.length() < 23) continue; // timestamp length
            String tsPart = line.substring(0, 23);
            try {
                LocalDateTime ts = LocalDateTime.parse(tsPart, TS);
                if ((ts.isAfter(from) || ts.isEqual(from)) && ts.isBefore(to)) {
                    out.add(line);
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private List<String> findExceptionBlocks(List<String> lines) {
        List<String> result = new ArrayList<>();
        boolean inBlock = false;
        List<String> current = new ArrayList<>();
        int contextToPrepend = Math.max(0, this.contextLines); // how many lines before the exception start we want
        java.util.ArrayDeque<String> prevBuffer = new java.util.ArrayDeque<>(contextToPrepend);

        for (String line : lines) {
            // maintain rolling buffer of previous lines
            if (prevBuffer.size() == contextToPrepend) {
                prevBuffer.removeFirst();
            }
            prevBuffer.addLast(line);

            boolean isStart = EXCEPTION_LINE.matcher(line).matches();
            if (isStart) {
                if (inBlock) {
                    // If already in a block, treat this as part of the same chain (e.g., 'Caused by:')
                    current.add(line);
                    continue;
                }
                if (!current.isEmpty()) {
                    result.add(String.join(System.lineSeparator(), current));
                    current.clear();
                }
                inBlock = true;
                // If the start line is tagged as [ERR], anchor the block at the first contiguous [ERR] line
                // in the rolling buffer (do not include lines above that). Otherwise, fall back to context-lines behavior.
                java.util.List<String> bufferList = new java.util.ArrayList<>(prevBuffer);
                if (line.contains("[ERR]")) {
                    int idx = bufferList.size() - 1; // current line position
                    int startIdx = idx;
                    while (startIdx - 1 >= 0 && bufferList.get(startIdx - 1).contains("[ERR]")) {
                        startIdx--;
                    }
                    // Add from the first contiguous [ERR] to the current line
                    for (int i = startIdx; i < bufferList.size(); i++) {
                        current.add(bufferList.get(i));
                    }
                } else {
                    // add previous context lines (without duplicating current line)
                    for (String prevLine : bufferList) {
                        if (!prevLine.equals(line)) {
                            current.add(prevLine);
                        }
                    }
                    // ensure the start line is present once
                    if (current.isEmpty() || !current.get(current.size()-1).equals(line)) {
                        current.add(line);
                    }
                }
                continue;
            }
            if (inBlock) {
                // include probable stacktrace lines: accept with optional log prefix like "[...][ERR] - " before tokens
                String core = line.replaceFirst("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} \\[[^]]+] \\[[A-Z]{3}] - ", "");
                boolean isStackOrCause = Pattern.compile("^\\s*at\\s+").matcher(core).find()
                        || Pattern.compile("^\\s*Caused by: ", Pattern.CASE_INSENSITIVE).matcher(core).find()
                        || Pattern.compile("^\\s*\\.\\.\\.\\s+\\d+\\s+more\\b").matcher(core).find()
                        || Pattern.compile("^\\s{4,}\\S").matcher(core).find();
                if (isStackOrCause) {
                    current.add(line);
                } else {
                    // block ended when a non-stacktrace normal line comes; also include a few trailing context lines for clarity
                    inBlock = false;
                    // capture up to 3 trailing context lines after the stacktrace
                    current.add(line);
                    // try to add next up to 2 lines as additional context if available
                    // Note: since we process sequentially, we cannot look ahead easily without index; trailing context will be limited to this line
                    if (!current.isEmpty()) {
                        result.add(String.join(System.lineSeparator(), current));
                        current.clear();
                    }
                }
            }
        }
        if (!current.isEmpty()) {
            result.add(String.join(System.lineSeparator(), current));
        }
        return result;
    }

    private List<String> filterDeveloperExceptions(List<String> blocks) {
        List<String> out = new ArrayList<>();
        Pattern codePattern = Pattern.compile("\\bCode: E\\d{6}\\b");
        Pattern adapterDesc = Pattern.compile("\\|\\- Description: ", Pattern.CASE_INSENSITIVE);
        Pattern adapterReason = Pattern.compile("\\|\\- Reason: ", Pattern.CASE_INSENSITIVE);
        Pattern stackFrame = Pattern.compile("^\\s*at\\s+.+\\(.+\\)");
        Pattern causedBy = Pattern.compile("^\\s*Caused by: ", Pattern.CASE_INSENSITIVE);
        Pattern javaExc = Pattern.compile("java\\.[a-z0-9_.]*Exception|java\\.[a-z0-9_.]*Error", Pattern.CASE_INSENSITIVE);
        for (String block : blocks) {
            String lower = block.toLowerCase();
            boolean looksAdapter = codePattern.matcher(block).find()
                    || adapterDesc.matcher(block).find()
                    || adapterReason.matcher(block).find();
            if (looksAdapter) {
                // If it also contains a java exception/stacktrace, keep; otherwise skip
                boolean hasStack = stackFrame.matcher(block).find() || causedBy.matcher(block).find();
                boolean hasJava = javaExc.matcher(lower).find();
                if (!(hasStack || hasJava)) {
                    continue; // skip non-developer business error block
                }
            }
            // In general, keep only blocks that have either a java exception/error keyword or a stack frame/caused by
            boolean devLike = javaExc.matcher(lower).find() || stackFrame.matcher(block).find() || causedBy.matcher(block).find();
            if (devLike) {
                out.add(block);
            }
        }
        return out;
    }

    public void printNowForTesting() {
        // Manual trigger for testing: scan previous 1 hour relative to now and print unique exception blocks
        try {
            List<String> lines = readAllLines(Paths.get(logFilePath));
            if (lines.isEmpty()) {
                System.out.println("[LogMonitorTest] No lines in log file: " + logFilePath);
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime from = now.minusHours(20);
            List<String> window = filterByLastHour(lines, from, now);
            List<String> blocks = findExceptionBlocks(window);
            List<String> devOnly = filterDeveloperExceptions(blocks);
            List<String> suspicious = dedupeBlocks(devOnly);
            if (suspicious.isEmpty()) {
                System.out.println("[LogMonitorTest] No suspicious exceptions found in the last 1 hour.");
            } else {
                System.out.println("[LogMonitorTest] Found " + suspicious.size() + " unique suspicious exception block(s) in last 1 hour:");
                for (int i = 0; i < suspicious.size(); i++) {
                    System.out.println("\n===== Exception Block #" + (i+1) + " =====\n");
                    System.out.println(suspicious.get(i));
                }
            }
        } catch (Exception e) {
            System.out.println("[LogMonitorTest] Failed to run manual scan: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void sendEmail(LocalDateTime now, List<String> blocks) {
        if (recipients == null || recipients.isBlank()) {
            log.warn("No recipients configured for log monitor email. Skipping send.");
            return;
        }
        String subject = "[Log Monitor] Exceptions detected in last hour: " + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH"));
        String body = String.join("\n\n---\n\n", blocks);

        for (String to : recipients.split(",")) {
            String target = to.trim();
            if (target.isEmpty()) continue;
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(target);
            message.setSubject(subject);
            message.setText(body);
            try {
                mailSender.send(message);
            } catch (Exception e) {
                log.error("Failed to send email to {}", target, e);
            }
        }
        log.info("Log monitor email sent to configured recipients. Blocks: {}", blocks.size());
    }
}
