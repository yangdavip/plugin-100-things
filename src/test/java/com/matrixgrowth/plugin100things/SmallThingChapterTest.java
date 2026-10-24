package com.matrixgrowth.plugin100things;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 章节阈值计算与规范化的守卫。
 *
 * <p>存在理由：章节是控制台可改的数据，算错不会报错，只会让前台的进度条一直显示第一章，
 * 或者「距下一章还差 -3 件」这类负数。
 */
class SmallThingChapterTest {

    private List<SmallThingConfig.Chapter> defaults() {
        return SmallThingConfig.defaultChapters();
    }

    @Test
    void currentShouldStayOnFirstChapterBeforeAnyThreshold() {
        assertThat(SmallThingConfig.currentOf(defaults(), 0).getName()).isEqualTo("启程");
        assertThat(SmallThingConfig.currentOf(defaults(), 19).getName()).isEqualTo("启程");
    }

    @Test
    void currentShouldMoveOnExactlyAtThreshold() {
        // 20 件这一格刚好处在边界上：用 > 还是 >= 会让卡片数字和章节对不上
        assertThat(SmallThingConfig.currentOf(defaults(), 20).getName()).isEqualTo("相遇");
        assertThat(SmallThingConfig.currentOf(defaults(), 99).getName()).isEqualTo("珍藏");
        assertThat(SmallThingConfig.currentOf(defaults(), 100).getName()).isEqualTo("满载而归");
    }

    @Test
    void nextShouldBeNullWhenEverythingUnlocked() {
        assertThat(SmallThingConfig.nextOf(defaults(), 0).getName()).isEqualTo("相遇");
        assertThat(SmallThingConfig.nextOf(defaults(), 100)).isNull();
    }

    @Test
    void thresholdsShouldBeSortedSoUnorderedInputStillWorks() {
        List<SmallThingConfig.Chapter> unsorted = List.of(
            new SmallThingConfig.Chapter("后", "x", 50),
            new SmallThingConfig.Chapter("前", "x", 0));

        assertThat(SmallThingConfig.currentOf(unsorted, 60).getName()).isEqualTo("后");
        assertThat(SmallThingConfig.nextOf(unsorted, 0).getName()).isEqualTo("后");
    }

    @Test
    void normalizeShouldDropDuplicateThresholds() {
        // 两章都写 20 时，前一个永远命中不了 —— 界面上却还在，属于「看得见但不存在」
        List<SmallThingConfig.Chapter> normalized = SmallThingConfig.normalizeChapters(List.of(
            new SmallThingConfig.Chapter("甲", "a", 20),
            new SmallThingConfig.Chapter("乙", "b", 20)));

        assertThat(normalized).hasSize(1);
        assertThat(normalized.get(0).getName()).isEqualTo("甲");
    }

    @Test
    void normalizeShouldKeepChaptersWhoseNameIsBlank() {
        List<SmallThingConfig.Chapter> normalized = SmallThingConfig.normalizeChapters(List.of(
            new SmallThingConfig.Chapter("  ", "没写名字", 0),
            new SmallThingConfig.Chapter("收尾", "写完了", 100)));

        // 名字留空不该让整章消失（否则用户只是漏填一个字，进度线就少一格）
        assertThat(normalized).hasSize(2);
        assertThat(normalized.get(0).getName()).isNotBlank();
        assertThat(normalized.get(1).getName()).isEqualTo("收尾");
    }

    @Test
    void normalizeShouldFallBackToDefaultsWhenInputIsEmpty() {
        assertThat(SmallThingConfig.normalizeChapters(List.of())).hasSize(6);
        assertThat(SmallThingConfig.normalizeChapters(null)).hasSize(6);
    }

    @Test
    void normalizeShouldClampNegativeThresholdAndCapCount() {
        List<SmallThingConfig.Chapter> many = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(new SmallThingConfig.Chapter("第" + i, "", i - 5));
        }

        List<SmallThingConfig.Chapter> normalized = SmallThingConfig.normalizeChapters(many);

        assertThat(normalized).hasSize(SmallThingConfig.MAX_CHAPTERS);
        assertThat(normalized.get(0).getMin()).isGreaterThanOrEqualTo(0);
    }
}
