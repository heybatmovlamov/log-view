package stream.api.adapter.log.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import stream.api.adapter.log.model.request.CommonRequest;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

// PDF
import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Element;
import com.lowagie.text.pdf.PdfWriter;

@Service
public class ExceptionMonitorService {
    private static final Logger logger = LoggerFactory.getLogger(ExceptionMonitorService.class);

    private final RestTemplate restTemplate;

    @Value("${log.monitor.enabled}")
    private boolean enabled;

    @Value("${log.monitor.file}")
    private String logFilePath;

    @Value("${log.monitor.recipients:}")
    private String recipients;

    @Value("${log.monitor.context-lines}")
    private int contextLines;

    @Value("${log.monitor.notification.url:}")
    private String notificationUrl;

    @Value("${log.monitor.notification.module}")
    private Integer notificationModule;

    @Value("${log.monitor.notification.sender:}")
    private String notificationSender;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Pattern EXCEPTION_LINE = Pattern.compile(".*(Exception|Error)[: ].*", Pattern.CASE_INSENSITIVE);
    // Precompiled patterns for performance and readability
    private static final Pattern STACK_AT = Pattern.compile("^\\s*at\\s+");
    private static final Pattern CAUSED_BY = Pattern.compile("^\\s*Caused by: ", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOTS_MORE = Pattern.compile("^\\s*\\.\\.\\.\\s+\\d+\\s+more\\b");
    private static final Pattern INDENTED_LINE = Pattern.compile("^\\s{4,}\\S");

    // Developer-facing filters
    private static final Pattern ADAPTER_CODE = Pattern.compile("\\bCode: E\\d{6}\\b");
    private static final Pattern ADAPTER_DESC = Pattern.compile("\\|- Description: ", Pattern.CASE_INSENSITIVE);
    private static final Pattern ADAPTER_REASON = Pattern.compile("\\|- Reason: ", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEV_STACK_FRAME = Pattern.compile("^\\s*at\\s+.+\\(.+\\)");
    private static final Pattern JAVA_EXCEPTION_NAME = Pattern.compile("java\\.[a-z0-9_.]*Exception|java\\.[a-z0-9_.]*Error", Pattern.CASE_INSENSITIVE);


    public ExceptionMonitorService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }


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
                sendNotification(now, suspicious);
            }
        } catch (Exception e) {
            logger.error("Exception in ExceptionMonitorService:", e);
        }
    }
    public void printTestLog() {
        try {
            List<String> lines = readAllLines(Paths.get(logFilePath));
            if (lines.isEmpty()) {
                logger.info("[LogMonitorTest] No lines in log file: {}", logFilePath);
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime from = now.minusHours(1);
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
                sendNotification(now, suspicious);
            }
        } catch (Exception e) {
            logger.error("[LogMonitorTest] Unexpected error while scanning logs", e);
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
                boolean isStackOrCause = STACK_AT.matcher(core).find()
                        || CAUSED_BY.matcher(core).find()
                        || DOTS_MORE.matcher(core).find()
                        || INDENTED_LINE.matcher(core).find();
                if (isStackOrCause) {
                    current.add(line);
                } else {
                    inBlock = false;
                    current.add(line);
                    result.add(String.join(System.lineSeparator(), current));
                    current.clear();
                }
            }
        }
        if (!current.isEmpty()) result.add(String.join(System.lineSeparator(), current));
        return result;
    }

    private List<String> filterDeveloperExceptions(List<String> blocks) {
        List<String> out = new ArrayList<>();
        for (String block : blocks) {
            String lower = block.toLowerCase();
            boolean looksAdapter = ADAPTER_CODE.matcher(block).find()
                    || ADAPTER_DESC.matcher(block).find()
                    || ADAPTER_REASON.matcher(block).find();
            if (looksAdapter) {
                boolean hasStack = DEV_STACK_FRAME.matcher(block).find() || CAUSED_BY.matcher(block).find();
                boolean hasJava = JAVA_EXCEPTION_NAME.matcher(lower).find();
                if (!(hasStack || hasJava)) continue;
            }
            boolean devLike = JAVA_EXCEPTION_NAME.matcher(lower).find() || DEV_STACK_FRAME.matcher(block).find() || CAUSED_BY.matcher(block).find();
            if (devLike) out.add(block);
        }
        return out;
    }



    // === PDF generator ===
    private byte[] generatePdf(String window, List<String> blocks) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4.rotate(), 36, 36, 36, 36);
        PdfWriter.getInstance(document, baos);
        document.open();

        Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14);
        Font normalFont = FontFactory.getFont(FontFactory.HELVETICA, 10);
        Font monoFont = FontFactory.getFont(FontFactory.COURIER, 9);

        Paragraph title = new Paragraph("Log Monitor Exceptions", titleFont);
        title.setAlignment(Element.ALIGN_LEFT);
        document.add(title);
        document.add(new Paragraph("Time window: " + window, normalFont));
        document.add(new Paragraph("Blocks found: " + blocks.size(), normalFont));
        document.add(new Paragraph(" ", normalFont));

        for (int i = 0; i < blocks.size(); i++) {
            String header = String.format("==== Block #%d ====", i + 1);
            document.add(new Paragraph(header, titleFont));
            String text = blocks.get(i);
            Paragraph p = new Paragraph(text.replace("\r\n", "\n"), monoFont);
            p.setLeading(12f);
            document.add(p);
            document.add(new Paragraph("\n\n", normalFont));
        }

        document.close();
        return baos.toByteArray();
    }

    private void sendNotification(LocalDateTime now, List<String> blocks) {
        if (recipients == null || recipients.isBlank()) {
            logger.warn("No recipients configured for log monitor email. Skipping send.");
            return;
        }
        String window = buildTimeWindow(now);
        String subject = String.format("[YIGIM Log Monitor] %d exception(s) in last hour · %s",
                blocks.size(), window);

        // NOTE: Per request, do NOT include error details in the email body anymore. Only send the PDF attachment.
        // The following HTML construction is intentionally removed/commented out.
        // String htmlBody = ...; // removed

        String[] toList = parseRecipients(recipients);

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

            // Build PDF attachment from logs
            byte[] pdfBytes = generatePdf(window, blocks);
            String base64Pdf = Base64.getEncoder().encodeToString(pdfBytes);
            String fileName = "log-errors-" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".pdf";
            Map<String, String> attachmentMap = new HashMap<>();
            attachmentMap.put(fileName, base64Pdf);
            req.setAttachmentMap(attachmentMap);

            // Set a minimal body without any embedded logs
            String minimalBody = "Subject: " + subject + "\n\nPlease see the attached PDF for detailed exception logs.";
            req.setText(minimalBody);
            req.setReceiver(toList);
            req.setSender(notificationSender);
            req.setUnicode(true);
            restTemplate.postForEntity(notificationEndpoint, req, Void.class);
            sendTelegramMultipart(subject, pdfBytes, fileName);
            logger.info("Log monitor notifications posted (mail: JSON, telegram: multipart) to {}. Recipients: {}, Blocks: {}, Attachment: {} bytes",
                    notificationUrl, toList.length, blocks.size(), pdfBytes.length);
        } catch (Exception ex) {
            logger.error("Failed to post log monitor notification to {}", notificationUrl, ex);
        }
    }

    private void sendTelegramMultipart(String message, byte[] pdfBytes, String fileName) {
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            if (message != null) {
                body.add("message", message);
            }

            ByteArrayResource resource = new ByteArrayResource(pdfBytes) {
                @Override
                public String getFilename() {
                    return fileName;
                }
            };

            HttpHeaders partHeaders = new HttpHeaders();
            partHeaders.setContentType(MediaType.APPLICATION_PDF);
            HttpEntity<ByteArrayResource> filePart = new HttpEntity<>(resource, partHeaders);
            body.add("file", filePart);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
            restTemplate.postForEntity(notificationUrl + "api/telegram/send", requestEntity, Void.class);
        } catch (Exception e) {
            logger.error("Failed to send telegram notification to {}", notificationUrl, e);
        }
    }

    // Helpers
    private String buildTimeWindow(LocalDateTime now) {
        LocalDateTime from = now.minusHours(1);
        return from.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00"))
                + " - " + now.format(DateTimeFormatter.ofPattern("HH:00"));
    }

    private String[] parseRecipients(String recipientsStr) {
        if (recipientsStr == null || recipientsStr.isBlank()) return new String[0];
        return Arrays.stream(recipientsStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toArray(String[]::new);
    }

}