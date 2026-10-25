package com.zwx.asteragent.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 文件沙箱：fs 组所有路径强制限制在配置根目录内。
 *
 * <p>防护点：路径穿越（../、绝对路径）、符号链接逃逸（canonical path 复核）、
 * 读取/写入大小上限、目录列表条数上限。fs_write 另有总开关（默认只读）。
 */
public class FsSandbox {

    private final Path root;

    public FsSandbox(String rootPath) {
        try {
            this.root = Path.of(rootPath).toAbsolutePath().normalize();
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建沙箱根目录：" + rootPath + "（" + e.getMessage() + "）", e);
        }
    }

    public Path root() {
        return root;
    }

    /** 把用户输入路径解析为沙箱内的绝对路径；越界即抛异常。 */
    public Path resolve(String userInput) {
        if (userInput == null || userInput.isBlank()) throw new IllegalArgumentException("路径不能为空");
        Path raw = Path.of(userInput.trim());
        if (raw.isAbsolute()) throw new IllegalArgumentException("仅允许沙箱内的相对路径，拒绝绝对路径：" + userInput);
        Path candidate = root.resolve(raw).normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("路径越出沙箱根目录，已拒绝：" + userInput);
        }
        return candidate;
    }

    /** 对已存在路径做 canonical 复核，防符号链接逃逸；不存在则退回 normalize 结果。 */
    public Path checkReal(Path candidate) throws IOException {
        Path probe = candidate;
        while (!Files.exists(probe) && probe.getParent() != null) probe = probe.getParent();
        if (Files.exists(probe)) {
            Path real = probe.toRealPath();
            Path realRoot = root.toRealPath();
            if (!real.startsWith(realRoot)) {
                throw new IllegalArgumentException("路径经符号链接越出沙箱，已拒绝");
            }
        }
        return candidate;
    }

    public String readString(Path file, int maxBytes) throws IOException {
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("不是常规文件：" + file);
        long size = Files.size(file);
        if (size > maxBytes) throw new IllegalArgumentException("文件过大：" + size + " 字节（上限 " + maxBytes + "）");
        return Files.readString(file);
    }

    public void writeString(Path file, String content, int maxBytes) throws IOException {
        byte[] bytes = content == null ? new byte[0] : content.getBytes();
        if (bytes.length > maxBytes) throw new IllegalArgumentException("写入内容过大：" + bytes.length + " 字节（上限 " + maxBytes + "）");
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.writeString(file, content == null ? "" : content);
    }

    /** 列出目录（一层），含类型标注，按目录优先排序。 */
    public List<String> list(Path dir, int maxEntries) throws IOException {
        if (!Files.isDirectory(dir)) throw new IllegalArgumentException("不是目录：" + dir);
        List<Path> children;
        try (Stream<Path> stream = Files.list(dir)) {
            children = stream.sorted(Comparator.comparing((Path p) -> !Files.isDirectory(p)).thenComparing(Path::getFileName)).toList();
        }
        List<String> lines = new ArrayList<>();
        for (Path child : children) {
            if (lines.size() >= maxEntries) {
                lines.add("…（超过 " + maxEntries + " 条已截断）");
                break;
            }
            String size = Files.isDirectory(child) ? "-" : String.valueOf(Files.size(child));
            lines.add((Files.isDirectory(child) ? "[DIR]  " : "[FILE] ") + root.relativize(child) + "  (" + size + " bytes)");
        }
        if (lines.isEmpty()) lines.add("（空目录）");
        return lines;
    }
}
