package com.example.airline;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

public class PolicyKnowledge {
    public record Source(String id, String title, String version, String category, String snippet) {}
    public record Document(String id, String title, String version, String category, String content) {}
    public record Lookup(String runId, String query, Instant timestamp, List<Source> sources) {}
    private record Entry(Document document, List<String> keywords) {}

    private static final List<Entry> ENTRIES = List.of(
            entry("baggage-demo", "演示行李政策", "baggage",
                    "演示：可携带一件不超过50磅、22×14×9英寸的行李；挂失追踪未实现，请联系人工客服。",
                    "bag", "baggage", "行李"),
            entry("wifi-demo", "机上网络说明", "policy",
                    "演示：免费 Wi-Fi，网络名称 Airline-Wifi。", "wifi", "无线", "网络"),
            entry("seating-demo", "演示机舱布局", "seat",
                    "演示布局共" + AirlineData.seats().size()
                            + "座：商务舱16座、优选经济舱24座、经济舱96座；4和16排为出口排。"
                            + "座位占用以演示座位图为准，选座须提交并确认，不能仅凭文字回复视为修改成功。",
                    "seat", "plane", "座位", "飞机", "布局"),
            entry("compensation-demo", "演示延误关怀", "compensation",
                    "演示延误场景可申请酒店$180、餐食$60及交通$40凭证；这不是真实航空政策。"
                            + "须先查询政策，再申请并确认凭证；已发放的凭证不可重复申请。",
                    "compensation", "delay", "voucher", "补偿", "延误", "酒店", "餐食"));

    private static Entry entry(String id, String title, String category, String content, String... keywords) {
        return new Entry(new Document(id, title, "demo-v1", category, content), List.of(keywords));
    }

    public List<Source> search(String query) {
        String normalized = query.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Pd}_]", "");
        return ENTRIES.stream().filter(entry -> entry.keywords().stream().anyMatch(normalized::contains))
                .map(entry -> {
                    Document doc = entry.document();
                    return new Source(doc.id(), doc.title(), doc.version(), doc.category(), doc.content());
                }).toList();
    }

    public Document document(String id) {
        return ENTRIES.stream().map(Entry::document).filter(doc -> doc.id().equals(id)).findFirst()
                .orElseThrow(() -> new ApiException(404, "SOURCE_NOT_FOUND", "参考资料不存在"));
    }
}
