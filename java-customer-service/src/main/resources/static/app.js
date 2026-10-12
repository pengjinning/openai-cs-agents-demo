"use strict";

const byId = (id) => document.getElementById(id);
let csrfToken;
let threadId;
let state;
let stream;
let seats;
let sending = false;
let lastRevision = -1;
let replyFrame;
let seatLoading = false;
const toolLabels = {
  update_seat: "修改座位", assign_special_service_seat: "安排特殊服务座位",
  book_new_flight: "改签航班", cancel_flight: "取消预订", issue_compensation: "发放演示补偿",
  get_trip_details: "查询航程", flight_status_tool: "查询航班状态", get_matching_flights: "查找替代航班",
  faq_lookup_tool: "查询政策", display_seat_map: "打开座位图", transfer_to_agent: "转接客服",
};
const statusLabels = {
  IDLE: "等待您的咨询", RUNNING: "正在为您处理", COMPLETED: "本次服务已完成",
  REJECTED: "暂无法处理此请求", FAILED: "处理未完成", PASSED: "已通过",
  ERROR: "检查未完成", NOT_RUN: "等待检查", CONFIRMED: "已确认", CANCELLED: "已取消", UNLOADED: "尚未选择航程",
};
const eventLabels = {
  run_started: "开始服务", guardrail: "安全检查", progress: "正在处理", tool_call: "服务申请",
  tool_output: "处理结果", handoff: "客服转接", run_finished: "服务结果", ui_action: "您的申请",
  operation_result: "操作结果",
  service_insights: "咨询整理", human_requested: "人工服务申请", human_resumed: "恢复智能服务",
};
const moodLabels = { positive: "积极", neutral: "平和", negative: "不满", curious: "希望了解",
  frustrated: "遇到困扰", confused: "需要说明" };
const categoryLabels = { flight: "航班", booking: "预订", seat: "座位", policy: "政策",
  baggage: "行李", compensation: "延误关怀", human: "人工服务" };
const argumentLabels = {
  flight_number: "目标航班", confirmation_number: "确认号", new_seat: "目标座位",
  seat_request: "特殊服务需求", reason: "申请原因",
};

function serviceText(text) {
  let result = text;
  Object.entries(toolLabels).forEach(([name, label]) => { result = result.replaceAll(name, label); });
  return result;
}

function renderRichText(text) {
  const container = node("div", undefined, "rich-text");
  container.innerHTML = DOMPurify.sanitize(marked.parse(text, { gfm: true, breaks: true }), {
    ALLOWED_TAGS: ["p", "br", "strong", "em", "del", "code", "pre", "blockquote",
      "ul", "ol", "li", "h1", "h2", "h3", "h4", "h5", "h6", "hr",
      "table", "thead", "tbody", "tr", "th", "td", "a"],
    ALLOWED_ATTR: ["href", "title", "start", "align"],
  });
  container.querySelectorAll("table").forEach((table) => {
    const wrap = node("div", undefined, "table-wrap");
    wrap.tabIndex = 0;
    wrap.setAttribute("aria-label", "回复中的表格");
    table.replaceWith(wrap);
    wrap.append(table);
  });
  container.querySelectorAll("p").forEach((paragraph) => {
    if (/^(?:💡\s*)?(?:温馨提示|提示|注意|说明)\s*[:：]/u.test(paragraph.textContent)) {
      paragraph.classList.add("tip-block");
    }
  });
  return container;
}

function renderStreamingReply() {
  const messages = byId("messages");
  const shouldScroll = messages.scrollHeight - messages.scrollTop - messages.clientHeight < 80;
  let draft = messages.querySelector(".streaming-reply");
  if (state?.runStatus !== "RUNNING") {
    draft?.remove();
    return;
  }
  if (!draft) {
    draft = node("div", undefined, "message assistant streaming-reply");
    messages.append(draft);
  }
  const content = state.streamingReply?.content;
  draft.replaceChildren(content ? renderRichText(serviceText(content))
    : node("p", "正在核实您的需求…", "hint"));
  draft.append(node("span", content ? "正在回复…" : "正在处理…", "streaming-status"));
  if (state.events?.some((event) => event.runId === state.streamingReply?.runId
      && event.type === "tool_output" && event.metadata?.toolName === "display_seat_map"
      && event.metadata?.status === "SUCCESS")) draft.append(seatMapButton());
  if (shouldScroll) messages.scrollTop = messages.scrollHeight;
}

function seatMapButton() {
  const button = node("button", "查看座位图", "seat-map-action secondary");
  button.type = "button";
  button.setAttribute("aria-haspopup", "dialog");
  button.setAttribute("aria-controls", "seat-drawer");
  button.disabled = !threadId || seatLoading;
  button.addEventListener("click", () => openSeats());
  return button;
}

function receiveReplyDelta(delta) {
  if (delta.threadId !== threadId || delta.revision <= lastRevision
      || state?.runStatus !== "RUNNING" || state.streamingReply?.runId !== delta.runId
      || state.streamingReply.step !== delta.step) return;
  lastRevision = delta.revision;
  state = { ...state, revision: delta.revision,
    streamingReply: { ...state.streamingReply, content: state.streamingReply.content + delta.delta } };
  if (!replyFrame) {
    replyFrame = requestAnimationFrame(() => {
      replyFrame = undefined;
      renderStreamingReply();
    });
  }
}

function notice(message = "") { byId("notice").textContent = message; }
function node(tag, text, className) {
  const element = document.createElement(tag);
  if (text !== undefined) element.textContent = text;
  if (className) element.className = className;
  return element;
}
async function api(path, method = "GET", body) {
  const response = await fetch(path, {
    method, credentials: "same-origin",
    headers: { "Content-Type": "application/json", "X-CSRF-Token": csrfToken ?? "" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const data = await response.json();
  if (!response.ok) throw new Error(data.message || `HTTP ${response.status}`);
  return data;
}
const threadPath = (id = threadId) => `/api/threads/${encodeURIComponent(id)}`;

function busy() { return sending || state?.runStatus === "RUNNING"; }
function humanPending() { return state?.humanCases?.at(-1)?.status === "REQUESTED"; }
function controls() {
  byId("send").disabled = !threadId || busy() || humanPending();
  byId("message").disabled = !threadId || humanPending();
  byId("new-thread").disabled = busy();
  byId("delete-thread").disabled = !threadId || busy();
  byId("open-seats").disabled = !threadId || seatLoading;
  document.querySelectorAll(".seat-map-action").forEach((button) => {
    button.disabled = !threadId || seatLoading;
  });
  document.querySelectorAll("[data-prompt],.confirm-operation,.seat").forEach((button) => {
    button.disabled = !threadId || busy() || humanPending() || button.classList.contains("occupied")
      || (seatLoading && button.classList.contains("seat"));
  });
  byId("request-human").disabled = !threadId || busy() || humanPending();
  byId("human-reason").disabled = !threadId || busy() || humanPending();
  byId("resume-human").hidden = !humanPending();
  byId("resume-human").disabled = busy();
}

function insightTags(insights) {
  if (!insights) return [];
  return [node("span", moodLabels[insights.mood], "insight-tag"),
    ...insights.categories.map((category) => node("span", categoryLabels[category], "insight-tag"))];
}

function sourceButton(sourceId, title, id = threadId) {
  const button = node("button", title, "secondary");
  button.type = "button";
  button.addEventListener("click", () => showSource(sourceId, id));
  return button;
}

async function showSource(sourceId, id) {
  try {
    const source = await api(`${threadPath(id)}/knowledge/${encodeURIComponent(sourceId)}`);
    if (id !== threadId) return;
    byId("source-title").textContent = source.title;
    byId("source-version").textContent = `资料版本：${source.version} · 演示政策`;
    byId("source-content").textContent = source.content;
    if (!byId("source-dialog").open) byId("source-dialog").showModal();
  } catch (error) { if (id === threadId) notice(error.message); }
}

function render(snapshot) {
  if (snapshot.threadId !== threadId || snapshot.revision < lastRevision) return;
  state = snapshot;
  lastRevision = snapshot.revision;
  byId("agents").replaceChildren(...snapshot.agents.map((agent) => {
    const element = node("div", agent.name, `agent${agent.id === snapshot.activeAgentId ? " active" : ""}`);
    return element;
  }));
  byId("run-status").textContent = humanPending() ? "已登记人工申请，智能服务暂停"
    : statusLabels[snapshot.runStatus] || "服务状态更新中";
  const labels = { bookingStatus: "预订状态", passengerName: "演示乘客", confirmationNumber: "演示确认号",
    flightNumber: "操作航班", seatNumber: "当前座位", vouchers: "演示凭证" };
  const fields = [];
  Object.entries(snapshot.context).forEach(([key, value]) => {
    if (!labels[key]) return;
    fields.push(node("dt", labels[key]), node("dd", Array.isArray(value) ? value.join("；") : statusLabels[value] || String(value)));
  });
  byId("context").replaceChildren(...fields);
  byId("guardrails").replaceChildren(...snapshot.guardrails.map((check) =>
    node("div", `${check.name === "Relevance Guardrail" ? "服务范围检查" : "请求安全检查"}：${statusLabels[check.status] || "检查中"}`, `guardrail ${check.status}`)));
  byId("events").replaceChildren(...snapshot.events.slice().reverse().map((event) => {
    const element = node("div", undefined, "event");
    const agentName = snapshot.agents.find((agent) => agent.id === event.agent)?.name || "客服";
    element.append(node("strong", `${eventLabels[event.type] || "服务进展"} · ${agentName}`),
      node("div", serviceText(event.content)));
    return element;
  }));
  const sources = new Map(snapshot.knowledgeHistory.flatMap((lookup) => lookup.sources.map((source) => [source.id, source])));
  byId("messages").replaceChildren(...snapshot.messages.map((message) => {
    const element = node("div", undefined, `message ${message.role}`);
    if (message.role === "assistant") {
      element.append(renderRichText(serviceText(message.content)));
      if (message.uiActions?.includes("OPEN_SEAT_MAP")) element.append(seatMapButton());
      if (message.sourceIds.length) {
        const links = node("div", undefined, "source-links");
        links.append(node("span", "查询到的参考资料："), ...message.sourceIds.map((id) =>
          sourceButton(id, sources.get(id)?.title || "查看资料")));
        element.append(links);
      }
    } else {
      element.append(node("div", message.content));
    }
    return element;
  }));
  renderStreamingReply();
  byId("insights").replaceChildren(...insightTags(snapshot.insights));
  if (!snapshot.insights) byId("insights").append(node("p", "本轮尚未整理咨询信息", "hint"));
  byId("follow-ups").replaceChildren(...snapshot.suggestedQuestions.map((question) => {
    const button = node("button", question, "secondary");
    button.dataset.prompt = question;
    button.addEventListener("click", () => sendMessage(question));
    return button;
  }));
  byId("knowledge").replaceChildren(...snapshot.knowledgeHistory.slice().reverse().map((lookup) => {
    const card = node("div", undefined, "source-card");
    card.append(node("strong", lookup.query), node("p", new Date(lookup.timestamp).toLocaleString(), "hint"));
    if (!lookup.sources.length) card.append(node("p", "没有匹配的演示资料，可申请人工服务。"));
    lookup.sources.forEach((source) => {
      card.append(node("p", source.snippet), sourceButton(source.id, source.title));
    });
    return card;
  }));
  if (!snapshot.knowledgeHistory.length) byId("knowledge").append(node("p", "尚未查询政策资料", "hint"));
  byId("human-recommendation").textContent = snapshot.humanRecommended
    ? "如果问题仍未解决，您可以登记演示人工服务申请。" : "";
  byId("human-cases").replaceChildren(...snapshot.humanCases.slice().reverse().map((supportCase) => {
    const card = node("div", undefined, "human-case");
    card.append(node("strong", supportCase.status === "REQUESTED" ? "申请已登记 · 尚未接通真人" : "已取消 · 恢复智能服务"),
      node("p", `申请号：${supportCase.id}`), node("p", `原因：${supportCase.reason}`),
      node("p", `登记时间：${new Date(supportCase.createdAt).toLocaleString()}`));
    const details = node("details");
    details.append(node("summary", "登记时的公开对话摘要"));
    supportCase.summary.forEach((entry) =>
      details.append(node("p", `${entry.role === "user" ? "您" : "客服"}：${serviceText(entry.content)}`)));
    card.append(details);
    return card;
  }));
  if (!snapshot.humanCases.length) byId("human-cases").append(node("p", "暂无人工服务申请", "hint"));
  byId("messages").scrollTop = byId("messages").scrollHeight;
  const operationElement = (operation) => {
    const element = node("div", undefined, "operation");
    element.append(node("strong", `${toolLabels[operation.tool] || "服务申请"}（演示）`),
      node("p", `航班：${operation.booking.flightNumber} · 演示确认号：${operation.booking.confirmationNumber}`),
      node("p", Object.entries(operation.arguments).map(([key, value]) => `${argumentLabels[key] || "申请内容"}：${value}`).join("；")),
      node("p", `有效期至 ${new Date(operation.expiresAt).toLocaleTimeString()}`));
    const button = node("button", "确认此演示操作", "confirm-operation");
    button.addEventListener("click", () => confirmOperation(operation.id));
    element.append(button);
    return element;
  };
  byId("operations").replaceChildren(...snapshot.pendingOperations.map(operationElement));
  byId("seat-operations").replaceChildren(...snapshot.pendingOperations
    .filter((operation) => ["update_seat", "assign_special_service_seat"].includes(operation.tool))
    .map(operationElement));
  if (!byId("seat-operations").childElementCount) {
    byId("seat-operations").append(node("p", "选择座位后，将在这里显示待确认申请。", "hint"));
  }
  if (!snapshot.pendingOperations.length) byId("operations").append(node("p", "暂无待确认操作", "hint"));
  if (seats && seats.version !== snapshot.context.seatMapVersion) {
    seats = null;
    byId("seat-map").replaceChildren();
    byId("seat-status").textContent = "状态已变化，请重新打开座位图。";
  }
  if (byId("seat-drawer").open && !seats && !seatLoading) void openSeats();
  controls();
}

async function refresh(id = threadId) {
  if (!id) return;
  const snapshot = await api(threadPath(id));
  if (id === threadId) render(snapshot);
}

function bind(snapshot) {
  stream?.close();
  cancelAnimationFrame(replyFrame);
  replyFrame = undefined;
  byId("source-dialog").close();
  byId("seat-drawer").close();
  byId("human-reason").value = "";
  threadId = snapshot.threadId;
  sessionStorage.setItem("airline-thread", threadId);
  lastRevision = -1;
  seats = null;
  byId("seat-map").replaceChildren();
  byId("seat-status").textContent = "";
  render(snapshot);
  const boundId = threadId;
  stream = new EventSource(`${threadPath()}/events`);
  stream.addEventListener("snapshot", (event) => {
    if (boundId !== threadId) return;
    try { render(JSON.parse(event.data)); }
    catch { notice("服务进展更新失败，请刷新页面。"); }
  });
  stream.addEventListener("reply_delta", (event) => {
    if (boundId !== threadId) return;
    try { receiveReplyDelta(JSON.parse(event.data)); }
    catch { notice("回复更新失败，请刷新页面恢复最新内容。"); }
  });
  stream.onopen = () => {
    if (boundId === threadId) byId("connection").textContent = "服务已连接";
  };
  stream.onerror = () => {
    if (boundId === threadId) byId("connection").textContent = "连接中断，正在重新连接…";
  };
}

async function createThread() {
  if (busy()) return;
  sending = true;
  controls();
  try {
    notice();
    bind(await api("/api/threads", "POST", { scenario: byId("scenario").value }));
  } catch (error) { notice(error.message); }
  finally { sending = false; controls(); }
}

async function sendMessage(message) {
  if (!threadId || busy() || humanPending()) return;
  const id = threadId;
  sending = true;
  controls();
  notice();
  try {
    const reply = await api(`${threadPath(id)}/messages`, "POST", { requestId: crypto.randomUUID(), message });
    if (id === threadId) {
      if (reply.status !== "COMPLETED") notice(serviceText(reply.message));
      byId("message").value = "";
      await refresh(id);
    }
  } catch (error) { notice(error.message); }
  finally {
    sending = false;
    controls();
  }
}

async function confirmOperation(operationId) {
  if (busy() || humanPending()) return;
  sending = true;
  controls();
  try {
    const result = await api(`${threadPath()}/operations/confirm`, "POST", { operationId });
    notice(serviceText(result.userMessage));
    await refresh();
  } catch (error) { notice(error.message); }
  finally { sending = false; controls(); }
}

async function openSeats() {
  if (!threadId || seatLoading) return;
  const id = threadId;
  const drawer = byId("seat-drawer");
  if (!drawer.open) drawer.showModal();
  seatLoading = true;
  controls();
  byId("seat-status").textContent = "正在加载座位图…";
  try {
    const data = await api(`${threadPath(id)}/seats`);
    if (id !== threadId) return;
    seats = data;
    byId("seat-status").textContent = `演示航班 ${data.flightNumber} · 当前座位 ${data.selectedSeat}`;
    const rows = [];
    for (let row = 1; row <= 24; row++) {
      if ([1, 5, 9].includes(row)) rows.push(node("div", row === 1 ? "商务舱" : row === 5 ? "优选经济舱" : "经济舱", "cabin-title"));
      const element = node("div", undefined, "seat-row");
      element.append(node("span", row, "row-label"));
      const rowSeats = data.seats.filter((seat) => parseInt(seat.number, 10) === row);
      rowSeats.forEach((seat, index) => {
        const selected = seat.number === data.selectedSeat;
        const occupied = seat.occupied && !selected;
        const button = node("button", seat.number, `seat${occupied ? " occupied" : ""}${selected ? " selected" : ""}${seat.exitRow ? " exit" : ""}${index === rowSeats.length / 2 - 1 ? " aisle" : ""}`);
        button.disabled = occupied || busy() || humanPending();
        button.title = `${seat.number} ${seat.cabin}${seat.exitRow ? " 出口排" : ""}`;
        button.addEventListener("click", () => selectSeat(seat.number, data.version));
        element.append(button);
      });
      rows.push(element);
    }
    byId("seat-map").replaceChildren(...rows);
  } catch (error) {
    if (id === threadId) {
      seats = null;
      byId("seat-map").replaceChildren();
      byId("seat-status").textContent = `座位图加载失败：${error.message}。请点击刷新重试。`;
      notice(error.message);
    }
  } finally {
    seatLoading = false;
    controls();
  }
}

async function selectSeat(seatNumber, version) {
  if (busy() || humanPending()) return;
  sending = true;
  controls();
  try {
    const result = await api(`${threadPath()}/seats`, "POST", { seatNumber, version });
    notice(serviceText(result.userMessage));
    byId("seat-status").textContent = serviceText(result.userMessage);
    await refresh();
  } catch (error) { notice(error.message); }
  finally { sending = false; controls(); }
}

byId("chat-form").addEventListener("submit", (event) => {
  event.preventDefault();
  const message = byId("message").value.trim();
  if (message) void sendMessage(message);
});
byId("new-thread").addEventListener("click", createThread);
byId("close-source").addEventListener("click", () => byId("source-dialog").close());
byId("close-seats").addEventListener("click", () => byId("seat-drawer").close());
byId("human-form").addEventListener("submit", (event) => {
  event.preventDefault();
  void changeHuman(false);
});
byId("resume-human").addEventListener("click", () => changeHuman(true));
byId("open-seats").addEventListener("click", openSeats);
byId("delete-thread").addEventListener("click", async () => {
  if (!threadId || busy()) return;
  try {
    await api(threadPath(), "DELETE");
    stream?.close();
    threadId = null;
    state = null;
    sessionStorage.removeItem("airline-thread");
    await createThread();
  } catch (error) { notice(error.message); }
});
document.querySelectorAll("[data-prompt]").forEach((button) => {
  button.addEventListener("click", () => sendMessage(button.dataset.prompt));
});

async function changeHuman(resume) {
  if (!threadId || busy() || (!resume && humanPending())) return;
  const id = threadId;
  sending = true;
  controls();
  try {
    await api(`${threadPath(id)}/human-support${resume ? "/resume" : ""}`, "POST",
      resume ? undefined : { reason: byId("human-reason").value.trim() });
    if (id === threadId) {
      notice(resume ? "已恢复智能服务。" : "已登记演示人工申请，尚未接通真人。");
      await refresh(id);
    }
  } catch (error) { if (id === threadId) notice(error.message); }
  finally { sending = false; controls(); }
}

async function initialize() {
  try {
    const session = await api("/api/session");
    csrfToken = session.csrfToken;
    const previous = sessionStorage.getItem("airline-thread");
    if (previous) {
      try { bind(await api(threadPath(previous))); return; }
      catch (error) { notice(`原会话已失效：${error.message}`); }
    }
    await createThread();
  } catch (error) { notice(error.message); }
}
void initialize();
