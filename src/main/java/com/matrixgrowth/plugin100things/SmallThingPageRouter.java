package com.matrixgrowth.plugin100things;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import lombok.RequiredArgsConstructor;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.theme.TemplateNameResolver;
import run.halo.app.theme.router.ModelConst;

import static org.springframework.web.reactive.function.server.RouterFunctions.route;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;

/**
 * 「100 件小事」的博客前台路由。
 *
 * <p>渲染 {@code templates/small-things.html}：主题若提供了同名模板则优先使用主题的，
 * 否则回退到插件自带的默认模板（{@link TemplateNameResolver#resolveTemplateNameOrDefault}）。
 *
 * <p>模板数据契约（对主题可见，属于版本化契约）：
 * <ul>
 *   <li>{@code smallThings} — 小事列表</li>
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

    private final TemplateNameResolver templateNameResolver;
    private final SmallThingService service;

    @Bean
    RouterFunction<ServerResponse> smallThingPageRouterFunction() {
        return route(GET("/small-things"), this::renderSmallThingsPage);
    }

    Mono<ServerResponse> renderSmallThingsPage(ServerRequest request) {
        return service.list()
            .collectList()
            .flatMap(items -> {
                Map<String, Object> model = new HashMap<>();
                model.put("smallThings", items);
                // 分组在 Java 侧算好：模板里用 SpEL 集合选择取不到外层循环变量（EL1008E），
                // 而 th:if 先于 th:with 求值会让失败退化成「静默空列表」。
                model.put("groups", service.groupByCategory(items));
                model.put("treasuredCount",
                    items.stream()
                        .filter(item -> item.getSpec() != null
                            && item.getSpec().getStatus() == SmallThing.SmallThingSpec.Status.TREASURED)
                        .count());
                model.put(ModelConst.TEMPLATE_ID, "plugin:plugin-100-things:small-things");
                return templateNameResolver
                    .resolveTemplateNameOrDefault(request.exchange(), "small-things")
                    .flatMap(templateName -> ServerResponse.ok().render(templateName, model));
            });
    }

    /**
     * 供模板使用的分类清单。
     *
     * @return 分类列表
     */
    public List<String> categories() {
        return SmallThingPublicEndpoint.defaultCategories();
    }
}
