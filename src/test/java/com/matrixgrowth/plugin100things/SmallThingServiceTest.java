package com.matrixgrowth.plugin100things;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * {@link SmallThingService} 的行为测试。
 *
 * <p>重点覆盖「增量更新」语义：请求体中为 null 的字段不应覆盖原有值——这与资料库版本的
 * 增量更新保持一致。
 */
@ExtendWith(MockitoExtension.class)
class SmallThingServiceTest {

    @Mock
    ReactiveExtensionClient client;

    SmallThingService service;

    @BeforeEach
    void setUp() {
        service = new SmallThingService(client);
    }

    private SmallThing existingThing() {
        SmallThing thing = new SmallThing();
        Metadata metadata = new Metadata();
        metadata.setName("thing-001");
        metadata.setVersion(1L);
        thing.setMetadata(metadata);
        SmallThing.SmallThingSpec spec = new SmallThing.SmallThingSpec();
        spec.setNumber(1);
        spec.setTitle("和十七岁的自己聊一次天");
        spec.setCategory("青春时光");
        spec.setStatus(SmallThing.SmallThingSpec.Status.TREASURED);
        spec.setMood(SmallThing.SmallThingSpec.Mood.MOVED);
        spec.setNote("翻开旧日记，字迹还是那个会为一场雨难过的自己。");
        spec.setDoneAt(Instant.parse("2025-03-08T00:00:00Z"));
        thing.setSpec(spec);
        return thing;
    }

    @Test
    void updateShouldKeepUnspecifiedFields() {
        SmallThing existed = existingThing();
        when(client.get(eq(SmallThing.class), eq("thing-001"))).thenReturn(Mono.just(existed));
        when(client.update(any(SmallThing.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        // 请求体只带了心情，其余字段为 null
        SmallThing patch = new SmallThing();
        Metadata patchMetadata = new Metadata();
        patchMetadata.setName("thing-001");
        patch.setMetadata(patchMetadata);
        SmallThing.SmallThingSpec patchSpec = new SmallThing.SmallThingSpec();
        patchSpec.setMood(SmallThing.SmallThingSpec.Mood.SURPRISED);
        patch.setSpec(patchSpec);

        StepVerifier.create(service.update(patch))
            .assertNext(updated -> {
                assertThat(updated.getSpec().getMood())
                    .isEqualTo(SmallThing.SmallThingSpec.Mood.SURPRISED);
                // 未指定的字段必须保持原值
                assertThat(updated.getSpec().getTitle()).isEqualTo("和十七岁的自己聊一次天");
                assertThat(updated.getSpec().getCategory()).isEqualTo("青春时光");
                assertThat(updated.getSpec().getStatus())
                    .isEqualTo(SmallThing.SmallThingSpec.Status.TREASURED);
                assertThat(updated.getSpec().getNumber()).isEqualTo(1);
                assertThat(updated.getSpec().getNote()).contains("旧日记");
                assertThat(updated.getSpec().getDoneAt()).isEqualTo(Instant.parse("2025-03-08T00:00:00Z"));
            })
            .verifyComplete();
    }

    @Test
    void deleteShouldFetchBeforeDelete() {
        SmallThing existed = existingThing();
        when(client.get(eq(SmallThing.class), eq("thing-001"))).thenReturn(Mono.just(existed));
        when(client.delete(any(SmallThing.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.delete("thing-001"))
            .assertNext(deleted -> assertThat(deleted.getMetadata().getVersion()).isEqualTo(1L))
            .verifyComplete();

        // 必须传带 version 的对象，否则 Halo 会退化成 insert
        ArgumentCaptor<SmallThing> captor = ArgumentCaptor.forClass(SmallThing.class);
        verify(client).delete(captor.capture());
        assertThat(captor.getValue().getMetadata().getVersion()).isNotNull();
    }

    @Test
    void deleteShouldErrorWhenNotFound() {
        when(client.get(eq(SmallThing.class), eq("missing"))).thenReturn(Mono.empty());

        StepVerifier.create(service.delete("missing"))
            .expectError(IllegalArgumentException.class)
            .verify();
    }

    @Test
    void updateShouldErrorWhenNotFound() {
        when(client.get(eq(SmallThing.class), eq("missing"))).thenReturn(Mono.empty());

        SmallThing patch = new SmallThing();
        Metadata metadata = new Metadata();
        metadata.setName("missing");
        patch.setMetadata(metadata);
        patch.setSpec(new SmallThing.SmallThingSpec());

        StepVerifier.create(service.update(patch))
            .expectError(IllegalArgumentException.class)
            .verify();
    }

    @Test
    void saveShouldPersistLabelsAsIsWithoutRefetching() {
        // 回归守卫：Reconciler 通过 save 写回 labels + status。
        // update（增量语义）会重新 get 一次、只合并 spec，把调用方设置的 labels 丢掉，
        // 真实环境中因此每条记录的 label 都写不进去（单元测试当时 mock 了 service 没察觉）。
        SmallThing thing = existingThing();
        thing.getMetadata().setLabels(
            java.util.Map.of("hundredthings.halo.run/status", "treasured"));

        ArgumentCaptor<SmallThing> captor = ArgumentCaptor.forClass(SmallThing.class);
        when(client.update(any(SmallThing.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.save(thing))
            .assertNext(saved -> assertThat(saved.getMetadata().getLabels())
                .containsEntry("hundredthings.halo.run/status", "treasured"))
            .verifyComplete();

        verify(client).update(captor.capture());
        assertThat(captor.getValue().getMetadata().getLabels())
            .containsEntry("hundredthings.halo.run/status", "treasured");
        // 全量保存不得再回库取对象：既多一次往返，也会让调用方手里的 labels 失效
        verify(client, never()).get(any(), any());
    }

    @Test
    void updateShouldKeepExistingLabelsFromDatabase() {
        // 增量更新虽然不能覆盖调用方设置的新 label，但绝不能把库里已有的 label 抹掉
        SmallThing existed = existingThing();
        existed.getMetadata().setLabels(
            java.util.Map.of("hundredthings.halo.run/status", "treasured"));
        when(client.get(eq(SmallThing.class), eq("thing-001"))).thenReturn(Mono.just(existed));

        ArgumentCaptor<SmallThing> captor = ArgumentCaptor.forClass(SmallThing.class);
        when(client.update(any(SmallThing.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        SmallThing patch = new SmallThing();
        Metadata patchMetadata = new Metadata();
        patchMetadata.setName("thing-001");
        patch.setMetadata(patchMetadata);
        SmallThing.SmallThingSpec patchSpec = new SmallThing.SmallThingSpec();
        patchSpec.setStatus(SmallThing.SmallThingSpec.Status.TODO);
        patch.setSpec(patchSpec);

        StepVerifier.create(service.update(patch)).expectNextCount(1).verifyComplete();

        verify(client).update(captor.capture());
        assertThat(captor.getValue().getMetadata().getLabels())
            .containsEntry("hundredthings.halo.run/status", "treasured");
    }

    @Test
    void groupByCategoryShouldFollowCanonicalOrderAndDropEmptyGroups() {
        SmallThing first = existingThing();
        SmallThing second = existingThing();
        second.getMetadata().setName("thing-002");
        second.getSpec().setCategory("留下痕迹");
        SmallThing third = existingThing();
        third.getMetadata().setName("thing-003");

        var groups = service.groupByCategory(List.of(first, second, third));

        // 空分类不出现；顺序按 defaultCategories（青春时光 在 留下痕迹 之前）
        assertThat(groups).extracting(SmallThingService.CategoryGroup::category)
            .containsExactly("青春时光", "留下痕迹");
        assertThat(groups.get(0).items()).hasSize(2);
        assertThat(groups.get(1).items()).hasSize(1);
    }

    @Test
    void groupByCategoryShouldKeepCategoryOutsideTheCanonicalList() {
        SmallThing custom = existingThing();
        custom.getSpec().setCategory("自己加的分类");

        var groups = service.groupByCategory(List.of(custom));

        // 用户自定义的分类不能被丢掉，只是排在规范分类之后
        assertThat(groups).extracting(SmallThingService.CategoryGroup::category)
            .containsExactly("自己加的分类");
    }

    @Test
    void groupByCategoryShouldTolerateEmptyAndUncategorizedInput() {
        SmallThing noCategory = existingThing();
        noCategory.getSpec().setCategory(null);

        assertThat(service.groupByCategory(List.of())).isEmpty();
        assertThat(service.groupByCategory(List.of(noCategory))).isEmpty();
    }

    @Test
    void buildLabelsShouldContainStatusCategoryAndMood() {
        SmallThing thing = existingThing();

        var labels = service.buildLabels(thing);

        assertThat(labels).containsEntry("hundredthings.halo.run/status", "treasured");
        assertThat(labels).containsEntry("hundredthings.halo.run/category", "青春时光");
        assertThat(labels).containsEntry("hundredthings.halo.run/mood", "moved");
    }

    @Test
    void countTreasuredShouldOnlyCountTreasured() {
        SmallThing treasured = existingThing();
        SmallThing todo = existingThing();
        todo.getMetadata().setName("thing-002");
        todo.getSpec().setStatus(SmallThing.SmallThingSpec.Status.TODO);

        // 客户端负责按 predicate 过滤；这里让 mock 真实地应用 predicate，
        // 从而验证 countTreasured 传入的过滤条件确实只挑出 TREASURED。
        when(client.list(eq(SmallThing.class), any(), any()))
            .thenAnswer(invocation -> {
                java.util.function.Predicate<SmallThing> predicate = invocation.getArgument(1);
                return reactor.core.publisher.Flux
                    .fromIterable(List.of(treasured, todo))
                    .filter(predicate);
            });

        StepVerifier.create(service.countTreasured())
            .expectNext(1L)
            .verifyComplete();
    }

    @Test
    void allStatusesAndMoodsShouldCoverFourAndTen() {
        assertThat(service.allStatuses()).hasSize(4);
        assertThat(service.allMoods()).hasSize(10);
        assertThat(service.allMoods()).contains(SmallThing.SmallThingSpec.Mood.OTHER);
    }

    @Test
    void createShouldSetStatusBeforePersisting() {
        SmallThing thing = existingThing();
        thing.getMetadata().setName(null);
        when(client.create(any(SmallThing.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.create(thing))
            .assertNext(created -> {
                assertThat(created.getStatus()).isNotNull();
                assertThat(created.getStatus().getPhase()).isEqualTo("TREASURED");
                assertThat(created.getStatus().getStage()).isEqualTo("TRACKED");
            })
            .verifyComplete();
    }

    @Test
    void listShouldReturnAllThings() {
        when(client.list(eq(SmallThing.class), any(), any()))
            .thenReturn(reactor.core.publisher.Flux.just(existingThing()));

        StepVerifier.create(service.list())
            .expectNextCount(1)
            .verifyComplete();

        verify(client).list(eq(SmallThing.class), any(), any());
    }

    @Test
    void publicCategoriesShouldContainTenGroups() {
        assertThat(SmallThingPublicEndpoint.defaultCategories()).hasSize(10);
        assertThat(SmallThingPublicEndpoint.defaultCategories()).contains("好好告别");
    }

    @Test
    void statsResponseShouldCarryCount() {
        var response = new SmallThingConsoleEndpoint.StatsResponse(24L);
        assertThat(response.treasuredCount()).isEqualTo(24L);
    }

    @Test
    void optionsResponseShouldCarryStatusesAndMoods() {
        var response = new SmallThingConsoleEndpoint.OptionsResponse(
            List.of("TODO"), List.of("CALM"));
        assertThat(response.statuses()).containsExactly("TODO");
        assertThat(response.moods()).containsExactly("CALM");
    }

    @Test
    void getShouldReturnEmptyWhenMissing() {
        when(client.fetch(eq(SmallThing.class), eq("missing"))).thenReturn(Mono.empty());

        StepVerifier.create(service.get("missing"))
            .verifyComplete();

        verify(client, never()).delete(any());
    }

    @Test
    void updateShouldRejectContentChanges() {
        when(client.get(eq(SmallThing.class), eq("thing-001")))
            .thenReturn(Mono.just(existingThing()));

        SmallThing patch = new SmallThing();
        Metadata metadata = new Metadata();
        metadata.setName("thing-001");
        patch.setMetadata(metadata);
        SmallThing.SmallThingSpec patchSpec = new SmallThing.SmallThingSpec();
        patchSpec.setTitle("擅自改掉的标题");
        patch.setSpec(patchSpec);

        StepVerifier.create(service.update(patch))
            .expectErrorSatisfies(error -> {
                assertThat(error).isInstanceOf(ResponseStatusException.class);
                assertThat(((ResponseStatusException) error).getStatusCode().value())
                    .isEqualTo(400);
            })
            .verify();

        // 关键：被拒绝时不能有任何写库动作，否则就是「报了错但还是改了」
        verify(client, never()).update(any());
    }

    @Test
    void updateShouldRejectCategoryChange() {
        when(client.get(eq(SmallThing.class), eq("thing-001")))
            .thenReturn(Mono.just(existingThing()));

        SmallThing patch = new SmallThing();
        Metadata metadata = new Metadata();
        metadata.setName("thing-001");
        patch.setMetadata(metadata);
        SmallThing.SmallThingSpec patchSpec = new SmallThing.SmallThingSpec();
        patchSpec.setCategory("换个分类");
        patch.setSpec(patchSpec);

        StepVerifier.create(service.update(patch))
            .expectError(ResponseStatusException.class)
            .verify();

        verify(client, never()).update(any());
    }

    @Test
    void updateShouldAllowUnchangedContentBeingResent() {
        // 控制台回传的是整份对象，内容没变时必须放行，否则连打卡都存不了
        when(client.get(eq(SmallThing.class), eq("thing-001")))
            .thenReturn(Mono.just(existingThing()));
        when(client.update(any(SmallThing.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        SmallThing patch = new SmallThing();
        Metadata metadata = new Metadata();
        metadata.setName("thing-001");
        patch.setMetadata(metadata);
        SmallThing.SmallThingSpec patchSpec = new SmallThing.SmallThingSpec();
        patchSpec.setNumber(1);
        patchSpec.setTitle("和十七岁的自己聊一次天");
        patchSpec.setCategory("青春时光");
        patchSpec.setStatus(SmallThing.SmallThingSpec.Status.WAITING);
        patch.setSpec(patchSpec);

        StepVerifier.create(service.update(patch)).expectNextCount(1).verifyComplete();
    }

    @Test
    void groupByCategoryShouldCollapseIntoOneGroupWhenDisabled() {
        SmallThing first = existingThing();
        SmallThing second = existingThing();
        second.getMetadata().setName("thing-002");
        second.getSpec().setNumber(2);
        second.getSpec().setCategory("留下痕迹");

        var groups = service.groupByCategory(List.of(second, first), false);

        // 平铺模式：只有一个分组，且没有分类名（模板据此不画分组标题）
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).category()).isNull();
        assertThat(groups.get(0).items()).hasSize(2);
        // 仍然按编号升序，不能因为「平铺」就丢了排序
        assertThat(groups.get(0).items()).extracting(item -> item.getSpec().getNumber())
            .containsExactly(1, 2);
    }
}
