package com.matrixgrowth.plugin100things;

import org.springframework.stereotype.Component;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.index.IndexSpecs;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/**
 * <p>插件主类：管理「100 件小事」插件的生命周期。</p>
 * <p>启动时注册 {@link SmallThing} 自定义模型（含索引），停止时注销 Scheme。注销 Scheme
 * <b>不会</b>删除已保存的数据，插件再次启用后数据仍在。</p>
 *
 * @author matrixgrowth
 * @since 1.0.0
 */
@Component
public class Plugin100ThingsPlugin extends BasePlugin {

    private final SchemeManager schemeManager;
    private final SmallThingInitializer initializer;

    public Plugin100ThingsPlugin(PluginContext pluginContext, SchemeManager schemeManager,
        SmallThingInitializer initializer) {
        super(pluginContext);
        this.schemeManager = schemeManager;
        this.initializer = initializer;
    }

    @Override
    public void start() {
        schemeManager.register(SmallThing.class, indexSpecs -> {
            indexSpecs.add(IndexSpecs.<SmallThing, Integer>single("spec.number", Integer.class)
                .indexFunc(thing -> thing.getSpec() == null ? null : thing.getSpec().getNumber()));
            indexSpecs.add(IndexSpecs.<SmallThing, String>single("spec.category", String.class)
                .indexFunc(thing -> thing.getSpec() == null ? null : thing.getSpec().getCategory()));
            indexSpecs.add(IndexSpecs.<SmallThing, String>single("spec.status", String.class)
                .indexFunc(thing -> thing.getSpec() == null || thing.getSpec().getStatus() == null
                    ? null : thing.getSpec().getStatus().name()));
            indexSpecs.add(IndexSpecs.<SmallThing, String>single("spec.mood", String.class)
                .indexFunc(thing -> thing.getSpec() == null || thing.getSpec().getMood() == null
                    ? null : thing.getSpec().getMood().name()));
        });
        // Scheme 注册完成后再写入预置数据
        initializer.seed();
    }

    @Override
    public void stop() {
        schemeManager.unregister(Scheme.buildFromType(SmallThing.class));
    }
}
