package com.matrixgrowth.plugin100things;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;

/**
 * Console 端路由的行为测试。
 *
 * <p>存在的理由：此前所有测试都直接调用 service 方法，**从没验证过请求能不能到达处理器**。
 * 结果是 {@code /smallthings/stats} 与 {@code /smallthings/options} 被
 * {@code /smallthings/{name}} 抢先匹配，稳定返回 404（把 "stats" 当成一条记录的名字），
 * 而页面表现为「顶部进度恒为 0、状态下拉框为空」——看起来像业务缺陷。
 *
 * <p>只有真正把 RouterFunction 挂进 WebTestClient 才能发现这类问题：断言的锚点是
 * **HTTP 状态码 + 响应体形状**，不是 service 有没有被调用。
 */
@ExtendWith(MockitoExtension.class)
class SmallThingConsoleEndpointTest {

    @Mock
    SmallThingService service;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        SmallThingConsoleEndpoint endpoint = new SmallThingConsoleEndpoint(service);
        client = WebTestClient.bindToRouterFunction(endpoint.endpoint()).build();
    }

    private SmallThing thing() {
        SmallThing thing = new SmallThing();
        Metadata metadata = new Metadata();
        metadata.setName("smallthing-001");
        metadata.setVersion(1L);
        thing.setMetadata(metadata);
        SmallThing.SmallThingSpec spec = new SmallThing.SmallThingSpec();
        spec.setNumber(1);
        spec.setTitle("和十七岁的自己聊一次天");
        spec.setCategory("青春时光");
        spec.setStatus(SmallThing.SmallThingSpec.Status.TREASURED);
        thing.setSpec(spec);
        return thing;
    }

    @Test
    void statsPathMustNotBeSwallowedByPathVariableRoute() {
        when(service.countTreasured()).thenReturn(Mono.just(24L));

        client.get().uri("/smallthings/stats")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.treasuredCount").isEqualTo(24);
    }

    @Test
    void optionsPathMustNotBeSwallowedByPathVariableRoute() {
        when(service.allStatuses())
            .thenReturn(java.util.List.of(SmallThing.SmallThingSpec.Status.values()));
        when(service.allMoods())
            .thenReturn(java.util.List.of(SmallThing.SmallThingSpec.Mood.values()));

        client.get().uri("/smallthings/options")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.statuses").isArray()
            .jsonPath("$.moods").isArray();
    }

    @Test
    void singleThingRouteStillResolves() {
        when(service.get("smallthing-001")).thenReturn(Mono.just(thing()));

        client.get().uri("/smallthings/smallthing-001")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.spec.title").isEqualTo("和十七岁的自己聊一次天");
    }

    @Test
    void missingThingShouldBeNotFound() {
        when(service.get("nope")).thenReturn(Mono.empty());

        client.get().uri("/smallthings/nope")
            .exchange()
            .expectStatus().isNotFound();
    }

    @Test
    void updateShouldReturnTheUpdatedThing() {
        when(service.update(any(SmallThing.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        client.put().uri("/smallthings/smallthing-001")
            .bodyValue(thing())
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.spec.title").isEqualTo("和十七岁的自己聊一次天");
    }
}
