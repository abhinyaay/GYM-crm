package com.gymops.classes;

import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import com.gymops.classes.dto.ClassDtos.ClassTypeRequest;
import com.gymops.classes.dto.ClassDtos.ClassTypeResponse;
import com.gymops.common.exception.ConflictException;
import com.gymops.common.exception.ResourceNotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Class catalogue (organisation-wide, not branch scoped). */
@Service
@RequiredArgsConstructor
public class ClassTypeService {

    private final ClassTypeRepository repository;
    private final ClassSessionRepository sessionRepository;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public List<ClassTypeResponse> list(boolean includeInactive) {
        List<ClassType> types = includeInactive ? repository.findAllByOrderByNameAsc() : repository.findByActiveTrueOrderByNameAsc();
        return types.stream().map(ClassTypeResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ClassTypeResponse get(Long id) {
        return ClassTypeResponse.from(load(id));
    }

    @Transactional
    public ClassTypeResponse create(ClassTypeRequest r) {
        if (repository.existsByName(r.name().trim(), null)) {
            throw new ConflictException("A class type named '" + r.name().trim() + "' already exists");
        }
        ClassType type = repository.saveAndFlush(new ClassType(r.name(), r.description(), r.category(), r.intensity(),
                r.defaultDurationMin(), r.defaultCapacity()));
        audit.record(AuditEvent.of("CLASS_TYPE", type.getId(), "CREATED").with("name", type.getName()));
        return ClassTypeResponse.from(type);
    }

    @Transactional
    public ClassTypeResponse update(Long id, ClassTypeRequest r) {
        ClassType type = load(id);
        if (repository.existsByName(r.name().trim(), id)) {
            throw new ConflictException("A class type named '" + r.name().trim() + "' already exists");
        }
        type.update(r.name(), r.description(), r.category(), r.intensity(), r.defaultDurationMin(), r.defaultCapacity(),
                r.active() == null ? type.isActive() : r.active());
        repository.flush();
        audit.record(AuditEvent.of("CLASS_TYPE", id, "UPDATED").with("active", type.isActive()));
        return ClassTypeResponse.from(type);
    }

    /** Hard delete only while unused; a type with history must be deactivated instead. */
    @Transactional
    public void delete(Long id) {
        ClassType type = load(id);
        if (sessionRepository.countByClassTypeId(id) > 0) {
            throw new ConflictException("Class type '" + type.getName() + "' has scheduled or past classes; deactivate it instead");
        }
        repository.delete(type);
        audit.record(AuditEvent.of("CLASS_TYPE", id, "DELETED").with("name", type.getName()));
    }

    public ClassType load(Long id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Class type", id));
    }
}
