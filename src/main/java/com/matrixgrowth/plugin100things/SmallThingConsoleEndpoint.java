package com.matrixgrowth.plugin100things;

import static org.springframework.http.MediaType.APPLICATION_JSON;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

/**
 * 「100 件小事」的 Console 端 API（需要登录并具备角色权限）。
 *
 * <p>路径前缀为 {@code /apis/console.api.hundredthings.plugin.halo.run/v1alpha1}。
 *
 * <p>能力边界：清单文案（编号 / 标题 / 分类）是内置内容，**没有任何写入入口**——
 * 没有创建、没有删除，更新也只接受进度字段（状态 / 日期 / 心情 / 一句话），
 * 改文案会被 {@link SmallThingService#update(SmallThing)} 拒绝为 400。
 * 可写的东西只有 {@code /settings} 里的展示配置。
 *
 * @author matrixgrowth
 * @since 1.0.0
 */
@Component
public class SmallThingConsoleEndpoint implements CustomEndpoint {

    private final SmallThingService service;
    private final SmallThingConfigService configService;

    public SmallThingConsoleEndpoint(SmallThingService service,
        SmallThingConfigService configService) {
        this.service = service;
        this.configService = configService;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        // ⚠ 顺序有意义：RouterFunction 按注册顺序匹配，"/smallthings/{name}" 会吃掉
        // "/smallthings/stats" 与 "/smallthings/options"（把 stats 当成一条记录的名字），
        // 结果这两个接口稳定返回 404，而页面只是「进度显示 0、下拉框为空」——
        // 表现为功能缺陷而不是报错。字面量路径必须排在路径变量之前。
        return RouterFunctions.route()
            .GET("/settings", this::getSettings)
            .PUT("/settings", this::updateSettings)
            .GET("/smallthings", this::listSmallThings)
            .GET("/smallthings/stats", this::stats)
            .GET("/smallthings/options", this::options)
            .GET("/smallthings/{name}", this::getSmallThing)
            .PUT("/smallthings/{name}", this::updateSmallThing)
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

    private Mono<ServerResponse> updateSmallThing(ServerRequest request) {
        return request.bodyToMono(SmallThing.class)
            .flatMap(service::update)
            .flatMap(updated -> ServerResponse.ok()
                .contentType(APPLICATION_JSON)
                .bodyValue(updated));
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

    /**
     * 读取展示配置。
     *
     * <p>顺带把前台地址回给界面，避免「路径写死在路由、又写死一份在页面上」——
     * 两处各写一份时改一处不会报错，只会给出一个打不开的链接。
     *
     * @param request 请求
     * @return 配置
     */
    private Mono<ServerResponse> getSettings(ServerRequest request) {
        return configService.load()
            .flatMap(config -> ServerResponse.ok()
                .contentType(APPLICATION_JSON)
                .bodyValue(new SettingsResponse(
                    SmallThingPageRouter.PAGE_PATH, config, SmallThingConfig.defaults())));
    }

    /**
     * 保存展示配置（改完立即生效，不需要重启插件）。
     *
     * @param request 请求
     * @return 保存后的配置
     */
    private Mono<ServerResponse> updateSettings(ServerRequest request) {
        return request.bodyToMono(SmallThingConfig.class)
            .switchIfEmpty(Mono.error(
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "配置内容不能为空")))
            .flatMap(configService::save)
            .flatMap(saved -> ServerResponse.ok()
                .contentType(APPLICATION_JSON)
                .bodyValue(new SettingsResponse(
                    SmallThingPageRouter.PAGE_PATH, saved, SmallThingConfig.defaults())));
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

    /**
     * 配置响应。
     *
     * @param pagePath 前台访问路径（只读，来自路由常量）
     * @param config 当前配置
     * @param defaults 默认配置（供界面「恢复默认」使用，避免前端再抄一份默认值）
     */
    public record SettingsResponse(String pagePath, SmallThingConfig config,
        SmallThingConfig defaults) {
    }
}
