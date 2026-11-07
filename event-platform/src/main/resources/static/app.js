import { createApp } from "/vendor/vue.js";
createApp({
  data() {
    return {
      account: JSON.parse(sessionStorage.getItem("account") || "null"),
      username: "alice",
      password: "",
      page: "discover",
      busy: false,
      error: "",
      notice: "",
      sessions: [],
      bookings: [],
      orderDetails: {},
      notifications: [],
      ticket: null,
      checkToken: "",
      waitSession: "",
      waitlist: [],
      draft: {
        title: "",
        description: "",
        sessionTitle: "",
        capacity: 10,
        priceCents: 0,
        holdSeconds: 300,
      },
      checkerUser: "",
      summaries: [],
      failedMessages: [],
      replayReason: "",
      accountEpoch: 0,
      headings: {
        ops: "消息恢复",
        checkin: "入场核验",
        discover: "发现下一场活动",
        orders: "我的预约",
        manage: "活动管理",
        notifications: "通知",
      },
      labels: {
        ACCEPTED: "已受理，等待分配",
        WAITING: "候补中",
        HELD: "待确认名额",
        CONFIRMED: "预约成功",
        CANCELLED: "已取消",
        EXPIRED: "已过期",
        REFUNDED: "已模拟退款",
        OFFERED: "已发出候补邀请",
        CHECKED_IN: "已入场",
        INVALID: "已失效",
        ISSUED: "可入场",
      },
    };
  },
  computed: {
    tabs() {
      return [
        { id: "discover", label: "发现活动" },
        { id: "orders", label: "我的预约" },
        ...(this.account?.role === "ORGANIZER"
          ? [{ id: "manage", label: "活动管理" }]
          : []),
        ...(this.account?.role === "CHECKER"
          ? [{ id: "checkin", label: "入场核验" }]
          : []),
        ...(this.account?.role === "OPERATOR"
          ? [{ id: "ops", label: "消息恢复" }]
          : []),
        { id: "notifications", label: "通知" },
      ];
    },
  },
  methods: {
    async api(path, options = {}) {
      const epoch = this.accountEpoch;
      const headers = {
        "Content-Type": "application/json",
        ...(this.account
          ? { Authorization: "Bearer " + this.account.accessToken }
          : {}),
        ...options.headers,
      };
      const response = await fetch("/api" + path, {
        ...options,
        headers,
      });
      if (epoch !== this.accountEpoch) throw Error("会话已切换，请重新操作");
      if (!response.ok) {
        let message = "服务暂不可用";
        try {
          const body = await response.json();
          message = body.message || body.detail || message;
        } catch {}
        if (response.status === 401 && this.account) {
          this.logout();
        }
        throw Error(message);
      }
      return response.status === 204 ? null : response.json();
    },
    async action(fn) {
      this.error = "";
      this.busy = true;
      try {
        return await fn();
      } catch (e) {
        this.error = e.message;
      } finally {
        this.busy = false;
      }
    },
    async login() {
      await this.action(async () => {
        this.account = await this.api("/auth/login", {
          method: "POST",
          body: JSON.stringify({
            username: this.username,
            password: this.password,
          }),
        });
        this.accountEpoch++;
        sessionStorage.setItem("account", JSON.stringify(this.account));
        this.password = "";
        await this.refresh();
      });
    },
    logout() {
      this.accountEpoch++;
      this.draft = {
        title: "",
        description: "",
        sessionTitle: "",
        capacity: 10,
        priceCents: 0,
        holdSeconds: 300,
      };
      this.waitSession = "";
      this.waitlist = [];
      this.checkToken = "";
      this.checkerUser = "";
      this.summaries = [];
      this.failedMessages = [];
      this.account = null;
      sessionStorage.removeItem("account");
      this.page = "discover";
      this.bookings = [];
      this.orderDetails = {};
      this.ticket = null;
      this.notifications = [];
      this.notice = "";
      this.error = "";
    },
    async refresh() {
      if (!this.account) return;
      await this.action(async () => {
        this.sessions = await this.api("/sessions");
        if (this.page === "orders") {
          this.bookings = await this.api("/booking-requests");
          for (const b of this.bookings) {
            if (b.orderId)
              this.orderDetails[b.id] = await this.api("/orders/" + b.orderId);
          }
        }
        if (this.page === "notifications")
          this.notifications = await this.api("/notifications");
        if (this.page === "ops")
          this.failedMessages = await this.api("/ops/outbox");
      });
    },
    money(cents) {
      return cents === 0 ? "免费" : "¥" + (cents / 100).toFixed(2);
    },
    time(value) {
      return new Date(value + "Z").toLocaleString("zh-CN");
    },
    sessionTitle(id) {
      return this.sessions.find((s) => s.id === id)?.title || "活动场次";
    },
    summaryText(summary) {
      if (summary.status !== "OK") return "暂不可用，请稍后重试";
      const { requests, waiting, checkedIn } = summary.counts;
      return `请求 ${requests} / 候补 ${waiting} / 已核验 ${checkedIn}`;
    },
    async loadSummaries() {
      await this.action(async () => {
        const ids = this.sessions
          .filter((s) => s.ownerId === this.account.user)
          .slice(0, 20)
          .map((s) => s.id);
        if (!ids.length) {
          this.summaries = [];
          return;
        }
        this.summaries = await this.api("/organizer/session-summaries", {
          method: "POST",
          body: JSON.stringify({ sessionIds: ids }),
        });
      });
    },
    async replayMessage(e) {
      if (!this.replayReason.trim()) {
        this.error = "请填写排查结果与重放原因";
        return;
      }
      await this.action(async () => {
        await this.api("/ops/outbox/" + e.id + "/replay", {
          method: "POST",
          body: JSON.stringify({ reason: this.replayReason }),
        });
        this.notice = "已提交重放，原事件编号保留";
        await this.refresh();
      });
    },
    async stableKey(slot, body) {
      const epoch = this.accountEpoch,
        user = this.account?.user;
      if (!user) throw Error("请先登录");
      const encoded = new TextEncoder().encode(JSON.stringify(body)),
        digest = await crypto.subtle.digest("SHA-256", encoded),
        signature = Array.from(new Uint8Array(digest), (n) =>
          n.toString(16).padStart(2, "0"),
        ).join("");
      if (epoch !== this.accountEpoch || this.account?.user !== user)
        throw Error("会话已切换，请重新操作");
      const name = "pending-requests:" + user;
      let keys = {};
      try {
        keys = JSON.parse(sessionStorage.getItem(name) || "{}");
      } catch {}
      if (!keys[slot] || keys[slot].signature !== signature)
        keys[slot] = { signature, key: crypto.randomUUID() };
      sessionStorage.setItem(name, JSON.stringify(keys));
      return keys[slot].key;
    },
    releaseKey(slot) {
      const name = "pending-requests:" + this.account.user;
      let keys = {};
      try {
        keys = JSON.parse(sessionStorage.getItem(name) || "{}");
      } catch {}
      delete keys[slot];
      sessionStorage.setItem(name, JSON.stringify(keys));
    },
    async reserve(s) {
      await this.action(async () => {
        const slot = "booking:" + s.id,
          key = await this.stableKey(slot, { sessionId: s.id });
        await this.api("/booking-requests", {
          method: "POST",
          headers: { "Idempotency-Key": key },
          body: JSON.stringify({ sessionId: s.id }),
        });
        this.releaseKey(slot);
        this.notice = "已受理，请到“我的预约”查看分配结果";
        this.page = "orders";
        await this.refresh();
      });
    },
    async confirm(b) {
      await this.action(async () => {
        const order =
          this.orderDetails[b.id] || (await this.api("/orders/" + b.orderId));
        await this.api(
          "/orders/" +
            b.orderId +
            (order.priceCents > 0 ? "/simulate-payment" : "/confirm"),
          { method: "POST" },
        );
        await this.refresh();
      });
    },
    async cancel(b) {
      await this.action(async () => {
        await this.api("/booking-requests/" + b.id + "/cancel", {
          method: "POST",
        });
        await this.refresh();
      });
    },
    async showTicket(b) {
      await this.action(async () => {
        this.ticket = await this.api("/orders/" + b.orderId + "/ticket");
      });
    },
    async publish() {
      await this.action(async () => {
        const key = await this.stableKey("publish", this.draft);
        await this.api("/activities/publish", {
          method: "POST",
          headers: { "Idempotency-Key": key },
          body: JSON.stringify(this.draft),
        });
        this.releaseKey("publish");
        this.notice = "活动场次已发布";
        await this.refresh();
      });
    },
    async checkIn() {
      await this.action(async () => {
        await this.api("/check-in", {
          method: "POST",
          body: JSON.stringify({ token: this.checkToken }),
        });
        this.notice = "核验成功，可以入场";
        this.checkToken = "";
      });
    },
    async loadWaitlist() {
      if (this.waitSession)
        await this.action(async () => {
          this.waitlist = await this.api(
            "/sessions/" + this.waitSession + "/waitlist",
          );
        });
    },
    async setChecker(grant) {
      await this.action(async () => {
        await this.api("/sessions/" + this.waitSession + "/checkers", {
          method: "POST",
          body: JSON.stringify({ userId: this.checkerUser, grant }),
        });
        this.notice = grant ? "已授予该场次核验权限" : "已撤销该场次核验权限";
      });
    },
  },
  mounted() {
    this.refresh();
    this.timer = setInterval(() => {
      if (this.account && this.page === "orders" && !this.busy)
        this.refresh();
    }, 5000);
  },
  beforeUnmount() {
    clearInterval(this.timer);
    this.stopStream();
  },
}).mount("#app");
