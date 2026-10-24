package com.matrixgrowth.plugin100things;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;

/**
 * {@link SmallThing} 的调谐器。
 *
 * <p>职责是幂等地维护条目的 status：把 spec.status 冗余到 status.phase、刷新 observedTime，
 * 并把状态/心情/分类写进 label，使按状态与分类的查询走索引。
 *
 * <p><b>关键</b>：写回前必须比较新旧值是否真的不同——否则 update 会再次触发调谐，形成无限循环。
 *
 * <p>注意：泛型必须精确写 {@code Reconciler.Request}。Halo 核心用
 * {@code forClassWithGenerics(Reconciler.class, Reconciler.Request.class)} 查找 Bean，
 * 写成其它类型会导致控制器静默不启动。
 *
 * @author matrixgrowth
 * @since 1.0.0
 */
@Component
@RequiredArgsConstructor
public class SmallThingReconciler implements Reconciler<Reconciler.Request> {

    private final SmallThingService service;

    @Override
    public Result reconcile(Request request) {
        // reconcile 是同步签名；调谐在控制器线程内被串行调用，这里用 block 拿回结果。
        // 写入前已比较新旧值，状态未变时不会产生 update，因此不会自激循环。
        service.get(request.name())
            .flatMap(thing -> {
                SmallThing.SmallThingStatus status = new SmallThing.SmallThingStatus();
                SmallThing.SmallThingSpec.Status specStatus =
                    thing.getSpec() == null ? null : thing.getSpec().getStatus();
                status.setPhase(specStatus == null ? null : specStatus.name());
                status.setStage("TRACKED");
                status.setObservedTime(Instant.now());

                Map<String, String> labels = service.buildLabels(thing);
                boolean statusChanged = !Objects.equals(
                    thing.getStatus() == null ? null : thing.getStatus().getPhase(), status.getPhase());
                boolean labelsChanged = !Objects.equals(
                    thing.getMetadata().getLabels(), labels);

                if (!statusChanged && !labelsChanged) {
                    // 已经是期望状态，不写回，避免 update → 调谐 → update 的无限循环
                    return Mono.empty();
                }
                thing.setStatus(status);
                thing.getMetadata().setLabels(labels);
                return service.update(thing);
            })
            .block();
        return Result.doNotRetry();
    }

    @Override
    public Controller setupWith(ControllerBuilder builder) {
        return builder
            .extension(new SmallThing())
            .build();
    }
}
