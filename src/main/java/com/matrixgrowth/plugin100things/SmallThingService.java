package com.matrixgrowth.plugin100things;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
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

    /** 编号上限：编号在页面上按三位补零展示，超过 999 会变成四位。 */
    static final int MAX_NUMBER = 999;

    /** 标题长度上限。 */
    static final int MAX_TITLE_LENGTH = 100;

    /** 分类长度上限（与 {@link SmallThing.SmallThingSpec} 的 Schema 一致）。 */
    static final int MAX_CATEGORY_LENGTH = 32;

    /** 一句话记录长度上限。 */
    static final int MAX_NOTE_LENGTH = 500;

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
     * <p>名称由后端按编号生成（{@code smallthing-007}），不接受前端传名：中文标题做不了
     * Halo 要求的 DNS-1123 名称，而让前端自己拼一个则可能出现两条记录抢同一个名字。
     *
     * <p>编号必须唯一：两张卡片都写着 № 007 时，用户没法判断自己点开的是哪一条。
     *
     * @param smallThing 待创建的小事
     * @return 创建后的小事
     */
    public Mono<SmallThing> create(SmallThing smallThing) {
        // 校验（会抛 400）必须发生在订阅之后：直接同步抛的话，报错会绕过响应式错误通道，
        // 到 HTTP 层就变成没有 message 的 500，界面无从提示。
        return Mono.defer(() -> {
            Assert.notNull(smallThing, "小事不能为空");
            Assert.notNull(smallThing.getSpec(), "小事内容不能为空");
            validateSpec(smallThing.getSpec(), true);
            SmallThing.SmallThingSpec spec = smallThing.getSpec();
            spec.setTitle(trim(spec.getTitle()));
            spec.setCategory(trim(spec.getCategory()));
            if (spec.getStatus() == null) {
                spec.setStatus(SmallThing.SmallThingSpec.Status.TODO);
            }
            if (smallThing.getMetadata() == null) {
                smallThing.setMetadata(new Metadata());
            }
            smallThing.getMetadata().setName(null);
            Integer number = spec.getNumber();
            String generatedName = nameOf(number);
            return client.list(SmallThing.class,
                    thing -> thing.getSpec() != null
                        && number.equals(thing.getSpec().getNumber()),
                    Comparator.comparing(thing -> thing.getSpec().getNumber()))
                .collectList()
                .flatMap(sameNumber -> {
                    if (!sameNumber.isEmpty()) {
                        // 列表在删除收尾期间仍会看到这条记录：此时它不是「被占用」，
                        // 而是「刚删、还在收尾」，两者对用户的下一步动作完全不同。
                        boolean terminating = sameNumber.stream().allMatch(thing ->
                            thing.getMetadata() != null
                                && thing.getMetadata().getDeletionTimestamp() != null);
                        return Mono.error(badRequest(terminating
                            ? "编号 " + number + " 正在删除中，请稍后再试或换一个编号"
                            : "编号 " + number + " 已被占用，请换一个编号"));
                    }
                    // 编号已空但资源名仍被占用：上一次删除还在收尾。
                    // 直接 create 会抛冲突异常，到界面就是一个看不懂的 500。
                    return client.fetch(SmallThing.class, generatedName)
                        .hasElement()
                        .flatMap(nameTaken -> Boolean.TRUE.equals(nameTaken)
                            ? Mono.<SmallThing>error(badRequest(
                                "编号 " + number + " 正在删除中，请稍后再试或换一个编号"))
                            : Mono.defer(() -> {
                                smallThing.getMetadata().setName(generatedName);
                                smallThing.setStatus(buildStatus(smallThing));
                                return client.create(smallThing);
                            }));
                });
        });
    }

    /**
     * 编号对应的资源名（与预置数据同名规则，便于在控制台的资源列表里一眼对上）。
     *
     * @param number 编号
     * @return metadata.name
     */
    static String nameOf(Integer number) {
        return String.format("smallthing-%03d", number);
    }

    /**
     * 全量保存：把传入对象原样写回，不做任何字段合并。
     *
     * <p><b>必须与 {@link #update(SmallThing)} 区分开</b>：{@code update} 是给「控制台只提交
     * 改动过的几个字段」用的增量语义，它会重新从库里取一次对象、只覆盖 spec；
     * 调用方在内存里设置的其他字段（典型是 metadata.labels）会被静默丢弃。
     *
     * <p>调谐器拿到的本来就是一个完整对象（自己 fetch 的），它的写入意图是「整份写回」，
     * 必须走这里。真实环境曾因此丢掉 Reconciler 写入的 label，而单元测试因为 mock 了
     * service 完全没察觉。
     *
     * @param smallThing 完整的小事对象（metadata.name 与 metadata.version 必填）
     * @return 保存后的小事
     */
    public Mono<SmallThing> save(SmallThing smallThing) {
        Assert.notNull(smallThing, "小事不能为空");
        Assert.notNull(smallThing.getMetadata(), "小事元数据不能为空");
        Assert.hasText(smallThing.getMetadata().getName(), "小事名称不能为空");
        return client.update(smallThing);
    }

    /**
     * 更新一条小事（增量语义，会重新从库里取对象并只覆盖 spec）。
     *
     * <p>仅覆盖请求体中携带的字段，未携带的字段保持原值——这与资料库版本的「增量更新」语义一致。
     *
     * <p>清单文案（编号 / 标题 / 分类）在控制台可以改：后台是唯一的编辑入口，前台展示页
     * 对访客只读。改编号时会重新校验唯一性，避免两条记录顶着同一个 №。
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
            .switchIfEmpty(Mono.error(notFound(name)))
            .flatMap(existed -> assertNumberAvailable(existed, smallThing).thenReturn(existed))
            .map(existed -> {
                SmallThing merged = merge(existed, smallThing);
                merged.setStatus(buildStatus(merged));
                return merged;
            })
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
            .switchIfEmpty(Mono.error(notFound(name)))
            // 已经带删除时间戳的记录表示「正在删」，此时再删一次会「成功」——
            // 界面于是显示删除成功，而实际上什么都没发生（真实环境实测：连删两次都返回 200）
            .flatMap(thing -> thing.getMetadata() != null
                && thing.getMetadata().getDeletionTimestamp() != null
                ? Mono.error(notFound(name))
                : client.delete(thing));
    }

    private ResponseStatusException notFound(String name) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "小事不存在：" + name);
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
            validateSpec(newSpec, false);
            SmallThing.SmallThingSpec spec = new SmallThing.SmallThingSpec();
            spec.setNumber(newSpec.getNumber() != null ? newSpec.getNumber() : oldSpec.getNumber());
            spec.setTitle(trim(newSpec.getTitle() != null ? newSpec.getTitle() : oldSpec.getTitle()));
            spec.setCategory(trim(newSpec.getCategory() != null
                ? newSpec.getCategory() : oldSpec.getCategory()));
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

    /** 编号上限、标题长度上限等常量见类顶部。 */

    /**
     * 改编号时校验唯一性。
     *
     * <p>编号没变或本次没传编号时直接放行——已经在用自己那个编号不算冲突。
     *
     * @param existed 库里的原记录
     * @param incoming 请求体
     * @return 校验结果（冲突时以 400 结束）
     */
    private Mono<Void> assertNumberAvailable(SmallThing existed, SmallThing incoming) {
        Integer number = incoming.getSpec() == null ? null : incoming.getSpec().getNumber();
        Integer current = existed.getSpec() == null ? null : existed.getSpec().getNumber();
        if (number == null || number.equals(current)) {
            return Mono.empty();
        }
        return client.list(SmallThing.class,
                thing -> thing.getSpec() != null && number.equals(thing.getSpec().getNumber()),
                Comparator.comparing(thing -> thing.getSpec().getNumber()))
            .hasElements()
            .flatMap(occupied -> Boolean.TRUE.equals(occupied)
                ? Mono.error(badRequest("编号 " + number + " 已被占用，请换一个编号"))
                : Mono.empty());
    }

    /**
     * 校验标语字段。
     *
     * <p>只在「本次真的传了这个字段」时校验（{@code creating} 为 true 时全部必填）：
     * 增量更新里 null 表示不修改，拿 null 去报「不能为空」会让只改心情的请求也被拒。
     *
     * <p>分类尤其不能留空：{@code groupByCategory} 会跳过空分类，卡片会在前台
     * **整条消失**（不报错、也不提示），比报 400 糟得多。
     *
     * @param spec 待校验的 spec
     * @param creating 是否为新建
     */
    private void validateSpec(SmallThing.SmallThingSpec spec, boolean creating) {
        if (spec == null) {
            throw badRequest("小事内容不能为空");
        }
        if (creating || spec.getNumber() != null) {
            Integer number = spec.getNumber();
            if (number == null || number < 1 || number > MAX_NUMBER) {
                throw badRequest("编号必须是 1-" + MAX_NUMBER + " 之间的整数");
            }
        }
        if (creating || spec.getTitle() != null) {
            String title = spec.getTitle() == null ? "" : spec.getTitle().trim();
            if (title.isEmpty()) {
                throw badRequest("小事标题不能为空");
            }
            if (title.length() > MAX_TITLE_LENGTH) {
                throw badRequest("小事标题最多 " + MAX_TITLE_LENGTH + " 个字");
            }
        }
        if (creating || spec.getCategory() != null) {
            String category = spec.getCategory() == null ? "" : spec.getCategory().trim();
            if (category.isEmpty()) {
                throw badRequest("分类不能为空，空分类的条目不会出现在前台");
            }
            if (category.length() > MAX_CATEGORY_LENGTH) {
                throw badRequest("分类最多 " + MAX_CATEGORY_LENGTH + " 个字");
            }
        }
        if (spec.getNote() != null && spec.getNote().length() > MAX_NOTE_LENGTH) {
            throw badRequest("一句话记录最多 " + MAX_NOTE_LENGTH + " 个字");
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * 去首尾空白。
     *
     * <p>分类必须去空白：分组是按字符串精确匹配的，写成「" 青春时光"」会被当成一个
     * 自定义分类排到列表末尾——卡片没丢，但位置不对，而且没有任何报错提示。
     *
     * @param value 原值
     * @return 去空白后的值（null 保持 null）
     */
    private String trim(String value) {
        return value == null ? null : value.trim();
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
     * 十个分类的规范顺序（清单与前台展示都以它为准）。
     *
     * <p>这是**唯一真源**：模板不再写死一份分类数组，否则两边迟早漂移
     * （改了一个分类，另一处还是旧值，且不会报错）。
     *
     * @return 分类名列表
     */
    public static List<String> defaultCategories() {
        return List.of(
            "青春时光", "生活日常", "朋友之间", "一个人的浪漫", "小小远方",
            "关于热爱", "勇敢一点", "感受世界", "留下痕迹", "好好告别");
    }

    /**
     * 按分类分组，供前台模板直接遍历。
     *
     * <p>分组放在 Java 侧而不是模板里，是因为模板侧的 SpEL 集合选择
     * {@code smallThings.?[spec.category == cat]} 取不到外层 {@code th:each} 的循环变量，
     * 会抛 {@code EL1008E}；而同一元素上 {@code th:if} 又先于 {@code th:with} 求值，
     * 失败时表现为「整页只有标题、一条记录都没有」的静默空列表。
     *
     * <p>顺序遵循 {@link #defaultCategories()}；清单里出现规范之外的分类时追加在末尾，
     * 不会被丢弃。空分组不返回。
     *
     * @param things 小事列表
     * @return 按分类分组后的结果
     */
    public List<CategoryGroup> groupByCategory(List<SmallThing> things) {
        return groupByCategory(things, true);
    }

    /**
     * 按配置决定是否分组。
     *
     * <p>平铺模式下返回**唯一一个** {@code category} 为 null 的分组，而不是让模板去
     * 走两条不同的渲染分支：卡片标记只写一份，避免「分组视图改了、平铺视图还是旧的」
     * 这类两处漂移。
     *
     * @param things 小事列表
     * @param byCategory true 按分类分组；false 平铺
     * @return 分组结果
     */
    public List<CategoryGroup> groupByCategory(List<SmallThing> things, boolean byCategory) {
        if (!byCategory) {
            List<SmallThing> sorted = things == null ? List.of() : things.stream()
                .sorted(Comparator.comparing(thing -> thing.getSpec() == null
                    || thing.getSpec().getNumber() == null
                    ? Integer.MAX_VALUE : thing.getSpec().getNumber()))
                .toList();
            return sorted.isEmpty() ? List.of() : List.of(new CategoryGroup(null, sorted));
        }
        Map<String, List<SmallThing>> grouped = new LinkedHashMap<>();
        for (String category : defaultCategories()) {
            grouped.put(category, new ArrayList<>());
        }
        if (things != null) {
            for (SmallThing thing : things) {
                String category = thing.getSpec() == null ? null : thing.getSpec().getCategory();
                if (category == null || category.isBlank()) {
                    continue;
                }
                grouped.computeIfAbsent(category, key -> new ArrayList<>()).add(thing);
            }
        }
        return grouped.entrySet().stream()
            .filter(entry -> !entry.getValue().isEmpty())
            .map(entry -> new CategoryGroup(entry.getKey(), List.copyOf(entry.getValue())))
            .toList();
    }

    /**
     * 一个分类下的小事集合（前台模板的数据契约）。
     *
     * @param category 分类名
     * @param items 该分类下的小事
     */
    public record CategoryGroup(String category, List<SmallThing> items) {
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
