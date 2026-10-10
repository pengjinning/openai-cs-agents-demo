package com.example.airline;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import tools.jackson.databind.JsonNode;

public record ServiceInsights(String mood, List<String> categories, boolean humanRequested) {
    public static final Set<String> MOODS = Set.of("positive", "neutral", "negative", "curious", "frustrated", "confused");
    public static final Set<String> CATEGORIES = Set.of("flight", "booking", "seat", "policy", "baggage", "compensation", "human");

    public static ServiceInsights parse(JsonNode node) {
        if (!node.path("mood").isString() || !MOODS.contains(node.path("mood").asString())
                || !node.path("categories").isArray() || node.path("categories").size() > CATEGORIES.size()
                || !node.path("humanRequested").isBoolean()) {
            throw new ApiException(502, "INVALID_SERVICE_INSIGHTS", "咨询标签返回格式不合法");
        }
        List<String> categories = new ArrayList<>();
        for (JsonNode category : node.path("categories")) {
            if (!category.isString() || !CATEGORIES.contains(category.asString()) || categories.contains(category.asString())) {
                throw new ApiException(502, "INVALID_SERVICE_INSIGHTS", "咨询分类包含未知或重复标识");
            }
            categories.add(category.asString());
        }
        return new ServiceInsights(node.path("mood").asString(), List.copyOf(categories), node.path("humanRequested").asBoolean());
    }

    public static List<String> suggestions(Conversation conversation) {
        if (HumanSupport.requested(conversation) || conversation.runStatus.equals("REJECTED")
                || conversation.pending.values().stream().anyMatch(op -> op.expiresAt().isAfter(java.time.Instant.now()))) {
            return List.of();
        }
        if (conversation.runStatus.equals("FAILED") || conversation.context.bookingStatus.equals("CANCELLED")) {
            return List.of("我想申请人工服务。", "还有哪些演示航空政策可以查询？");
        }
        if (conversation.insights == null) {
            return List.of();
        }
        List<String> questions = new ArrayList<>();
        if (conversation.insights.categories().contains("flight") && "disrupted".equals(conversation.context.scenario)) {
            questions.add("有哪些可以改签的替代航班？");
        }
        if (conversation.insights.categories().contains("booking")) {
            questions.add("我想查看当前航班的座位图。");
        }
        if (conversation.insights.categories().contains("seat")) {
            questions.add("特殊服务座位如何申请？");
        }
        if ("disrupted".equals(conversation.context.scenario) && conversation.context.vouchers.isEmpty()) {
            questions.add("延误后有哪些演示补偿政策？");
        }
        if (conversation.humanRecommended) {
            questions.add("我想申请人工服务。");
        }
        questions.add("Wi-Fi和行李政策是什么？");
        return questions.stream().distinct().limit(3).toList();
    }
}
