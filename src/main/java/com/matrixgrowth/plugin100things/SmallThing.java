package com.matrixgrowth.plugin100things;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 「100 件小事」清单条目。
 *
 * <p>对应资料库版本「人生小事清单」表的 8 个字段：编号 / 小事 / 分类 / 状态 / 完成日期 / 心情 /
 * 一句话记录 / 照片。
 *
 * <p>单选字段的建模取舍：状态与心情是固定值域，用 Java 枚举（与 Halo 核心 {@code Post.VisibleEnum}
 * 同模式）；分类是开放值域（用户可自行扩展），用 String。
 *
 * @author matrixgrowth
 * @since 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(
    group = "hundredthings.plugin.halo.run",
    version = "v1alpha1",
    kind = "SmallThing",
    plural = "smallthings",
    singular = "smallthing"
)
public class SmallThing extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private SmallThingSpec spec;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private SmallThingStatus status;

    @Data
    @Schema(name = "SmallThingSpec")
    public static class SmallThingSpec {

        /** 编号：001-100 的序号。 */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "1000")
        private Integer number;

        /** 小事：清单条目的标题。 */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 200)
        private String title;

        /** 分类：开放值域，允许用户自行扩展。 */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 32)
        private String category;

        /** 状态：固定值域。 */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, defaultValue = "TODO")
        private Status status;

        /** 心情：固定值域，可为空（未记录时）。 */
        private Mood mood;

        /** 完成日期：为空表示尚未完成。 */
        private Instant doneAt;

        /** 一句话记录。 */
        @Schema(maxLength = 500)
        private String note;

        /** 照片：附件 permalink 或 URI 列表。 */
        private List<String> photos;

        /** 归属用户的 metadata.name。 */
        private String owner;

        /**
         * 小事状态。
         *
         * <p>与资料库版「想去做 / 等待发生 / 已经珍藏 / 留给以后」一一对应。
         */
        public enum Status {
            /** 想去做 */
            TODO,
            /** 等待发生 */
            WAITING,
            /** 已经珍藏 */
            TREASURED,
            /** 留给以后 */
            LATER
        }

        /**
         * 心情。
         *
         * <p>与资料库版「平静 / 期待 / 惊喜 / 感动 / 放松 / 疲惫 / 迷茫 / 想念 / 自由 / 其他」一一对应。
         */
        public enum Mood {
            /** 平静 */
            CALM,
            /** 期待 */
            EXPECTED,
            /** 惊喜 */
            SURPRISED,
            /** 感动 */
            MOVED,
            /** 放松 */
            RELAXED,
            /** 疲惫 */
            TIRED,
            /** 迷茫 */
            CONFUSED,
            /** 想念 */
            MISSING,
            /** 自由 */
            FREE,
            /** 其他 */
            OTHER
        }
    }

    @Data
    @Schema(name = "SmallThingStatus")
    public static class SmallThingStatus {

        /** 观察到的阶段，与 spec.status 保持一致，便于按状态索引查询。 */
        private String phase;

        /** 条目的生命周期阶段：CREATED / TRACKED / ARCHIVED。 */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String stage;

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private Instant observedTime;
    }
}
