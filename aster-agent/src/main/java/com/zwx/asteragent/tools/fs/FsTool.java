package com.zwx.asteragent.tools.fs;

import com.zwx.asteragent.ToolboxProperties;
import com.zwx.asteragent.core.FsSandbox;
import com.zwx.asteragent.core.OutputLimiter;
import com.zwx.asteragent.core.ToolError;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.file.Path;

/**
 * fs 组（Filesystem 位）：沙箱内文件读写列表。默认只读，fs_write 需 aster.tools.fs.allow-write=true。
 */
public class FsTool {

    private final FsSandbox sandbox;
    private final ToolboxProperties.Fs props;
    private final int maxChars;

    public FsTool(FsSandbox sandbox, ToolboxProperties.Fs props, int maxChars) {
        this.sandbox = sandbox;
        this.props = props;
        this.maxChars = maxChars;
    }

    @Tool(name = "fs_list", description = "列出沙箱内某目录的一层内容（[DIR]/[FILE] 标注与大小）。path 留空列出沙箱根。")
    public String list(@ToolParam(description = "沙箱内相对路径，如 notes/ 或留空表示根目录", required = false) String path) {
        try {
            Path dir = sandbox.resolve(path == null || path.isBlank() ? "." : path);
            sandbox.checkReal(dir);
            return OutputLimiter.limit("沙箱根：" + sandbox.root() + "\n" + String.join("\n", sandbox.list(dir, props.getMaxEntries())), maxChars);
        } catch (IllegalArgumentException e) {
            return ToolError.of(e.getMessage());
        } catch (IOException e) {
            return ToolError.of(e);
        }
    }

    @Tool(name = "fs_read", description = "读取沙箱内的文本文件全文（受大小上限保护）。")
    public String read(@ToolParam(description = "沙箱内相对路径") String path) {
        try {
            Path file = sandbox.checkReal(sandbox.resolve(path));
            return OutputLimiter.limit(sandbox.readString(file, props.getMaxReadBytes()), maxChars);
        } catch (IllegalArgumentException e) {
            return ToolError.of(e.getMessage());
        } catch (IOException e) {
            return ToolError.of(e);
        }
    }

    @Tool(name = "fs_write", description = "向沙箱内写入/覆盖一个文本文件（草稿、结果缓存、中间产物）。"
            + "仅当服务开启 aster.tools.fs.allow-write 时可用，路径始终被限制在沙箱根内。")
    public String write(@ToolParam(description = "沙箱内相对路径") String path,
                        @ToolParam(description = "写入的文本内容") String content) {
        if (!props.isAllowWrite()) {
            return ToolError.of("fs_write 已被只读模式禁用：在服务配置 aster.tools.fs.allow-write=true 后可用"
                    + "（启动 env：ASTER_FS_ALLOW_WRITE=true）");
        }
        try {
            Path file = sandbox.checkReal(sandbox.resolve(path));
            sandbox.writeString(file, content == null ? "" : content, props.getMaxWriteBytes());
            return "已写入 " + file + "（" + (content == null ? 0 : content.getBytes().length) + " 字节）";
        } catch (IllegalArgumentException e) {
            return ToolError.of(e.getMessage());
        } catch (IOException e) {
            return ToolError.of(e);
        }
    }
}
