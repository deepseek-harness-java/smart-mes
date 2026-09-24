package cn.xiaofuge.m.app;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/** 智能制造 MES 数据中心：产线/工单/设备/质量/统计 */
@Component
public class MStore {

    static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /** 产线档案 */
    public static class Line {
        public String id; public String name; public String product; public String status; // 运行中/换型中/停机/保养
        public int planQty; public int doneQty; public String shift; public double oeeTarget;
    }

    /** 工单 */
    public static class WorkOrder {
        public String id; public String lineId; public String product; public int qty; public int done;
        public String status; // 待开工/生产中/已完成/已暂停
        public String due; public String createdAt;
    }

    /** 设备 */
    public static class Equipment {
        public String id; public String name; public String lineId; public String status; // 正常/预警/故障/保养中
        public double temp; public double vibration; public int runtimeHours; public String lastMaint;
    }

    /** 质量检验记录 */
    public static class QualityRec {
        public String id; public String orderNo; public String lineId; public int sampled; public int defect;
        public String defectType; public String at;
    }

    public final List<Line> lines = new ArrayList<>();
    public final List<WorkOrder> orders = new ArrayList<>();
    public final List<Equipment> equipments = new ArrayList<>();
    public final List<QualityRec> quality = new ArrayList<>();
    private int orderSeq = 8001;

    public MStore() { seed(); }

    private void seed() {
        lines.add(line("L01", "SMT 贴片线", "智能温控器", "运行中", 5000, 3820, "白班", 0.85));
        lines.add(line("L02", "总装一线", "智能温控器", "运行中", 4000, 2100, "白班", 0.80));
        lines.add(line("L03", "包装线", "智能温控器", "换型中", 6000, 0, "白班", 0.75));
        lines.add(line("L04", "注塑线", "外壳结构件", "停机", 3000, 1450, "夜班", 0.78));

        orders.add(wo("W8001", "L01", "智能温控器", 5000, 3820, "生产中", "09-28", "09-15 08:00"));
        orders.add(wo("W8002", "L02", "智能温控器", 4000, 2100, "生产中", "09-30", "09-17 09:30"));
        orders.add(wo("W8003", "L03", "智能温控器", 6000, 0, "待开工", "10-05", "09-20 14:00"));
        orders.add(wo("W8004", "L04", "外壳结构件", 3000, 1450, "已暂停", "09-26", "09-12 07:45"));
        orders.add(wo("W8005", "L01", "传感器模块", 2000, 2000, "已完成", "09-18", "09-08 08:00"));

        equipments.add(eq("E101", "贴片机", "L01", "预警", 82.5, 4.8, 4200, "08-30"));
        equipments.add(eq("E102", "回流焊", "L01", "正常", 61.2, 2.1, 3800, "09-10"));
        equipments.add(eq("E201", "总装机器人", "L02", "正常", 55.0, 1.8, 5100, "09-05"));
        equipments.add(eq("E401", "注塑机", "L04", "故障", 95.8, 9.6, 8600, "07-20"));
        equipments.add(eq("E301", "包装机", "L03", "保养中", 40.0, 1.2, 2900, "09-22"));

        quality.add(q("Q9001", "W8001", "L01", 200, 6, "焊点虚焊", "09-22 10:00"));
        quality.add(q("Q9002", "W8002", "L02", 150, 2, "外观划伤", "09-21 15:30"));
        quality.add(q("Q9003", "W8004", "L04", 100, 9, "缩水痕", "09-19 11:20"));
        quality.add(q("Q9004", "W8005", "L01", 200, 1, "焊点虚焊", "09-16 09:00"));
    }

    private Line line(String id, String name, String product, String status, int plan, int done, String shift, double oee) {
        Line x = new Line(); x.id = id; x.name = name; x.product = product; x.status = status;
        x.planQty = plan; x.doneQty = done; x.shift = shift; x.oeeTarget = oee; return x;
    }

    private WorkOrder wo(String id, String lineId, String product, int qty, int done, String status, String due, String at) {
        WorkOrder x = new WorkOrder(); x.id = id; x.lineId = lineId; x.product = product;
        x.qty = qty; x.done = done; x.status = status; x.due = due; x.createdAt = at; return x;
    }

    private Equipment eq(String id, String name, String lineId, String status, double temp, double vib, int hours, String maint) {
        Equipment x = new Equipment(); x.id = id; x.name = name; x.lineId = lineId; x.status = status;
        x.temp = temp; x.vibration = vib; x.runtimeHours = hours; x.lastMaint = maint; return x;
    }

    private QualityRec q(String id, String orderNo, String lineId, int sampled, int defect, String type, String at) {
        QualityRec x = new QualityRec(); x.id = id; x.orderNo = orderNo; x.lineId = lineId;
        x.sampled = sampled; x.defect = defect; x.defectType = type; x.at = at; return x;
    }

    /** 排产：校验产线状态与产能，生成工单 */
    public synchronized Map<String, Object> schedule(String lineId, String product, int qty, String due) {
        Line ln = lines.stream().filter(l -> l.id.equals(lineId)).findFirst().orElse(null);
        if (ln == null) return Map.of("ok", false, "msg", "产线 " + lineId + " 不存在");
        if ("停机".equals(ln.status)) return Map.of("ok", false, "msg", "产线 " + ln.name + "（" + lineId + "）当前停机，不可排产，请先恢复产线");
        if ("保养中".equals(ln.status)) return Map.of("ok", false, "msg", "产线 " + ln.name + "（" + lineId + "）保养中，预计完成后可排产");
        // 产能校验：该线未完成工单总量 + 新单 不得超过日产能×2（简化：planQty 为日产能）
        int openQty = orders.stream().filter(o -> o.lineId.equals(lineId)
                && ("待开工".equals(o.status) || "生产中".equals(o.status) || "已暂停".equals(o.status)))
                .mapToInt(o -> o.qty - o.done).sum();
        if (openQty + qty > ln.planQty * 2) {
            return Map.of("ok", false, "msg", "产线 " + ln.name + " 在产余量 " + openQty + " 件，叠加新单 " + qty
                    + " 件超过两倍日产能 " + (ln.planQty * 2) + " 件，建议拆单或延后交期");
        }
        WorkOrder x = new WorkOrder();
        x.id = "W" + orderSeq++;
        x.lineId = lineId; x.product = product; x.qty = qty; x.done = 0; x.status = "待开工"; x.due = due;
        x.createdAt = LocalDateTime.now().format(HM);
        orders.add(0, x);
        return Map.of("ok", true, "orderId", x.id, "line", ln.name, "qty", qty, "due", due,
                "msg", "已排产，排队在 " + (openQty > 0 ? "在产余量 " + openQty + " 件之后" : "产线最前"));
    }

    /** 报工：推进工单完成数 */
    public synchronized Map<String, Object> report(String orderId, int qty) {
        WorkOrder x = orders.stream().filter(o -> o.id.equals(orderId)).findFirst().orElse(null);
        if (x == null) return Map.of("ok", false, "msg", "工单 " + orderId + " 不存在");
        if ("已完成".equals(x.status)) return Map.of("ok", false, "msg", "工单 " + orderId + " 已完成，无需再报工");
        if (qty <= 0) return Map.of("ok", false, "msg", "报工数量必须大于 0");
        x.done = Math.min(x.qty, x.done + qty);
        String msg;
        if (x.done >= x.qty) { x.status = "已完成"; msg = "工单已全部完成"; }
        else {
            if ("待开工".equals(x.status)) x.status = "生产中";
            msg = "累计完成 " + x.done + "/" + x.qty;
        }
        return Map.of("ok", true, "orderId", x.id, "done", x.done, "qty", x.qty, "status", x.status, "msg", msg);
    }

    /** 设备状态评估与维保建议 */
    public Map<String, Object> equipment(String eqId) {
        Equipment e = equipments.stream().filter(x -> x.id.equals(eqId)).findFirst().orElse(null);
        if (e == null) return Map.of("ok", false, "msg", "设备 " + eqId + " 不存在");
        List<String> risks = new ArrayList<>();
        if ("故障".equals(e.status)) risks.add("设备当前故障停机，需立即派工维修");
        if (e.temp >= 80) risks.add("温度 " + e.temp + "℃ 超过 80℃ 阈值，存在过热风险");
        if (e.vibration >= 4.5) risks.add("振动 " + e.vibration + "mm/s 超过 4.5 阈值，轴承可能磨损");
        if (e.runtimeHours >= 8000) risks.add("累计运行 " + e.runtimeHours + " 小时，已到大修周期");
        else if (e.runtimeHours >= 6000) risks.add("累计运行 " + e.runtimeHours + " 小时，接近大修周期（8000h）");
        String advice = risks.isEmpty() ? "设备状态正常，按计划保养即可" : "建议：" + String.join("；", risks);
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("ok", true); r.put("id", e.id); r.put("name", e.name); r.put("lineId", e.lineId);
        r.put("status", e.status); r.put("temp", e.temp); r.put("vibration", e.vibration);
        r.put("runtimeHours", e.runtimeHours); r.put("lastMaint", e.lastMaint);
        r.put("risks", risks); r.put("advice", advice);
        return r;
    }

    /** 质量分析：不良率 + 帕累托排序 + 改进建议 */
    public Map<String, Object> qualityAnalysis() {
        int totalSampled = quality.stream().mapToInt(x -> x.sampled).sum();
        int totalDefect = quality.stream().mapToInt(x -> x.defect).sum();
        Map<String, Integer> byType = quality.stream().collect(Collectors.groupingBy(
                x -> x.defectType, Collectors.summingInt(x -> x.defect)));
        List<Map<String, Object>> pareto = byType.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("defectType", e.getKey());
                    m.put("count", e.getValue());
                    m.put("share", Math.round(e.getValue() * 1000.0 / totalDefect) / 10.0);
                    return m;
                }).collect(Collectors.toList());
        double rate = totalSampled > 0 ? Math.round(totalDefect * 10000.0 / totalSampled) / 100.0 : 0;
        String advice;
        if (!pareto.isEmpty() && rate > 3.0) {
            Map<String, Object> top = pareto.get(0);
            advice = "综合不良率 " + rate + "% 偏高（阈值 3%），首要改善 " + top.get("defectType")
                    + "（占 " + top.get("share") + "%），建议针对性做工艺参数复查与首件确认";
        } else {
            advice = "综合不良率 " + rate + "% 在阈值内，保持现有工艺监控";
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("totalSampled", totalSampled);
        r.put("totalDefect", totalDefect);
        r.put("defectRate", rate);
        r.put("pareto", pareto);
        r.put("records", quality);
        r.put("advice", advice);
        return r;
    }

    /** 生产看板统计 */
    public Map<String, Object> stats() {
        int totalPlan = orders.stream().filter(o -> !"已完成".equals(o.status)).mapToInt(o -> o.qty).sum();
        int totalDone = orders.stream().filter(o -> !"已完成".equals(o.status)).mapToInt(o -> o.done).sum();
        List<Map<String, Object>> lineStats = lines.stream().map(l -> {
            double progress = l.planQty > 0 ? Math.round(l.doneQty * 1000.0 / l.planQty) / 10.0 : 0;
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", l.id); m.put("name", l.name); m.put("product", l.product);
            m.put("status", l.status); m.put("progress", progress); m.put("shift", l.shift);
            m.put("oeeTarget", l.oeeTarget);
            return m;
        }).collect(Collectors.toList());
        long alarmEquip = equipments.stream().filter(e -> "故障".equals(e.status) || "预警".equals(e.status)).count();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("openOrders", orders.stream().filter(o -> !"已完成".equals(o.status)).count());
        r.put("totalPlan", totalPlan); r.put("totalDone", totalDone);
        r.put("progress", totalPlan > 0 ? Math.round(totalDone * 1000.0 / totalPlan) / 10.0 : 0);
        r.put("lines", lineStats);
        r.put("equipAlarm", alarmEquip);
        r.put("defectRate", quality.stream().mapToInt(x -> x.defect).sum() * 100.0
                / Math.max(1, quality.stream().mapToInt(x -> x.sampled).sum()));
        r.put("advice", (alarmEquip > 0 ? "有 " + alarmEquip + " 台设备预警/故障需立即处理；" : "设备运行正常；")
                + ("换型中".equals(lines.get(2).status) ? "L03 包装线换型中，注意换型时长控制" : ""));
        return r;
    }

    public Line findLine(String id) { return lines.stream().filter(x -> x.id.equals(id)).findFirst().orElse(null); }
}
