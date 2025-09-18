package stream.api.adapter.log.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import stream.api.adapter.log.model.request.LogRequest;
import stream.api.adapter.log.model.response.LogResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogReaderService {

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
        List<String> allLogs = readAllLogs(filePath);
        List<String> serialLogs = readLogs(allLogs, serial);

        String paySerial = serialLogs.get(serialLogs.size() - 1);

        List<LogRequest> requests = extractField(serialLogs);
        String threadId = requests.get(0).getThreadId();
        String timestamp = requests.get(0).getTimestamp();

        List<String> register = register(allLogs, threadId, timestamp , serial);
        List<String> payment = pay(allLogs, paySerial);

        String detailList = register.stream().filter(line -> line.contains("id:")).toList().toString();
        String id = getId(detailList);
        System.out.println(id);
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

    private String extractThread(String logLine) {
        String threadPattern = "\\[T\\d+\\]";
        String threadId = "";

        Pattern threadPat = Pattern.compile(threadPattern);
        Matcher threadMatcher = threadPat.matcher(logLine);

        if (threadMatcher.find()) {
            threadId = threadMatcher.group().replaceAll("[\\[\\]]", ""); // yalnız T00012345 hissəsi
        }

        return threadId;
    }

    private List<String> register(List<String> logs, String threadId, String targetTimestamp , String serial) {
        int resIndex = -1;

        for (int i = 0; i < logs.size(); i++) {
            String line = logs.get(i);
            if (line.contains("[" + threadId + "]") && line.contains("RES:") && line.contains(targetTimestamp) || line.contains("Result: "+serial)) {
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

    private List<String> pay(List<String> logs, String serial) {
        int serialIndex = -1;
        String threadId = null;

        String rowSerial = serial.split("Serial:")[1].trim();

        // 1. "serial:" olan sətri tap
        for (int i = 0; i < logs.size(); i++) {
            String line = logs.get(i);

            if (line.contains("Serial:") && line.contains(rowSerial)) {

                boolean found = false;

                // Əvvəlcə geriyə bax
                for (int j = i; j >= 0; j--) {
                    String previousLine = logs.get(j);
                    if (previousLine.contains("REQ: Pay")) {
                        int start = previousLine.indexOf('[');
                        int end = previousLine.indexOf(']', start);
                        if (start != -1 && end != -1) {
                            threadId = previousLine.substring(start + 1, end);
                            serialIndex = j;
                            found = true; // tapıldı
                            break;
                        }
                    }
                    if (previousLine.contains("REQ: GetPayment")) {
                        break;
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

    private  List<String> billList(List<String> logs, String id) {
        int idIndex = -1;
        String threadId = null;

        // 1. "id:" olan və GetBillList ilə bağlı olan threadId-ni tap
        for (int i = 0; i < logs.size(); i++) {
            String line = logs.get(i);
            if (line.contains("Detail list: [id: "+id) || line.contains(id)) {
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
                    if (previousLine.contains("REQ: GetPayment")) {
                        break;
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
                        result.add(logs.get(i+1));
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
}
