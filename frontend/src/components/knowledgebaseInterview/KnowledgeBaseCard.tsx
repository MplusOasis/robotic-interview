import { useEffect, useRef, useState } from 'react';
import {
  Database,
  Layers,
  Loader2,
  Settings2,
  Sparkles,
} from 'lucide-react';
import {
  knowledgeBaseApi,
  type KnowledgeBaseItem,
  type KnowledgeBaseQuestion,
} from '../../api/knowledgebase';
import { isQuestionGenerationActive } from '../../pages/questionGenerationStatus';

interface KbStats {
  total: number;
  draft: number;
  active: number;
  archived: number;
}

const EMPTY_STATS: KbStats = { total: 0, draft: 0, active: 0, archived: 0 };

function reduceStats(questions: KnowledgeBaseQuestion[]): KbStats {
  return questions.reduce<KbStats>(
    (acc, q) => {
      acc.total += 1;
      if (q.status === 'DRAFT') acc.draft += 1;
      else if (q.status === 'ACTIVE') acc.active += 1;
      else if (q.status === 'ARCHIVED') acc.archived += 1;
      return acc;
    },
    { ...EMPTY_STATS }
  );
}

interface KnowledgeBaseCardProps {
  kb: KnowledgeBaseItem;
  onStart: (kb: KnowledgeBaseItem) => void;
  onGenerate: (kb: KnowledgeBaseItem) => void;
  onManage: (kb: KnowledgeBaseItem) => void;
}

export default function KnowledgeBaseCard({
  kb,
  onStart,
  onGenerate,
  onManage,
}: KnowledgeBaseCardProps) {
  const ref = useRef<HTMLDivElement>(null);
  const [stats, setStats] = useState<KbStats | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    const node = ref.current;
    if (!node) return;

    let cancelled = false;
    const fetchStats = () => {
      setLoading(true);
      knowledgeBaseApi
        .listQuestions(kb.id)
        .then(list => {
          if (!cancelled) setStats(reduceStats(list));
        })
        .catch(() => {
          if (!cancelled) setStats({ ...EMPTY_STATS });
        })
        .finally(() => {
          if (!cancelled) setLoading(false);
        });
    };

    if (typeof IntersectionObserver === 'undefined') {
      fetchStats();
      return () => {
        cancelled = true;
      };
    }

    const observer = new IntersectionObserver(
      entries => {
        if (entries.some(entry => entry.isIntersecting)) {
          observer.disconnect();
          fetchStats();
        }
      },
      { rootMargin: '200px' }
    );
    observer.observe(node);

    return () => {
      cancelled = true;
      observer.disconnect();
    };
  }, [kb.id]);

  const statsCell = loading || !stats
    ? {
        total: '–' as string | number,
        draft: '–',
        active: '–',
        archived: '–',
      }
    : stats;
  const generating = isQuestionGenerationActive(kb.questionGenStatus);

  return (
    <div
      ref={ref}
      className="neon-card neon-border-flow flex flex-col p-5"
    >
      <div className="flex items-start gap-3 mb-4">
        <div className="w-11 h-11 rounded-xl bg-primary-50 dark:bg-gradient-to-br dark:from-neon-purple/35 dark:to-neon-blue/30 dark:shadow-[0_0_16px_-4px_rgba(104,85,255,0.8)] text-primary-500 dark:text-neon-cyan flex items-center justify-center shrink-0 transition-transform duration-300 hover:scale-110 hover:rotate-6">
          <Database className="w-5 h-5" />
        </div>
        <div className="min-w-0 flex-1">
          <h3 className="font-semibold text-slate-900 dark:text-white truncate">{kb.name}</h3>
          <p className="text-xs text-slate-400 truncate mt-0.5">{kb.originalFilename}</p>
        </div>
        <div className={`shrink-0 px-2 py-0.5 rounded text-xs font-medium ${
          generating
            ? 'bg-primary-50 dark:bg-primary-900/20 text-primary-600 dark:text-primary-400'
            : stats && stats.active > 0
            ? 'bg-emerald-50 dark:bg-emerald-900/20 text-emerald-600 dark:text-emerald-400'
            : 'bg-slate-100 dark:bg-slate-700 text-slate-400'
        }`}>
          {generating
            ? kb.questionGenStatus === 'QUEUED' ? '等待生成' : '生成中'
            : loading ? '加载中' : stats && stats.active > 0 ? '可面试' : '未启用'}
        </div>
      </div>

      {loading ? (
        <div className="flex items-center gap-2 text-xs text-slate-400 mb-4">
          <Loader2 className="w-3 h-3 animate-spin" /> 统计题目中…
        </div>
      ) : (
        <div className="grid grid-cols-4 gap-2 mb-5 text-center">
          <StatCell label="总数" value={statsCell.total} />
          <StatCell label="草稿" value={statsCell.draft} />
          <StatCell label="已启用" value={statsCell.active} highlight={!!stats && stats.active > 0} />
          <StatCell label="已归档" value={statsCell.archived} />
        </div>
      )}

      <div className="mt-auto flex flex-col gap-2">
        <button
          onClick={() => onStart(kb)}
          disabled={!stats || stats.active === 0}
          className="btn-primary w-full inline-flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg text-sm disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:translate-y-0 whitespace-nowrap"
        >
          <Layers className="w-4 h-4" />
          开始面试
        </button>
        <div className="grid grid-cols-2 gap-2">
          <button
            onClick={() => onGenerate(kb)}
            disabled={generating}
            className="btn-secondary inline-flex items-center justify-center gap-1.5 px-3 py-2 rounded-lg text-xs disabled:opacity-40 disabled:cursor-not-allowed whitespace-nowrap"
          >
            {generating
              ? <Loader2 className="w-3.5 h-3.5 animate-spin" />
              : <Sparkles className="w-3.5 h-3.5" />}
            {generating ? '生成中' : '生成题目'}
          </button>
          <button
            onClick={() => onManage(kb)}
            className="btn-secondary inline-flex items-center justify-center gap-1.5 px-3 py-2 rounded-lg text-xs whitespace-nowrap"
          >
            <Settings2 className="w-3.5 h-3.5" />
            管理题库
          </button>
        </div>
      </div>
    </div>
  );
}

function StatCell({
  label,
  value,
  highlight = false,
}: {
  label: string;
  value: string | number;
  highlight?: boolean;
}) {
  return (
    <div className="rounded-lg bg-slate-50 dark:bg-[rgba(11,20,48,0.65)] dark:border dark:border-[rgba(104,85,255,0.14)] py-2">
      <p className={`text-lg font-bold ${highlight ? 'text-primary-600 dark:text-primary-400' : 'text-slate-900 dark:text-white'}`}>
        {value}
      </p>
      <p className="text-xs text-slate-400">{label}</p>
    </div>
  );
}
