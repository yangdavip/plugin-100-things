package com.matrixgrowth.plugin100things;

import lombok.Data;

/**
 * 「100 件小事」的可配置项。
 *
 * <p>只影响**展示方式**，不影响清单内容本身：100 件小事的文案、分类、编号来自种子数据，
 * 一经初始化就不再由任何入口修改（{@link SmallThingService#update(SmallThing)} 会拒绝
 * 改动这些字段的请求）。这样前台展示页的内容对所有人都是只读的。
 *
 * <p>字段全部是基本类型或字符串，直接以「一个键一个值」的形式存进 ConfigMap
 * （见 {@link SmallThingConfigService}），不额外包一层 JSON——这样在 Halo 控制台的
 * ConfigMap 里能直接读，缺键时也能逐字段回退默认值。
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

    /**
     * 默认配置。
     *
     * @return 全默认值的配置对象
     */
    public static SmallThingConfig defaults() {
        return new SmallThingConfig();
    }
}
