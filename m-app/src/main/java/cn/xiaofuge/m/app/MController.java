package cn.xiaofuge.m.app;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** MES REST API */
@RestController
public class MController {

    private final MStore store;

    public MController(MStore store) { this.store = store; }

    @GetMapping("/api/lines")
    public Map<String, Object> lines() {
        return Map.of("code", 0, "data", store.lines);
    }

    @GetMapping("/api/orders")
    public Map<String, Object> orders(@RequestParam(required = false) String status) {
        var list = store.orders.stream()
                .filter(o -> status == null || status.isBlank() || o.status.equals(status))
                .toList();
        return Map.of("code", 0, "data", list);
    }

    @PostMapping("/api/schedule")
    public Map<String, Object> schedule(@RequestBody Map<String, Object> body) {
        String lineId = String.valueOf(body.getOrDefault("lineId", ""));
        String product = String.valueOf(body.getOrDefault("product", ""));
        int qty = MHelper.intVal(body.get("qty"));
        String due = String.valueOf(body.getOrDefault("due", ""));
        return Map.of("code", 0, "data", store.schedule(lineId, product, qty, due));
    }

    @PostMapping("/api/report")
    public Map<String, Object> report(@RequestBody Map<String, Object> body) {
        String orderId = String.valueOf(body.getOrDefault("orderId", ""));
        int qty = MHelper.intVal(body.get("qty"));
        return Map.of("code", 0, "data", store.report(orderId, qty));
    }

    @GetMapping("/api/equipment")
    public Map<String, Object> equipment(@RequestParam String id) {
        return Map.of("code", 0, "data", store.equipment(id));
    }

    @GetMapping("/api/equipments")
    public Map<String, Object> equipments() {
        return Map.of("code", 0, "data", store.equipments);
    }

    @GetMapping("/api/quality")
    public Map<String, Object> quality() {
        return Map.of("code", 0, "data", store.qualityAnalysis());
    }

    @GetMapping("/api/stats")
    public Map<String, Object> stats() {
        return Map.of("code", 0, "data", store.stats());
    }
}

/** 解析工具 */
final class MHelper {
    static int intVal(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        try { return (int) Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return 0; }
    }
}
