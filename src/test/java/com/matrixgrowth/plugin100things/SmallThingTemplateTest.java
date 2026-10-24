package com.matrixgrowth.plugin100things;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 前台模板的静态守卫。
 *
 * <p>这两条规则各自对应一次真实事故，共同点是**页面不报错、只是内容悄悄变空或变错**：
 *
 * <ol>
 *   <li>{@code ${smallThings.?[spec.category == cat]}} —— SpEL 集合选择取不到外层
 *       {@code th:each} 的循环变量，抛 {@code EL1008E}；而同一元素上 {@code th:if}(300)
 *       先于 {@code th:with}(600) 求值，异常被吞成「整页只剩标题」。</li>
 *   <li>{@code ${item.spec.status == 'TREASURED'}} —— {@code spec.status} 是枚举，
 *       和字符串不相等恒为 false，100 张卡片全部渲染成「想去做」。</li>
 * </ol>
 *
 * <p>扫描前必须先剥掉 HTML 注释，否则本类自己的说明文字（含这些片段）会把守卫变成假红。
 */
class SmallThingTemplateTest {

    private static String template;

    @BeforeAll
    static void loadTemplate() throws IOException {
        try (InputStream in = SmallThingTemplateTest.class
            .getResourceAsStream("/templates/small-things.html")) {
            assertThat(in).as("模板必须存在于 classpath:/templates/small-things.html").isNotNull();
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        template = template.replaceAll("(?s)<!--.*?-->", "");
    }

    @Test
    void templateMustNotUseSpelSelectionOverLoopVariable() {
        // 分组必须来自 model.groups，而不是在模板里现筛
        assertThat(template).doesNotContain(".?[");
        assertThat(template).contains("${group.items}");
    }

    @Test
    void templateMustNotCompareStatusEnumWithStringLiteral() {
        assertThat(template).doesNotContain("spec.status ==");
        assertThat(template).doesNotContain("spec.mood ==");
        // 枚举取值必须经过 name()
        assertThat(template).contains("spec.status?.name()");
    }

    @Test
    void templateMustNotHardcodeCategoryList() {
        // 分类清单的唯一真源在 SmallThingService.defaultCategories()；
        // 模板里再写一份，改分类时两边必然漂移且不会报错。
        assertThat(template).doesNotContain("一个人的浪漫");
    }

    @Test
    void templateMustBeReadOnlyForEveryone() {
        // 前台展示页对任何人只读：模板里不能出现任何可提交的控件。
        // 界面藏起来不算数——真正的约束在服务层（改文案会被拒成 400），
        // 这条守卫只是保证「页面本身也不提供入口」。
        assertThat(template).doesNotContain("<form");
        assertThat(template).doesNotContain("<input");
        assertThat(template).doesNotContain("<textarea");
    }

    @Test
    void templateMustTakeCopyFromSettings() {
        // 标题写死在模板里，控制台的「页面标题」就永远不生效（且不报错）
        assertThat(template).contains("${settings.pageTitle}");
        assertThat(template).contains("${settings.pageSubtitle}");
        assertThat(template).contains("${treasuredCount}");
    }
}
