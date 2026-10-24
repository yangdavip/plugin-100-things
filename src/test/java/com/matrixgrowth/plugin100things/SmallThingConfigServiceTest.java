package com.matrixgrowth.plugin100things;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * {@link SmallThingConfigService} 的行为测试。
 *
 * <p>重点在「坏数据不能把整页打挂」：ConfigMap 是公开可编辑的扩展，缺键、空值、
 * 写了非法布尔串都可能出现。读配置必须逐字段回退默认值，坏值只影响它自己。
 */
@ExtendWith(MockitoExtension.class)
class SmallThingConfigServiceTest {

    @Mock
    ReactiveExtensionClient client;

    SmallThingConfigService service;

    @BeforeEach
    void setUp() {
        service = new SmallThingConfigService(client);
    }

    private ConfigMap configMap(Map<String, String> data) {
        ConfigMap configMap = new ConfigMap();
        configMap.setApiVersion("v1alpha1");
        configMap.setKind(ConfigMap.KIND);
        Metadata metadata = new Metadata();
        metadata.setName(SmallThingConfigService.CONFIG_MAP_NAME);
        configMap.setMetadata(metadata);
        configMap.setData(data);
        return configMap;
    }

    @Test
    void loadShouldReturnDefaultsWhenConfigMapMissing() {
        when(client.fetch(eq(ConfigMap.class), eq(SmallThingConfigService.CONFIG_MAP_NAME)))
            .thenReturn(Mono.empty());

        StepVerifier.create(service.load())
            .assertNext(config -> {
                assertThat(config.getPageTitle())
                    .isEqualTo(SmallThingConfig.DEFAULT_PAGE_TITLE);
                assertThat(config.getPageSubtitle())
                    .isEqualTo(SmallThingConfig.DEFAULT_PAGE_SUBTITLE);
                assertThat(config.isShowProgress()).isTrue();
                assertThat(config.isGroupByCategory()).isTrue();
            })
            .verifyComplete();
    }

    @Test
    void loadShouldFallBackFieldByField() {
        Map<String, String> data = new HashMap<>();
        data.put(SmallThingConfigService.KEY_PAGE_TITLE, "  ");          // 空白 ⟹ 默认标题
        data.put(SmallThingConfigService.KEY_PAGE_SUBTITLE, "自定义副标题");
        data.put(SmallThingConfigService.KEY_SHOW_PROGRESS, "maybe");    // 非法布尔 ⟹ 默认 true
        data.put(SmallThingConfigService.KEY_GROUP_BY_CATEGORY, "false");
        data.put(SmallThingConfigService.KEY_FOOTER_NOTE, "");           // 显式清空 ⟹ 不显示
        when(client.fetch(eq(ConfigMap.class), eq(SmallThingConfigService.CONFIG_MAP_NAME)))
            .thenReturn(Mono.just(configMap(data)));

        StepVerifier.create(service.load())
            .assertNext(config -> {
                assertThat(config.getPageTitle())
                    .isEqualTo(SmallThingConfig.DEFAULT_PAGE_TITLE);
                assertThat(config.getPageSubtitle()).isEqualTo("自定义副标题");
                assertThat(config.isShowProgress()).isTrue();
                assertThat(config.isGroupByCategory()).isFalse();
                assertThat(config.getFooterNote()).isEmpty();
            })
            .verifyComplete();
    }

    @Test
    void saveShouldCreateConfigMapWhenMissing() {
        when(client.fetch(eq(ConfigMap.class), eq(SmallThingConfigService.CONFIG_MAP_NAME)))
            .thenReturn(Mono.empty());
        when(client.create(any(ConfigMap.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        SmallThingConfig config = new SmallThingConfig();
        config.setPageTitle("  我的小事清单  ");
        config.setShowProgress(false);

        StepVerifier.create(service.save(config))
            .assertNext(saved -> {
                assertThat(saved.getPageTitle()).isEqualTo("我的小事清单");
                assertThat(saved.isShowProgress()).isFalse();
            })
            .verifyComplete();

        ArgumentCaptor<ConfigMap> captor = ArgumentCaptor.forClass(ConfigMap.class);
        verify(client).create(captor.capture());
        ConfigMap created = captor.getValue();
        assertThat(created.getMetadata().getName())
            .isEqualTo(SmallThingConfigService.CONFIG_MAP_NAME);
        assertThat(created.getData())
            .containsEntry(SmallThingConfigService.KEY_PAGE_TITLE, "我的小事清单")
            .containsEntry(SmallThingConfigService.KEY_SHOW_PROGRESS, "false");
    }

    @Test
    void saveShouldUpdateExistingConfigMap() {
        ConfigMap existing = configMap(Map.of(SmallThingConfigService.KEY_PAGE_TITLE, "旧标题"));
        when(client.fetch(eq(ConfigMap.class), eq(SmallThingConfigService.CONFIG_MAP_NAME)))
            .thenReturn(Mono.just(existing));
        when(client.update(any(ConfigMap.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        SmallThingConfig config = new SmallThingConfig();
        config.setPageTitle("新标题");

        StepVerifier.create(service.save(config)).expectNextCount(1).verifyComplete();

        verify(client).update(existing);
        assertThat(existing.getData())
            .containsEntry(SmallThingConfigService.KEY_PAGE_TITLE, "新标题");
        verify(client, never()).create(any());
    }

    @Test
    void saveShouldFallBackWhenCreateLosesTheRace() {
        // 两个请求同时保存：后者 create 失败，应退化为更新而不是把错误抛给用户
        when(client.fetch(eq(ConfigMap.class), eq(SmallThingConfigService.CONFIG_MAP_NAME)))
            .thenReturn(Mono.empty())
            .thenReturn(Mono.just(configMap(Map.of())));
        when(client.create(any(ConfigMap.class)))
            .thenReturn(Mono.error(new IllegalStateException("already exists")));
        when(client.update(any(ConfigMap.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.save(new SmallThingConfig()))
            .expectNextCount(1)
            .verifyComplete();

        verify(client).update(any(ConfigMap.class));
    }

    @Test
    void saveShouldTruncateOverlongText() {
        when(client.fetch(eq(ConfigMap.class), anyString())).thenReturn(Mono.empty());
        when(client.create(any(ConfigMap.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        SmallThingConfig config = new SmallThingConfig();
        config.setPageTitle("长".repeat(SmallThingConfigService.MAX_TEXT_LENGTH + 40));

        StepVerifier.create(service.save(config))
            .assertNext(saved -> assertThat(saved.getPageTitle())
                .hasSize(SmallThingConfigService.MAX_TEXT_LENGTH))
            .verifyComplete();
    }

    @Test
    void loadShouldParseChaptersFromConfigMap() {
        Map<String, String> data = new HashMap<>();
        data.put(SmallThingConfigService.KEY_CHAPTERS,
            "[{\"name\":\"序章\",\"say\":\"从这里开始。\",\"min\":0},"
                + "{\"name\":\"收尾\",\"say\":\"走到了。\",\"min\":30}]");
        when(client.fetch(eq(ConfigMap.class), eq(SmallThingConfigService.CONFIG_MAP_NAME)))
            .thenReturn(Mono.just(configMap(data)));

        StepVerifier.create(service.load())
            .assertNext(config -> {
                assertThat(config.getChapters()).hasSize(2);
                assertThat(config.getChapters().get(0).getName()).isEqualTo("序章");
                assertThat(config.getChapters().get(1).getMin()).isEqualTo(30);
            })
            .verifyComplete();
    }

    @Test
    void loadShouldFallBackToDefaultChaptersWhenJsonIsBroken() {
        // 章节是 ConfigMap 里唯一的多值字段，最容易被手改成非法 JSON。
        // 坏数据只能让章节回退默认值，不能让前台整页 500。
        Map<String, String> data = new HashMap<>();
        data.put(SmallThingConfigService.KEY_CHAPTERS, "{ not json ]");
        data.put(SmallThingConfigService.KEY_PAGE_TITLE, "标题仍然生效");
        when(client.fetch(eq(ConfigMap.class), eq(SmallThingConfigService.CONFIG_MAP_NAME)))
            .thenReturn(Mono.just(configMap(data)));

        StepVerifier.create(service.load())
            .assertNext(config -> {
                assertThat(config.getPageTitle()).isEqualTo("标题仍然生效");
                assertThat(config.getChapters()).hasSize(6);
                assertThat(config.getChapters().get(0).getName()).isEqualTo("启程");
            })
            .verifyComplete();
    }

    @Test
    void saveShouldWriteChaptersAsJsonAndNormalizeThem() {
        when(client.fetch(eq(ConfigMap.class), anyString())).thenReturn(Mono.empty());
        when(client.create(any(ConfigMap.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        SmallThingConfig config = new SmallThingConfig();
        config.setChapters(java.util.List.of(
            new SmallThingConfig.Chapter("后半程", "慢慢走。", 40),
            new SmallThingConfig.Chapter("启程", "开始。", 0),
            // 阈值重复：两章都写 40 时后者永远命中不了，必须去重
            new SmallThingConfig.Chapter("幽灵章", "不会出现。", 40)));

        StepVerifier.create(service.save(config))
            .assertNext(saved -> {
                assertThat(saved.getChapters()).hasSize(2);
                assertThat(saved.getChapters()).extracting(SmallThingConfig.Chapter::getName)
                    .containsExactly("启程", "后半程");
            })
            .verifyComplete();

        ArgumentCaptor<ConfigMap> captor = ArgumentCaptor.forClass(ConfigMap.class);
        verify(client).create(captor.capture());
        assertThat(captor.getValue().getData())
            .containsKey(SmallThingConfigService.KEY_CHAPTERS);
        assertThat(captor.getValue().getData().get(SmallThingConfigService.KEY_CHAPTERS))
            .contains("\"启程\"").contains("\"min\":0");
    }
}
