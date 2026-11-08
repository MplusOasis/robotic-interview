import {motion} from 'framer-motion';
import {calculatePercentage} from '../utils/score';

interface ScoreProgressBarProps {
  label: string;
  score: number;
  maxScore: number;
  color?: string;
  delay?: number;
  className?: string;
}

/**
 * 分数进度条组件
 */
export default function ScoreProgressBar({
  label,
  score,
  maxScore,
  color = 'bg-primary-500',
  delay = 0,
  className = ''
}: ScoreProgressBarProps) {
  const percentage = calculatePercentage(score, maxScore);

  return (
      <div className={`bg-slate-50 dark:bg-[rgb(var(--skin-navy-rgb)/0.6)] dark:border dark:border-[rgb(var(--skin-accent)/0.14)] rounded-lg p-3 ${className}`}>
          <div className="text-xs text-slate-500 dark:text-slate-400 mb-1">{label}</div>
      <div className="flex items-center gap-2">
          <div className="flex-1 h-2 bg-slate-200 dark:bg-[rgb(var(--skin-surface-deep)/0.9)] rounded-full overflow-hidden dark:shadow-[inset_0_0_6px_rgb(var(--skin-shadow)/0.9)]">
          <motion.div
            className={`h-full ${color} dark:neon-progress rounded-full dark:shadow-[0_0_10px_rgb(var(--skin-accent-2)/0.8)]`}
            initial={{ width: 0 }}
            animate={{ width: `${percentage}%` }}
            transition={{ duration: 0.8, delay }}
          />
        </div>
          <span className="text-sm font-semibold text-slate-700 dark:text-slate-300 w-8 text-right">
          {score}/{maxScore}
        </span>
      </div>
    </div>
  );
}
