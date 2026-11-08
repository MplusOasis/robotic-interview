import { DEFAULT_SKIN_ID, THEME_SKINS, type SkinId, type ThemeSkinOption } from '../constants/themeSkin';

const SKIN_IDS = new Set<string>(THEME_SKINS.map((skin) => skin.id));

/** 判断任意来源的字符串是否为已知风格，用于过滤 localStorage 中的脏数据。 */
export function isSkinId(value: unknown): value is SkinId {
  return typeof value === 'string' && SKIN_IDS.has(value);
}

/** 未知、空值或旧版本遗留值一律回落到默认风格，保证切换入口永远有可选项。 */
export function normalizeSkinId(value: unknown): SkinId {
  return isSkinId(value) ? value : DEFAULT_SKIN_ID;
}

/** 按 id 取风格元信息；未知 id 返回默认风格，调用方无需再判空。 */
export function getSkinOption(skinId: SkinId): ThemeSkinOption {
  return THEME_SKINS.find((skin) => skin.id === skinId) ?? THEME_SKINS[0];
}
