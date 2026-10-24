package com.matrixgrowth.plugin100things;

import static org.springframework.http.MediaType.APPLICATION_JSON;

import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

/**
 * 「100 件小事」的博客前台公开 API（免登录可读）。
 *
 * <p>前台只需要读，因此仅暴露列表与统计两个只读接口；写操作一律走 Console 端。
 *
 * <p>路径前缀为 {@code /apis/api.hundredthings.plugin.halo.run/v1alpha1}。
 *
 * @author matrixgrowth
 * @since 1.0.0
 */
@Component
public class SmallThingPublicEndpoint implements CustomEndpoint {

    private final SmallThingService service;

    public SmallThingPublicEndpoint(SmallThingService service) {
        this.service = service;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .GET("/smallthings", this::listSmallThings)
            .GET("/smallthings/stats", this::stats)
            .build();
    }

    private Mono<ServerResponse> listSmallThings(ServerRequest request) {
        return service.list()
            .collectList()
            .flatMap(items -> ServerResponse.ok()
                .contentType(APPLICATION_JSON)
                .bodyValue(items));
    }

    private Mono<ServerResponse> stats(ServerRequest request) {
        return service.countTreasured()
            .flatMap(count -> ServerResponse.ok()
                .contentType(APPLICATION_JSON)
                .bodyValue(new PublicStatsResponse(count)));
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("api.hundredthings.plugin.halo.run", "v1alpha1");
    }

    /**
     * 前台统计响应。
     *
     * @param treasuredCount 已珍藏件数
     */
    public record PublicStatsResponse(Long treasuredCount) {
    }

    /**
     * 供前台使用的分类常量。
     *
     * <p>委托给 {@link SmallThingService#defaultCategories()}，保持单一真源。
     *
     * @return 十个分类
     */
    public static List<String> defaultCategories() {
        return SmallThingService.defaultCategories();
    }
}
