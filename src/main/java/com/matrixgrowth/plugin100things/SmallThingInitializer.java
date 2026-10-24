package com.matrixgrowth.plugin100things;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * 插件首次启用时写入 100 件小事预置数据。
 *
 * <p><b>只在一条都没有时写入</b>，而不是「补齐缺失的编号」。控制台现在可以删除条目，
 * 按老的「补差集」做法，用户删掉的条目会在插件下次启用时原样回来——他删了三次、
 * 回来了三次，且日志里看不出任何异常。
 *
 * <p>由插件主类的 {@code start()} 调用——放在这里而不是事件监听器上，是因为
 * {@code PluginStartedEvent} 在插件 Spring 容器就绪后触发，那时 Scheme 可能尚未注册完成。
 *
 * @author matrixgrowth
 * @since 1.0.0
 */
@Component
@RequiredArgsConstructor
public class SmallThingInitializer {

    private final ReactiveExtensionClient client;

    /**
     * 写入预置数据（幂等：只要有任意一条存在就整体跳过）。
     */
    public void seed() {
        List<SmallThingSeed> seeds = SmallThingSeed.load();
        if (seeds.isEmpty()) {
            return;
        }
        client.list(SmallThing.class, thing -> true,
                Comparator.comparing(SmallThingInitializer::numberOf,
                    Comparator.nullsLast(Comparator.naturalOrder())))
            .count()
            .flatMapMany(existing -> existing > 0
                ? Flux.empty()
                : Flux.fromIterable(seeds))
            // 单条失败不影响其余条目：一条数据写不进去，不该让整个插件启动失败
            .flatMap(seed -> client.create(toExtension(seed))
                .onErrorResume(error -> Mono.empty()))
            .then()
            .block();
    }

    private static Integer numberOf(SmallThing thing) {
        return thing.getSpec() == null ? null : thing.getSpec().getNumber();
    }

    /**
     * 把预置条目转换为扩展对象。
     *
     * @param seed 预置条目
     * @return 扩展对象
     */
    static SmallThing toExtension(SmallThingSeed seed) {
        SmallThing thing = new SmallThing();
        Metadata metadata = new Metadata();
        metadata.setName("smallthing-" + String.format("%03d", seed.getNumber()));
        thing.setMetadata(metadata);

        SmallThing.SmallThingSpec spec = new SmallThing.SmallThingSpec();
        spec.setNumber(seed.getNumber());
        spec.setTitle(seed.getTitle());
        spec.setCategory(seed.getCategory());
        spec.setStatus(parseStatus(seed.getStatus()));
        spec.setMood(parseMood(seed.getMood()));
        spec.setDoneAt(seed.doneAtInstant());
        spec.setNote(seed.getNote());
        thing.setSpec(spec);

        SmallThing.SmallThingStatus status = new SmallThing.SmallThingStatus();
        status.setPhase(spec.getStatus() == null ? null : spec.getStatus().name());
        status.setStage("TRACKED");
        status.setObservedTime(Instant.now());
        thing.setStatus(status);
        return thing;
    }

    private static SmallThing.SmallThingSpec.Status parseStatus(String value) {
        if (value == null) {
            return SmallThing.SmallThingSpec.Status.TODO;
        }
        try {
            return SmallThing.SmallThingSpec.Status.valueOf(value);
        } catch (IllegalArgumentException e) {
            return SmallThing.SmallThingSpec.Status.TODO;
        }
    }

    private static SmallThing.SmallThingSpec.Mood parseMood(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return SmallThing.SmallThingSpec.Mood.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
