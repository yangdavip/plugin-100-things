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
 * 「100 件小事」的 Console 端 API（需要登录并具备角色权限）。
 *
 * <p>路径前缀为 {@code /apis/console.api.hundredthings.plugin.halo.run/v1alpha1}。
 *
 * @author matrixgrowth
 * @since 1.0.0
 */
@Component
public class SmallThingConsoleEndpoint implements CustomEndpoint {

    private final SmallThingService service;

    public SmallThingConsoleEndpoint(SmallThingService service) {
        this.service = service;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        // ⚠ 顺序有意义：RouterFunction 按注册顺序匹配，"/smallthings/{name}" 会吃掉
        // "/smallthings/stats" 与 "/smallthings/options"（把 stats 当成一条记录的名字），
        // 结果这两个接口稳定返回 404，而页面只是「进度显示 0、下拉框为空」——
        // 表现为功能缺陷而不是报错。字面量路径必须排在路径变量之前。
        return RouterFunctions.route()
            .GET("/smallthings", this::listSmallThings)
            .GET("/smallthings/stats", this::stats)
            .GET("/smallthings/options", this::options)
            .GET("/smallthings/{name}", this::getSmallThing)
            .POST("/smallthings", this::createSmallThing)
            .PUT("/smallthings/{name}", this::updateSmallThing)
            .DELETE("/smallthings/{name}", this::deleteSmallThing)
            .build();
    }

    private Mono<ServerResponse> listSmallThings(ServerRequest request) {
        return service.list()
            .collectList()
            .flatMap(items -> ServerResponse.ok()
                .contentType(APPLICATION_JSON)
                .bodyValue(items));
    }

    private Mono<ServerResponse> getSmallThing(ServerRequest request) {
        return service.get(request.pathVariable("name"))
            .flatMap(item -> ServerResponse.ok()
                .contentType(APPLICATION_JSON)
                .bodyValue(item))
            .switchIfEmpty(ServerResponse.notFound().build());
    }

    private Mono<ServerResponse> createSmallThing(ServerRequest request) {
        return request.bodyToMono(SmallThing.class)
            .flatMap(service::create)
            .flatMap(created -> ServerResponse.ok()
                .contentType(APPLICATION_JSON)
                .bodyValue(created));
    }

    private Mono<ServerResponse> updateSmallThing(ServerRequest request) {
        return request.bodyToMono(SmallThing.class)
            .flatMap(service::update)
            .flatMap(updated -> ServerResponse.ok()
                .contentType(APPLICATION_JSON)
                .bodyValue(updated));
    }

    private Mono<ServerResponse> deleteSmallThing(ServerRequest request) {
        return service.delete(request.pathVariable("name"))
            .flatMap(deleted -> ServerResponse.ok()
                .contentType(APPLICATION_JSON)
                .bodyValue(deleted));
    }

    /**
     * 统计接口：已珍藏件数。
     *
     * @param request 请求
     * @return 统计结果
     */
    private Mono<ServerResponse> stats(ServerRequest request) {
        return service.countTreasured()
            .flatMap(count -> ServerResponse.ok()
                .contentType(APPLICATION_JSON)
                .bodyValue(new StatsResponse(count)));
    }

    /**
     * 选项接口：状态与心情的取值列表，供管理端动态渲染下拉。
     *
     * @param request 请求
     * @return 选项
     */
    private Mono<ServerResponse> options(ServerRequest request) {
        return ServerResponse.ok()
            .contentType(APPLICATION_JSON)
            .bodyValue(new OptionsResponse(
                service.allStatuses().stream().map(Enum::name).toList(),
                service.allMoods().stream().map(Enum::name).toList()));
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.hundredthings.plugin.halo.run", "v1alpha1");
    }

    /**
     * 统计响应。
     *
     * @param treasuredCount 已珍藏件数
     */
    public record StatsResponse(Long treasuredCount) {
    }

    /**
     * 选项响应。
     *
     * @param statuses 状态取值
     * @param moods 心情取值
     */
    public record OptionsResponse(List<String> statuses, List<String> moods) {
    }
}
