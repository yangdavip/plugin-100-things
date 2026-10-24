package com.matrixgrowth.plugin100things;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.plugin.PluginContext;

/**
 * 插件生命周期测试。
 *
 * <p>注意：主类新增了 {@link SchemeManager} 构造参数，{@code @InjectMocks} 会注入 mock，
 * 因此必须在 start / stop 时验证 Scheme 的注册与注销——否则 Spring 的
 * {@code forClassWithGenerics} 匹配失败会让控制器静默不启动。
 */
@ExtendWith(MockitoExtension.class)
class Plugin100ThingsPluginTest {

    @Mock
    PluginContext context;

    @Mock
    SchemeManager schemeManager;

    @Mock
    SmallThingInitializer initializer;

    @InjectMocks
    Plugin100ThingsPlugin plugin;

    @Test
    void shouldRegisterAndUnregisterSchemeOnLifecycle() {
        plugin.start();

        verify(schemeManager).register(org.mockito.ArgumentMatchers.eq(SmallThing.class),
            org.mockito.ArgumentMatchers.any());
        assertThat(plugin).isNotNull();

        plugin.stop();

        verify(schemeManager).unregister(Scheme.buildFromType(SmallThing.class));
    }

    @Test
    void shouldSeedPresetItemsOnStart() {
        plugin.start();

        verify(initializer).seed();
    }
}
