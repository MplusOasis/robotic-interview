import type {ReactNode} from 'react';
import type {ResumeContent} from '../../types/resumeDocument';

interface ResumeTemplateProps {
  content: ResumeContent;
  /** true 时用于打印：隐藏空白占位与未填写的模块。 */
  printMode?: boolean;
}

interface SectionProps {
  title: string;
  empty: boolean;
  printMode: boolean;
  children: ReactNode;
}

function Section({title, empty, printMode, children}: SectionProps) {
  if (empty && printMode) return null;
  return (
    <section className="rt-section">
      <h2 className="rt-section-title">{title}</h2>
      {empty ? (
        <div className="rt-empty">尚未填写，可在左侧通过 AI 对话补充</div>
      ) : children}
    </section>
  );
}

function hasText(value?: string | null): boolean {
  return typeof value === 'string' && value.trim().length > 0;
}

function dateRange(startDate: string, endDate: string): string {
  const parts = [startDate, endDate].filter(hasText);
  return parts.join(' — ');
}

function Link({value}: {value: string}) {
  if (!hasText(value)) return null;
  if (/^https?:\/\//i.test(value.trim())) {
    return (
      <a className="rt-link" href={value.trim()} target="_blank" rel="noreferrer">
        {value.trim()}
      </a>
    );
  }
  return <span>{value}</span>;
}

/**
 * 默认简历模板（第一版唯一模板）。
 * 预览与 PDF 打印共用本组件与 resume-print.css，只通过 printMode 控制空白占位。
 */
export default function ResumeTemplate({content, printMode = false}: ResumeTemplateProps) {
  const info = content.basicInfo;
  const contacts = [
    info.phone,
    info.email,
    info.githubUrl,
    info.blogUrl,
  ].filter(hasText);

  const education = content.education.filter(item =>
    hasText(item.school) || hasText(item.major) || hasText(item.description));
  const projects = content.projects.filter(item =>
    hasText(item.name) || hasText(item.summary) || item.highlights.some(h => hasText(h.content)));
  const skills = content.skills.filter(hasText);
  const internships = content.internships.filter(item =>
    hasText(item.company) || hasText(item.position) || hasText(item.description));
  const awards = content.awards.filter(item => hasText(item.name) || hasText(item.description));

  return (
    <div className="resume-template">
      <div className="resume-sheet">
        <header className="rt-header">
          <h1 className={`rt-name${hasText(info.name) ? '' : ' rt-name-placeholder'}`}>
            {hasText(info.name) ? info.name : '你的姓名'}
          </h1>
          {hasText(info.targetPosition) && (
            <div className="rt-target">求职意向：{info.targetPosition}</div>
          )}
          {contacts.length > 0 && (
            <div className="rt-contact">
              {contacts.map((item, index) => (
                <span className="rt-contact-item" key={`${item}-${index}`}>
                  <Link value={item}/>
                </span>
              ))}
            </div>
          )}
          {(hasText(info.school) || hasText(info.major) || hasText(info.degree)) && (
            <div className="rt-contact">
              {[info.school, info.major, info.degree].filter(hasText).map((item, index) => (
                <span className="rt-contact-item" key={`${item}-${index}`}>{item}</span>
              ))}
            </div>
          )}
        </header>

        <Section title="教育经历" empty={education.length === 0} printMode={printMode}>
          {education.map(item => (
            <div className="rt-item" key={item.id}>
              <div className="rt-item-head">
                <div>
                  <span className="rt-item-title">{item.school}</span>
                  {(hasText(item.major) || hasText(item.degree)) && (
                    <span className="rt-item-sub">
                      {'  ·  '}{[item.major, item.degree].filter(hasText).join(' · ')}
                    </span>
                  )}
                </div>
                <span className="rt-item-date">{dateRange(item.startDate, item.endDate)}</span>
              </div>
              {hasText(item.description) && <div className="rt-summary">{item.description}</div>}
            </div>
          ))}
        </Section>

        <Section title="专业技能" empty={skills.length === 0} printMode={printMode}>
          <div className="rt-skills">
            {skills.map((skill, index) => (
              <span className="rt-skill" key={`${skill}-${index}`}>{skill}</span>
            ))}
          </div>
        </Section>

        <Section title="项目经历" empty={projects.length === 0} printMode={printMode}>
          {projects.map(project => {
            const highlights = [...project.highlights]
              .sort((a, b) => a.sortOrder - b.sortOrder)
              .filter(item => hasText(item.content));
            const techStack = project.techStack.filter(hasText);
            return (
              <div className="rt-item" key={project.id}>
                <div className="rt-item-head">
                  <span className="rt-item-title">{project.name || '未命名项目'}</span>
                  <span className="rt-item-date">{dateRange(project.startDate, project.endDate)}</span>
                </div>
                {hasText(project.url) && (
                  <div className="rt-muted-line"><Link value={project.url}/></div>
                )}
                {techStack.length > 0 && (
                  <div className="rt-tags">
                    {techStack.map((tech, index) => (
                      <span className="rt-tag" key={`${tech}-${index}`}>{tech}</span>
                    ))}
                  </div>
                )}
                {hasText(project.summary) && <div className="rt-summary">{project.summary}</div>}
                {highlights.length > 0 && (
                  <ul className="rt-highlights">
                    {highlights.map(highlight => (
                      <li key={highlight.id}>{highlight.content}</li>
                    ))}
                  </ul>
                )}
              </div>
            );
          })}
        </Section>

        {internships.length > 0 && (
          <Section title="实习经历" empty={false} printMode={printMode}>
            {internships.map(item => (
              <div className="rt-item" key={item.id}>
                <div className="rt-item-head">
                  <span className="rt-item-title">
                    {item.company}{hasText(item.position) ? ` · ${item.position}` : ''}
                  </span>
                  <span className="rt-item-date">{dateRange(item.startDate, item.endDate)}</span>
                </div>
                {hasText(item.description) && <div className="rt-summary">{item.description}</div>}
              </div>
            ))}
          </Section>
        )}

        {awards.length > 0 && (
          <Section title="获奖经历" empty={false} printMode={printMode}>
            {awards.map(item => (
              <div className="rt-item" key={item.id}>
                <div className="rt-item-head">
                  <span className="rt-item-title">{item.name}</span>
                  {hasText(item.date) && <span className="rt-item-date">{item.date}</span>}
                </div>
                {hasText(item.description) && <div className="rt-summary">{item.description}</div>}
              </div>
            ))}
          </Section>
        )}
      </div>
    </div>
  );
}
