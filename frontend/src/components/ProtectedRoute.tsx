import { Navigate, useLocation } from 'react-router-dom';
import type { ReactNode } from 'react';
import { useAuth } from '../contexts/AuthContext';

interface ProtectedRouteProps {
  children: ReactNode;
}

/**
 * 路由守卫。
 *
 * 注意：这里只做"体验层"的跳转，真正的权限判定在后端。
 * 前端隐藏入口不等于安全，所有接口仍由 Spring Security 校验。
 */
export default function ProtectedRoute({ children }: ProtectedRouteProps) {
  const { isAuthenticated, loading } = useAuth();
  const location = useLocation();

  if (loading) {
    return (
      <div className="flex items-center justify-center min-h-screen bg-gradient-to-br from-slate-50 to-indigo-50 dark:from-slate-900 dark:to-slate-800">
        <div className="text-center">
          <div className="w-10 h-10 border-4 border-slate-200 border-t-primary-500 rounded-full mx-auto mb-4 animate-spin" />
          <p className="text-slate-500 dark:text-slate-400">正在验证登录状态…</p>
        </div>
      </div>
    );
  }

  if (!isAuthenticated) {
    // 记录来源，登录后跳回原页面
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  }

  return <>{children}</>;
}
