const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const { JSDOM } = require("jsdom");

const staticDir = path.resolve(__dirname, "../../main/resources/static");
function page() {
  const dom = new JSDOM(fs.readFileSync(path.join(staticDir, "index.html"), "utf8"), {
    url: "http://localhost/", runScripts: "outside-only", pretendToBeVisual: true,
  });
  dom.execute = code => vm.runInContext(code, dom.getInternalVMContext());
  dom.window.HTMLDialogElement.prototype.showModal = function () { this.open = true; };
  dom.window.HTMLDialogElement.prototype.close = function () { this.open = false; };
  for (const file of ["vendor/marked.js", "vendor/purify.js", "app.js"]) {
    dom.execute(fs.readFileSync(path.join(staticDir, file), "utf8").replace("void initialize();", ""));
  }
  return dom;
}

test("seat map opens only from a message action and preserves selection/confirmation inside drawer", async () => {
  const dom = page();
  dom.execute('threadId = "seat-thread";');
  let snapshot = {
    threadId: "seat-thread", revision: 1, runStatus: "COMPLETED", agents: [],
    context: { seatMapVersion: 1 }, guardrails: [], events: [], knowledgeHistory: [],
    messages: [{ role: "assistant", content: "请选择座位", sourceIds: [], uiActions: ["OPEN_SEAT_MAP"] }],
    insights: null, suggestedQuestions: [], humanCases: [], pendingOperations: [],
  };
  const seats = Array.from({ length: 24 }, (_, i) => {
    const row = i + 1;
    return [...(row <= 4 ? "ABCD" : "ABCDEF")].map(letter => ({
      number: `${row}${letter}`, cabin: "演示舱位", occupied: row === 1 && letter === "A", exitRow: row === 4,
    }));
  }).flat();
  const requests = [];
  dom.window.fetch = async (url, options) => {
    requests.push({ url, method: options.method, body: options.body && JSON.parse(options.body) });
    let data;
    if (url.endsWith("/seats") && options.method === "GET") {
      data = { flightNumber: "PA441", selectedSeat: "14C", version: snapshot.context.seatMapVersion, seats };
    } else if (url.endsWith("/seats")) {
      snapshot = { ...snapshot, revision: 2, pendingOperations: [{
        id: "op", tool: "update_seat", booking: { flightNumber: "PA441", confirmationNumber: "IR-D204" },
        arguments: { new_seat: "15C" }, expiresAt: new Date(Date.now() + 60000).toISOString(),
      }] };
      data = { userMessage: "请确认选座申请" };
    } else if (url.endsWith("/operations/confirm")) {
      snapshot = { ...snapshot, revision: 3, pendingOperations: [] };
      data = { userMessage: "演示选座已完成" };
    } else data = snapshot;
    return { ok: true, json: async () => data };
  };
  dom.window.render(snapshot);
  const drawer = dom.window.document.getElementById("seat-drawer");
  assert.equal(drawer.open, false);
  assert.equal(requests.length, 0);
  assert.equal(dom.window.document.querySelectorAll("main #seat-map").length, 0);
  assert.ok(dom.window.document.querySelector("#messages .seat-map-action"));
  await dom.window.openSeats();
  assert.equal(drawer.open, true);
  assert.equal(drawer.querySelectorAll(".seat").length, 136);
  assert.equal(drawer.querySelector(".occupied").disabled, true);
  assert.equal(drawer.querySelector(".selected").textContent, "14C");
  await dom.window.selectSeat("15C", 1);
  assert.ok(drawer.querySelector("#seat-operations .confirm-operation"));
  assert.equal(requests.find(r => r.method === "POST").body.seatNumber, "15C");
  await dom.window.confirmOperation("op");
  assert.equal(drawer.querySelectorAll("#seat-operations .confirm-operation").length, 0);
  dom.window.document.getElementById("close-seats").click();
  assert.equal(drawer.open, false);
  await dom.window.openSeats();
  assert.equal(drawer.open, true);
  dom.window.close();
});

const sample = `可改签的替代航班（演示数据，均为次日到达奥斯汀）：

| 航班 | 航段 | 起飞 | 到达 | 状态 | 座位 |
|---|---|---|---|---|---|
| NY950 | 纽约 JFK → 奥斯汀 AUS | 2024-12-10 09:45 | 2024-12-10 12:30 | 演示：可改签 | 2A |
| NY982 | 纽约 JFK → 奥斯汀 AUS | 2024-12-10 13:20 | 2024-12-10 16:05 | 演示：可改签 | 3D |

请问您希望改签到 **NY950** 还是 **NY982**？确认后我会提交改签申请，需要您在页面上确认具体操作。`;

test("consultation tags appear only in the sidebar, not chat bubbles", () => {
  const dom = page();
  dom.execute('threadId = "test-thread";');
  const insights = { mood: "neutral", categories: ["flight", "booking"] };
  dom.window.render({
    threadId: "test-thread", revision: 1, runStatus: "COMPLETED", agents: [],
    context: {}, guardrails: [], events: [], knowledgeHistory: [{
      query: "政策", timestamp: Date.now(), sources: [{ id: "policy", title: "政策资料", snippet: "演示政策" }],
    }],
    messages: [{ role: "assistant", content: "**航班回复**", sourceIds: ["policy"], insights }],
    insights, suggestedQuestions: [], humanCases: [], pendingOperations: [],
  });
  const messages = dom.window.document.getElementById("messages");
  assert.equal(messages.querySelectorAll(".insight-tag").length, 0);
  assert.equal(dom.window.document.querySelectorAll("#insights .insight-tag").length, 3);
  assert.equal(messages.querySelector("strong").textContent, "航班回复");
  assert.equal(messages.querySelector(".source-links button").textContent, "政策资料");
  dom.window.close();
});

test("screenshot text renders tables and Chinese-adjacent emphasis, including short separators", () => {
  const dom = page();
  for (const source of [sample, sample.replaceAll("---", "--")]) {
    const el = dom.window.renderRichText(source);
    assert.equal(el.querySelectorAll("table").length, 1);
    assert.equal(el.querySelectorAll("tbody tr").length, 2);
    assert.equal(el.querySelectorAll("th").length, 6);
    assert.deepEqual([...el.querySelectorAll("strong")].map(el => el.textContent), ["NY950", "NY982"]);
    assert.ok(!el.textContent.includes("|"));
    assert.ok(!el.textContent.includes("**"));
  }
  dom.window.close();
});

test("mixed blocks preserve headings, nested lists, code, quotes and literal line breaks", () => {
  const dom = page();
  const el = dom.window.renderRichText(`**航班状态**
- **PA441** 巴黎
  - 状态：延误
- **NY802** 纽约
  - 状态：错过

**替代航班**
| 航班 | 座位 |
|---|---|
| NY950 | 2A |

## 标题
第一行
第二行

> 引用

1. 有序项
2. 下一项

\`\`\`text
**不是粗体** |---|
\`\`\`

💡 温馨提示：需要**前排座位**请说明。`);
  assert.equal(el.querySelectorAll("ul ul").length, 2);
  assert.equal(el.querySelectorAll("table").length, 1);
  assert.equal(el.querySelector("h2").textContent, "标题");
  assert.ok(el.querySelector("br"));
  assert.ok(!el.textContent.includes("<br>"));
  assert.equal(el.querySelector("pre code").textContent.trim(), "**不是粗体** |---|");
  assert.ok(el.querySelector("blockquote"));
  assert.equal(el.querySelectorAll("ol li").length, 2);
  assert.ok(el.querySelector(".tip-block"));
  dom.window.close();
});

test("Markdown and raw HTML cannot create scripts, active attributes or unsafe links", () => {
  const dom = page();
  const el = dom.window.renderRichText('<script>alert(1)</script><img src=x onerror=alert(1)>'
    + '\n\n[坏链接](javascript:alert(1)) <a href="data:text/html,bad" onclick="bad()">链接</a>'
    + '\n\n[资料](https://example.com)\n\n`**原样**`');
  assert.equal(el.querySelectorAll("script,img,[onclick],[onerror]").length, 0);
  assert.ok([...el.querySelectorAll("[href]")].every(a => a.getAttribute("href").startsWith("https:")));
  assert.equal(el.querySelector("code").textContent, "**原样**");
  dom.window.close();
});

test("reply deltas update before completion and ignore duplicates, stale steps and foreign threads", () => {
  const dom = page();
  dom.execute(`threadId = "test-thread"; lastRevision = 1;
    state = { runStatus: "RUNNING", streamingReply: { runId: "run", step: 1, content: "" } };`);
  const delta = { threadId: "test-thread", runId: "run", step: 1, revision: 2, delta: "**NY" };
  dom.window.receiveReplyDelta(delta);
  dom.window.receiveReplyDelta(delta);
  dom.window.receiveReplyDelta({ ...delta, threadId: "other", revision: 3, delta: "错误" });
  dom.window.receiveReplyDelta({ ...delta, step: 2, revision: 3, delta: "错误" });
  dom.window.renderStreamingReply();
  assert.ok(dom.window.document.querySelector(".streaming-reply").textContent.includes("**NY"));
  dom.window.receiveReplyDelta({ ...delta, revision: 3, delta: "950**\n\n" });
  dom.window.renderStreamingReply();
  assert.equal(dom.window.document.querySelector(".streaming-reply strong").textContent, "NY950");
  dom.execute('state.runStatus = "COMPLETED"');
  dom.window.renderStreamingReply();
  assert.equal(dom.window.document.querySelectorAll(".streaming-reply").length, 0);
  dom.window.close();
});
