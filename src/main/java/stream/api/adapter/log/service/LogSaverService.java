package stream.api.adapter.log.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import stream.api.adapter.log.dao.entity.LogEntity;
import stream.api.adapter.log.dao.repository.LogRepository;
import stream.api.adapter.log.mapper.LogMapper;
import stream.api.adapter.log.model.request.LogRequest;

import java.util.List;

@Service
@RequiredArgsConstructor
public class LogSaverService {

    private final LogRepository logRepository;
    private final LogMapper mapper;

    public  List<LogEntity> saveAll(List<LogRequest> requests){
        List<LogEntity> entity = mapper.toEntity(requests);
        return logRepository.saveAll(entity);
    }

    //1096248531
}
