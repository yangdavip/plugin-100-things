package com.matrixgrowth.plugin100things;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import run.halo.app.theme.TemplateNameResolver;

/**
 * 前台路由的守卫。
 *
 * <p>存在理由：插件安装后的对外地址是「博客域名/plugin-100-things」。上一版只注册了
 * {@code /small-things}，于是这个地址稳定 404——而单测因为从不检查路由，全程是绿的。
 *
 * <p>这里断言的是**路由是否匹配**与**跳转目标**，不是「处理器被调过」：
 * 渲染路径需要真实模板引擎，属于端到端验证的范畴（真实实例上跑过）。
 */
@ExtendWith(MockitoExtension.class)
class SmallThingPageRouterTest {

    @Mock
    TemplateNameResolver templateNameResolver;

    @Mock
    SmallThingService service;

    @Mock
    SmallThingConfigService configService;

    private RouterFunction<ServerResponse> router() {
        return new SmallThingPageRouter(templateNameResolver, service, configService)
            .smallThingPageRouterFunction();
    }

    @Test
    void pagePathMustBeRouted() {
        // 只验证「路由能匹配上」，不触发渲染：渲染需要真实模板引擎，属于端到端验证。
        // 注意不能用 MockServerRequest —— Spring 6 的方法判定会取 request.exchange()，
        // 而它没有 exchange，直接抛 IllegalStateException。
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get(SmallThingPageRouter.PAGE_PATH).build());
        ServerRequest request =
            ServerRequest.create(exchange, HandlerStrategies.withDefaults().messageReaders());

        assertThat(router().route(request).hasElement().block()).isTrue();
    }

    @Test
    void legacyPathMustRedirectToPagePath() {
        WebTestClient client = WebTestClient.bindToRouterFunction(router()).build();

        client.get().uri(SmallThingPageRouter.LEGACY_PATH)
            .exchange()
            .expectStatus().isFound()
            .expectHeader().location(SmallThingPageRouter.PAGE_PATH);
    }

    @Test
    void pagePathMustBeThePluginPrefixedOne() {
        // 应用市场文档与 README 都按这个地址对外承诺，改这里等于改对外契约
        assertThat(SmallThingPageRouter.PAGE_PATH).isEqualTo("/plugin-100-things");
        assertThat(SmallThingPageRouter.PAGE_PATH)
            .isNotEqualTo(SmallThingPageRouter.LEGACY_PATH);
    }
}
