package interview.guide.modules.user.dto;

import interview.guide.modules.user.model.AppUserEntity;

import java.time.LocalDateTime;

/**
 * 用户对外视图。不包含 passwordHash 等任何敏感字段。
 */
public record UserDTO(
    Long id,
    String username,
    String email,
    String role,
    String status,
    LocalDateTime createdAt,
    LocalDateTime lastLoginAt
) {
    public static UserDTO from(AppUserEntity entity) {
        if (entity == null) {
            return null;
        }
        return new UserDTO(
            entity.getId(),
            entity.getUsername(),
            entity.getEmail(),
            entity.getRole() == null ? null : entity.getRole().name(),
            entity.getStatus() == null ? null : entity.getStatus().name(),
            entity.getCreatedAt(),
            entity.getLastLoginAt()
        );
    }
}
