package interview.guide.modules.interviewschedule.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interviewschedule.model.CreateInterviewRequest;
import interview.guide.modules.interviewschedule.model.InterviewScheduleDTO;
import interview.guide.common.security.CurrentUserProvider;
import interview.guide.modules.interviewschedule.model.InterviewScheduleEntity;
import interview.guide.modules.interviewschedule.model.InterviewStatus;
import interview.guide.modules.interviewschedule.repository.InterviewScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InterviewScheduleService {

    private final InterviewScheduleRepository repository;
    private final CurrentUserProvider currentUserProvider;

    private static final String[] COPYABLE_FIELDS = {
        "companyName", "position", "interviewTime", "interviewType",
        "meetingLink", "roundNumber", "interviewer", "notes"
    };

    @Transactional
    public InterviewScheduleDTO create(CreateInterviewRequest request) {
        InterviewScheduleEntity entity = new InterviewScheduleEntity();
        BeanUtils.copyProperties(request, entity);
        // 归属用户只能来自认证上下文
        entity.setUserId(currentUserProvider.requireUserId());
        entity.setStatus(InterviewStatus.PENDING);

        return toDTO(repository.save(entity));
    }

    @Transactional
    public InterviewScheduleDTO update(Long id, CreateInterviewRequest request) {
        InterviewScheduleEntity entity = getByIdOrThrow(id);
        BeanUtils.copyProperties(request, entity, "id", "status");
        return toDTO(repository.save(entity));
    }

    @Transactional
    public void delete(Long id) {
        // 归属校验：非本人日程按"不存在"处理
        repository.delete(getByIdOrThrow(id));
    }

    @Transactional
    public InterviewScheduleDTO updateStatus(Long id, InterviewStatus status) {
        InterviewScheduleEntity entity = getByIdOrThrow(id);
        entity.setStatus(status);
        return toDTO(repository.save(entity));
    }

    public List<InterviewScheduleDTO> getAll(String status, LocalDateTime start, LocalDateTime end) {
        // 列表自动过滤为当前用户的日程
        Long userId = currentUserProvider.requireUserId();
        List<InterviewScheduleEntity> entities;

        if (start != null && end != null) {
            entities = repository.findByUserIdAndInterviewTimeBetweenOrderByInterviewTimeAsc(
                userId, start, end);
        } else {
            entities = repository.findAllByUserIdOrderByInterviewTimeDesc(userId);
        }

        if (status != null) {
            InterviewStatus target = InterviewStatus.valueOf(status);
            entities = entities.stream().filter(e -> e.getStatus() == target).toList();
        }

        return entities.stream()
            .map(this::toDTO)
            .collect(Collectors.toList());
    }

    public InterviewScheduleDTO getById(Long id) {
        return toDTO(getByIdOrThrow(id));
    }

    /**
     * 取日程并校验归属。跨用户访问返回"不存在"，不泄露资源存在性。
     */
    private InterviewScheduleEntity getByIdOrThrow(Long id) {
        return repository
            .findByIdAndUserId(id, currentUserProvider.requireUserId())
            .orElseThrow(() -> new BusinessException(
                ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND, "面试日程不存在"));
    }

    private InterviewScheduleDTO toDTO(InterviewScheduleEntity entity) {
        InterviewScheduleDTO dto = new InterviewScheduleDTO();
        BeanUtils.copyProperties(entity, dto);
        return dto;
    }
}
