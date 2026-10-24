package com.matrixgrowth.plugin100things;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * 「100 件小事」清单的业务逻辑。
 *
 * <p>使用 {@link ReactiveExtensionClient}（响应式），因为调用链位于 WebFlux 中，阻塞式
 * {@code ExtensionClient} 会违规占用事件循环线程。
 *
 * @author matrixgrowth
 * @since 1.0.0
 */
@Service
public class SmallThingService {

    private final ReactiveExtensionClient client;

    public SmallThingService(ReactiveExtensionClient client) {
        this.client = client;
    }

    /**
     * 列出全部小事，按编号升序。
     *
     * @return 小事列表（响应式流）
     */
    public Flux<SmallThing> list() {
        return client.list(SmallThing.class,
            thing -> true,
            Comparator.comparing(thing -> thing.getSpec() == null
                ? Integer.MAX_VALUE
                : thing.getSpec().getNumber() == null ? Integer.MAX_VALUE : thing.getSpec().getNumber()));
    }

    /**
     * 按名称获取单条小事。
     *
     * @param name 小事的 metadata.name
     * @return 小事，不存在时为空
     */
    public Mono<SmallThing> get(String name) {
        return client.fetch(SmallThing.class, name);
    }

    /**
     * 创建一条小事。
     *
     * @param smallThing 待创建的小事
     * @return 创建后的小事
     */
    public Mono<SmallThing> create(SmallThing smallThing) {
        Assert.notNull(smallThing, "小事不能为空");
        Assert.notNull(smallThing.getSpec(), "小事内容不能为空");
        smallThing.setStatus(buildStatus(smallThing));
        return client.create(smallThing);
    }

    /**
     * 更新一条小事。
     *
     * <p>仅覆盖请求体中携带的字段，未携带的字段保持原值——这与资料库版本的「增量更新」语义一致。
     *
     * @param smallThing 待更新的小事（metadata.name 必填）
     * @return 更新后的小事
     */
    public Mono<SmallThing> update(SmallThing smallThing) {
        Assert.notNull(smallThing, "小事不能为空");
        Assert.notNull(smallThing.getMetadata(), "小事元数据不能为空");
        String name = smallThing.getMetadata().getName();
        Assert.hasText(name, "小事名称不能为空");
        return client.get(SmallThing.class, name)
            .map(existed -> {
                SmallThing merged = merge(existed, smallThing);
                merged.setStatus(buildStatus(merged));
                return merged;
            })
            .switchIfEmpty(Mono.error(new IllegalArgumentException("小事不存在：" + name)))
            .flatMap(client::update);
    }

    /**
     * 删除一条小事。
     *
     * <p>删除前必须先 fetch 到当前对象（携带 metadata.version），否则会退化为 insert。
     *
     * @param name 小事的 metadata.name
     * @return 删除结果
     */
    public Mono<SmallThing> delete(String name) {
        return client.get(SmallThing.class, name)
            .flatMap(client::delete)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("小事不存在：" + name)));
    }

    /**
     * 统计已珍藏（状态为 TREASURED）的小事数量。
     *
     * @return 已珍藏件数
     */
    public Mono<Long> countTreasured() {
        return client.list(SmallThing.class,
                thing -> thing.getSpec() != null
                    && thing.getSpec().getStatus() == SmallThing.SmallThingSpec.Status.TREASURED,
                Comparator.comparing(thing -> thing.getSpec().getNumber() == null
                    ? Integer.MAX_VALUE : thing.getSpec().getNumber()))
            .count();
    }

    /**
     * 合并增量更新：请求体中非 null 的字段覆盖原值，null 表示不修改。
     *
     * @param existed 已存在的小事
     * @param incoming 请求体
     * @return 合并后的小事
     */
    private SmallThing merge(SmallThing existed, SmallThing incoming) {
        SmallThing merged = existed;
        SmallThing.SmallThingSpec oldSpec = existed.getSpec();
        SmallThing.SmallThingSpec newSpec = incoming.getSpec();
        if (newSpec != null) {
            SmallThing.SmallThingSpec spec = new SmallThing.SmallThingSpec();
            spec.setNumber(newSpec.getNumber() != null ? newSpec.getNumber() : oldSpec.getNumber());
            spec.setTitle(newSpec.getTitle() != null ? newSpec.getTitle() : oldSpec.getTitle());
            spec.setCategory(newSpec.getCategory() != null ? newSpec.getCategory() : oldSpec.getCategory());
            spec.setStatus(newSpec.getStatus() != null ? newSpec.getStatus() : oldSpec.getStatus());
            spec.setMood(newSpec.getMood() != null ? newSpec.getMood() : oldSpec.getMood());
            spec.setDoneAt(newSpec.getDoneAt() != null ? newSpec.getDoneAt() : oldSpec.getDoneAt());
            spec.setNote(newSpec.getNote() != null ? newSpec.getNote() : oldSpec.getNote());
            spec.setPhotos(newSpec.getPhotos() != null ? newSpec.getPhotos() : oldSpec.getPhotos());
            spec.setOwner(newSpec.getOwner() != null ? newSpec.getOwner() : oldSpec.getOwner());
            merged.setSpec(spec);
        }
        return merged;
    }

    /**
     * 构建 status：把 spec.status 冗余到 status.phase，便于按状态索引查询。
     *
     * @param thing 小事
     * @return 状态
     */
    private SmallThing.SmallThingStatus buildStatus(SmallThing thing) {
        SmallThing.SmallThingStatus status = new SmallThing.SmallThingStatus();
        SmallThing.SmallThingSpec.Status specStatus =
            thing.getSpec() == null ? null : thing.getSpec().getStatus();
        status.setPhase(specStatus == null ? null : specStatus.name());
        status.setStage("TRACKED");
        status.setObservedTime(Instant.now());
        return status;
    }

    /**
     * 供测试与上层复用的枚举取值列表。
     *
     * @return 状态枚举
     */
    public List<SmallThing.SmallThingSpec.Status> allStatuses() {
        return List.of(SmallThing.SmallThingSpec.Status.values());
    }

    /**
     * 供测试与上层复用的心情取值列表。
     *
     * @return 心情枚举
     */
    public List<SmallThing.SmallThingSpec.Mood> allMoods() {
        return List.of(SmallThing.SmallThingSpec.Mood.values());
    }

    /**
     * 依据状态与心情生成稳定的 label，便于用 labelSelector 查询（枚举走 fieldSelector 不保证命中）。
     *
     * @param thing 小事
     * @return label 键值对
     */
    public Map<String, String> buildLabels(SmallThing thing) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (thing.getSpec() == null) {
            return labels;
        }
        if (thing.getSpec().getStatus() != null) {
            labels.put("hundredthings.halo.run/status", thing.getSpec().getStatus().name().toLowerCase());
        }
        if (thing.getSpec().getCategory() != null) {
            labels.put("hundredthings.halo.run/category", thing.getSpec().getCategory());
        }
        if (thing.getSpec().getMood() != null) {
            labels.put("hundredthings.halo.run/mood", thing.getSpec().getMood().name().toLowerCase());
        }
        return labels;
    }
}
