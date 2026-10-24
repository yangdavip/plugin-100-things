package com.matrixgrowth.plugin100things;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * 插件配置的读写（存于 ConfigMap {@value #CONFIG_MAP_NAME}）。
 *
 * <p>为什么是 ConfigMap 而不是写死在代码里：改标题、开关进度条这类事情不该需要重新打包发版，
 * 也不该需要重启插件；ConfigMap 改动在下一次请求就生效。
 *
 * <p>为什么要做逐字段回退：ConfigMap 是公开可编辑的扩展，缺键、空值、写了非法布尔串
 * 都可能发生。任何一处脏数据都不应该让前台整页渲染失败——读到的每个字段单独回退默认值，
 * 坏值只影响它自己。
 *
 * @author matrixgrowth
 * @since 1.0.0
 */
@Slf4j
@Service
public class SmallThingConfigService {

    /** 配置所在的 ConfigMap 名称。 */
    public static final String CONFIG_MAP_NAME = "plugin-100-things-config";

    static final String KEY_PAGE_TITLE = "pageTitle";
    static final String KEY_PAGE_SUBTITLE = "pageSubtitle";
    static final String KEY_PAGE_QUOTE = "pageQuote";
    static final String KEY_FOOTER_NOTE = "footerNote";
    static final String KEY_SHOW_PROGRESS = "showProgress";
    static final String KEY_GROUP_BY_CATEGORY = "groupByCategory";
    static final String KEY_CHAPTERS = "chapters";

    /** 章节是列表，只能序列化成 JSON 存进单个键。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TypeReference<List<SmallThingConfig.Chapter>> CHAPTER_LIST =
        new TypeReference<>() {
        };

    /** 字符串字段的长度上限，避免一屏标题被填成一段文章。 */
    static final int MAX_TEXT_LENGTH = 120;

    private final ReactiveExtensionClient client;

    public SmallThingConfigService(ReactiveExtensionClient client) {
        this.client = client;
    }

    /**
     * 读取配置；ConfigMap 不存在时返回默认值。
     *
     * @return 当前生效的配置
     */
    public Mono<SmallThingConfig> load() {
        return client.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .map(configMap -> fromData(configMap.getData()))
            .defaultIfEmpty(SmallThingConfig.defaults());
    }

    /**
     * 保存配置（不存在则创建）。
     *
     * @param config 待保存的配置，允许字段为 null（回退默认值）
     * @return 实际保存后的配置（已规范化，可直接回给前端）
     */
    public Mono<SmallThingConfig> save(SmallThingConfig config) {
        SmallThingConfig sanitized = sanitize(config);
        Map<String, String> data = toData(sanitized);
        return client.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .flatMap(existing -> {
                existing.setData(data);
                return client.update(existing);
            })
            .switchIfEmpty(Mono.defer(() -> client.create(buildConfigMap(data))
                // 两个请求同时创建时后者会失败；此时退化为更新，而不是把错误抛给用户。
                .onErrorResume(error -> client.fetch(ConfigMap.class, CONFIG_MAP_NAME)
                    .switchIfEmpty(Mono.error(error))
                    .flatMap(existing -> {
                        existing.setData(data);
                        return client.update(existing);
                    }))))
            .thenReturn(sanitized);
    }

    /**
     * 从 ConfigMap 的 data 解析配置，逐字段回退默认值。
     *
     * @param data ConfigMap 的 data（可为 null）
     * @return 配置对象
     */
    SmallThingConfig fromData(Map<String, String> data) {
        SmallThingConfig config = SmallThingConfig.defaults();
        if (data == null || data.isEmpty()) {
            return config;
        }
        config.setPageTitle(
            text(data, KEY_PAGE_TITLE, SmallThingConfig.DEFAULT_PAGE_TITLE));
        config.setPageSubtitle(
            text(data, KEY_PAGE_SUBTITLE, SmallThingConfig.DEFAULT_PAGE_SUBTITLE));
        config.setPageQuote(
            text(data, KEY_PAGE_QUOTE, SmallThingConfig.DEFAULT_PAGE_QUOTE));
        // 页脚允许被显式清空（保存空串即为「不显示」），因此这里不用默认值兜底。
        config.setFooterNote(
            data.get(KEY_FOOTER_NOTE) == null
                ? SmallThingConfig.DEFAULT_FOOTER_NOTE
                : data.get(KEY_FOOTER_NOTE).trim());
        config.setShowProgress(flag(data, KEY_SHOW_PROGRESS, true));
        config.setGroupByCategory(flag(data, KEY_GROUP_BY_CATEGORY, true));
        config.setChapters(chapters(data.get(KEY_CHAPTERS)));
        return config;
    }

    /**
     * 把配置序列化为 ConfigMap 的 data（每个字段一个键）。
     *
     * @param config 配置
     * @return data 映射
     */
    Map<String, String> toData(SmallThingConfig config) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put(KEY_PAGE_TITLE, config.getPageTitle());
        data.put(KEY_PAGE_SUBTITLE, config.getPageSubtitle());
        data.put(KEY_PAGE_QUOTE, config.getPageQuote());
        data.put(KEY_FOOTER_NOTE, config.getFooterNote() == null ? "" : config.getFooterNote());
        data.put(KEY_SHOW_PROGRESS, Boolean.toString(config.isShowProgress()));
        data.put(KEY_GROUP_BY_CATEGORY, Boolean.toString(config.isGroupByCategory()));
        data.put(KEY_CHAPTERS, writeChapters(config.getChapters()));
        return data;
    }

    /**
     * 规范化：去空白、截断超长、null 回退默认值。
     *
     * @param config 原始配置（可为 null）
     * @return 规范化后的配置
     */
    SmallThingConfig sanitize(SmallThingConfig config) {
        Assert.notNull(config, "配置不能为空");
        SmallThingConfig result = SmallThingConfig.defaults();
        result.setPageTitle(clean(config.getPageTitle(),
            SmallThingConfig.DEFAULT_PAGE_TITLE));
        result.setPageSubtitle(clean(config.getPageSubtitle(),
            SmallThingConfig.DEFAULT_PAGE_SUBTITLE));
        result.setPageQuote(clean(config.getPageQuote(),
            SmallThingConfig.DEFAULT_PAGE_QUOTE));
        result.setFooterNote(truncate(config.getFooterNote() == null
            ? "" : config.getFooterNote().trim()));
        result.setShowProgress(config.isShowProgress());
        result.setGroupByCategory(config.isGroupByCategory());
        result.setChapters(SmallThingConfig.normalizeChapters(config.getChapters()));
        return result;
    }

    /**
     * 解析章节 JSON。
     *
     * <p>解析失败不抛错：坏数据只让章节回退默认值，不能让前台整页 500。章节是 ConfigMap 里
     * 唯一的多值字段，也是最容易被手改成非法 JSON 的一个。
     *
     * @param json 章节 JSON（可为 null）
     * @return 规范化后的章节列表
     */
    private List<SmallThingConfig.Chapter> chapters(String json) {
        if (json == null || json.isBlank()) {
            return SmallThingConfig.defaultChapters();
        }
        try {
            List<SmallThingConfig.Chapter> parsed = MAPPER.readValue(json, CHAPTER_LIST);
            return SmallThingConfig.normalizeChapters(parsed);
        } catch (Exception e) {
            log.warn("配置项 {} 不是合法的章节 JSON，已回退默认章节：{}", KEY_CHAPTERS, e.getMessage());
            return SmallThingConfig.defaultChapters();
        }
    }

    /**
     * 序列化章节。
     *
     * @param chapters 章节列表（可为空）
     * @return JSON；序列化失败时返回默认章节的 JSON
     */
    private String writeChapters(List<SmallThingConfig.Chapter> chapters) {
        try {
            return MAPPER.writeValueAsString(
                SmallThingConfig.normalizeChapters(chapters));
        } catch (Exception e) {
            log.warn("章节序列化失败，已写入默认章节：{}", e.getMessage());
            try {
                return MAPPER.writeValueAsString(SmallThingConfig.defaultChapters());
            } catch (Exception ignored) {
                // 默认章节是固定结构，理论上不会失败；真失败了也不能让保存整体崩掉
                return "[]";
            }
        }
    }

    private String clean(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return truncate(value.trim());
    }

    private String truncate(String value) {
        return value.length() > MAX_TEXT_LENGTH ? value.substring(0, MAX_TEXT_LENGTH) : value;
    }

    private String text(Map<String, String> data, String key, String fallback) {
        String value = data.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return truncate(value.trim());
    }

    private boolean flag(Map<String, String> data, String key, boolean fallback) {
        String value = data.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim();
        if ("true".equalsIgnoreCase(normalized)) {
            return true;
        }
        if ("false".equalsIgnoreCase(normalized)) {
            return false;
        }
        log.warn("配置项 {} 的取值不是布尔值：{}，已回退默认值 {}", key, value, fallback);
        return fallback;
    }

    private ConfigMap buildConfigMap(Map<String, String> data) {
        ConfigMap configMap = new ConfigMap();
        configMap.setApiVersion("v1alpha1");
        configMap.setKind(ConfigMap.KIND);
        Metadata metadata = new Metadata();
        metadata.setName(CONFIG_MAP_NAME);
        configMap.setMetadata(metadata);
        configMap.setData(data);
        return configMap;
    }
}
