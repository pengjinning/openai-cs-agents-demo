"use strict";

const byId = (id) => document.getElementById(id);
let csrfToken;
let threadId;
let state;
let stream;
let seats;
let sending = false;
let lastRevision = -1;
const toolLabels = {
  update_seat: "修改座位", assign_special_service_seat: "安排特殊服务座位",
  book_new_flight: "改签航班", cancel_flight: "取消预订", issue_compensation: "发放演示补偿",
};

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
function controls() {
  byId("send").disabled = !threadId || busy();
  byId("new-thread").disabled = busy();
  byId("delete-thread").disabled = !threadId || busy();
  byId("open-seats").disabled = !threadId || busy();
  document.querySelectorAll("[data-prompt],.confirm-operation,.seat").forEach((button) => {
    button.disabled = busy() || button.classList.contains("occupied");
  });
}

function render(snapshot) {
  if (snapshot.threadId !== threadId || snapshot.revision < lastRevision) return;
  state = snapshot;
  lastRevision = snapshot.revision;
  byId("agents").replaceChildren(...snapshot.agents.map((agent) => {
    const element = node("div", agent.name, `agent${agent.id === snapshot.activeAgentId ? " active" : ""}`);
    element.title = `工具：${agent.tools.join(", ")}；交接：${agent.handoffs.join(", ")}`;
    return element;
  }));
  byId("run-status").textContent = `运行：${snapshot.runStatus} · 状态版本 ${snapshot.revision}`;
  const labels = { bookingStatus: "预订状态", passengerName: "演示乘客", confirmationNumber: "演示确认号",
    flightNumber: "操作航班", seatNumber: "当前座位", vouchers: "演示凭证", seatMapVersion: "座位图版本", demo: "演示" };
  const fields = [];
  Object.entries(snapshot.context).forEach(([key, value]) => {
    fields.push(node("dt", labels[key] || key), node("dd", Array.isArray(value) ? value.join("；") : String(value)));
  });
  byId("context").replaceChildren(...fields);
  byId("guardrails").replaceChildren(...snapshot.guardrails.map((check) =>
    node("div", `${check.name}: ${check.status}${check.reasoning ? ` — ${check.reasoning}` : ""}`, `guardrail ${check.status}`)));
  byId("events").replaceChildren(...snapshot.events.slice().reverse().map((event) => {
    const element = node("div", undefined, "event");
    element.append(node("strong", `#${event.sequence} ${event.type} · ${event.agent}`),
      node("div", event.content));
    return element;
  }));
  byId("messages").replaceChildren(...snapshot.messages.map((message) => node("div", message.content, `message ${message.role}`)));
  byId("messages").scrollTop = byId("messages").scrollHeight;
  byId("operations").replaceChildren(...snapshot.pendingOperations.map((operation) => {
    const element = node("div", undefined, "operation");
    element.append(node("strong", `${toolLabels[operation.tool] || operation.tool}（演示）`),
      node("p", `航班：${operation.booking.flightNumber} · 演示确认号：${operation.booking.confirmationNumber}`),
      node("pre", JSON.stringify(operation.arguments, null, 2)),
      node("p", `有效期至 ${new Date(operation.expiresAt).toLocaleTimeString()}`));
    const button = node("button", "确认此演示操作", "confirm-operation");
    button.addEventListener("click", () => confirmOperation(operation.id));
    element.append(button);
    return element;
  }));
  if (!snapshot.pendingOperations.length) byId("operations").append(node("p", "暂无待确认操作", "hint"));
  if (seats && seats.version !== snapshot.context.seatMapVersion) {
    seats = null;
    byId("seat-map").replaceChildren();
    byId("seat-status").textContent = "状态已变化，请重新打开座位图。";
  }
  const lastOpen = snapshot.events.findLast((event) => event.type === "tool_call" && event.content === "display_seat_map");
  if (lastOpen && !seats && !busy()) void openSeats();
  controls();
}

async function refresh(id = threadId) {
  if (!id) return;
  const snapshot = await api(threadPath(id));
  if (id === threadId) render(snapshot);
}

function bind(snapshot) {
  stream?.close();
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
    catch { notice("状态事件格式错误，请刷新页面。"); }
  });
  stream.onopen = () => {
    if (boundId === threadId) byId("connection").textContent = "状态流已连接 · 断线后自动获取完整快照";
  };
  stream.onerror = () => {
    if (boundId === threadId) byId("connection").textContent = "状态流断开，正在尝试重连…";
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
  if (!threadId || busy()) return;
  const id = threadId;
  sending = true;
  controls();
  notice();
  try {
    const reply = await api(`${threadPath(id)}/messages`, "POST", { requestId: crypto.randomUUID(), message });
    if (id === threadId) {
      if (reply.status !== "COMPLETED") notice(reply.message);
      byId("message").value = "";
      await refresh(id);
    }
  } catch (error) { notice(error.message); }
  finally {
    sending = false;
    controls();
    if (state?.events.some((event) => event.type === "tool_call" && event.content === "display_seat_map") && !seats) {
      void openSeats();
    }
  }
}

async function confirmOperation(operationId) {
  if (busy()) return;
  sending = true;
  controls();
  try {
    const result = await api(`${threadPath()}/operations/confirm`, "POST", { operationId });
    notice(result.userMessage);
    await refresh();
  } catch (error) { notice(error.message); }
  finally { sending = false; controls(); }
}

async function openSeats() {
  if (!threadId || busy()) return;
  const id = threadId;
  try {
    const data = await api(`${threadPath(id)}/seats`);
    if (id !== threadId) return;
    seats = data;
    byId("seat-status").textContent = `演示航班 ${data.flightNumber} · 版本 ${data.version}`;
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
        button.disabled = occupied || busy();
        button.title = `${seat.number} ${seat.cabin}${seat.exitRow ? " 出口排" : ""}`;
        button.addEventListener("click", () => selectSeat(seat.number, data.version));
        element.append(button);
      });
      rows.push(element);
    }
    byId("seat-map").replaceChildren(...rows);
  } catch (error) { notice(error.message); }
}

async function selectSeat(seatNumber, version) {
  if (busy()) return;
  sending = true;
  controls();
  try {
    const result = await api(`${threadPath()}/seats`, "POST", { seatNumber, version });
    notice(result.userMessage);
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
