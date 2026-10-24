package com.matrixgrowth.plugin100things;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import run.halo.app.theme.TemplateNameResolver;
import run.halo.app.theme.router.ModelConst;

/**
 * 「100 件小事」的博客前台路由。
 *
 * <p>对外地址固定为 {@link #PAGE_PATH}（即「博客域名/plugin-100-things」）。早先版本用的
 * {@link #LEGACY_PATH} 保留为 302 跳转，避免已经发出去的链接变成 404。
 *
 * <p>渲染 {@code templates/small-things.html}：主题若提供了同名模板则优先使用主题的，
 * 否则回退到插件自带的默认模板（{@link TemplateNameResolver#resolveTemplateNameOrDefault}）。
 *
 * <p>模板数据契约（对主题可见，属于版本化契约）：
 * <ul>
 *   <li>{@code settings} — 展示配置（{@link SmallThingConfig}）</li>
 *   <li>{@code smallThings} — 小事列表</li>
 *   <li>{@code groups} — 分好组的小事（平铺模式下是唯一的无序分组）</li>
 *   <li>{@code treasuredCount} — 已珍藏件数</li>
 *   <li>{@code _templateId} — 固定为 {@code plugin:plugin-100-things:small-things}</li>
 * </ul>
 *
 * @author matrixgrowth
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class SmallThingPageRouter {

    /** 前台页面的对外路径。 */
    public static final String PAGE_PATH = "/plugin-100-things";

    /** 旧路径，仅用于 302 跳转。 */
    public static final String LEGACY_PATH = "/small-things";

    /** 插件内置模板名。 */
    public static final String DEFAULT_TEMPLATE = "small-things";

    /** 模板标识，写入 {@code _templateId} 供布局解析使用。 */
    public static final String TEMPLATE_ID = "plugin:plugin-100-things:small-things";

    private final TemplateNameResolver templateNameResolver;
    private final SmallThingService service;
    private final SmallThingConfigService configService;

    @Bean
    RouterFunction<ServerResponse> smallThingPageRouterFunction() {
        return RouterFunctions.route()
            .GET(PAGE_PATH, this::renderSmallThingsPage)
            .GET(LEGACY_PATH, this::redirectLegacyPath)
            .build();
    }

    Mono<ServerResponse> renderSmallThingsPage(ServerRequest request) {
        return Mono.zip(service.list().collectList(), configService.load())
            .flatMap(tuple -> {
                List<SmallThing> items = tuple.getT1();
                SmallThingConfig settings = tuple.getT2();
                Map<String, Object> model = new HashMap<>();
                model.put("settings", settings);
                model.put("smallThings", items);
                // 分组在 Java 侧算好：模板里用 SpEL 集合选择取不到外层循环变量（EL1008E），
                // 而 th:if 先于 th:with 求值会让失败退化成「静默空列表」。
                model.put("groups", service.groupByCategory(items, settings.isGroupByCategory()));
                model.put("treasuredCount",
                    items.stream()
                        .filter(item -> item.getSpec() != null
                            && item.getSpec().getStatus() == SmallThing.SmallThingSpec.Status.TREASURED)
                        .count());
                model.put(ModelConst.TEMPLATE_ID, TEMPLATE_ID);
                return templateNameResolver
                    .resolveTemplateNameOrDefault(request.exchange(), DEFAULT_TEMPLATE)
                    .flatMap(templateName -> ServerResponse.ok().render(templateName, model));
            });
    }

    /**
     * 旧路径跳转到新路径。
     *
     * <p>用请求自身的 raw path 做替换而不是写死 "/plugin-100-things"，这样 Halo 部署在
     * 子路径下时跳转目标也是对的。
     *
     * @param request 请求
     * @return 302 响应
     */
    Mono<ServerResponse> redirectLegacyPath(ServerRequest request) {
        String rawPath = request.uri().getRawPath();
        String target = rawPath == null ? PAGE_PATH : rawPath.replace(LEGACY_PATH, PAGE_PATH);
        String query = request.uri().getRawQuery();
        URI location = URI.create(query == null || query.isBlank()
            ? target : target + "?" + query);
        return ServerResponse.status(HttpStatus.FOUND).location(location).build();
    }

    /**
     * 供模板使用的分类清单。
     *
     * @return 分类列表
     */
    public List<String> categories() {
        return SmallThingService.defaultCategories();
    }
}
