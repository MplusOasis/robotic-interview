import { Check, ChevronDown } from 'lucide-react';
import {
  useCallback,
  useEffect,
  useId,
  useRef,
  useState,
  type CSSProperties,
  type KeyboardEvent,
} from 'react';
import { THEME_SKINS, type SkinId, type ThemeSkinOption } from '../constants/themeSkin';
import { useThemeSkin } from '../hooks/useThemeSkin';
import { getSkinOption } from '../utils/themeSkin';
import './ThemeSkinSwitcher.css';

/** 把风格的三色预览写进 CSS 变量，色板本身仍由 CSS 负责渲染 */
function swatchStyle(option: ThemeSkinOption): CSSProperties {
  return {
    '--swatch-1': option.swatch[0],
    '--swatch-2': option.swatch[1],
    '--swatch-3': option.swatch[2],
  } as CSSProperties;
}

interface ThemeSkinSwitcherProps {
  /** 允许调用方追加布局类，组件内部样式不受影响 */
  className?: string;
}

/**
 * 页面风格切换入口：按钮 + 下拉列表（listbox）。
 * 支持鼠标、键盘（方向键 / Home / End / Esc）、点击外部收起，
 * 选择结果经 useThemeSkin 写入 <html data-skin> 与 localStorage。
 */
export default function ThemeSkinSwitcher({ className }: ThemeSkinSwitcherProps) {
  const { skin, selectSkin } = useThemeSkin();
  const [open, setOpen] = useState(false);
  const [announcement, setAnnouncement] = useState('');
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const optionRefs = useRef(new Map<SkinId, HTMLLIElement>());
  const baseId = useId();
  const labelId = `${baseId}-label`;
  const listId = `${baseId}-list`;
  const valueId = `${baseId}-value`;
  const current = getSkinOption(skin);

  const close = useCallback((returnFocus: boolean) => {
    setOpen(false);
    if (returnFocus) triggerRef.current?.focus();
  }, []);

  // 展开后把焦点交给当前选中项，键盘用户可直接用方向键挑选
  useEffect(() => {
    if (!open) return;
    optionRefs.current.get(skin)?.focus();
  }, [open, skin]);

  // 点击组件外部收起
  useEffect(() => {
    if (!open) return;
    const handlePointerDown = (event: PointerEvent) => {
      if (rootRef.current?.contains(event.target as Node)) return;
      setOpen(false);
    };
    document.addEventListener('pointerdown', handlePointerDown);
    return () => document.removeEventListener('pointerdown', handlePointerDown);
  }, [open]);

  const handleSelect = (next: SkinId) => {
    selectSkin(next);
    setAnnouncement(`已切换到${getSkinOption(next).name}风格`);
    close(true);
  };

  const focusOptionAt = (index: number) => {
    const total = THEME_SKINS.length;
    optionRefs.current.get(THEME_SKINS[(index + total) % total].id)?.focus();
  };

  const handleTriggerKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
    event.preventDefault();
    setOpen(true);
  };

  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (!open) return;
    if (event.key === 'Escape') {
      event.stopPropagation();
      close(true);
      return;
    }
    if (event.key === 'Tab') {
      setOpen(false);
      return;
    }
    if (event.key === 'Home' || event.key === 'End') {
      event.preventDefault();
      focusOptionAt(event.key === 'Home' ? 0 : THEME_SKINS.length - 1);
      return;
    }
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
    event.preventDefault();
    const focusedId = (document.activeElement as HTMLElement | null)?.dataset.skinId;
    const currentIndex = THEME_SKINS.findIndex((option) => option.id === focusedId);
    focusOptionAt((currentIndex === -1 ? 0 : currentIndex) + (event.key === 'ArrowDown' ? 1 : -1));
  };

  const handleOptionKeyDown = (event: KeyboardEvent<HTMLLIElement>, optionId: SkinId) => {
    if (event.key !== 'Enter' && event.key !== ' ') return;
    event.preventDefault();
    handleSelect(optionId);
  };

  return (
    <div
      ref={rootRef}
      className={className ? `skin-switcher ${className}` : 'skin-switcher'}
      onKeyDown={handleKeyDown}
    >
      <span id={labelId} className="skin-switcher__label">页面风格</span>

      <button
        ref={triggerRef}
        type="button"
        className="skin-switcher__trigger"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={listId}
        aria-labelledby={`${labelId} ${valueId}`}
        onClick={() => setOpen((prev) => !prev)}
        onKeyDown={handleTriggerKeyDown}
      >
        <span className="skin-switcher__swatch" style={swatchStyle(current)} aria-hidden="true" />
        <span id={valueId} className="skin-switcher__value">{current.name}</span>
        <ChevronDown className="skin-switcher__chevron" aria-hidden="true" />
      </button>

      {open && (
        <ul id={listId} className="skin-switcher__panel" role="listbox" aria-labelledby={labelId}>
          {THEME_SKINS.map((option) => {
            const selected = option.id === skin;
            return (
              <li
                key={option.id}
                ref={(element) => {
                  if (element) optionRefs.current.set(option.id, element);
                  else optionRefs.current.delete(option.id);
                }}
                id={`${baseId}-${option.id}`}
                role="option"
                aria-selected={selected}
                tabIndex={-1}
                data-skin-id={option.id}
                className="skin-switcher__option"
                onClick={() => handleSelect(option.id)}
                onKeyDown={(event) => handleOptionKeyDown(event, option.id)}
              >
                <span className="skin-switcher__swatch" style={swatchStyle(option)} aria-hidden="true" />
                <span className="skin-switcher__option-text">
                  <span className="skin-switcher__option-name">{option.name}</span>
                  <span className="skin-switcher__option-hint">{option.hint}</span>
                </span>
                {selected && <Check className="skin-switcher__check" aria-hidden="true" />}
              </li>
            );
          })}
        </ul>
      )}

      <span className="skin-switcher__sr" role="status" aria-live="polite">{announcement}</span>
    </div>
  );
}
