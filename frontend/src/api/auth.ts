import request from './request';
import type { AuthUser, LoginPayload, RegisterPayload } from '../types/auth';

/**
 * 认证接口。
 *
 * 令牌由后端写入 HttpOnly Cookie，前端不接触、也不存储令牌字符串。
 * 因此这里没有任何 token 读写逻辑，只需保证 axios 携带 Cookie 与 CSRF 头。
 */
export const authApi = {
  /** 当前登录用户；未登录时后端返回 401 */
  me(): Promise<AuthUser> {
    return request.get<AuthUser>('/api/auth/me');
  },

  login(payload: LoginPayload): Promise<AuthUser> {
    return request.post<AuthUser>('/api/auth/login', payload);
  },

  register(payload: RegisterPayload): Promise<AuthUser> {
    return request.post<AuthUser>('/api/auth/register', payload);
  },

  logout(): Promise<void> {
    return request.post<void>('/api/auth/logout');
  },

  /** 预热 CSRF 令牌，确保首个写请求可携带正确的 X-XSRF-TOKEN */
  ensureCsrf(): Promise<void> {
    return request.get<void>('/api/auth/csrf');
  },
};

export default authApi;
