package stream.api.adapter.log.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class LogReaderService {

    public static List<String> readErrorLogs(String filePath) {
        try (Stream<String> lines = Files.lines(Paths.get(filePath))) {
            return  lines
                    .filter(line -> line.contains("[ERR]"))
                    .collect(Collectors.toList());

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        String log = "C:/Users/user/Desktop/stream-2025-09-06.log";

        List<String> strings = readErrorLogs(log);
        strings.forEach(System.out::println);
    }
}
