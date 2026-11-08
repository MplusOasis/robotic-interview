import { useCallback, useEffect, useState } from 'react';
import { SKIN_STORAGE_KEY, type SkinId } from '../constants/themeSkin';
import { normalizeSkinId } from '../utils/themeSkin';

/** 首次渲染前先读本地存储，保证刷新后不会先闪一下默认风格。 */
function readStoredSkin(): SkinId {
  try {
    return normalizeSkinId(window.localStorage.getItem(SKIN_STORAGE_KEY));
  } catch {
    // 隐私模式或存储被禁用时退化为默认风格
    return normalizeSkinId(null);
  }
}

/**
 * 页面风格状态：负责读写 <html data-skin>、localStorage，并同步其它标签页。
 * 与 useTheme 一样是轻量 hook，不引入全局 Provider。
 */
export function useThemeSkin() {
  const [skin, setSkin] = useState<SkinId>(readStoredSkin);

  useEffect(() => {
    document.documentElement.dataset.skin = skin;
    try {
      window.localStorage.setItem(SKIN_STORAGE_KEY, skin);
    } catch {
      // 写入失败不影响本次会话的切换效果
    }
  }, [skin]);

  // 多个标签页同时打开时保持一致
  useEffect(() => {
    const handleStorage = (event: StorageEvent) => {
      if (event.key !== null && event.key !== SKIN_STORAGE_KEY) return;
      setSkin(normalizeSkinId(event.newValue));
    };
    window.addEventListener('storage', handleStorage);
    return () => window.removeEventListener('storage', handleStorage);
  }, []);

  const selectSkin = useCallback((next: SkinId) => setSkin(normalizeSkinId(next)), []);

  return { skin, selectSkin };
}
