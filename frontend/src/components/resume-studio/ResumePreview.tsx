import {useEffect, useRef, useState, type CSSProperties} from 'react';
import ResumeTemplate from './ResumeTemplate';
import type {ResumeContent} from '../../types/resumeDocument';

interface ResumePreviewProps {
  content: ResumeContent;
}

const A4_WIDTH_PX = 794; // 210mm @ 96dpi

/** 右侧 A4 实时预览。缩放只影响屏幕显示，打印副本始终按真实 A4 输出。 */
export default function ResumePreview({content}: ResumePreviewProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [scale, setScale] = useState(1);

  useEffect(() => {
    const element = containerRef.current;
    if (!element || typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(entries => {
      const width = entries[0]?.contentRect.width ?? A4_WIDTH_PX;
      const next = Math.min(1, Math.max(0.55, (width - 48) / A4_WIDTH_PX));
      setScale(Number(next.toFixed(3)));
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  return (
    <div className="flex flex-col min-h-0 h-full">
      <div className="flex items-center justify-between px-1 pb-2 text-xs text-slate-500 dark:text-slate-400">
        <span>实时预览 · A4 210 × 297 mm</span>
        <span>缩放 {Math.round(scale * 100)}%</span>
      </div>
      <div
        ref={containerRef}
        className="flex-1 min-h-0 overflow-auto scrollbar-thin rounded-2xl border border-slate-200 dark:border-[rgba(104,85,255,0.18)] bg-slate-100 dark:bg-[rgba(7,13,32,0.55)] p-6"
      >
        <div className="flex justify-center">
          <div style={{zoom: scale} as CSSProperties}>
            <ResumeTemplate content={content}/>
          </div>
        </div>
      </div>
    </div>
  );
}
