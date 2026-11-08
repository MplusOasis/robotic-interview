import {useCallback, useEffect, useRef, useState} from 'react';
import {createPortal} from 'react-dom';
import {useLocation, useNavigate, useParams} from 'react-router-dom';
import {ChevronDown, ChevronUp} from 'lucide-react';
import ResumeToolbar from '../components/resume-studio/ResumeToolbar';
import ResumeChat, {type ChatMessage} from '../components/resume-studio/ResumeChat';
import ResumePreview from '../components/resume-studio/ResumePreview';
import ResumeTemplate from '../components/resume-studio/ResumeTemplate';
import '../components/resume-studio/resume-print.css';
import {resumeDocumentApi} from '../api/resumeDocument';
import {getErrorMessage} from '../api/request';
import {ROUTES} from '../constants/routes';
import {
  createBlankResumeContent,
  newLocalId,
  normalizeProposal,
  normalizeResumeContent,
  type ResumeBasicInfo,
  type ResumeChatTurn,
  type ResumeContent,
  type ResumeEditProposal as Proposal,
  type SaveStatus,
} from '../types/resumeDocument';

const BASIC_FIELDS: {key: keyof ResumeBasicInfo; label: string; placeholder: string}[] = [
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

/**
 * 简历编辑工作台（简历管理模块内部二级页面，不新增 Dock 入口）。
 * 左侧 AI 对话 + 基本信息快速编辑，右侧 A4 实时预览；预览与 PDF 共用同一模板。
 */
export default function ResumeStudioPage() {
  const {documentId: rawId} = useParams<{documentId: string}>();
  const documentId = Number(rawId);
  const navigate = useNavigate();
  const location = useLocation();

  const [loaded, setLoaded] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [title, setTitle] = useState('');
  const [content, setContent] = useState<ResumeContent>(() => createBlankResumeContent());
  const [version, setVersion] = useState(0);
  const [templateCode, setTemplateCode] = useState('default');
  const [sourceResumeId, setSourceResumeId] = useState<number | null>(null);
  const [saveStatus, setSaveStatus] = useState<SaveStatus>('saved');

  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [history, setHistory] = useState<ResumeChatTurn[]>([]);
  const [proposal, setProposal] = useState<Proposal | null>(null);
  const [chatLoading, setChatLoading] = useState(false);
  const [applyLoading, setApplyLoading] = useState(false);
  const [chatError, setChatError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [basicOpen, setBasicOpen] = useState(false);

  const dirtyRef = useRef(false);
  const stateRef = useRef({title: '', content: createBlankResumeContent(), version: 0});
  stateRef.current = {title, content, version};
  const saveChainRef = useRef<Promise<number>>(Promise.resolve(0));

  useEffect(() => {
    document.body.classList.add('resume-studio-active');
    return () => document.body.classList.remove('resume-studio-active');
  }, []);

  useEffect(() => {
    if (!Number.isFinite(documentId) || documentId <= 0) {
      setLoadError('无效的简历编号');
      return;
    }
    let cancelled = false;
    resumeDocumentApi.get(documentId)
      .then(document => {
        if (cancelled) return;
        const normalized = normalizeResumeContent(document.content);
        stateRef.current = {
          title: document.title,
          content: normalized,
          version: document.version,
        };
        setTitle(document.title);
        setContent(normalized);
        setVersion(document.version);
        setTemplateCode(document.templateCode || 'default');
        setSourceResumeId(document.sourceResumeId);
        dirtyRef.current = false;
        setSaveStatus('saved');
        setLoaded(true);
      })
      .catch(error => {
        if (!cancelled) setLoadError(getErrorMessage(error));
      });
    return () => { cancelled = true; };
  }, [documentId]);

  /**
   * 串行化保存：自动保存、手动保存、AI 调用前的 flush 都走同一条 Promise 链，
   * 保证不会出现两个并发 PUT 用同一个旧 version。只保存已确认的简历状态。
   */
  const enqueueSave = useCallback((): Promise<number> => {
    const next = saveChainRef.current.then(async () => {
      const snapshot = stateRef.current;
      if (!dirtyRef.current) return snapshot.version;
      setSaveStatus('saving');
      try {
        const updated = await resumeDocumentApi.update(documentId, {
          title: snapshot.title,
          templateCode: 'default',
          content: snapshot.content,
          version: snapshot.version,
        });
        stateRef.current.version = updated.version;
        setVersion(updated.version);
        if (stateRef.current.content === snapshot.content
          && stateRef.current.title === snapshot.title) {
          dirtyRef.current = false;
        }
        setSaveStatus(dirtyRef.current ? 'idle' : 'saved');
        return updated.version;
      } catch (error) {
        setSaveStatus('error');
        throw error;
      }
    });
    saveChainRef.current = next.catch(() => stateRef.current.version);
    return next;
  }, [documentId]);

  useEffect(() => {
    if (!loaded || !dirtyRef.current) return;
    const timer = window.setTimeout(() => {
      enqueueSave().catch(() => setSaveStatus('error'));
    }, 1200);
    return () => window.clearTimeout(timer);
  }, [loaded, title, content, enqueueSave]);

  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
        event.preventDefault();
        enqueueSave().catch(() => setSaveStatus('error'));
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [enqueueSave]);

  const autoPrint = (location.state as {autoPrint?: boolean} | null)?.autoPrint === true;
  useEffect(() => {
    if (!loaded || !autoPrint) return;
    const timer = window.setTimeout(() => {
      window.print();
      navigate(ROUTES.resumeStudio(documentId), {replace: true, state: null});
    }, 500);
    return () => window.clearTimeout(timer);
  }, [loaded, autoPrint, documentId, navigate]);

  const markDirty = () => {
    dirtyRef.current = true;
    setSaveStatus('idle');
  };

  const handleTitleChange = (value: string) => {
    stateRef.current.title = value;
    setTitle(value);
    markDirty();
  };

  const handleBasicChange = (field: keyof ResumeBasicInfo, value: string) => {
    const next: ResumeContent = {
      ...content,
      basicInfo: {...content.basicInfo, [field]: value},
    };
    stateRef.current.content = next;
    setContent(next);
    markDirty();
  };

  const handleSend = useCallback(async (text: string) => {
    setChatError(null);
    setProposal(null);
    setMessages(previous => [...previous, {id: newLocalId(), role: 'user', content: text}]);
    setChatLoading(true);
    try {
      // 先落定所有已确认的修改，保证 AI 提案基于最新的版本号
      await enqueueSave();
      const raw = await resumeDocumentApi.chat(documentId, text, history.slice(-8));
      const parsed = normalizeProposal(raw);
      if (!parsed) throw new Error('AI 返回的修改提案格式无效');
      setProposal(parsed);
      const reply = parsed.needsClarification
        ? (parsed.clarificationQuestion || parsed.reply)
        : parsed.reply;
      setMessages(previous => [...previous, {id: newLocalId(), role: 'assistant', content: reply}]);
      setHistory(previous => {
        const turns: ResumeChatTurn[] = [
          ...previous,
          {role: 'user', content: text},
          {role: 'assistant', content: reply},
        ];
        return turns.slice(-8);
      });
    } catch (error) {
      setChatError(getErrorMessage(error));
    } finally {
      setChatLoading(false);
    }
  }, [documentId, history, enqueueSave]);

  const handleConfirmProposal = async () => {
    if (!proposal) return;
    setApplyLoading(true);
    setChatError(null);
    try {
      await enqueueSave();
      const updated = await resumeDocumentApi.applyProposal(
        documentId, proposal, stateRef.current.version);
      const nextContent = normalizeResumeContent(updated.content);
      stateRef.current.content = nextContent;
      stateRef.current.version = updated.version;
      setContent(nextContent);
      setVersion(updated.version);
      dirtyRef.current = false;
      setSaveStatus('saved');
      setProposal(null);
      setMessages(previous => [...previous, {
        id: newLocalId(), role: 'assistant', content: '修改已确认并保存，右侧预览已更新。',
      }]);
      setNotice('修改已保存');
      window.setTimeout(() => setNotice(null), 2600);
    } catch (error) {
      setChatError(getErrorMessage(error));
    } finally {
      setApplyLoading(false);
    }
  };

  const handleCancelProposal = () => {
    setProposal(null);
    setMessages(previous => [...previous, {
      id: newLocalId(), role: 'assistant', content: '已取消本次修改，简历内容保持不变。',
    }]);
  };

  const handleSaveNow = () => {
    enqueueSave().catch(error => {
      setNotice(getErrorMessage(error));
      window.setTimeout(() => setNotice(null), 3200);
    });
  };

  if (loadError) {
    return (
      <div className="dark-card p-8">
        <h2 className="text-lg font-semibold text-slate-800 dark:text-white mb-2">无法打开简历工作台</h2>
        <p className="text-sm text-slate-600 dark:text-slate-300 mb-4">{loadError}</p>
        <button onClick={() => navigate(ROUTES.resumeHistory)} className="btn-secondary px-4 py-2 rounded-lg">
          返回简历管理
        </button>
      </div>
    );
  }

  if (!loaded) {
    return (
      <div className="flex items-center justify-center min-h-[50vh]">
        <div className="w-10 h-10 border-3 border-slate-200 border-t-primary-500 rounded-full animate-spin"/>
      </div>
    );
  }

  const frozen = proposal !== null || chatLoading || applyLoading;

  return (
    <div className="flex flex-col w-full h-[calc(100vh-6rem)] min-h-[640px]">
      <ResumeToolbar
        title={title}
        onTitleChange={handleTitleChange}
        saveStatus={saveStatus}
        onSave={handleSaveNow}
        templateCode={templateCode}
        onBack={() => navigate(ROUTES.resumeHistory)}
        onPrint={() => window.print()}
        busy={chatLoading || applyLoading}
        frozen={frozen}
      />

      {sourceResumeId !== null && (
        <p className="text-xs text-slate-500 dark:text-slate-400 pt-2">
          由上传简历初始化，原始文件与 AI 分析结果仍可在「简历管理」中查看。
        </p>
      )}

      <div className="flex-1 min-h-0 grid grid-cols-1 lg:grid-cols-[minmax(340px,36%)_minmax(0,1fr)] gap-5 pt-4">
        <div className="flex flex-col min-h-0 gap-3">
          <div className="dark-card px-4 py-3 flex-none">
            <button
              onClick={() => setBasicOpen(open => !open)}
              className="w-full flex items-center justify-between text-sm font-semibold text-slate-700 dark:text-slate-200"
            >
              <span>基本信息快速编辑</span>
              {basicOpen ? <ChevronUp className="w-4 h-4"/> : <ChevronDown className="w-4 h-4"/>}
            </button>
            {basicOpen && (
              <div className="grid grid-cols-2 gap-2 mt-3">
                {BASIC_FIELDS.map(field => (
                  <label key={field.key} className="flex flex-col gap-1 text-xs text-slate-500 dark:text-slate-400">
                    {field.label}
                    <input
                      value={content.basicInfo[field.key]}
                      onChange={event => handleBasicChange(field.key, event.target.value)}
                      disabled={frozen}
                      maxLength={200}
                      placeholder={field.placeholder}
                      className="dark-input px-2 py-1.5 rounded-lg text-sm text-slate-700 dark:text-slate-200 disabled:opacity-60"
                    />
                  </label>
                ))}
              </div>
            )}
          </div>

          <div className="flex-1 min-h-0">
            <ResumeChat
              messages={messages}
              loading={chatLoading}
              error={chatError}
              onSend={handleSend}
              proposal={proposal}
              applying={applyLoading}
              onConfirmProposal={handleConfirmProposal}
              onCancelProposal={handleCancelProposal}
              onOpenSettings={() => navigate('/settings')}
            />
          </div>
        </div>

        <ResumePreview content={content}/>
      </div>

      {/* 打印副本：Portal 到 body，@media print 时隐藏工作台、只输出这份简历 */}
      {createPortal(
        <div className="resume-print-root">
          <ResumeTemplate content={content} printMode/>
        </div>,
        document.body,
      )}

      {notice && (
        <div className="fixed bottom-6 left-1/2 -translate-x-1/2 z-[70] px-5 py-3 rounded-xl bg-slate-800 text-white text-sm shadow-lg">
          {notice}
        </div>
      )}
    </div>
  );
}
