package com.example.airline;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

@Component
public class BusinessTools {
    public record Result(String status, String code, String userMessage, Object data,
                         String operationId, boolean retryable) {
        static Result success(String message, Object data) {
            return new Result("SUCCESS", "OK", message, data, null, false);
        }
        static Result rejected(String code, String message) {
            return new Result("REJECTED", code, message, null, null, false);
        }
    }
    public record Spec(String name, String description, List<String> parameters, List<String> required) {
        ToolDefinition definition() {
            Map<String, Object> fields = new LinkedHashMap<>();
            parameters.forEach(name -> fields.put(name, Map.of("type", "string")));
            String schema = Json.write(Map.of("type", "object", "properties", fields,
                    "required", required, "additionalProperties", false));
            return ToolDefinition.builder().name(name).description(description).inputSchema(schema).build();
        }
        void validate(Map<String, String> args) {
            if (!parameters.containsAll(args.keySet())
                    || required.stream().anyMatch(key -> args.getOrDefault(key, "").isBlank())) {
                throw new ApiException(502, "INVALID_TOOL_ARGUMENTS", "工具参数缺失或含未知字段");
            }
        }
    }

    private static final Set<String> WRITES = Set.of(
            "book_new_flight", "cancel_flight", "update_seat", "assign_special_service_seat", "issue_compensation");
    private static final List<Spec> SPECS = List.of(
            spec("get_trip_details", "读取当前演示航程；未选择场景时仅根据明确地点推断，不猜测确认号",
                    List.of("message"), List.of("message")),
            spec("faq_lookup_tool", "查询演示政策：行李、Wi-Fi、座位、延误补偿",
                    List.of("question"), List.of("question")),
            spec("flight_status_tool", "查询演示航班状态和误机风险",
                    List.of("flight_number"), List.of("flight_number")),
            spec("get_matching_flights", "返回符合起终点的替代航班，无匹配时明确返回空结果",
                    List.of("origin", "destination"), List.of()),
            spec("book_new_flight", "申请改签，需要客户在页面确认具体操作",
                    List.of("flight_number"), List.of("flight_number")),
            spec("cancel_flight", "申请取消当前演示预订，需要客户页面确认", List.of(), List.of()),
            spec("update_seat", "申请修改座位，需要客户页面确认",
                    List.of("confirmation_number", "new_seat"), List.of("confirmation_number", "new_seat")),
            spec("assign_special_service_seat", "申请可用前排座位，需要客户页面确认",
                    List.of("seat_request"), List.of("seat_request")),
            spec("display_seat_map", "通过结构化结果打开136座演示座位图", List.of(), List.of()),
            spec("issue_compensation", "查过补偿政策后申请演示凭证，需要客户页面确认",
                    List.of("reason"), List.of("reason")),
            spec("transfer_to_agent", "转移控制权到允许的目标角色，不与其他工具混用",
                    List.of("target"), List.of("target")));

    private static Spec spec(String name, String description, List<String> parameters, List<String> required) {
        return new Spec(name, description, parameters, required);
    }

    public Spec get(String name) {
        return SPECS.stream().filter(spec -> spec.name().equals(name)).findFirst()
                .orElseThrow(() -> new ApiException(502, "UNKNOWN_TOOL", "模型请求了未知工具"));
    }

    public Result execute(Conversation conversation, String name, Map<String, String> args) {
        get(name).validate(args);
        synchronized (conversation) {
            AirlineData.Context context = conversation.context;
            if (name.equals("get_trip_details")) {
                if (context.scenario == null) {
                    String text = args.get("message").toLowerCase(Locale.ROOT);
                    if (contains(text, "paris", "new york", "austin", "巴黎", "纽约", "奥斯汀")) {
                        context.load("disrupted");
                    } else if (contains(text, "flt-123", "san francisco", "los angeles", "旧金山", "洛杉矶")) {
                        context.load("on_time");
                    } else {
                        return Result.rejected("MISSING_TRIP", "请先选择正常/延误演示航程，或提供具体航班");
                    }
                }
                return Result.success("已加载演示航程", modelContext(context));
            }
            if (name.equals("faq_lookup_tool")) {
                return faq(context, args.get("question"));
            }
            if (context.scenario == null) {
                return Result.rejected("MISSING_TRIP", "请先选择演示航程");
            }
            if (name.equals("flight_status_tool")) {
                AirlineData.Flight flight = AirlineData.FLIGHTS.get(args.get("flight_number").toUpperCase(Locale.ROOT));
                return flight == null
                        ? new Result("NOT_FOUND", "UNKNOWN_FLIGHT", "没有此演示航班，不代表航班准点", null, null, false)
                        : Result.success("演示航班状态", flight);
            }
            if (name.equals("get_matching_flights")) {
                List<AirlineData.Flight> matches = context.scenario.equals("disrupted")
                        ? AirlineData.ALTERNATIVES.stream()
                        .filter(flight -> matches(flight.origin(), args.get("origin"))
                                && matches(flight.destination(), args.get("destination"))).toList()
                        : List.of();
                return new Result(matches.isEmpty() ? "NOT_FOUND" : "SUCCESS", matches.isEmpty() ? "NO_MATCH" : "OK",
                        matches.isEmpty() ? "没有符合条件的演示候选航班" : "演示候选航班，均为次日到达",
                        matches, null, false);
            }
            if (name.equals("display_seat_map")) {
                return Result.success("请在页面选择演示座位", Map.of("uiAction", "OPEN_SEAT_MAP", "seatMap", seatMap(context)));
            }
            if (WRITES.contains(name)) {
                if (conversation.completed.size() >= 128) {
                    return Result.rejected("OPERATION_LIMIT", "演示操作记录达到上限，请创建新会话");
                }
                Result validation = validateWrite(context, name, args);
                if (validation != null) {
                    return validation;
                }
                conversation.pending.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(Instant.now()));
                Conversation.Operation existing = conversation.pending.values().stream()
                        .filter(op -> op.tool().equals(name)
                                && publicArguments(op.arguments()).equals(args)).findFirst().orElse(null);
                if (existing != null) {
                    return confirmation(existing);
                }
                if (conversation.pending.size() >= 8) {
                    return Result.rejected("PENDING_LIMIT", "待确认操作过多，请先处理页面中的操作");
                }
                Map<String, String> stored = new LinkedHashMap<>(args);
                stored.put("_version", Long.toString(context.seatMapVersion));
                Conversation.Operation operation = new Conversation.Operation(UUID.randomUUID().toString(),
                        name, Map.copyOf(stored), Map.of("flightNumber", context.flightNumber,
                                "confirmationNumber", context.confirmationNumber), Instant.now().plusSeconds(300));
                conversation.pending.put(operation.id(), operation);
                return confirmation(operation);
            }
            throw new ApiException(502, "UNSUPPORTED_TOOL", "交接必须由宿主编排器处理");
        }
    }

    public Result confirm(Conversation conversation, String operationId) {
        synchronized (conversation) {
            if (conversation.completed.containsKey(operationId)) {
                return conversation.completed.get(operationId);
            }
            Conversation.Operation operation = conversation.pending.remove(operationId);
            if (operation == null) {
                throw new ApiException(404, "OPERATION_NOT_FOUND", "待确认操作不存在");
            }
            if (operation.expiresAt().isBefore(Instant.now())) {
                return remember(conversation, operationId, Result.rejected("OPERATION_EXPIRED", "操作已过期，请重新申请"));
            }
            AirlineData.Context context = conversation.context;
            Map<String, String> args = publicArguments(operation.arguments());
            if (Long.parseLong(operation.arguments().get("_version")) != context.seatMapVersion) {
                return remember(conversation, operationId,
                        Result.rejected("STATE_CHANGED", "预订状态已变化，请根据最新状态重新申请"));
            }
            Result validation = validateWrite(context, operation.tool(), args);
            if (validation != null) {
                return remember(conversation, operationId, validation);
            }
            Object data;
            switch (operation.tool()) {
                case "book_new_flight" -> {
                    AirlineData.Flight selected = AirlineData.ALTERNATIVES.stream()
                            .filter(flight -> flight.number().equalsIgnoreCase(args.get("flight_number"))).findFirst().orElseThrow();
                    List<AirlineData.Flight> itinerary = new ArrayList<>(context.itinerary);
                    itinerary.removeIf(flight -> flight.origin().startsWith("New York")
                            && flight.destination().startsWith("Austin"));
                    itinerary.add(selected);
                    context.itinerary = List.copyOf(itinerary);
                    context.flightNumber = selected.number();
                    context.seatNumber = selected.seat();
                    data = selected;
                }
                case "cancel_flight" -> {
                    context.bookingStatus = "CANCELLED";
                    data = Map.of("bookingStatus", "CANCELLED", "flightNumber", context.flightNumber);
                }
                case "update_seat" -> {
                    context.seatNumber = args.get("new_seat").toUpperCase(Locale.ROOT);
                    data = Map.of("seatNumber", context.seatNumber);
                }
                case "assign_special_service_seat" -> {
                    context.seatNumber = availableFrontSeat();
                    context.specialServiceNote = args.get("seat_request");
                    data = Map.of("seatNumber", context.seatNumber, "specialService", true);
                }
                case "issue_compensation" -> {
                    context.compensationCaseId = "CMP-" + UUID.randomUUID().toString().substring(0, 8);
                    context.vouchers = List.of("演示酒店额度 $180", "演示餐食额度 $60", "演示交通额度 $40");
                    data = Map.of("caseId", context.compensationCaseId, "vouchers", context.vouchers);
                }
                default -> throw new IllegalStateException("Unknown stored operation");
            }
            context.seatMapVersion++;
            Result result = new Result("SUCCESS", "OK", "已完成演示操作：" + operation.tool(), data, operationId, false);
            return remember(conversation, operationId, result);
        }
    }

    private Result remember(Conversation conversation, String id, Result result) {
        conversation.completed.put(id, result);
        return result;
    }

    public static Map<String, String> publicArguments(Map<String, String> args) {
        Map<String, String> result = new LinkedHashMap<>(args);
        result.remove("_version");
        return Map.copyOf(result);
    }

    private Result confirmation(Conversation.Operation operation) {
        return new Result("NEEDS_CONFIRMATION", "CONFIRM_REQUIRED", "请在页面确认具体演示操作",
                Map.of("tool", operation.tool(), "arguments", publicArguments(operation.arguments()),
                        "booking", operation.booking(), "expiresAt", operation.expiresAt().toString()), operation.id(), false);
    }

    private Result validateWrite(AirlineData.Context context, String name, Map<String, String> args) {
        if (!context.bookingStatus.equals("CONFIRMED")) {
            return Result.rejected("BOOKING_NOT_ACTIVE", "预订未加载或已取消，不能执行此操作");
        }
        if (name.equals("book_new_flight")) {
            if (!context.scenario.equals("disrupted") || AirlineData.ALTERNATIVES.stream()
                    .noneMatch(flight -> flight.number().equalsIgnoreCase(args.get("flight_number")))) {
                return Result.rejected("NO_MATCH", "指定航班不在此演示航程的候选列表中");
            }
        }
        if (name.equals("update_seat")) {
            if (!context.confirmationNumber.equalsIgnoreCase(args.get("confirmation_number"))) {
                return Result.rejected("CONFIRMATION_MISMATCH", "确认号与当前演示预订不符");
            }
            String requested = args.get("new_seat").toUpperCase(Locale.ROOT);
            if (AirlineData.seats().stream().noneMatch(seat -> seat.number().equals(requested))) {
                return Result.rejected("INVALID_SEAT", "座位不存在");
            }
            if (AirlineData.OCCUPIED.contains(requested) && !requested.equals(context.seatNumber)) {
                return Result.rejected("SEAT_OCCUPIED", "座位已占用，请选择其他座位");
            }
        }
        if (name.equals("issue_compensation")) {
            if (!context.scenario.equals("disrupted")) {
                return Result.rejected("NOT_ELIGIBLE", "此演示航程不满足延误补偿条件");
            }
            if (!context.policyConsulted) {
                return Result.rejected("POLICY_REQUIRED", "请先使用 FAQ 查询补偿政策");
            }
            if (context.compensationCaseId != null) {
                return Result.rejected("ALREADY_ISSUED", "此演示航程已经发放凭证，无需重复申请");
            }
        }
        return null;
    }

    private Result faq(AirlineData.Context context, String question) {
        String q = question.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Pd}_]", "");
        List<String> answers = new ArrayList<>();
        if (contains(q, "bag", "baggage", "行李")) {
            answers.add("演示：可携带一件不超过50磅、22×14×9英寸的行李；挂失追踪未实现，请联系人工客服。");
        }
        if (contains(q, "wifi", "无线", "网络")) {
            answers.add("演示：免费 Wi-Fi，网络名称 Airline-Wifi。");
        }
        if (contains(q, "seat", "plane", "座位", "飞机", "布局")) {
            answers.add("演示布局共136座：商务舱16座、优选经济舱24座、经济舱96座；4和16排为出口排。");
        }
        if (contains(q, "compensation", "delay", "voucher", "补偿", "延误", "酒店", "餐食")) {
            context.policyConsulted = true;
            answers.add("演示延误场景可申请酒店$180、餐食$60及交通$40凭证；这不是真实航空政策。");
        }
        return answers.isEmpty()
                ? new Result("NOT_FOUND", "UNKNOWN_POLICY", "演示政策库没有此答案", null, null, false)
                : Result.success(String.join("\n", answers), Map.of("answers", answers));
    }

    public AirlineData.SeatMap seatMap(AirlineData.Context context) {
        if (context.scenario == null) {
            throw new ApiException(409, "MISSING_TRIP", "请先选择演示航程");
        }
        return new AirlineData.SeatMap(context.seatMapVersion, context.flightNumber, context.seatNumber, AirlineData.seats());
    }

    public Map<String, Object> modelContext(AirlineData.Context context) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("demo", true);
        data.put("bookingStatus", context.bookingStatus);
        if (context.scenario != null) {
            data.put("confirmationNumber", context.confirmationNumber);
            data.put("flightNumber", context.flightNumber);
            data.put("seatNumber", context.seatNumber);
            data.put("itinerary", context.itinerary);
            data.put("vouchers", context.vouchers);
        }
        return data;
    }

    private String availableFrontSeat() {
        return AirlineData.seats().stream()
                .filter(seat -> seat.number().startsWith("1") && seat.number().length() == 2 && !seat.occupied())
                .map(AirlineData.Seat::number).findFirst().orElseThrow();
    }

    private static boolean contains(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String value, String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        String normalized = filter.replace("纽约", "New York").replace("奥斯汀", "Austin");
        return value.toLowerCase(Locale.ROOT).contains(normalized.toLowerCase(Locale.ROOT));
    }
}
