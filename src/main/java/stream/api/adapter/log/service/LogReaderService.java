package stream.api.adapter.log.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import stream.api.adapter.log.model.request.LogRequest;

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

    public void findByOrdinatorOrReference(String filePath, String serial) {
        List<String> allLogs = readAllLogs(filePath);
        List<String> serialLogs = readLogs(allLogs, serial);

        String paySerial = serialLogs.get(serialLogs.size() - 1);

        List<LogRequest> requests = extractField(serialLogs);
        String threadId = requests.get(0).getThreadId();
        String timestamp = requests.get(0).getTimestamp();

        List<String> register = register(allLogs, threadId, timestamp);
        List<String> payment = pay(allLogs, paySerial);

        String  detailList = register.stream().filter(line -> line.contains("id:")).toList().toString();
        String id = getId(detailList);
        List<String> billList = billList(allLogs, id);

        billList.forEach(System.out::println);
        register.forEach(System.out::println);
        payment.forEach(System.out::println);
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

    private List<String> register(List<String> logs, String threadId, String targetTimestamp) {
        int resIndex = -1;

        for (int i = 0; i < logs.size(); i++) {
            String line = logs.get(i);
            if (line.contains("[" + threadId + "]") && line.contains("RES:") && line.contains(targetTimestamp)) {
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
        String threadId = extractThread(serialLine);
        String rowSerial = serialLine.split("Serial:")[1].trim();

        // Serial sətirini tap
        boolean serialFound = false;
        for (String line : logs) {
            if (line.contains("Serial:") && line.contains(rowSerial) && line.contains("[" + threadId + "]")) {
                serialFound = true;
                break;
            }
        }

        if (!serialFound) {
            System.out.println("Serial tapılmadı.");
            return Collections.emptyList();
        }

        // Pay blokunu çıxart (REQ → RES qədər)
        List<String> result = new ArrayList<>();
        boolean started = false;

        for (String line : logs) {
            if (line.contains("[" + threadId + "]")) {
                if (!started && line.contains("REQ: Pay")) {
                    started = true;
                }
                if (started) {
                    result.add(line);

                    if (line.contains("RES: Pay")) {
                        // RES: Pay daxil olmaqla bütün cavabı götür
                        int currentIndex = logs.indexOf(line);
                        int k = currentIndex + 1;

                        while (k < logs.size()) {
                            String next = logs.get(k);
                            if (!next.contains("[" + threadId + "]")) break; // başqa thread başladı
                            result.add(next);
                            k++;
                        }
                        break;
                    }
                }
            }
        }

        if (result.isEmpty()) {
            System.out.println("REQ: Pay və ya RES: Pay tapılmadı.");
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
            if(line.contains(",")){
                endIndex = line.indexOf(",", startIndex);
            }else {
                endIndex = line.indexOf("]", startIndex);
            }
            id = line.substring(startIndex, endIndex).trim();
        } else {
            System.out.println("ID tapılmadı.");
        }
        return id;
    }

    private List<String> billList(List<String> logs, String id) {
        String threadId = "";
        int startIndex = -1;

        // 1. "Detail list:" və id ilə uyğun sətri tap
        for (int i = 0; i < logs.size(); i++) {
            String line = logs.get(i);
            if (line.contains("Detail list:") && line.contains(id)) {
                // Geri gedərək REQ: GetBillList tap və threadId çıxart
                for (int j = i; j >= 0; j--) {
                    String prev = logs.get(j);
                    if (prev.contains("REQ: GetBillList")) {
                        int start = prev.indexOf('[');
                        int end = prev.indexOf(']', start);
                        if (start != -1 && end != -1) {
                            threadId = prev.substring(start + 1, end);
                            startIndex = j;
                            break;
                        }
                    }
                }
                break;
            }
        }

        if (startIndex == -1 || threadId == null) {
            System.out.println("REQ: GetBillList bloku tapılmadı.");
            return Collections.emptyList();
        }

        // 2. REQ və RES xətlərini yığ
        // Pay blokunu çıxart (REQ → RES qədər)
        List<String> result = new ArrayList<>();
        boolean started = false;

        for (String line : logs) {
            if (line.contains("[" + threadId + "]")) {
                if (!started && line.contains("REQ: GetBillList")) {
                    started = true;
                }
                if (started) {
                    result.add(line);

                    if (line.contains("RES: GetBillList")) {
                        // RES: Pay daxil olmaqla bütün cavabı götür
                        int currentIndex = logs.indexOf(line);
                        int k = currentIndex + 1;

                        while (k < logs.size()) {
                            String next = logs.get(k);
                            if (!next.contains("[" + threadId + "]")) break; // başqa thread başladı
                            result.add(next);
                            k++;
                        }
                        break;
                    }
                }
            }
        }

        if (result.isEmpty()) {
            System.out.println("REQ: BilList və ya RES: BillList tapılmadı.");
        }

        return result;
    }

}
