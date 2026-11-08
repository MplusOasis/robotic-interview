package interview.guide.modules.resumedocument.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.resumedocument.model.ResumeContent;
import interview.guide.modules.resumedocument.model.ResumeDocumentLimits;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结构化简历正文的序列化、规范化与校验。
 *
 * <p>同时负责「由原始简历解析全文初始化结构化数据」——只做本地规则抽取，
 * 绝不重复调用大模型分析，符合上传链路零侵入的要求。</p>
 */
@Component
@RequiredArgsConstructor
public class ResumeContentCodec {

    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern GITHUB = Pattern.compile("https?://(?:www\\.)?github\\.com/[A-Za-z0-9._\\-/]+");
    private static final Pattern URL = Pattern.compile("https?://[^\\s，,；;）)]+");
    private static final Pattern DEGREE = Pattern.compile("(博士|硕士|研究生|本科|学士|大专|专科)");
    private static final Pattern MAJOR = Pattern.compile("专业\\s*[:：]?\\s*([^\\s，,；;|]{2,20})");
    private static final Pattern TARGET = Pattern.compile("(?:求职意向|应聘岗位|目标岗位|意向岗位|期望岗位)\\s*[:：]?\\s*([^\\n\\r，,；;|]{2,30})");
    private static final Pattern NAME_LINE = Pattern.compile("^[\\u4e00-\\u9fa5]{2,6}$|^[A-Za-z][A-Za-z .'-]{1,29}$");
    private static final List<String> NON_NAME_KEYWORDS = List.of(
        "简历", "个人", "求职", "姓名", "电话", "邮箱", "教育", "项目", "技能", "经历",
        "大学", "学院", "专业", "意向", "地址", "性别", "出生", "政治", "objective", "resume");

    private final ObjectMapper objectMapper;

    public ResumeContent blank() {
        return new ResumeContent();
    }

    public String serialize(ResumeContent content) {
        try {
            return objectMapper.writeValueAsString(content);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.RESUME_DOCUMENT_CONTENT_INVALID, "简历数据序列化失败");
        }
    }

    public ResumeContent deserialize(String json) {
        if (json == null || json.isBlank()) {
            return blank();
        }
        try {
            ResumeContent parsed = objectMapper.readValue(json, ResumeContent.class);
            return parsed == null ? blank() : parsed;
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.RESUME_DOCUMENT_CONTENT_INVALID, "简历数据解析失败");
        }
    }

    /**
     * 规范化：补齐空结构、为缺失/重复的记录生成稳定 UUID、重排技术亮点序号。
     * 任何来自前端或 AI 的内容在写库前都必须经过这里。
     */
    public ResumeContent normalize(ResumeContent raw) {
        ResumeContent content = raw == null ? blank() : raw;
        ResumeContent.BasicInfo basic = content.getBasicInfo() == null
            ? new ResumeContent.BasicInfo() : content.getBasicInfo();
        content.setBasicInfo(basic);
        normalizeShort(basic);

        Set<String> usedIds = new HashSet<>();
        content.setEducation(normalizeEducation(content.getEducation(), usedIds));
        content.setProjects(normalizeProjects(content.getProjects(), usedIds));
        content.setInternships(normalizeInternships(content.getInternships(), usedIds));
        content.setAwards(normalizeAwards(content.getAwards(), usedIds));
        content.setSkills(normalizeStrings(content.getSkills(),
            ResumeDocumentLimits.MAX_SKILLS, ResumeDocumentLimits.MAX_SHORT_TEXT, usedIds, false));
        return content;
    }

    /**
     * 内容合法性校验：数量、字符串长度、ID 唯一性。
     */
    public void validate(ResumeContent content) {
        if (content == null) {
            throw invalid("简历内容不能为空");
        }
        checkSize(content.getEducation().size(), ResumeDocumentLimits.MAX_LIST_ITEMS, "教育经历");
        checkSize(content.getProjects().size(), ResumeDocumentLimits.MAX_LIST_ITEMS, "项目经历");
        checkSize(content.getInternships().size(), ResumeDocumentLimits.MAX_LIST_ITEMS, "实习经历");
        checkSize(content.getAwards().size(), ResumeDocumentLimits.MAX_LIST_ITEMS, "获奖经历");
        checkSize(content.getSkills().size(), ResumeDocumentLimits.MAX_SKILLS, "专业技能");
        checkText(content.getBasicInfo().getName(), "姓名");
        checkText(content.getBasicInfo().getPhone(), "电话");
        checkText(content.getBasicInfo().getEmail(), "邮箱");
        checkText(content.getBasicInfo().getSchool(), "学校");
        checkText(content.getBasicInfo().getMajor(), "专业");
        checkText(content.getBasicInfo().getDegree(), "学历");
        checkText(content.getBasicInfo().getTargetPosition(), "求职意向");
        checkText(content.getBasicInfo().getGithubUrl(), "GitHub 链接");
        checkText(content.getBasicInfo().getBlogUrl(), "博客链接");
        for (String skill : content.getSkills()) {
            checkText(skill, "技能");
        }
        for (ResumeContent.EducationItem item : content.getEducation()) {
            checkText(item.getSchool(), "学校");
            checkText(item.getMajor(), "专业");
            checkText(item.getDescription(), "教育描述");
        }
        for (ResumeContent.ProjectItem item : content.getProjects()) {
            checkText(item.getName(), "项目名称");
            checkText(item.getUrl(), "项目链接");
            checkLongText(item.getSummary(), "项目介绍");
            checkSize(item.getTechStack().size(), ResumeDocumentLimits.MAX_LIST_ITEMS, "技术栈");
            checkSize(item.getHighlights().size(),
                ResumeDocumentLimits.MAX_HIGHLIGHTS_PER_PROJECT, "项目技术亮点");
            for (String tech : item.getTechStack()) {
                checkText(tech, "技术栈");
            }
            for (ResumeContent.HighlightItem highlight : item.getHighlights()) {
                checkLongText(highlight.getContent(), "技术亮点");
            }
        }
    }

    /**
     * 从已落库的 resume_text 做本地规则抽取，初始化结构化简历。
     * 不调用任何 LLM；抽取不到的字段留空，由用户通过 AI 对话或快速编辑补充。
     */
    public ResumeContent fromResumeText(String resumeText) {
        ResumeContent content = blank();
        if (resumeText == null || resumeText.isBlank()) {
            return content;
        }
        String text = resumeText.length() > 20_000 ? resumeText.substring(0, 20_000) : resumeText;
        String[] lines = text.split("\\R");
        ResumeContent.BasicInfo basic = content.getBasicInfo();

        Matcher phone = PHONE.matcher(text);
        if (phone.find()) {
            basic.setPhone(phone.group());
        }
        Matcher email = EMAIL.matcher(text);
        if (email.find()) {
            basic.setEmail(email.group());
        }
        Matcher github = GITHUB.matcher(text);
        if (github.find()) {
            basic.setGithubUrl(github.group());
        } else {
            Matcher blog = URL.matcher(text);
            if (blog.find() && !blog.group().contains("github.com")) {
                basic.setBlogUrl(blog.group());
            }
        }
        Matcher degree = DEGREE.matcher(text);
        if (degree.find()) {
            basic.setDegree(degree.group());
        }
        Matcher major = MAJOR.matcher(text);
        if (major.find()) {
            String candidate = major.group(1);
            if (!candidate.contains("技能") && !candidate.contains("简历")) {
                basic.setMajor(clip(candidate, ResumeDocumentLimits.MAX_SHORT_TEXT));
            }
        }
        Matcher target = TARGET.matcher(text);
        if (target.find()) {
            basic.setTargetPosition(clip(target.group(1).trim(), ResumeDocumentLimits.MAX_SHORT_TEXT));
        }

        String school = null;
        for (int i = 0; i < Math.min(lines.length, 25); i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            if (basic.getName().isEmpty() && NAME_LINE.matcher(line).matches()
                && NON_NAME_KEYWORDS.stream().noneMatch(line::contains)) {
                basic.setName(line);
            }
            if (school == null && (line.contains("大学") || line.contains("学院"))
                && line.length() <= 40) {
                school = line;
            }
        }
        if (school != null) {
            basic.setSchool(clip(school, ResumeDocumentLimits.MAX_SHORT_TEXT));
            ResumeContent.EducationItem item = new ResumeContent.EducationItem();
            item.setId(UUID.randomUUID().toString());
            item.setSchool(clip(school, ResumeDocumentLimits.MAX_SHORT_TEXT));
            item.setMajor(basic.getMajor());
            item.setDegree(basic.getDegree());
            content.getEducation().add(item);
        }
        content.setSkills(extractSkills(lines));
        return normalize(content);
    }

    private List<String> extractSkills(String[] lines) {
        List<String> skills = new ArrayList<>();
        boolean inSection = false;
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (!inSection) {
                if (line.contains("专业技能") || line.contains("技术栈") || line.contains("技能清单")) {
                    inSection = true;
                }
                continue;
            }
            if (isSectionTitle(line)) {
                break;
            }
            for (String part : line.split("[、,，;；|/]+")) {
                String skill = clip(part.trim(), ResumeDocumentLimits.MAX_SHORT_TEXT);
                if (skill.length() >= 2 && skill.length() <= 40 && skills.size() < ResumeDocumentLimits.MAX_SKILLS) {
                    skills.add(skill);
                }
            }
            if (skills.size() >= ResumeDocumentLimits.MAX_SKILLS) {
                break;
            }
        }
        return skills;
    }

    private boolean isSectionTitle(String line) {
        return line.length() <= 20 && (line.contains("教育经历") || line.contains("项目经历")
            || line.contains("项目经验") || line.contains("实习") || line.contains("获奖")
            || line.contains("自我评价") || line.contains("工作经历"));
    }

    private void normalizeShort(ResumeContent.BasicInfo basic) {
        basic.setName(text(basic.getName()));
        basic.setPhone(text(basic.getPhone()));
        basic.setEmail(text(basic.getEmail()));
        basic.setSchool(text(basic.getSchool()));
        basic.setMajor(text(basic.getMajor()));
        basic.setDegree(text(basic.getDegree()));
        basic.setTargetPosition(text(basic.getTargetPosition()));
        basic.setGithubUrl(text(basic.getGithubUrl()));
        basic.setBlogUrl(text(basic.getBlogUrl()));
    }

    private List<ResumeContent.EducationItem> normalizeEducation(
        List<ResumeContent.EducationItem> source, Set<String> usedIds) {
        List<ResumeContent.EducationItem> result = new ArrayList<>();
        if (source == null) {
            return result;
        }
        for (ResumeContent.EducationItem item : source) {
            if (item == null || result.size() >= ResumeDocumentLimits.MAX_LIST_ITEMS) {
                continue;
            }
            item.setId(stableId(item.getId(), usedIds));
            item.setSchool(text(item.getSchool()));
            item.setMajor(text(item.getMajor()));
            item.setDegree(text(item.getDegree()));
            item.setStartDate(text(item.getStartDate()));
            item.setEndDate(text(item.getEndDate()));
            item.setDescription(longText(item.getDescription()));
            result.add(item);
        }
        return result;
    }

    private List<ResumeContent.ProjectItem> normalizeProjects(
        List<ResumeContent.ProjectItem> source, Set<String> usedIds) {
        List<ResumeContent.ProjectItem> result = new ArrayList<>();
        if (source == null) {
            return result;
        }
        for (ResumeContent.ProjectItem item : source) {
            if (item == null || result.size() >= ResumeDocumentLimits.MAX_LIST_ITEMS) {
                continue;
            }
            item.setId(stableId(item.getId(), usedIds));
            item.setName(text(item.getName()));
            item.setUrl(text(item.getUrl()));
            item.setSummary(longText(item.getSummary()));
            item.setStartDate(text(item.getStartDate()));
            item.setEndDate(text(item.getEndDate()));
            item.setTechStack(normalizeStrings(item.getTechStack(),
                ResumeDocumentLimits.MAX_LIST_ITEMS, ResumeDocumentLimits.MAX_SHORT_TEXT, null, false));
            item.setHighlights(normalizeHighlights(item.getHighlights(), usedIds));
            result.add(item);
        }
        return result;
    }

    private List<ResumeContent.HighlightItem> normalizeHighlights(
        List<ResumeContent.HighlightItem> source, Set<String> usedIds) {
        List<ResumeContent.HighlightItem> result = new ArrayList<>();
        if (source == null) {
            return result;
        }
        for (ResumeContent.HighlightItem item : source) {
            if (item == null || result.size() >= ResumeDocumentLimits.MAX_HIGHLIGHTS_PER_PROJECT) {
                continue;
            }
            item.setId(stableId(item.getId(), usedIds));
            item.setContent(longText(item.getContent()));
            item.setSortOrder(result.size() + 1);
            result.add(item);
        }
        return result;
    }

    private List<ResumeContent.InternshipItem> normalizeInternships(
        List<ResumeContent.InternshipItem> source, Set<String> usedIds) {
        List<ResumeContent.InternshipItem> result = new ArrayList<>();
        if (source == null) {
            return result;
        }
        for (ResumeContent.InternshipItem item : source) {
            if (item == null || result.size() >= ResumeDocumentLimits.MAX_LIST_ITEMS) {
                continue;
            }
            item.setId(stableId(item.getId(), usedIds));
            item.setCompany(text(item.getCompany()));
            item.setPosition(text(item.getPosition()));
            item.setStartDate(text(item.getStartDate()));
            item.setEndDate(text(item.getEndDate()));
            item.setDescription(longText(item.getDescription()));
            result.add(item);
        }
        return result;
    }

    private List<ResumeContent.AwardItem> normalizeAwards(
        List<ResumeContent.AwardItem> source, Set<String> usedIds) {
        List<ResumeContent.AwardItem> result = new ArrayList<>();
        if (source == null) {
            return result;
        }
        for (ResumeContent.AwardItem item : source) {
            if (item == null || result.size() >= ResumeDocumentLimits.MAX_LIST_ITEMS) {
                continue;
            }
            item.setId(stableId(item.getId(), usedIds));
            item.setName(text(item.getName()));
            item.setDate(text(item.getDate()));
            item.setDescription(longText(item.getDescription()));
            result.add(item);
        }
        return result;
    }

    private List<String> normalizeStrings(List<String> source, int maxSize, int maxLength,
                                          Set<String> usedIds, boolean deduplicate) {
        List<String> result = new ArrayList<>();
        if (source == null) {
            return result;
        }
        Set<String> seen = new HashSet<>();
        for (String value : source) {
            if (result.size() >= maxSize) {
                break;
            }
            String normalized = clip(text(value), maxLength);
            if (normalized.isEmpty() || (deduplicate && !seen.add(normalized))) {
                continue;
            }
            result.add(normalized);
        }
        return result;
    }

    private String stableId(String raw, Set<String> usedIds) {
        String id = raw == null ? "" : raw.trim();
        if (id.isEmpty() || id.length() > 64 || !usedIds.add(id)) {
            id = UUID.randomUUID().toString();
            while (!usedIds.add(id)) {
                id = UUID.randomUUID().toString();
            }
        }
        return id;
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    private String longText(String value) {
        return value == null ? "" : value;
    }

    private String clip(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    private void checkSize(int actual, int max, String label) {
        if (actual > max) {
            throw invalid(label + "数量超过上限 " + max);
        }
    }

    private void checkText(String value, String label) {
        if (value != null && value.length() > ResumeDocumentLimits.MAX_SHORT_TEXT) {
            throw invalid(label + "长度超过上限 " + ResumeDocumentLimits.MAX_SHORT_TEXT);
        }
    }

    private void checkLongText(String value, String label) {
        if (value != null && value.length() > ResumeDocumentLimits.MAX_LONG_TEXT) {
            throw invalid(label + "长度超过上限 " + ResumeDocumentLimits.MAX_LONG_TEXT);
        }
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.RESUME_DOCUMENT_CONTENT_INVALID, message);
    }
}
