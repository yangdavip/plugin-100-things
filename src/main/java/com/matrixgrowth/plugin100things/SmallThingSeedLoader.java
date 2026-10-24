package com.matrixgrowth.plugin100things;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 从 classpath 读取预置的小事清单 JSON。
 *
 * @author matrixgrowth
 * @since 1.0.0
 */
final class SmallThingSeedLoader {

    private static final String SEED_RESOURCE = "seed/small-things.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SmallThingSeedLoader() {
    }

    /**
     * 读取预置清单。
     *
     * @return 100 条小事；读取失败时返回空列表（不阻断插件启动）
     */
    static List<SmallThingSeed> load() {
        try (InputStream in = SmallThingSeedLoader.class.getClassLoader()
            .getResourceAsStream(SEED_RESOURCE)) {
            if (in == null) {
                return List.of();
            }
            return MAPPER.readValue(in, new TypeReference<List<SmallThingSeed>>() {
            });
        } catch (IOException e) {
            return List.of();
        }
    }
}
