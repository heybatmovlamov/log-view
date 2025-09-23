package stream.api.adapter.log.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import stream.api.adapter.log.model.request.LogRequest;
import stream.api.adapter.log.model.response.LogResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogReaderService {

    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    @Value("${log.monitor.file}")
    private String filePath;

    private final LogSaverService saverService;

    private List<String> readErrorLogs(String filePath) {
        try (Stream<String> lines = Files.lines(Paths.get(filePath))) {
            return lines
                    .filter(line -> line.contains("[ERR]"))
                    .filter(line -> line.contains("java.lang.") || line.contains("stream.api.adapter"))
                    .collect(Collectors.toList());

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private List<String> readLogs(List<String> allLogs, String code) {
        return allLogs
                .stream()
                .filter(line -> line.contains(code))
                .collect(Collectors.toList());
    }

    private List<LogRequest> extractField(List<String> log) {
        String timestampPattern = "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}"; // Timestamp
        String threadPattern = "\\[T\\d+\\]";

        String timestamp = "";
        String threadId = "";

        Pattern timestampPat = Pattern.compile(timestampPattern);
        Pattern threadPat = Pattern.compile(threadPattern);

        List<LogRequest> logRequests = new ArrayList<>();
        for (String logLine : log) {
            Matcher timestampMatcher = timestampPat.matcher(logLine);
            if (timestampMatcher.find()) {
                timestamp = timestampMatcher.group();
            }


            Matcher threadMatcher = threadPat.matcher(logLine);
            if (threadMatcher.find()) {
                threadId = threadMatcher.group().replaceAll("[\\[\\]]", ""); // Sadece Thread ID
            }

            LogRequest add = LogRequest.builder()
                    .timestamp(timestamp)
                    .threadId(threadId)
                    .build();
            logRequests.add(add);
        }
        return logRequests;
    }

    public LogResponse findByOrdinatorOrReference(String filePath, String serial) {
        //serial  - > thread
        //
        List<String> allLogs =  readAllLogs(filePath);
        List<String> serialLogs = readLogs(allLogs, serial);

        String paySerial = serialLogs.get(serialLogs.size() - 1);
        serialLogs.forEach(System.out::println);

        List<LogRequest> requests = extractField(serialLogs);
        String threadId = requests.get(0).getThreadId();
        String timestamp = requests.get(0).getTimestamp();

        List<String> register = register(allLogs, threadId, timestamp, serial);
        List<String> payment = pay(allLogs, paySerial);

        String detailList = register.stream().filter(line -> line.contains("id:")).toList().toString();
        String id = getId(detailList);
        List<String> billList = billList(allLogs, id);

        billList.forEach(System.out::println);
        register.forEach(System.out::println);
        payment.forEach(System.out::println);

        return LogResponse.builder()
                .billList(billList)
                .register(register)
                .pay(payment)
                .build();
    }

    private List<String> readAllLogs(String filePath) {
        try (Stream<String> lines = Files.lines(Paths.get(filePath))) {
            return lines.collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private List<String> register(List<String> logs, String threadId, String targetTimestamp, String serial) {
        int resIndex = -1;

        for (int i = 0; i < logs.size(); i++) {
            String line = logs.get(i);
            if (line.contains("[" + threadId + "]") && line.contains("RES:") && line.contains(targetTimestamp) || line.contains("Result: " + serial)) {
                resIndex = i;
                break;
            }
        }

        if (resIndex == -1) {
            System.out.println("RES sətri tapılmadı.");
            return Collections.emptyList();
        }

        int reqIndex = -1;
        for (int i = resIndex; i >= 0; i--) {
            String line = logs.get(i);
            if (line.contains("[" + threadId + "]") && line.contains("REQ:")) {
                reqIndex = i;
                break;
            }
        }

        if (reqIndex == -1) {
            System.out.println("REQ sətri tapılmadı.");
            return Collections.emptyList();
        }
        resIndex++;
        // İndi REQ ilə RES arasındakı blok yığılır
        List<String> result = new ArrayList<>();
        for (int i = reqIndex; i <= resIndex; i++) {
            String line = logs.get(i);
            if (line.contains("[" + threadId + "]")) {
                result.add(line);
            }
        }
        return result;
    }

    private List<String> pay(List<String> logs, String serialLine) {
        int serialIndex = -1;
        String threadId = null;

        String rowSerial = serialLine.split("Serial:")[1].trim();
        // 1. "serial:" olan sətri tap
        for (int i = 0; i < logs.size(); i++) {
            String line = logs.get(i);

            if (line.equals(serialLine)) {
                boolean found = false;

                // Əvvəlcə geriyə bax
                for (int j = i; j >= 0; j--) {
                    String previousLine = logs.get(j);
                    if (previousLine.contains("REQ: Pay") || previousLine.contains("[" + threadId + "]")) {
                        int start = previousLine.indexOf('[');
                        int end = previousLine.indexOf(']', start);
                        if (start != -1 && end != -1) {
                            threadId = previousLine.substring(start + 1, end);
                            serialIndex = j;
                            found = true; // tapıldı
                            break;
                        }
                    }
                }

                // Əgər yuxarıda tapılmadısa, onda aşağıya bax
                if (!found) {
                    for (int j = i; j < logs.size(); j++) {
                        String nextLine = logs.get(j);

                        if (nextLine.contains("REQ: Pay")) {
                            int start = nextLine.indexOf('[');
                            int end = nextLine.indexOf(']', start);
                            if (start != -1 && end != -1) {
                                threadId = nextLine.substring(start + 1, end);
                                serialIndex = j;
                                break;
                            }
                        }

                        // Burada "REQ: GetPayment" görsək belə, davam edirik
                    }
                }

                break; // serial tapıldıqdan sonra artıq əsas döngüdən çıx
            }
        }

        if (serialIndex == -1 || threadId == null) {
            System.out.println("REQ: Pay bloku tapılmadı.");
            return Collections.emptyList();
        }

        // 2. Pay istəyinin cavablarını yığ
        List<String> result = new ArrayList<>();
        boolean started = false;
        boolean done = false;

        for (int i = serialIndex; i < logs.size(); i++) {
            String line = logs.get(i);

            if (line.contains("[" + threadId + "]")) {
                if (!started && line.contains("REQ: Pay")) {
                    started = true;
                }

                if (started) {
                    result.add(line);
                    if (line.contains("RES: Pay")) {
                        // Sonrakı xətləri də əlavə et
                        if (i + 1 < logs.size()) result.add(logs.get(i + 1));
                        if (line.contains("[ERR] - RES: Pay")) {
                            if (i + 2 < logs.size()) result.add(logs.get(i + 2));
                            if (i + 3 < logs.size()) result.add(logs.get(i + 3));
                        }
                        done = true;
                        break;
                    }
                }
            } else if (started && !line.contains("[")) {
                // log xətti boş və ya parse edilə bilməyən xəttdirsə → əlavə et
                result.add(line);
            } else if (started && !done) {
                continue; // başqa thread gəlib, amma hələ bizim RES gəlməyib → gözləməyə davam
            } else if (done) {
                break;
            }
        }

        return result;
    }

    private String getId(String line) {
        String search = "id:";
        String id = "";
        int startIndex = line.indexOf(search);
        if (startIndex != -1) {
            startIndex += search.length();
            int endIndex = -1;

            if (line.contains(",")) {
                endIndex = line.indexOf(",", startIndex);
            } else {
                endIndex = line.indexOf("]", startIndex);
            }
            id = line.substring(startIndex, endIndex).trim();
        } else {
            System.out.println("ID tapılmadı.");
        }
        return id;
    }

    private List<String> billList(List<String> logs, String id) {
        int idIndex = -1;
        String threadId = null;

        // 1. "id:" olan və GetBillList ilə bağlı olan threadId-ni tap
        for (int i = 0; i < logs.size(); i++) {
            String line = logs.get(i);
            if (line.contains("Detail list: [id: " + id) || line.contains(id)) {
                System.out.println(line);
                // Geri gedərək REQ: GetBillList tap
                for (int j = i; j >= 0; j--) {
                    String previousLine = logs.get(j);
                    if (previousLine.contains("REQ: GetBillList")) {
                        int start = previousLine.indexOf('[');
                        int end = previousLine.indexOf(']', start);
                        if (start != -1 && end != -1) {
                            threadId = previousLine.substring(start + 1, end);
                            idIndex = j;
                            break;
                        }
                    }
                }
                break;
            }
        }

        if (idIndex == -1 || threadId == null) {
            System.out.println("REQ: GetBillList bloku tapılmadı.");
            return Collections.emptyList();
        }

        // 2. GetBillList istəyinin cavablarını yığ
        List<String> result = new ArrayList<>();
        boolean started = false;
        boolean done = false;

        for (int i = idIndex; i < logs.size(); i++) {
            String line = logs.get(i);

            if (line.contains("[" + threadId + "]")) {
                if (!started && line.contains("REQ: GetBillList")) {
                    started = true;
                }

                if (started) {
                    result.add(line);
                    if (line.contains("RES: GetBillList")) {
                        result.add(logs.get(i + 1));
                        done = true;
                        break;
                    }
                }
            } else if (started && !line.contains("[")) {
                // log xətti boş və ya parse edilə bilməyən xəttdirsə → əlavə et
                result.add(line);
            } else if (started && !done) {
                // başqa thread gəlib, amma hələ bizim RES gəlməyib → gözləməyə davam
                continue;
            } else if (done) {
                break;
            }
        }

        return result;
    }


    private List<String> readLogsUniqueData(List<String> allLogs, String code) {
        return allLogs
                .stream()
                .filter(line -> line.contains( code ))
                .collect(Collectors.toList());
    }


    public static List<String> extractAroundTimestamp(List<String> logs, String threadId, String targetTimestamp) {
        LocalDateTime targetTime = LocalDateTime.parse(targetTimestamp, formatter);
        List<String> result = new ArrayList<>();

        for (String line : logs) {
            // Hər sətirdə uyğun threadId varsa davam et
            if (!line.contains("[" + threadId + "]")) continue;

            // Sətirin timestamp-ini çıxar
            String timeStr = line.substring(0, 23); // "2025-09-19 00:00:25.161"
            LocalDateTime lineTime = LocalDateTime.parse(timeStr, formatter);

            long secondsDiff = Duration.between(targetTime, lineTime).getSeconds();

            // 1 dəqiqə əvvəl və 1 dəqiqə sonra olan sətirlər
            if (secondsDiff >= -60 && secondsDiff <= 60) {
                result.add(line);
            }
        }

        return result;
    }


    public List<String> extractFindPaged(String file, String data, int page, int size) {
        if (data.length() < 3) {
            throw new RuntimeException("Axtardığınız datanın uzunluğu 4 simvoldan böyük olmalıdır");
        }
        String path = filePath+ "/" + file;
        List<String> allLogs = readAllLogs(path);
        List<String> filteredLogs = readLogsUniqueData(allLogs, data);

        // Unikal log requestləri toplayırıq
        List<LogRequest> uniqueThreadAndTimeStamp = filteredLogs.stream()
                .map(this::extractField)
                .distinct()
                .collect(Collectors.toList());

        uniqueThreadAndTimeStamp.forEach(System.out::println);
        // Pagination
        int start = page * size;
        int end = Math.min(start + size, uniqueThreadAndTimeStamp.size());
        if (start >= uniqueThreadAndTimeStamp.size()) return Collections.emptyList();

        // Result listini page-lə doldururuq
        return uniqueThreadAndTimeStamp.subList(start, end).parallelStream()
                .flatMap(logRequest -> extractAroundTimestamp(
                        allLogs,
                        logRequest.getThreadId(),
                        logRequest.getTimestamp()
                ).stream())
                .collect(Collectors.toList());
    }



    private LogRequest extractField(String log) {
        String timestampPattern = "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}"; // Timestamp
        String threadPattern = "\\[T-[A-Za-z0-9]+\\]";


        String timestamp = "";
        String threadId = "";

        Pattern timestampPat = Pattern.compile(timestampPattern);
        Pattern threadPat = Pattern.compile(threadPattern);

        Matcher timestampMatcher = timestampPat.matcher(log);
        if (timestampMatcher.find()) {
            timestamp = timestampMatcher.group();
        }


        Matcher threadMatcher = threadPat.matcher(log);
        if (threadMatcher.find()) {
            threadId = threadMatcher.group().replaceAll("[\\[\\]]", ""); // Sadece Thread ID
        }

        return LogRequest.builder()
                .timestamp(timestamp)
                .threadId(threadId)
                .build();
    }

    public List<String> loadFiles() {
        try {
            Path logDir = Paths.get(filePath);
            List<String> files = Files.list(logDir)
                    .filter(p -> p.toString().endsWith(".log"))
                    .map(p -> p.getFileName().toString())
                    .toList();
            return files;
        }catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
