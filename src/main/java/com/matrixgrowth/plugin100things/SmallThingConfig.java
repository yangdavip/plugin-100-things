package com.matrixgrowth.plugin100things;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import lombok.Data;

/**
 * 「100 件小事」的可配置项。
 *
 * <p>控制台「配置」页签里的页面文案与展示开关，加上「生活章节」页签里的章节定义，
 * 都存在这里。清单条目本身（100 件小事）是独立的扩展资源，由控制台直接增删改，
 * 不属于本配置。
 *
 * <p>字段以「一个键一个值」的形式存进 ConfigMap（见 {@link SmallThingConfigService}），
 * 唯一的例外是 {@link #chapters}：它是列表，序列化成一段 JSON 放在单个键里。这样在
 * Halo 控制台的 ConfigMap 里依然可读，缺键时也能整体回退默认值。
 *
 * @author matrixgrowth
 * @since 1.0.0
 */
@Data
public class SmallThingConfig {

    /** 默认页面主标题。 */
    public static final String DEFAULT_PAGE_TITLE = "短暂人生，你想做的 100 件事情";

    /** 默认副标题。 */
    public static final String DEFAULT_PAGE_SUBTITLE = "一百件小事 · 一生的清单";

    /** 默认引言。 */
    public static final String DEFAULT_PAGE_QUOTE = "有些事情不是为了完成，而是为了记住。";

    /** 默认页脚签名。 */
    public static final String DEFAULT_FOOTER_NOTE = "—— 那些小事，都算数。";

    /** 章节数量上限，避免一段 JSON 里塞进几百个章节。 */
    public static final int MAX_CHAPTERS = 12;

    /** 页面主标题。 */
    private String pageTitle = DEFAULT_PAGE_TITLE;

    /** 副标题。 */
    private String pageSubtitle = DEFAULT_PAGE_SUBTITLE;

    /** 标题下的引言。 */
    private String pageQuote = DEFAULT_PAGE_QUOTE;

    /** 页脚签名（留空则不显示）。 */
    private String footerNote = DEFAULT_FOOTER_NOTE;

    /** 是否显示已珍藏进度条。 */
    private boolean showProgress = true;

    /** 是否按分类分组展示（关闭后为平铺网格）。 */
    private boolean groupByCategory = true;

    /** 生活章节：按「已珍藏件数」解锁的六个阶段，控制台可增删改。 */
    private List<Chapter> chapters = defaultChapters();

    /**
     * 默认章节：0 / 20 / 40 / 60 / 80 / 100 件各解锁一章。
     *
     * <p>返回不可变列表——默认值是全局共享的，被谁就地改一下就会污染后续所有 {@code defaults()}。
     *
     * @return 默认章节
     */
    public static List<Chapter> defaultChapters() {
        return List.of(
            new Chapter("启程", "你刚刚把这张清单铺开。", 0),
            new Chapter("相遇", "你开始遇见喜欢的人和事。", 20),
            new Chapter("生长", "你正在长成更完整的自己。", 40),
            new Chapter("远行", "你走出了熟悉的生活半径。", 60),
            new Chapter("珍藏", "你的人生已经攒下许多故事。", 80),
            new Chapter("满载而归", "这一段人生，值得被好好记住。", 100));
    }

    /**
     * 按已珍藏件数取当前所处章节。
     *
     * <p>件数还没达到任何阈值时返回第一章（而不是 null）：「一件都没做」本身就是第一章的状态，
     * 返回 null 会让前台进度条空一块。
     *
     * @param chapters 章节列表（可为空）
     * @param treasured 已珍藏件数
     * @return 当前章节，章节列表为空时返回 null
     */
    public static Chapter currentOf(List<Chapter> chapters, long treasured) {
        if (chapters == null || chapters.isEmpty()) {
            return null;
        }
        List<Chapter> sorted = sortedChapters(chapters);
        Chapter current = sorted.get(0);
        for (Chapter chapter : sorted) {
            if (treasured >= chapter.getMin()) {
                current = chapter;
            }
        }
        return current;
    }

    /**
     * 取下一个尚未解锁的章节。
     *
     * @param chapters 章节列表（可为空）
     * @param treasured 已珍藏件数
     * @return 下一章节；已全部解锁或列表为空时返回 null
     */
    public static Chapter nextOf(List<Chapter> chapters, long treasured) {
        if (chapters == null || chapters.isEmpty()) {
            return null;
        }
        for (Chapter chapter : sortedChapters(chapters)) {
            if (treasured < chapter.getMin()) {
                return chapter;
            }
        }
        return null;
    }

    /**
     * 按阈值升序排列，返回新列表（不改动传入的列表）。
     *
     * @param chapters 章节列表
     * @return 排序后的副本
     */
    public static List<Chapter> sortedChapters(List<Chapter> chapters) {
        List<Chapter> copy = new ArrayList<>(chapters);
        copy.sort(Comparator.comparingInt(Chapter::getMin));
        return copy;
    }

    /**
     * 规范化章节列表：去空名、去重复阈值、按阈值升序、限制数量。
     *
     * <p>重复阈值必须去掉：两章都写 20 件时，{@link #currentOf} 会永远只命中靠后的那一章，
     * 前一章等于不存在——而界面上它还好端端显示着。
     *
     * @param chapters 原始章节列表
     * @return 规范化后的新列表；输入为空时返回默认章节
     */
    public static List<Chapter> normalizeChapters(List<Chapter> chapters) {
        if (chapters == null || chapters.isEmpty()) {
            return defaultChapters();
        }
        List<Chapter> sorted = sortedChapters(chapters);
        List<Chapter> result = new ArrayList<>();
        LinkedHashSet<Integer> usedMins = new LinkedHashSet<>();
        List<Chapter> defaults = defaultChapters();
        for (Chapter chapter : sorted) {
            if (result.size() >= MAX_CHAPTERS) {
                break;
            }
            Chapter cleaned = new Chapter();
            String name = chapter.getName() == null ? "" : chapter.getName().trim();
            if (name.isEmpty()) {
                int index = result.size();
                // 名字为空不丢章节：否则用户只是漏填一个名字，整章就从进度线上消失
                name = index < defaults.size()
                    ? defaults.get(index).getName() : "第 " + (index + 1) + " 章";
            }
            cleaned.setName(name.length() > 32 ? name.substring(0, 32) : name);
            String say = chapter.getSay() == null ? "" : chapter.getSay().trim();
            cleaned.setSay(say.length() > 120 ? say.substring(0, 120) : say);
            cleaned.setMin(Math.max(0, chapter.getMin()));
            if (!usedMins.add(cleaned.getMin())) {
                continue;
            }
            result.add(cleaned);
        }
        return result.isEmpty() ? defaultChapters() : result;
    }

    /**
     * 默认配置。
     *
     * @return 全默认值的配置对象
     */
    public static SmallThingConfig defaults() {
        return new SmallThingConfig();
    }

    /**
     * 一个生活章节。
     *
     * @author matrixgrowth
     */
    @Data
    public static class Chapter {

        /** 章节短名（如「启程」），展示为「Lv.1 启程」。 */
        private String name;

        /** 章节寄语，章节视图里显示在标题下方。 */
        private String say;

        /** 解锁该章所需的已珍藏件数。 */
        private int min;

        /** 供 Jackson 反序列化使用。 */
        public Chapter() {
        }

        /**
         * 构造一个章节。
         *
         * @param name 短名
         * @param say 寄语
         * @param min 所需件数
         */
        public Chapter(String name, String say, int min) {
            this.name = name;
            this.say = say;
            this.min = min;
        }
    }
}
