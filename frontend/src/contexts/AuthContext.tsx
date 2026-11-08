import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { authApi } from '../api/auth';
import { AUTH_EXPIRED_EVENT, type AuthUser, type LoginPayload, type RegisterPayload } from '../types/auth';
import { getErrorMessage } from '../api/request';

interface AuthContextValue {
  user: AuthUser | null;
  loading: boolean;
  isAuthenticated: boolean;
  isAdmin: boolean;
  login: (payload: LoginPayload) => Promise<void>;
  register: (payload: RegisterPayload) => Promise<void>;
  logout: () => Promise<void>;
  refresh: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [loading, setLoading] = useState(true);
  // 防止并发 refresh 造成状态抖动
  const refreshingRef = useRef<Promise<void> | null>(null);

  const refresh = useCallback(async () => {
    if (refreshingRef.current) {
      return refreshingRef.current;
    }
    const task = (async () => {
      try {
        const current = await authApi.me();
        setUser(current);
      } catch {
        // 未登录或会话过期：保持未认证状态，不抛错
        setUser(null);
      } finally {
        refreshingRef.current = null;
      }
    })();
    refreshingRef.current = task;
    return task;
  }, []);

  // 首次挂载：先确保拿到 CSRF 令牌，再取当前用户
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        await authApi.ensureCsrf();
      } catch {
        // CSRF 预热失败不阻塞启动，后续写请求会再次尝试
      }
      if (!cancelled) {
        await refresh();
        setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [refresh]);

  // 任意请求返回 401 时，清空用户态（ProtectedRoute 会自动跳转登录页）
  useEffect(() => {
    const handler = () => setUser(null);
    window.addEventListener(AUTH_EXPIRED_EVENT, handler);
    return () => window.removeEventListener(AUTH_EXPIRED_EVENT, handler);
  }, []);

  const login = useCallback(async (payload: LoginPayload) => {
    const current = await authApi.login(payload);
    setUser(current);
  }, []);

  const register = useCallback(async (payload: RegisterPayload) => {
    const current = await authApi.register(payload);
    setUser(current);
  }, []);

  const logout = useCallback(async () => {
    try {
      await authApi.logout();
    } catch (error) {
      // 退出失败也要清理本地状态，避免残留他人数据
      console.warn('退出登录请求失败：', getErrorMessage(error));
    } finally {
      setUser(null);
    }
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      loading,
      isAuthenticated: user !== null,
      isAdmin: user?.role === 'ADMIN',
      login,
      register,
      logout,
      refresh,
    }),
    [user, loading, login, register, logout, refresh],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error('useAuth 必须在 AuthProvider 内使用');
  }
  return ctx;
}

export default AuthContext;
