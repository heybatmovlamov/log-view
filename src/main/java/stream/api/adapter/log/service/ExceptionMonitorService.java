package stream.api.adapter.log.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import stream.api.adapter.log.model.request.CommonRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class ExceptionMonitorService {
    private Logger logger = LoggerFactory.getLogger(ExceptionMonitorService.class);

    private final RestTemplate restTemplate;

    @Value("${log.monitor.enabled:true}")
    private boolean enabled;

    @Value("${log.monitor.file:src/main/resources/stream.log}")
    private String logFilePath;

    @Value("${log.monitor.recipients:}")
    private String recipients;

    @Value("${log.monitor.context-lines:6}")
    private int contextLines;

    @Value("${log.monitor.notification.url:}")
    private String notificationUrl;

    @Value("${log.monitor.notification.module:1}")
    private Integer notificationModule;

    @Value("${log.monitor.notification.sender:notification@yigim.az}")
    private String notificationSender;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Pattern EXCEPTION_LINE = Pattern.compile(".*(Exception|Error)[: ].*", Pattern.CASE_INSENSITIVE);


    public ExceptionMonitorService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    // (İstəsən saxla; hazırda istifadə olunmur)
    private static String signatureOf(String block) {
        String s = block
                .replaceAll("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} \\[[^]]+] \\[[A-Z]{3}] - ", "")
                .replaceAll("@[0-9a-fA-F]{6,}", "@xxxx")
                .replaceAll("0x[0-9a-fA-F]+", "0xXXXX")
                .replaceAll("\\bT\\d+\\b", "Txxxx")
                .replaceAll("\\b[0-9]{6,}\\b", "<num>");
        StringBuilder sb = new StringBuilder();
        String[] lines = s.split("\\R");
        for (String line : lines) {
            if (line.startsWith("\tat ")) {
                String cleaned = line.replaceAll("\\(.*\\)", "()");
                sb.append(cleaned).append('\n');
            } else if (line.contains("Exception") || line.contains("Error")) {
                sb.append(line.toLowerCase()).append('\n');
            }
        }
        return sb.length() == 0 ? s.toLowerCase() : sb.toString().trim();
    }

    private static List<String> dedupeBlocks(List<String> blocks) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (String b : blocks) {
            map.putIfAbsent(signatureOf(b), b);
        }
        return new ArrayList<>(map.values());
    }

    // hər saatda bir dəfə işləyir
    @Scheduled(cron = "0 0 * * * *")
    public void scanLastHourLogs() {
        if (!enabled) return;
        try {
            List<String> lines = readAllLines(Paths.get(logFilePath));
            if (lines.isEmpty()){
                logger.warn("No log found");
                return;
            }

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
            logger.error("Exception in ExceptionMonitorService:", e);
        }
    }

    private List<String> readAllLines(Path path) throws IOException {
        if (!Files.exists(path)) return List.of();
        return Files.readAllLines(path);
    }

    private List<String> filterByLastHour(List<String> lines, LocalDateTime from, LocalDateTime to) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            if (line.length() < 23) continue;
            String tsPart = line.substring(0, 23);
            try {
                LocalDateTime ts = LocalDateTime.parse(tsPart, TS);
                if ((ts.isAfter(from) || ts.isEqual(from)) && ts.isBefore(to)) {
                    out.add(line);
                }
            } catch (Exception ignored) {}
        }
        return out;
    }

    private List<String> findExceptionBlocks(List<String> lines) {
        List<String> result = new ArrayList<>();
        boolean inBlock = false;
        List<String> current = new ArrayList<>();
        int contextToPrepend = Math.max(0, this.contextLines);
        ArrayDeque<String> prevBuffer = new ArrayDeque<>(contextToPrepend);

        for (String line : lines) {
            if (prevBuffer.size() == contextToPrepend) prevBuffer.removeFirst();
            prevBuffer.addLast(line);

            boolean isStart = EXCEPTION_LINE.matcher(line).matches();
            if (isStart) {
                if (inBlock) {
                    current.add(line);
                    continue;
                }
                if (!current.isEmpty()) {
                    result.add(String.join(System.lineSeparator(), current));
                    current.clear();
                }
                inBlock = true;
                List<String> bufferList = new ArrayList<>(prevBuffer);
                if (line.contains("[ERR]")) {
                    int idx = bufferList.size() - 1, startIdx = idx;
                    while (startIdx - 1 >= 0 && bufferList.get(startIdx - 1).contains("[ERR]")) startIdx--;
                    for (int i = startIdx; i < bufferList.size(); i++) current.add(bufferList.get(i));
                } else {
                    for (String prevLine : bufferList) {
                        if (!prevLine.equals(line)) current.add(prevLine);
                    }
                    if (current.isEmpty() || !current.get(current.size() - 1).equals(line)) current.add(line);
                }
                continue;
            }
            if (inBlock) {
                String core = line.replaceFirst("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} \\[[^]]+] \\[[A-Z]{3}] - ", "");
                boolean isStackOrCause = Pattern.compile("^\\s*at\\s+").matcher(core).find()
                        || Pattern.compile("^\\s*Caused by: ", Pattern.CASE_INSENSITIVE).matcher(core).find()
                        || Pattern.compile("^\\s*\\.\\.\\.\\s+\\d+\\s+more\\b").matcher(core).find()
                        || Pattern.compile("^\\s{4,}\\S").matcher(core).find();
                if (isStackOrCause) {
                    current.add(line);
                } else {
                    inBlock = false;
                    current.add(line);
                    if (!current.isEmpty()) {
                        result.add(String.join(System.lineSeparator(), current));
                        current.clear();
                    }
                }
            }
        }
        if (!current.isEmpty()) result.add(String.join(System.lineSeparator(), current));
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
                boolean hasStack = stackFrame.matcher(block).find() || causedBy.matcher(block).find();
                boolean hasJava = javaExc.matcher(lower).find();
                if (!(hasStack || hasJava)) continue;
            }
            boolean devLike = javaExc.matcher(lower).find() || stackFrame.matcher(block).find() || causedBy.matcher(block).find();
            if (devLike) out.add(block);
        }
        return out;
    }

    public void printNowForTesting() {
        try {
            List<String> lines = readAllLines(Paths.get(logFilePath));
            if (lines.isEmpty()) {
                logger.info("[LogMonitorTest] No lines in log file: {}", logFilePath);
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime from = now.minusHours(3);
            List<String> window = filterByLastHour(lines, from, now);
            List<String> blocks = findExceptionBlocks(window);
            List<String> devOnly = filterDeveloperExceptions(blocks);
            List<String> suspicious = dedupeBlocks(devOnly);
            if (suspicious.isEmpty()) {
                logger.info("[LogMonitorTest] No suspicious exceptions found.");
            } else {
                logger.info("[LogMonitorTest] Found {} unique suspicious exception block(s):", suspicious.size());
                for (int i = 0; i < suspicious.size(); i++) {
                    logger.info("===== Exception Block #{} =====\n{}\n", (i + 1), suspicious.get(i));
                }
                sendEmail(now, suspicious);
            }
        } catch (Exception e) {
            logger.error("[LogMonitorTest] Unexpected error while scanning logs", e);
        }
    }

    // --- HTML escape helper (logları olduğu kimi göstərmək üçün) ---
    private static String escapeHtml(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(Math.max(16, s.length()));
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&#39;"); break;
                default: out.append(c);
            }
        }
        return out.toString();
    }

    // === HTML <pre> ilə göndərilən, sətir-sətir görünən versiya ===
    private void sendEmail(LocalDateTime now, List<String> blocks) {
        if (recipients == null || recipients.isBlank()) {
            logger.warn("No recipients configured for log monitor email. Skipping send.");
            return;
        }
        LocalDateTime from = now.minusHours(1);
        String window = from.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00"))
                + " - " + now.format(DateTimeFormatter.ofPattern("HH:00"));
        String subject = String.format("[YIGIM Log Monitor] %d exception(s) in last hour · %s",
                blocks.size(), window);

        // Header hissəsini də HTML-də veririk
        String headerHtml =
                "<div><b>Log Monitor Notification</b></div>" +
                        "<div>Time window: " + escapeHtml(window) + "</div>" +
                        "<div>Found " + blocks.size() + " exception block(s).</div>" +
                        "<div>Recipients: " + escapeHtml(recipients) + "</div>" +
                        "<br/>";

        // Blokları OLDUĞU KİMİ, heç bir regex/formatlamasız, yalnız HTML-escape edib <pre> içində göstəririk
        StringBuilder logsAsHtml = new StringBuilder();
        logsAsHtml.append("<pre class=\"notranslate\" translate=\"no\" style=\"font-family:monospace;white-space:pre-wrap;word-wrap:break-word;color:#000;background:#fff;\">");
        for (int i = 0; i < blocks.size(); i++) {
            logsAsHtml.append("==== Block #").append(i + 1).append(" ====\n");
            logsAsHtml.append(escapeHtml(blocks.get(i))).append("\n");
            logsAsHtml.append("====================\n\n");
        }
        logsAsHtml.append("</pre>");

        String htmlBody = "<html lang=\"en\"><head>" +
                "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\"/>" +
                "<meta name=\"google\" content=\"notranslate\"/>" +
                "<style>body,div,span,p,pre,code,tt,a{color:#000 !important;background:#fff !important;} a{ text-decoration:none; }</style>" +
                "</head><body class=\"notranslate\" translate=\"no\" style=\"color:#000;background:#fff;\">" + headerHtml + logsAsHtml + "</body></html>";

        // Çox vaxt notification servisləri HTML-i dəstəkləyir; ayrıca sahə yoxdursa, text kimi ötürsək də mail müştəriləri render edəcək.
        String bodyToSend = htmlBody;

        String[] toList = Arrays.stream(recipients.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toArray(String[]::new);

        if (toList.length == 0) {
            logger.warn("Recipients string is present but produced no valid emails. Skipping send.");
            return;
        }
        if (notificationUrl == null || notificationUrl.isBlank()) {
            logger.warn("Notification URL is not configured. Skipping send.");
            return;
        }
        try {
            String notificationEndpoint = notificationUrl + "api/mail/send";
            CommonRequest req = new CommonRequest();
            req.setModule(notificationModule);
            req.setText("Subject: " + subject + "\n\n" + bodyToSend); // varsa HTML sahəsinə qoymaq daha ideal olar
            req.setReceiver(toList);
            req.setSender(notificationSender);
            req.setUnicode(true); // ensure raw content preserved
            restTemplate.postForEntity(notificationEndpoint, req, Void.class);
            logger.info("Log monitor notification posted to {}. Recipients: {}, Blocks: {}",
                    notificationUrl, toList.length, blocks.size());
        } catch (Exception ex) {
            logger.error("Failed to post log monitor notification to {}", notificationUrl, ex);
        }
    }
}
