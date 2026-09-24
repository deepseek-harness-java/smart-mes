package cn.xiaofuge.m.plugin;

import cn.xiaofuge.deepseek.harness.domain.model.entity.AbstractTool;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolDefinition;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext;
import cn.xiaofuge.deepseek.harness.domain.spi.AbstractHarnessPlugin;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginContext;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginHookResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** AI 生产助手插件：把 smart-mes REST API 注册为 DSH Agent 工具 */
public class MesPlugin extends AbstractHarnessPlugin {

    public static final String PLUGIN_ID = "mes-copilot";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    public MesPlugin() { super(PLUGIN_ID); }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(
                new LineListTool(),
                new ScheduleTool(),
                new ReportTool(),
                new EquipmentTool(),
                new QualityTool(),
                new StatsTool());
    }

    @Override
    public void configure(PluginContext context) {
        super.configure(context);
        context.registerSystemPrompt("mes-capabilities", 20, """
                ## AI 生产助手（智能制造 MES · 2026-09-24）
                - 用户问"产线情况/生产进度" → line_list 或 stats
                - 用户要排产 → schedule（lineId/product/qty/due；先看产线状态与产能余量，停机/保养中不可排产，超两倍日产能建议拆单）
                - 车间报工 → report（orderId + qty；完成后状态自动流转）
                - 用户问设备状态/要不要修 → equipment（id；报温度/振动/运行时长，超阈值给维保建议：温度≥80℃ 过热、振动≥4.5 轴承磨损、8000h 大修）
                - 用户问质量/不良率 → quality（报综合不良率与帕累托 top 缺陷，>3% 给改善建议）
                - 回答要求：
                  1) 排产前必须复述产线/产品/数量/交期请用户确认，未确认不得下单
                  2) 排产与报工结果必报工单号（W 前缀）
                  3) 设备异常必须给出具体阈值对比与维保建议
                  4) 质量分析必须点出 top 缺陷类型与占比
                  5) 数据来自工具返回，禁止编造产线与工单数据
                """);
        context.registerHook("PRE_TOOL_USE", (toolName, payloadJson) -> {
            if (toolName != null && toolName.startsWith("plugin__" + PLUGIN_ID + "__")) {
                return PluginHookResult.context("audit: mes tool call.");
            }
            return null;
        });
    }

    private String get(String path, Map<String, Object> args) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl(args) + path)).GET().build());
    }

    private String post(String path, String jsonBody, Map<String, Object> args) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl(args) + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8)).build());
    }

    private String baseUrl(Map<String, Object> args) {
        Object override = args == null ? null : args.get("appBaseUrl");
        return override == null || String.valueOf(override).isBlank()
                ? System.getenv().getOrDefault("MES_APP_BASE_URL", "http://127.0.0.1:18098")
                : String.valueOf(override);
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) return "{\"error\":true,\"status\":" + resp.statusCode() + "}";
            return resp.body();
        } catch (Exception e) {
            return "{\"error\":true,\"message\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}";
        }
    }

    private String str(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private String json(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private class LineListTool extends AbstractTool {
        @Override public String name() { return "line_list"; }
        @Override public String description() {
            return "产线列表：产线号/名称/当前产品/状态（运行中/换型中/停机/保养中）/计划与完成数/班次/OEE 目标。"
                    + "何时必须调用：看产线、排产前确认产线状态。";
        }
        @Override public Map<String, Object> parameters() { return objectSchema().build(); }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            return ok(get("/api/lines", args));
        }
    }

    private class ScheduleTool extends AbstractTool {
        @Override public String name() { return "schedule"; }
        @Override public String description() {
            return "工单排产：lineId/product/qty/due 必填。停机与保养中产线不可排产；在产余量+新单超两倍日产能会被拦截并建议拆单。"
                    + "必须先复述排产要素经用户确认后才能调用。返回工单号。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("lineId", stringSchema("产线号，如 L01"))
                    .prop("product", stringSchema("产品名称"))
                    .prop("qty", stringSchema("排产数量（件）"))
                    .prop("due", stringSchema("交期，如 10-05"))
                    .required("lineId", "product", "qty", "due")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return false; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            String body = "{\"lineId\":\"" + json(str(args, "lineId"))
                    + "\",\"product\":\"" + json(str(args, "product"))
                    + "\",\"qty\":" + str(args, "qty")
                    + ",\"due\":\"" + json(str(args, "due")) + "\"}";
            return ok(post("/api/schedule", body, args));
        }
    }

    private class ReportTool extends AbstractTool {
        @Override public String name() { return "report"; }
        @Override public String description() {
            return "工单报工：orderId + qty（本次报工合格数量）。完成后自动累计并流转状态（待开工→生产中→已完成）。返回累计完成数。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("orderId", stringSchema("工单号，如 W8001"))
                    .prop("qty", stringSchema("本次报工数量（件）"))
                    .required("orderId", "qty")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return false; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            String body = "{\"orderId\":\"" + json(str(args, "orderId"))
                    + "\",\"qty\":" + str(args, "qty") + "}";
            return ok(post("/api/report", body, args));
        }
    }

    private class EquipmentTool extends AbstractTool {
        @Override public String name() { return "equipment"; }
        @Override public String description() {
            return "设备状态详情与维保建议：温度/振动/累计运行时长/上次保养，超阈值（温度≥80℃、振动≥4.5mm/s、运行≥8000h）自动列风险与建议。"
                    + "何时必须调用：问设备状态、报修、保养计划。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("id", stringSchema("设备编号，如 E101"))
                    .required("id")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            return ok(get("/api/equipment?id=" + java.net.URLEncoder.encode(str(args, "id"), StandardCharsets.UTF_8), args));
        }
    }

    private class QualityTool extends AbstractTool {
        @Override public String name() { return "quality"; }
        @Override public String description() {
            return "质量分析：综合不良率、帕累托排序的缺陷类型分布（top 缺陷与占比）、改进建议。"
                    + "何时必须调用：问质量、问不良率、问缺陷分析。";
        }
        @Override public Map<String, Object> parameters() { return objectSchema().build(); }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            return ok(get("/api/quality", args));
        }
    }

    private class StatsTool extends AbstractTool {
        @Override public String name() { return "stats"; }
        @Override public String description() {
            return "生产看板：在产工单数/计划与完成总数/整体进度/各产线状态与进度/设备预警数/综合不良率/处置建议。"
                    + "何时必须调用：问生产整体情况、问当天看板。";
        }
        @Override public Map<String, Object> parameters() { return objectSchema().build(); }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            return ok(get("/api/stats", args));
        }
    }
}
