package stream.api.adapter.log.mapper;


import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.factory.Mappers;
import stream.api.adapter.log.dao.entity.LogEntity;
import stream.api.adapter.log.model.request.LogRequest;

import java.util.List;

import static org.mapstruct.InjectionStrategy.CONSTRUCTOR;
import static org.mapstruct.ReportingPolicy.IGNORE;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING,
        injectionStrategy = CONSTRUCTOR,
        unmappedSourcePolicy = IGNORE,
        unmappedTargetPolicy = IGNORE)
public interface LogMapper {

    LogMapper INSTANCE = Mappers.getMapper(LogMapper.class);


    LogRequest toView(LogEntity entity);

    LogEntity toEntity(LogRequest request);

    List<LogEntity> toEntity(List<LogRequest> request);


//    @AfterMapping
//    default void setDefaults(@MappingTarget UserEntity entity) {
//        entity.setStatus(ACTIVE);
//        entity.setRole(USER);
//    }
}
