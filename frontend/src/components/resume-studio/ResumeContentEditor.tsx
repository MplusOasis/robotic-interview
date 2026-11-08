import {useEffect, useState, type ReactNode} from 'react';
import {ArrowDown, ArrowUp, ChevronDown, ChevronUp, Plus, Trash2} from 'lucide-react';
import {
  newLocalId,
  type ResumeAward,
  type ResumeBasicInfo,
  type ResumeContent,
  type ResumeEducation,
  type ResumeHighlight,
  type ResumeInternship,
  type ResumeProject,
} from '../../types/resumeDocument';

interface ResumeContentEditorProps {
  content: ResumeContent;
  /** 有待确认的 AI 提案或请求进行中时锁定编辑，避免基于旧版本继续改。 */
  disabled?: boolean;
  onChange: (next: ResumeContent) => void;
}

const BASIC_FIELDS: { key: keyof ResumeBasicInfo; label: string; placeholder: string }[] = [
  {key: 'name', label: '姓名', placeholder: '张三'},
  {key: 'phone', label: '电话', placeholder: '13800138000'},
  {key: 'email', label: '邮箱', placeholder: 'name@example.com'},
  {key: 'school', label: '学校', placeholder: 'XX 大学'},
  {key: 'major', label: '专业', placeholder: '计算机科学与技术'},
  {key: 'degree', label: '学历', placeholder: '本科'},
  {key: 'targetPosition', label: '求职意向', placeholder: 'Java 后端开发工程师'},
  {key: 'githubUrl', label: 'GitHub', placeholder: 'https://github.com/...'},
  {key: 'blogUrl', label: '博客', placeholder: 'https://...'},
];

const SHORT_TEXT = 200;
const LONG_TEXT = 4000;

function Field({label, value, onChange, disabled, placeholder, textarea, maxLength}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
  placeholder?: string;
  textarea?: boolean;
  maxLength?: number;
}) {
  const className = 'dark-input px-2 py-1.5 rounded-lg text-sm text-slate-700 dark:text-slate-200 disabled:opacity-60';
  return (
    <label className="flex flex-col gap-1 text-xs text-slate-500 dark:text-slate-400">
      <span>{label}</span>
      {textarea ? (
        <textarea
          value={value}
          onChange={event => onChange(event.target.value)}
          disabled={disabled}
          rows={3}
          maxLength={maxLength ?? LONG_TEXT}
          placeholder={placeholder}
          className={`${className} resize-y`}
        />
      ) : (
        <input
          value={value}
          onChange={event => onChange(event.target.value)}
          disabled={disabled}
          maxLength={maxLength ?? SHORT_TEXT}
          placeholder={placeholder}
          className={className}
        />
      )}
    </label>
  );
}

/** 逗号 / 顿号分隔的标签输入，失焦时才提交，避免边输入边被 join 重排光标。 */
function TagInput({value, disabled, placeholder, onCommit}: {
  value: string[];
  disabled?: boolean;
  placeholder?: string;
  onCommit: (next: string[]) => void;
}) {
  const joined = value.join('、');
  const [text, setText] = useState(joined);

  useEffect(() => {
    setText(joined);
  }, [joined]);

  const commit = () => {
    const next = text
      .split(/[,，、;；]/)
      .map(item => item.trim())
      .filter(item => item.length > 0)
      .slice(0, 30);
    onCommit(next);
    setText(next.join('、'));
  };

  return (
    <input
      value={text}
      onChange={event => setText(event.target.value)}
      onBlur={commit}
      disabled={disabled}
      placeholder={placeholder}
      className="dark-input px-2 py-1.5 rounded-lg text-sm text-slate-700 dark:text-slate-200 disabled:opacity-60"
    />
  );
}

function Section({title, count, open, onToggle, onAdd, children}: {
  title: string;
  count: number;
  open: boolean;
  onToggle: () => void;
  onAdd?: () => void;
  children: ReactNode;
}) {
  return (
    <div className="rounded-xl border border-slate-200 dark:border-[rgb(var(--skin-accent)/0.18)] overflow-hidden">
      <div className="flex items-center justify-between px-3 py-2 bg-slate-50 dark:bg-[rgb(var(--skin-surface-deep)/0.55)]">
        <button
          type="button"
          onClick={onToggle}
          className="flex items-center gap-2 text-sm font-semibold text-slate-700 dark:text-slate-200"
        >
          {open ? <ChevronUp className="w-4 h-4"/> : <ChevronDown className="w-4 h-4"/>}
          {title}
          <span className="text-xs font-normal text-slate-400 dark:text-slate-500">{count}</span>
        </button>
        {onAdd && (
          <button
            type="button"
            onClick={onAdd}
            className="flex items-center gap-1 text-xs px-2 py-1 rounded-lg text-primary-600 dark:text-neon-cyan hover:bg-primary-50 dark:hover:bg-[rgb(var(--skin-accent)/0.16)] transition-colors"
            title={`新增${title}`}
          >
            <Plus className="w-3.5 h-3.5"/>新增
          </button>
        )}
      </div>
      {open && <div className="p-3 space-y-3">{children}</div>}
    </div>
  );
}

function ItemCard({title, onRemove, disabled, children}: {
  title: string;
  onRemove: () => void;
  disabled?: boolean;
  children: ReactNode;
}) {
  return (
    <div className="rounded-lg border border-slate-200 dark:border-[rgb(var(--skin-accent)/0.14)] p-3 space-y-2">
      <div className="flex items-center justify-between">
        <span className="text-xs font-semibold text-slate-600 dark:text-slate-300 truncate">{title}</span>
        <button
          type="button"
          onClick={onRemove}
          disabled={disabled}
          className="p-1 text-slate-400 hover:text-red-500 rounded transition-colors disabled:opacity-50"
          title="删除这一条"
        >
          <Trash2 className="w-3.5 h-3.5"/>
        </button>
      </div>
      {children}
    </div>
  );
}

/**
 * 手动内容编辑器：教育经历 / 专业技能 / 项目经历（含多条项目亮点）/ 实习经历 / 获奖经历。
 * AI 对话与手动编辑共用同一份 content，任何改动都会进入自动保存链路。
 */
export default function ResumeContentEditor({content, disabled = false, onChange}: ResumeContentEditorProps) {
  const [openKeys, setOpenKeys] = useState<Record<string, boolean>>({
    basic: true,
    education: true,
    skills: false,
    projects: true,
    internships: false,
    awards: false,
  });

  const toggle = (key: string) => setOpenKeys(prev => ({...prev, [key]: !prev[key]}));

  const patchBasic = (field: keyof ResumeBasicInfo, value: string) => {
    onChange({...content, basicInfo: {...content.basicInfo, [field]: value}});
  };

  const patchEducation = (id: string, patch: Partial<ResumeEducation>) => {
    onChange({
      ...content,
      education: content.education.map(item => (item.id === id ? {...item, ...patch} : item)),
    });
  };

  const addEducation = () => {
    const item: ResumeEducation = {
      id: newLocalId(),
      school: '',
      major: '',
      degree: '',
      startDate: '',
      endDate: '',
      description: '',
    };
    onChange({...content, education: [...content.education, item]});
    setOpenKeys(prev => ({...prev, education: true}));
  };

  const removeEducation = (id: string) => {
    onChange({...content, education: content.education.filter(item => item.id !== id)});
  };

  const patchProject = (id: string, patch: Partial<ResumeProject>) => {
    onChange({
      ...content,
      projects: content.projects.map(item => (item.id === id ? {...item, ...patch} : item)),
    });
  };

  const addProject = () => {
    const item: ResumeProject = {
      id: newLocalId(),
      name: '',
      url: '',
      summary: '',
      techStack: [],
      highlights: [],
      startDate: '',
      endDate: '',
    };
    onChange({...content, projects: [...content.projects, item]});
    setOpenKeys(prev => ({...prev, projects: true}));
  };

  const removeProject = (id: string) => {
    onChange({...content, projects: content.projects.filter(item => item.id !== id)});
  };

  const patchHighlights = (projectId: string, highlights: ResumeHighlight[]) => {
    patchProject(projectId, {
      highlights: highlights.map((item, index) => ({...item, sortOrder: index + 1})),
    });
  };

  const addHighlight = (project: ResumeProject) => {
    if (project.highlights.length >= 50) return;
    patchHighlights(project.id, [
      ...project.highlights,
      {id: newLocalId(), content: '', sortOrder: project.highlights.length + 1},
    ]);
  };

  const updateHighlight = (project: ResumeProject, highlightId: string, value: string) => {
    patchHighlights(project.id, project.highlights.map(item =>
      (item.id === highlightId ? {...item, content: value} : item)));
  };

  const removeHighlight = (project: ResumeProject, highlightId: string) => {
    patchHighlights(project.id, project.highlights.filter(item => item.id !== highlightId));
  };

  const moveHighlight = (project: ResumeProject, index: number, offset: number) => {
    const target = index + offset;
    if (target < 0 || target >= project.highlights.length) return;
    const next = [...project.highlights];
    const [moved] = next.splice(index, 1);
    next.splice(target, 0, moved);
    patchHighlights(project.id, next);
  };

  const patchInternship = (id: string, patch: Partial<ResumeInternship>) => {
    onChange({
      ...content,
      internships: content.internships.map(item => (item.id === id ? {...item, ...patch} : item)),
    });
  };

  const addInternship = () => {
    const item: ResumeInternship = {
      id: newLocalId(),
      company: '',
      position: '',
      startDate: '',
      endDate: '',
      description: '',
    };
    onChange({...content, internships: [...content.internships, item]});
    setOpenKeys(prev => ({...prev, internships: true}));
  };

  const removeInternship = (id: string) => {
    onChange({...content, internships: content.internships.filter(item => item.id !== id)});
  };

  const patchAward = (id: string, patch: Partial<ResumeAward>) => {
    onChange({
      ...content,
      awards: content.awards.map(item => (item.id === id ? {...item, ...patch} : item)),
    });
  };

  const addAward = () => {
    const item: ResumeAward = {id: newLocalId(), name: '', date: '', description: ''};
    onChange({...content, awards: [...content.awards, item]});
    setOpenKeys(prev => ({...prev, awards: true}));
  };

  const removeAward = (id: string) => {
    onChange({...content, awards: content.awards.filter(item => item.id !== id)});
  };

  return (
    <div className="flex flex-col gap-3">
      <Section
        title="基本信息"
        count={0}
        open={!!openKeys.basic}
        onToggle={() => toggle('basic')}
      >
        <div className="grid grid-cols-2 gap-2">
          {BASIC_FIELDS.map(field => (
            <Field
              key={field.key}
              label={field.label}
              value={content.basicInfo[field.key]}
              onChange={value => patchBasic(field.key, value)}
              disabled={disabled}
              placeholder={field.placeholder}
            />
          ))}
        </div>
      </Section>

      <Section
        title="教育经历"
        count={content.education.length}
        open={!!openKeys.education}
        onToggle={() => toggle('education')}
        onAdd={disabled ? undefined : addEducation}
      >
        {content.education.length === 0 && (
          <p className="text-xs text-slate-400 dark:text-slate-500">还没有教育经历，点击右上角「新增」添加。</p>
        )}
        {content.education.map(item => (
          <ItemCard
            key={item.id}
            title={item.school || '未命名学校'}
            onRemove={() => removeEducation(item.id)}
            disabled={disabled}
          >
            <Field label="学校" value={item.school} onChange={v => patchEducation(item.id, {school: v})}
                   disabled={disabled} placeholder="XX 大学"/>
            <div className="grid grid-cols-2 gap-2">
              <Field label="专业" value={item.major} onChange={v => patchEducation(item.id, {major: v})}
                     disabled={disabled} placeholder="计算机科学与技术"/>
              <Field label="学历" value={item.degree} onChange={v => patchEducation(item.id, {degree: v})}
                     disabled={disabled} placeholder="本科"/>
              <Field label="开始时间" value={item.startDate} onChange={v => patchEducation(item.id, {startDate: v})}
                     disabled={disabled} placeholder="2022.09"/>
              <Field label="结束时间" value={item.endDate} onChange={v => patchEducation(item.id, {endDate: v})}
                     disabled={disabled} placeholder="2026.06"/>
            </div>
            <Field label="在校经历" value={item.description}
                   onChange={v => patchEducation(item.id, {description: v})}
                   disabled={disabled} textarea placeholder="主修课程、GPA、排名、在校成果等"/>
          </ItemCard>
        ))}
      </Section>

      <Section
        title="专业技能"
        count={content.skills.length}
        open={!!openKeys.skills}
        onToggle={() => toggle('skills')}
      >
        <div className="flex flex-col gap-1 text-xs text-slate-500 dark:text-slate-400">
          <span>用逗号或顿号分隔，例如：Java、Spring Boot、PostgreSQL</span>
          <TagInput
            value={content.skills}
            disabled={disabled}
            placeholder="Java、Spring Boot、PostgreSQL"
            onCommit={next => onChange({...content, skills: next})}
          />
        </div>
      </Section>

      <Section
        title="项目经历"
        count={content.projects.length}
        open={!!openKeys.projects}
        onToggle={() => toggle('projects')}
        onAdd={disabled ? undefined : addProject}
      >
        {content.projects.length === 0 && (
          <p className="text-xs text-slate-400 dark:text-slate-500">还没有项目经历，点击右上角「新增」添加。</p>
        )}
        {content.projects.map(project => (
          <ItemCard
            key={project.id}
            title={project.name || '未命名项目'}
            onRemove={() => removeProject(project.id)}
            disabled={disabled}
          >
            <Field label="项目名称" value={project.name}
                   onChange={v => patchProject(project.id, {name: v})}
                   disabled={disabled} placeholder="奶娃面试官"/>
            <div className="grid grid-cols-2 gap-2">
              <Field label="开始时间" value={project.startDate}
                     onChange={v => patchProject(project.id, {startDate: v})}
                     disabled={disabled} placeholder="2025.03"/>
              <Field label="结束时间" value={project.endDate}
                     onChange={v => patchProject(project.id, {endDate: v})}
                     disabled={disabled} placeholder="2025.09"/>
            </div>
            <Field label="项目链接" value={project.url}
                   onChange={v => patchProject(project.id, {url: v})}
                   disabled={disabled} placeholder="https://github.com/..."/>
            <div className="flex flex-col gap-1 text-xs text-slate-500 dark:text-slate-400">
              <span>技术栈（逗号或顿号分隔）</span>
              <TagInput
                value={project.techStack}
                disabled={disabled}
                placeholder="Spring Boot、Spring AI、PostgreSQL"
                onCommit={next => patchProject(project.id, {techStack: next})}
              />
            </div>
            <Field label="项目介绍" value={project.summary}
                   onChange={v => patchProject(project.id, {summary: v})}
                   disabled={disabled} textarea placeholder="项目背景、你的职责与整体方案"/>

            <div className="flex items-center justify-between pt-1">
              <span className="text-xs font-semibold text-slate-600 dark:text-slate-300">
                项目亮点 <span className="font-normal text-slate-400">{project.highlights.length}</span>
              </span>
              <button
                type="button"
                onClick={() => addHighlight(project)}
                disabled={disabled || project.highlights.length >= 50}
                className="flex items-center gap-1 text-xs px-2 py-1 rounded-lg text-primary-600 dark:text-neon-cyan hover:bg-primary-50 dark:hover:bg-[rgb(var(--skin-accent)/0.16)] transition-colors disabled:opacity-50"
              >
                <Plus className="w-3.5 h-3.5"/>新增亮点
              </button>
            </div>
            {project.highlights.length === 0 && (
              <p className="text-xs text-slate-400 dark:text-slate-500">
                每个亮点单独一条，导出 PDF 时会渲染为列表。
              </p>
            )}
            {project.highlights.map((highlight, index) => (
              <div key={highlight.id} className="flex items-start gap-1">
                <div className="flex flex-col gap-0.5 pt-2">
                  <button
                    type="button"
                    onClick={() => moveHighlight(project, index, -1)}
                    disabled={disabled || index === 0}
                    className="p-0.5 text-slate-300 hover:text-primary-500 disabled:opacity-30"
                    title="上移"
                  >
                    <ArrowUp className="w-3.5 h-3.5"/>
                  </button>
                  <button
                    type="button"
                    onClick={() => moveHighlight(project, index, 1)}
                    disabled={disabled || index === project.highlights.length - 1}
                    className="p-0.5 text-slate-300 hover:text-primary-500 disabled:opacity-30"
                    title="下移"
                  >
                    <ArrowDown className="w-3.5 h-3.5"/>
                  </button>
                </div>
                <textarea
                  value={highlight.content}
                  onChange={event => updateHighlight(project, highlight.id, event.target.value)}
                  disabled={disabled}
                  rows={2}
                  maxLength={LONG_TEXT}
                  placeholder={`亮点 ${index + 1}`}
                  className="dark-input flex-1 px-2 py-1.5 rounded-lg text-sm text-slate-700 dark:text-slate-200 resize-y disabled:opacity-60"
                />
                <button
                  type="button"
                  onClick={() => removeHighlight(project, highlight.id)}
                  disabled={disabled}
                  className="p-1.5 text-slate-400 hover:text-red-500 rounded transition-colors disabled:opacity-50"
                  title="删除亮点"
                >
                  <Trash2 className="w-3.5 h-3.5"/>
                </button>
              </div>
            ))}
          </ItemCard>
        ))}
      </Section>

      <Section
        title="实习经历"
        count={content.internships.length}
        open={!!openKeys.internships}
        onToggle={() => toggle('internships')}
        onAdd={disabled ? undefined : addInternship}
      >
        {content.internships.length === 0 && (
          <p className="text-xs text-slate-400 dark:text-slate-500">没有实习经历可以留空。</p>
        )}
        {content.internships.map(item => (
          <ItemCard
            key={item.id}
            title={item.company || '未命名公司'}
            onRemove={() => removeInternship(item.id)}
            disabled={disabled}
          >
            <div className="grid grid-cols-2 gap-2">
              <Field label="公司" value={item.company}
                     onChange={v => patchInternship(item.id, {company: v})} disabled={disabled}/>
              <Field label="职位" value={item.position}
                     onChange={v => patchInternship(item.id, {position: v})} disabled={disabled}/>
              <Field label="开始时间" value={item.startDate}
                     onChange={v => patchInternship(item.id, {startDate: v})} disabled={disabled}/>
              <Field label="结束时间" value={item.endDate}
                     onChange={v => patchInternship(item.id, {endDate: v})} disabled={disabled}/>
            </div>
            <Field label="工作内容" value={item.description}
                   onChange={v => patchInternship(item.id, {description: v})}
                   disabled={disabled} textarea/>
          </ItemCard>
        ))}
      </Section>

      <Section
        title="获奖经历"
        count={content.awards.length}
        open={!!openKeys.awards}
        onToggle={() => toggle('awards')}
        onAdd={disabled ? undefined : addAward}
      >
        {content.awards.length === 0 && (
          <p className="text-xs text-slate-400 dark:text-slate-500">没有获奖经历可以留空。</p>
        )}
        {content.awards.map(item => (
          <ItemCard
            key={item.id}
            title={item.name || '未命名奖项'}
            onRemove={() => removeAward(item.id)}
            disabled={disabled}
          >
            <div className="grid grid-cols-2 gap-2">
              <Field label="奖项名称" value={item.name}
                     onChange={v => patchAward(item.id, {name: v})} disabled={disabled}/>
              <Field label="获奖时间" value={item.date}
                     onChange={v => patchAward(item.id, {date: v})} disabled={disabled}/>
            </div>
            <Field label="说明" value={item.description}
                   onChange={v => patchAward(item.id, {description: v})}
                   disabled={disabled} textarea/>
          </ItemCard>
        ))}
      </Section>
    </div>
  );
}
