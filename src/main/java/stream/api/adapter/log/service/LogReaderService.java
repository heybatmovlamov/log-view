package stream.api.adapter.log.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.coyote.RequestInfo;
import org.springframework.stereotype.Service;
import stream.api.adapter.log.dao.entity.LogEntity;
import stream.api.adapter.log.model.request.LogRequest;
import stream.api.adapter.log.model.response.InfoResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogReaderService {

    private  final LogSaverService saverService;

    private List<String> readErrorLogs(String filePath) {
        try (Stream<String> lines = Files.lines(Paths.get(filePath))) {
            return  lines
                    .filter(line -> line.contains("[ERR]"))
                    .filter(line -> line.contains("java.lang.") || line.contains("stream.api.adapter"))
                    .collect(Collectors.toList());

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private List<String> readLogs(String filePath , String code) {
        try (Stream<String> lines = Files.lines(Paths.get(filePath))) {
                return lines
                        .filter(line -> line.contains(code))
                        .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private List<LogRequest> extractField(List<String> log) {
        String timestampPattern = "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}"; // Timestamp
        String threadPattern = "\\[T\\d+\\]";
        String exceptionPattern = "- (.*)";

        String timestamp = "";
        String threadId = "";
        String exception = "";

        Pattern timestampPat = Pattern.compile(timestampPattern);
        Pattern threadPat = Pattern.compile(threadPattern);
        Pattern exceptionPat = Pattern.compile(exceptionPattern);

        List<LogRequest> logRequests = new ArrayList<>();
        for(String logLine : log) {
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

//    public  List<LogEntity> serviceRun(String filePath) {
//        List<String> strings = readErrorLogs(filePath);
//        List<LogRequest> logRequests = extractField(strings);
//
//        return saverService.saveAll(logRequests);
//    }

    public void findByOrdinatorOrReference(String filePath,String serial){
        List<String> allLogs = readAllLogs(filePath);
        List<String> strings = readLogs(filePath, serial);

        if (strings == null || strings.isEmpty()) {
            System.out.println("No log lines found for the given serial.");
            return;
        }

        String paySerial = strings.get(strings.size() - 1);

        List<LogRequest> requests = extractField(strings);
        if (requests == null || requests.isEmpty()) {
            System.out.println("Could not extract thread/timestamp from lines.");
            return;
        }
        String threadId = Optional.ofNullable(requests.get(0).getThreadId()).orElse("");
        String timestamp = Optional.ofNullable(requests.get(0).getTimestamp()).orElse("");
        if (threadId.isEmpty() || timestamp.isEmpty()) {
            System.out.println("ThreadId or timestamp is empty.");
            return;
        }

        List<String> register = getRegister(allLogs, threadId, timestamp);
        List<String> payment = getPayment(allLogs, paySerial);

        if (register.isEmpty()) {
            System.out.println("Register block not found.");
            return;
        }

        String detailList = register.stream().filter(line -> line.contains("Detail list: [id:")).toList().toString();
        String id = getId(detailList);
        if (id == null || id.isBlank()) {
            System.out.println("ID not found in register detail list.");
            return;
        }
        List<String> billList = getBillList(allLogs, id);

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

    public static List<String> getRegister(List<String> logs, String threadId, String targetTimestamp) {
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

    public static List<String> getPayment(List<String> logs, String serial) {
        int serialIndex = -1;
        String threadId = null;

        // 1. Serial olan və REQ: Pay blokuna aid olan threadId-ni tap
        for (int i = 0; i < logs.size(); i++) {
            String line = logs.get(i);
            if (line.contains("Serial:") && line.contains(serial)) {

                // Geri gedərək "REQ: Pay" sətrini tapmağa çalış
                for (int j = i; j >= 0; j--) {
                    String previousLine = logs.get(j);
                    if (previousLine.contains("REQ: Pay")) {
                        // threadId-ni tap
                        int start = previousLine.indexOf('[');
                        int end = previousLine.indexOf(']', start);
                        if (start != -1 && end != -1) {
                            threadId = previousLine.substring(start + 1, end);
                            serialIndex = j; // REQ: Pay sətrinin index-i
                            break;
                        }
                    }
                    // Əgər REQ: GetPayment tapılıbsa, bu blok deyil → dayandır
                    if (previousLine.contains("REQ: GetPayment")) {
                        break;
                    }
                }
                break;
            }
        }

        if (serialIndex == -1 || threadId == null) {
            System.out.println("REQ: Pay bloku tapılmadı.");
            return Collections.emptyList();
        }

        // 2. REQ: Pay-dən başlayaraq RES: Pay sətrinə qədər yığ
        List<String> result = new ArrayList<>();
        boolean started = false;

        for (int i = serialIndex; i < logs.size(); i++) {
            String line = logs.get(i);
            if (line.contains("[" + threadId + "]")) {
                if (!started && line.contains("REQ: Pay")) {
                    started = true;
                }

                if (started) {
                    result.add(line);
                    if (line.contains("RES: Pay")) {
                        break;
                    }
                }
            } else if (started) {
                // Əgər başqa thread-ə keçibsə və artıq yığmağa başlamışıqsa, bitir
                break;
            }
        }

        return result;
    }

    private String getId(String line){
        String search = "id: ";
        String id = "";
        if (line == null || line.isEmpty()) {
            System.out.println("Line is empty while searching ID.");
            return id;
        }
        int startIndex = line.indexOf(search);
        if (startIndex != -1) {
            startIndex += search.length();
            int endIndex = line.indexOf(",", startIndex);
            if (endIndex == -1) {
                endIndex = line.indexOf("]", startIndex);
            }
            if (endIndex == -1) {
                endIndex = line.length();
            }
            if (startIndex <= endIndex && startIndex >= 0 && endIndex <= line.length()) {
                id = line.substring(startIndex, endIndex).trim();
            }
        } else {
            System.out.println("ID tapılmadı.");
        }
        return id;
    }

    public List<String> getBillList(List<String> logs, String id){
        int serialIndex = -1;
        String threadId = null;

        for (int i = 0; i < logs.size(); i++) {
            String line = logs.get(i);
            if (line.contains("id:") && line.contains(id)) {

                for (int j = i; j >= 0; j--) {
                    String previousLine = logs.get(j);
                    if (previousLine.contains("REQ: GetBillList")) {
                        int start = previousLine.indexOf('[');
                        int end = previousLine.indexOf(']', start);
                        if (start != -1 && end != -1) {
                            threadId = previousLine.substring(start + 1, end);
                            serialIndex = j;
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

        if (serialIndex == -1 || threadId == null) {
            System.out.println("REQ: GetBillList bloku tapılmadı.");
            return Collections.emptyList();
        }

        List<String> result = new ArrayList<>();
        boolean started = false;

        for (int i = serialIndex; i < logs.size(); i++) {
            String line = logs.get(i);
            if (line.contains("[" + threadId + "]")) {
                if (!started && line.contains("REQ: GetBillList")) {
                    started = true;
                }

                if (started) {
                    result.add(line);
                    if (line.contains("RES: GetBillList")) {
                        break;
                    }
                }
            } else if (started) {
                break;
            }
        }

        return result;
    }

}
