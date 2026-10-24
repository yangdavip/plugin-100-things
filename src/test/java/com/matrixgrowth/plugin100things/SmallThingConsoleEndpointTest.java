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

    @Mock
    SmallThingConfigService configService;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        SmallThingConsoleEndpoint endpoint =
            new SmallThingConsoleEndpoint(service, configService);
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

    @Test
    void settingsShouldExposePagePathAndDefaults() {
        when(configService.load()).thenReturn(Mono.just(new SmallThingConfig()));

        client.get().uri("/settings")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.pagePath").isEqualTo(SmallThingPageRouter.PAGE_PATH)
            .jsonPath("$.config.pageTitle")
            .isEqualTo(SmallThingConfig.DEFAULT_PAGE_TITLE)
            // 默认值由后端给出，前端不再抄一份，避免两边默认文案漂移
            .jsonPath("$.defaults.pageTitle")
            .isEqualTo(SmallThingConfig.DEFAULT_PAGE_TITLE)
            // 章节也走同一份契约：控制台的「生活章节」页签据此渲染
            .jsonPath("$.config.chapters").isArray()
            .jsonPath("$.config.chapters[0].name").isEqualTo("启程")
            .jsonPath("$.config.chapters[5].min").isEqualTo(100);
    }

    @Test
    void updatingSettingsShouldReturnSavedConfig() {
        SmallThingConfig saved = new SmallThingConfig();
        saved.setPageTitle("我的清单");
        saved.setShowProgress(false);
        saved.setChapters(java.util.List.of(
            new SmallThingConfig.Chapter("序章", "一切从今天开始。", 0),
            new SmallThingConfig.Chapter("收尾", "这一程走完了。", 50)));
        when(configService.save(any(SmallThingConfig.class))).thenReturn(Mono.just(saved));

        client.put().uri("/settings")
            .bodyValue(new SmallThingConfig())
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.config.pageTitle").isEqualTo("我的清单")
            .jsonPath("$.config.showProgress").isEqualTo(false)
            // 章节必须原样回传：否则保存后界面会显示成默认章节，用户以为没存上
            .jsonPath("$.config.chapters.length()").isEqualTo(2)
            .jsonPath("$.config.chapters[1].name").isEqualTo("收尾");
    }

    @Test
    void createShouldReturn201WithCreatedThing() {
        when(service.create(any(SmallThing.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        client.post().uri("/smallthings")
            .bodyValue(thing())
            .exchange()
            .expectStatus().isCreated()
            .expectBody()
            .jsonPath("$.spec.title").isEqualTo("和十七岁的自己聊一次天");
    }

    @Test
    void createShouldPropagateValidationErrorAs400() {
        // 校验失败必须变成 400：静默成功会让界面以为加进去了
        when(service.create(any(SmallThing.class)))
            .thenReturn(Mono.error(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "编号 1 已被占用")));

        client.post().uri("/smallthings")
            .bodyValue(thing())
            .exchange()
            .expectStatus().isBadRequest();
    }

    @Test
    void updateShouldTakeNameFromPathWhenBodyHasNoMetadata() {
        // 控制台发的是 { spec }，名称只在 URL 里。真实环境曾因此 500：
        // 服务层拿到一个没有 metadata 的对象，直接抛「小事元数据不能为空」。
        when(service.update(any(SmallThing.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        SmallThing body = new SmallThing();
        SmallThing.SmallThingSpec spec = new SmallThing.SmallThingSpec();
        spec.setTitle("只改标题");
        body.setSpec(spec);

        client.put().uri("/smallthings/smallthing-001")
            .bodyValue(body)
            .exchange()
            .expectStatus().isOk();

        org.mockito.ArgumentCaptor<SmallThing> captor =
            org.mockito.ArgumentCaptor.forClass(SmallThing.class);
        org.mockito.Mockito.verify(service).update(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getMetadata().getName())
            .isEqualTo("smallthing-001");
    }

    @Test
    void deleteShouldReturnDeletedThing() {
        when(service.delete("smallthing-001")).thenReturn(Mono.just(thing()));

        client.delete().uri("/smallthings/smallthing-001")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.metadata.name").isEqualTo("smallthing-001");
    }
}
