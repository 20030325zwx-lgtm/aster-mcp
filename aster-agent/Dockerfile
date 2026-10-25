# aster-agent MCP 工具箱服务：独立可部署镜像
# 构建（在 aster-mcp 根目录）：docker build -f aster-agent/Dockerfile -t aster-agent-mcp:0.0.1 aster-agent/
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app
COPY target/aster-agent-mcp-server-0.0.1-SNAPSHOT.jar app.jar

# 沙箱与审计日志落点（挂载卷持久化）
RUN mkdir -p /app/sandbox /app/logs
ENV ASTER_FS_ROOT=/app/sandbox

EXPOSE 8124
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
HEALTHCHECK --interval=30s --timeout=3s CMD curl -fsS http://127.0.0.1:8124/actuator/health || exit 1
