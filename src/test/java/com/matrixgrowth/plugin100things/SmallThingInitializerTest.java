package com.matrixgrowth.plugin100things;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * {@link SmallThingInitializer} 的行为测试。
 *
 * <p>重点：预置数据必须幂等——只补缺失的，不覆盖用户改动，也不重复创建。
 */
@ExtendWith(MockitoExtension.class)
class SmallThingInitializerTest {

    @Mock
    ReactiveExtensionClient client;

    SmallThingInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new SmallThingInitializer(client);
    }

    @Test
    void seedResourceShouldContainOneHundredItems() {
        List<SmallThingSeed> seeds = SmallThingSeed.load();
        assertThat(seeds).hasSize(100);
        assertThat(seeds).extracting(SmallThingSeed::getNumber)
            .doesNotHaveDuplicates()
            .contains(1, 100);
    }

    @Test
    void seedResourceShouldCoverTenCategoriesWithTenEach() {
        List<SmallThingSeed> seeds = SmallThingSeed.load();
        assertThat(seeds.stream().map(SmallThingSeed::getCategory).distinct().count()).isEqualTo(10);
        assertThat(seeds.stream()
            .collect(java.util.stream.Collectors.groupingBy(SmallThingSeed::getCategory,
                java.util.stream.Collectors.counting()))
            .values())
            .allMatch(count -> count == 10);
    }

    @Test
    void seedShouldWriteAllItemsWhenLibraryIsEmpty() {
        when(client.list(any(), any(), any())).thenReturn(Flux.empty());
        when(client.create(any(SmallThing.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        initializer.seed();

        verify(client, org.mockito.Mockito.times(100)).create(any(SmallThing.class));
    }

    @Test
    void seedShouldNotTouchAnythingWhenRecordsExist() {
        // 关键行为变化：以前是「补齐缺失编号」，那样用户删掉的条目会在插件重启后自己回来。
        // 只要还有任意一条记录存在，就整体跳过预置。
        SmallThing existed = new SmallThing();
        SmallThing.SmallThingSpec spec = new SmallThing.SmallThingSpec();
        spec.setNumber(1);
        existed.setSpec(spec);

        when(client.list(any(), any(), any())).thenReturn(Flux.just(existed));

        initializer.seed();

        verify(client, never()).create(any(SmallThing.class));
    }

    @Test
    void toExtensionShouldMapAllFields() {
        SmallThingSeed seed = new SmallThingSeed();
        seed.setNumber(7);
        seed.setTitle("看一次真正的大海");
        seed.setCategory("小小远方");
        seed.setStatus("TREASURED");
        seed.setMood("FREE");
        seed.setDoneAt("2026-01-02T00:00:00Z");
        seed.setNote("浪声盖过了所有话。");

        SmallThing thing = SmallThingInitializer.toExtension(seed);

        assertThat(thing.getMetadata().getName()).isEqualTo("smallthing-007");
        assertThat(thing.getSpec().getNumber()).isEqualTo(7);
        assertThat(thing.getSpec().getTitle()).isEqualTo("看一次真正的大海");
        assertThat(thing.getSpec().getCategory()).isEqualTo("小小远方");
        assertThat(thing.getSpec().getStatus())
            .isEqualTo(SmallThing.SmallThingSpec.Status.TREASURED);
        assertThat(thing.getSpec().getMood()).isEqualTo(SmallThing.SmallThingSpec.Mood.FREE);
        assertThat(thing.getSpec().getDoneAt()).isNotNull();
        assertThat(thing.getSpec().getNote()).isEqualTo("浪声盖过了所有话。");
        assertThat(thing.getStatus().getPhase()).isEqualTo("TREASURED");
    }

    @Test
    void toExtensionShouldFallBackToTodoForUnknownStatus() {
        SmallThingSeed seed = new SmallThingSeed();
        seed.setNumber(1);
        seed.setTitle("x");
        seed.setCategory("青春时光");
        seed.setStatus("NOT_A_STATUS");
        seed.setMood("NOT_A_MOOD");

        SmallThing thing = SmallThingInitializer.toExtension(seed);

        assertThat(thing.getSpec().getStatus()).isEqualTo(SmallThing.SmallThingSpec.Status.TODO);
        assertThat(thing.getSpec().getMood()).isNull();
    }

    @Test
    void seedShouldNotCreateWhenAllExist() {
        List<SmallThingSeed> seeds = SmallThingSeed.load();
        SmallThing existed = new SmallThing();
        SmallThing.SmallThingSpec spec = new SmallThing.SmallThingSpec();
        existed.setSpec(spec);
        // 构造一个包含全部编号的列表
        List<SmallThing> all = seeds.stream().map(s -> {
            SmallThing t = new SmallThing();
            SmallThing.SmallThingSpec sp = new SmallThing.SmallThingSpec();
            sp.setNumber(s.getNumber());
            t.setSpec(sp);
            return t;
        }).toList();

        when(client.list(any(), any(), any())).thenReturn(Flux.fromIterable(all));

        initializer.seed();

        verify(client, never()).create(any(SmallThing.class));
    }
}
