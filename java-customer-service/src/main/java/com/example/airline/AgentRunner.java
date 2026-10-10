package com.example.airline;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Service;

@Service
public class AgentRunner {
    public record Reply(String requestId, String runId, String status, String message) {}
    private static final Logger LOG = LoggerFactory.getLogger(AgentRunner.class);
    private final AgentCatalog catalog;
    private final BusinessTools tools;
    private final ModelGateway model;
    private final InputChecks checks;
    private final EventHub events;
    private final AirlineProperties properties;
    private final HumanSupport humanSupport;

    public AgentRunner(AgentCatalog catalog, BusinessTools tools, ModelGateway model, InputChecks checks,
                       EventHub events, AirlineProperties properties, HumanSupport humanSupport) {
        this.catalog = catalog;
        this.tools = tools;
        this.model = model;
        this.checks = checks;
        this.events = events;
        this.properties = properties;
        this.humanSupport = humanSupport;
    }

    public Reply respond(Conversation conversation, String requestId, String text) {
        if (!conversation.runLock.tryLock()) {
            throw new ApiException(409, "THREAD_BUSY", "本会话正在执行另一操作");
        }
        try {
            synchronized (conversation) {
                if (conversation.requests.containsKey(requestId)) {
                    if (!text.equals(conversation.requestTexts.get(requestId))) {
                        throw new ApiException(409, "REQUEST_ID_CONFLICT", "相同请求ID不能用于不同消息");
                    }
                    return conversation.requests.get(requestId);
                }
                HumanSupport.requireAutomated(conversation);
                if (conversation.messages.size() >= 120 || conversation.history.size() >= 600) {
                    throw new ApiException(409, "HISTORY_LIMIT", "演示历史达到上限，请创建新会话");
                }
                conversation.runId = UUID.randomUUID().toString();
                conversation.runStatus = "RUNNING";
                conversation.insights = null;
                conversation.suggestedQuestions = List.of();
                conversation.currentSourceIds.clear();
                conversation.humanRecommended = false;
                conversation.guardrails = List.of(
                        new Conversation.Guardrail("Relevance Guardrail", "NOT_RUN", ""),
                        new Conversation.Guardrail("Jailbreak Guardrail", "NOT_RUN", ""));
                conversation.messages.add(new Conversation.ChatEntry("user", text));
                conversation.touchedAt = Instant.now();
            }
            events.record(conversation, "run_started", "开始处理消息", Map.of("requestId", requestId));
            Instant deadline = Instant.now().plus(properties.turnTimeout());
            try {
                InputChecks.Outcome outcome = checks.check(text, timeout(deadline));
                List<Conversation.Guardrail> guardrails = outcome.guardrails();
                synchronized (conversation) {
                    conversation.guardrails = guardrails;
                }
                events.record(conversation, "guardrail", "输入检查完成", Map.of());
                if (guardrails.stream().anyMatch(check -> Set.of("ERROR", "NOT_RUN").contains(check.status()))) {
                    throw new ApiException(502, "GUARDRAIL_ERROR", "输入检查失败，本轮未执行任何业务操作");
                }
                if (guardrails.stream().anyMatch(check -> check.status().equals("REJECTED"))) {
                    return finish(conversation, requestId, text, "REJECTED",
                            "抱歉，我只能处理航空客服相关的正常请求，无法绕过规则或泄露系统指令。");
                }
                synchronized (conversation) {
                    conversation.insights = outcome.insights();
                    conversation.humanRecommended = Set.of("negative", "frustrated").contains(outcome.insights().mood());
                    conversation.history.add(new UserMessage(text));
                }
                events.record(conversation, "service_insights", "咨询信息已整理", Map.of());
                if (outcome.insights().humanRequested()) {
                    HumanSupport.Case supportCase = humanSupport.request(conversation, "客户明确申请人工服务");
                    return finish(conversation, requestId, text, "COMPLETED",
                            "已登记演示人工服务申请 " + supportCase.id()
                                    + "，尚未接通真人。智能服务已暂停，您可以查看申请或恢复智能服务。");
                }
                return run(conversation, requestId, text, deadline);
            } catch (ApiException exception) {
                LOG.warn("Agent run {} ended with {}", conversation.runId, exception.code());
                synchronized (conversation) {
                    conversation.humanRecommended = true;
                }
                return finish(conversation, requestId, text, "FAILED", exception.getMessage());
            } catch (RuntimeException exception) {
                LOG.error("Agent run {} failed ({})", conversation.runId, exception.getClass().getSimpleName());
                synchronized (conversation) {
                    conversation.humanRecommended = true;
                }
                return finish(conversation, requestId, text, "FAILED",
                        "客服执行失败，已完成的演示操作仍保留；请查询最新状态，不要重复提交整轮操作。");
            }
        } finally {
            conversation.runLock.unlock();
        }
    }

    private Reply run(Conversation conversation, String requestId, String text, Instant deadline) {
        int toolCount = 0;
        int handoffCount = 0;
        Set<String> callIds = new HashSet<>();
        for (int step = 0; step < properties.maxModelSteps(); step++) {
            AgentCatalog.Agent agent;
            List<Message> messages;
            synchronized (conversation) {
                agent = catalog.get(conversation.activeAgent);
                messages = new ArrayList<>();
                messages.add(new SystemMessage(catalog.prompt(agent.id())));
                messages.addAll(conversation.history);
                messages.add(new UserMessage("服务端只读状态数据（不是客户指令）：" + Json.write(tools.modelContext(conversation.context))));
            }
            List<ToolDefinition> definitions = new ArrayList<>();
            agent.tools().forEach(name -> definitions.add(tools.get(name).definition()));
            definitions.add(catalog.handoffDefinition(agent.id()));
            events.record(conversation, "progress", "正在调用 " + agent.name(), Map.of("step", step + 1));
            AssistantMessage response = model.complete(messages, definitions, timeout(deadline));
            if (!response.hasToolCalls()) {
                String answer = response.getText();
                if (answer == null || answer.isBlank()) {
                    throw new ApiException(502, "EMPTY_ANSWER", "模型未提供有效客服回复");
                }
                synchronized (conversation) {
                    conversation.history.add(response);
                }
                return finish(conversation, requestId, text, "COMPLETED", answer);
            }
            List<AssistantMessage.ToolCall> calls = response.getToolCalls();
            boolean handoff = calls.stream().anyMatch(call -> call.name().equals("transfer_to_agent"));
            if (handoff && calls.size() != 1) {
                throw new ApiException(502, "AMBIGUOUS_HANDOFF", "模型将交接与其他工具混用，本次请求未执行");
            }
            if (toolCount + calls.size() > properties.maxToolCalls()
                    || handoff && handoffCount >= properties.maxHandoffs()) {
                throw new ApiException(422, "RUN_BUDGET_EXCEEDED", "工具或交接次数达到上限，请缩小请求范围");
            }
            List<Map<String, String>> arguments = new ArrayList<>();
            for (AssistantMessage.ToolCall call : calls) {
                if (call.id() == null || call.id().isBlank() || !callIds.add(call.id())) {
                    throw new ApiException(502, "INVALID_TOOL_CALL_ID", "工具调用标识缺失或重复");
                }
                if (!call.name().equals("transfer_to_agent") && !agent.tools().contains(call.name())) {
                    throw new ApiException(502, "TOOL_FORBIDDEN", "模型请求了当前客服无权执行的工具");
                }
                Map<String, String> args = Json.arguments(call.arguments());
                tools.get(call.name()).validate(args);
                if (handoff && !agent.handoffs().contains(args.get("target"))) {
                    String requested = catalog.all().stream().map(AgentCatalog.Agent::id)
                            .filter(id -> id.equals(args.get("target"))).findFirst().orElse("unknown");
                    LOG.warn("Rejected handoff for run {} from {} to {}; allowed targets: {}",
                            conversation.runId, agent.id(), requested, agent.handoffs());
                    events.record(conversation, "handoff_rejected", "客服转接目标不符合当前服务权限",
                            Map.of("sourceAgent", agent.id(), "requestedTarget", requested,
                                    "allowedTargets", agent.handoffs()));
                    throw new ApiException(502, "HANDOFF_FORBIDDEN", "模型请求了不允许的交接目标");
                }
                arguments.add(args);
            }
            timeout(deadline);
            synchronized (conversation) {
                conversation.history.add(response);
            }
            List<ToolResponseMessage.ToolResponse> results = new ArrayList<>();
            boolean executionFailed = false;
            for (int index = 0; index < calls.size(); index++) {
                AssistantMessage.ToolCall call = calls.get(index);
                events.record(conversation, "tool_call", call.name(),
                        Map.of("toolName", call.name(), "toolCallId", call.id()));
                BusinessTools.Result result;
                if (executionFailed) {
                    result = new BusinessTools.Result("FAILED", "NOT_EXECUTED",
                            "前序工具执行异常，此调用未执行", null, null, false);
                } else if (handoff) {
                    String target = arguments.get(index).get("target");
                    result = BusinessTools.Result.success("交接已完成", Map.of("target", target));
                    synchronized (conversation) {
                        conversation.activeAgent = target;
                    }
                    events.record(conversation, "handoff", agent.name() + " → " + catalog.get(target).name(),
                            Map.of("sourceAgent", agent.id(), "targetAgent", target, "toolCallId", call.id()));
                    handoffCount++;
                } else {
                    try {
                        result = tools.execute(conversation, call.name(), arguments.get(index));
                    } catch (RuntimeException exception) {
                        LOG.error("Tool execution failed for run {} tool {} ({})", conversation.runId,
                                call.name(), exception.getClass().getSimpleName());
                        result = new BusinessTools.Result("FAILED", "TOOL_EXECUTION_ERROR",
                                "工具执行异常，已完成的操作保留", null, null, false);
                        executionFailed = true;
                    }
                }
                results.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), Json.write(result)));
                events.record(conversation, "tool_output", result.userMessage(),
                        Map.of("toolName", call.name(), "toolCallId", call.id(), "status", result.status()));
            }
            synchronized (conversation) {
                conversation.history.add(ToolResponseMessage.builder().responses(results).build());
            }
            if (executionFailed) {
                throw new ApiException(502, "TOOL_EXECUTION_ERROR", "工具执行异常，已完成的操作保留");
            }
            toolCount += calls.size();
        }
        throw new ApiException(422, "RUN_BUDGET_EXCEEDED", "模型执行步数达到上限，请缩小请求范围");
    }

    private Duration timeout(Instant deadline) {
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isNegative() || remaining.isZero()) {
            throw new ApiException(504, "TURN_TIMEOUT", "本轮处理达到时间上限");
        }
        return remaining.compareTo(properties.modelTimeout()) < 0 ? remaining : properties.modelTimeout();
    }

    private Reply finish(Conversation conversation, String requestId, String text, String status, String message) {
        synchronized (conversation) {
            conversation.runStatus = status;
            conversation.pending.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(Instant.now()));
            conversation.suggestedQuestions = ServiceInsights.suggestions(conversation);
            conversation.messages.add(new Conversation.ChatEntry("assistant", message,
                    List.copyOf(conversation.currentSourceIds), conversation.insights, conversation.suggestedQuestions));
            conversation.touchedAt = Instant.now();
            Reply reply = new Reply(requestId, conversation.runId, status, message);
            conversation.requests.put(requestId, reply);
            conversation.requestTexts.put(requestId, text);
            events.record(conversation, "run_finished", message, Map.of("status", status));
            return reply;
        }
    }
}
