/**
 * 页面风格（Skin）目录。
 *
 * 与「浅色 / 深色模式」正交：模式决定明暗，风格只决定这套配色偏冷还是偏暖。
 * 具体色值在 `src/index.css` 的 `html[data-skin="..."]` 中定义，这里只保留
 * 切换组件需要的元信息，避免颜色散落到 JS 里。
 */
export type SkinId = 'aurora' | 'jade' | 'ember' | 'graphite';

export interface ThemeSkinOption {
  id: SkinId;
  /** 展示名称 */
  name: string;
  /** 一句话说明，出现在下拉项副标题与无障碍描述中 */
  hint: string;
  /** 预览色点：主色 / 辅色 / 高光色，顺序与 CSS 中的 accent 变量一致 */
  swatch: readonly [string, string, string];
}

export const SKIN_STORAGE_KEY = 'app-skin';

export const DEFAULT_SKIN_ID: SkinId = 'aurora';

export const THEME_SKINS: readonly ThemeSkinOption[] = [
  {
    id: 'aurora',
    name: '深海霓虹',
    hint: '紫蓝霓虹，原始风格',
    swatch: ['#6855ff', '#268bff', '#00d9ff'],
  },
  {
    id: 'jade',
    name: '翡翠青瓷',
    hint: '青绿冷调，清爽安静',
    swatch: ['#0b8f6c', '#38bdf8', '#7ef2d4'],
  },
  {
    id: 'ember',
    name: '暮光琥珀',
    hint: '暖橙琥珀，柔和护眼',
    swatch: ['#c25727', '#fb923c', '#fbbf24'],
  },
  {
    id: 'graphite',
    name: '极简石墨',
    hint: '低饱和中性灰，专注内容',
    swatch: ['#616d7f', '#94a3b8', '#cbd5e1'],
  },
];
