package com.matrixgrowth.plugin100things;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.controller.Reconciler;

/**
 * {@link SmallThingReconciler} 的行为测试。
 *
 * <p>这层守卫针对的是一类真实事故：调谐器在内存里设置了 labels，然后通过增量语义的
 * {@code service.update()} 写回——该方法会重新从库里取对象、只合并 spec，label 被静默丢弃，
 * 界面看起来一切正常，只是按状态/分类的筛选永远为空。
 *
 * <p>因此这里同时守两件事：
 * <ol>
 *   <li>必须走 {@link SmallThingService#save}（全量写回），不能走 {@code update}；</li>
 *   <li>写回的对象上确实带着由 spec 派生的 labels 与 status。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class SmallThingReconcilerTest {

    @Mock
    SmallThingService service;

    SmallThingReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new SmallThingReconciler(service);
    }

    private SmallThing thingWithoutStatusAndLabels() {
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
        spec.setMood(SmallThing.SmallThingSpec.Mood.MOVED);
        thing.setSpec(spec);
        return thing;
    }

    @Test
    void reconcileShouldPersistDerivedLabelsAndStatus() {
        SmallThing thing = thingWithoutStatusAndLabels();
        when(service.get("smallthing-001")).thenReturn(Mono.just(thing));
        when(service.buildLabels(any(SmallThing.class))).thenCallRealMethod();
        when(service.save(any(SmallThing.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        Reconciler.Result result = reconciler.reconcile(new Reconciler.Request("smallthing-001"));

        assertThat(result.reEnqueue()).isFalse();

        ArgumentCaptor<SmallThing> captor = ArgumentCaptor.forClass(SmallThing.class);
        verify(service).save(captor.capture());
        SmallThing written = captor.getValue();

        assertThat(written.getMetadata().getLabels())
            .containsEntry("hundredthings.halo.run/status", "treasured")
            .containsEntry("hundredthings.halo.run/category", "青春时光")
            .containsEntry("hundredthings.halo.run/mood", "moved");
        assertThat(written.getStatus()).isNotNull();
        assertThat(written.getStatus().getPhase()).isEqualTo("TREASURED");
        assertThat(written.getStatus().getStage()).isEqualTo("TRACKED");
        assertThat(written.getStatus().getObservedTime()).isNotNull();
    }

    @Test
    void reconcileMustNotUseIncrementalUpdate() {
        // 这条是本次事故的正面守卫：一旦有人把 save 改回 update，label 就又会被丢掉，
        // 而 save 的 mock 不会报错——只有显式断言「没有调用 update」才抓得住。
        SmallThing thing = thingWithoutStatusAndLabels();
        when(service.get("smallthing-001")).thenReturn(Mono.just(thing));
        when(service.buildLabels(any(SmallThing.class))).thenCallRealMethod();
        when(service.save(any(SmallThing.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        reconciler.reconcile(new Reconciler.Request("smallthing-001"));

        verify(service, never()).update(any(SmallThing.class));
    }

    @Test
    void reconcileShouldNotWriteWhenAlreadyInDesiredState() {
        SmallThing thing = thingWithoutStatusAndLabels();
        SmallThing.SmallThingStatus status = new SmallThing.SmallThingStatus();
        status.setPhase("TREASURED");
        status.setStage("TRACKED");
        status.setObservedTime(Instant.parse("2025-03-08T00:00:00Z"));
        thing.setStatus(status);
        thing.getMetadata().setLabels(java.util.Map.of(
            "hundredthings.halo.run/status", "treasured",
            "hundredthings.halo.run/category", "青春时光",
            "hundredthings.halo.run/mood", "moved"));

        when(service.get("smallthing-001")).thenReturn(Mono.just(thing));
        when(service.buildLabels(any(SmallThing.class))).thenCallRealMethod();

        reconciler.reconcile(new Reconciler.Request("smallthing-001"));

        // 已是期望状态时绝不写回，否则 update → 调谐 → update 会自激成死循环
        verify(service, never()).save(any(SmallThing.class));
        verify(service, never()).update(any(SmallThing.class));
    }

    @Test
    void reconcileShouldDoNothingWhenThingIsGone() {
        when(service.get("smallthing-404")).thenReturn(Mono.empty());

        Reconciler.Result result = reconciler.reconcile(new Reconciler.Request("smallthing-404"));

        assertThat(result.reEnqueue()).isFalse();
        verify(service, never()).save(any(SmallThing.class));
    }

    @Test
    void reconcileShouldUpdateLabelsWhenStatusChangedInPlace() {
        // spec 状态从 TREASURED 改成 TODO，label 里的 status 必须跟着变
        SmallThing thing = thingWithoutStatusAndLabels();
        SmallThing.SmallThingStatus status = new SmallThing.SmallThingStatus();
        status.setPhase("TREASURED");
        status.setStage("TRACKED");
        thing.setStatus(status);
        thing.getMetadata().setLabels(java.util.Map.of(
            "hundredthings.halo.run/status", "treasured",
            "hundredthings.halo.run/category", "青春时光",
            "hundredthings.halo.run/mood", "moved"));
        thing.getSpec().setStatus(SmallThing.SmallThingSpec.Status.TODO);

        when(service.get("smallthing-001")).thenReturn(Mono.just(thing));
        when(service.buildLabels(any(SmallThing.class))).thenCallRealMethod();
        when(service.save(any(SmallThing.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        reconciler.reconcile(new Reconciler.Request("smallthing-001"));

        ArgumentCaptor<SmallThing> captor = ArgumentCaptor.forClass(SmallThing.class);
        verify(service).save(captor.capture());
        assertThat(captor.getValue().getMetadata().getLabels())
            .containsEntry("hundredthings.halo.run/status", "todo");
        assertThat(captor.getValue().getStatus().getPhase()).isEqualTo("TODO");
    }
}
