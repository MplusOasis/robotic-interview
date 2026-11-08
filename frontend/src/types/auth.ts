/**
 * 认证相关类型（与后端 UserDTO / Result 结构保持一致）
 */

export type UserRole = 'USER' | 'ADMIN';
export type UserStatus = 'ACTIVE' | 'DISABLED';

export interface AuthUser {
  id: number;
  username: string;
  email: string | null;
  role: UserRole;
  status: UserStatus;
  createdAt: string | null;
  lastLoginAt: string | null;
}

export interface LoginPayload {
  username: string;
  password: string;
}

export interface RegisterPayload {
  username: string;
  password: string;
  email?: string;
}

/** 认证相关的业务错误码（与后端 ErrorCode 对应） */
export const AUTH_ERROR_CODES = {
  INVALID_CREDENTIALS: 12001,
  TOKEN_INVALID: 12002,
  TOKEN_EXPIRED: 12003,
  FORBIDDEN: 12004,
  USER_NOT_FOUND: 12005,
  USER_ALREADY_EXISTS: 12006,
  REGISTRATION_DISABLED: 12009,
} as const;

/** 会话失效事件名：由 request 拦截器触发，AuthContext 监听 */
export const AUTH_EXPIRED_EVENT = 'auth:expired';
