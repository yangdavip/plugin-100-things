package com.matrixgrowth.plugin100things;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import lombok.Data;

/**
 * 预置的 100 件小事清单（存放在 {@code resources/seed/small-things.json}）。
 *
 * <p>插件首次启用时由 {@link SmallThingInitializer} 写入数据库，之后不再覆盖用户改动。
 *
 * @author matrixgrowth
 * @since 1.0.0
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SmallThingSeed {

    /** 编号 001-100。 */
    @JsonProperty("n")
    private Integer number;

    /** 小事名称。 */
    @JsonProperty("title")
    private String title;

    /** 所属分类。 */
    @JsonProperty("cat")
    private String category;

    /** 状态（TODO / WAITING / TREASURED / LATER）。 */
    @JsonProperty("status")
    private String status;

    /** 心情（可为空）。 */
    @JsonProperty("mood")
    private String mood;

    /** 完成日期（ISO 字符串，可为空）。 */
    @JsonProperty("doneAt")
    private String doneAt;

    /** 一句话记录（可为空）。 */
    @JsonProperty("note")
    private String note;

    /**
     * 读取预置清单。
     *
     * @return 100 条小事
     */
    public static List<SmallThingSeed> load() {
        return SmallThingSeedLoader.load();
    }

    /**
     * 把 ISO 日期字符串转成 Instant。
     *
     * @return 完成时间，未设置时为 null
     */
    public Instant doneAtInstant() {
        if (doneAt == null || doneAt.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(doneAt);
        } catch (Exception e) {
            return null;
        }
    }
}
