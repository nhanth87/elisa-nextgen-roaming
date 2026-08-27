# Nextgen STP — Pending issues (backlog sau P1)

> P1 đã ship (commit 6cb4b11): relay ổn định + admin outer/inner + connection
> cards + Log4j2-only + SCTP-only lab + dist packaging. Các mục dưới xếp theo
> thứ tự ưu tiên đã chốt với owner 2026-08-26.

## B4 — N-N tenant routing (networkId + tenantId) — option (a+b)
- [ ] ACL key `(incomingOpc, networkId)` thay vì OPC-only: diff `SccpIncomingAcl`
      + `IncomingAccessRule` (jSS7) + `StpTransitProfile` (ra-jss7) +
      `stp.json` schema (app). Fix G2: 2 tenants dùng chung OPC không thể tách
      policy, có cross-plane impersonation.
- [ ] Optional association→networkId stamping làm tiebreaker trước content-match
      (fix G1 SAP first-match ambiguity khi trùng localPC/destRange).
- [ ] `TenantRegistry` trong app: consume `tenantId` thật từ plane JSON
      (hiện bị `@JsonIgnoreProperties` discard) — map
      `tenantId → {networkId, OPC/GT ranges, AS/link placement}`.
- [ ] RC uniqueness validation trong `Ss7ConfigLoader`/`Ss7PlaneStore.savePlane`
      (fix G6: cả 2 AS đang `routingContext: 0` — collision khi RC là discriminator).
- [ ] Per-tenant status + KPI labels trong admin (cards theo tenant).
- [ ] RemoteSpc/RemoteSsn state global (G3) — document có chủ đích cho P1;
      cân nhắc per-networkId resource keys khi có tenant thật.
- [ ] Sequence diagram ingress→plane-assignment→GTT→egress (bắt buộc với
      protocol change — root AGENTS.md).
- [ ] SLS budget check per tenant (RUNBOOK §E: ≥9 AS/route → 16 buckets/AS).
- [ ] Fix doc DESIGN.md §3: claim "per-peer NI partition" KHÔNG đúng thực tế
      (wire NI bị ignore trong SAP matching).
- [ ] GttHarness determinism đưa vào live path (RouterExtImpl.findRule vẫn
      HashMap-order cho overlapping patterns trong 1 plane).

## B5 — Security hardening
- [ ] Fail-fast khi phát hiện default secret/creds trong prod profile:
      admin/stp-admin, api-key stp-admin, HMAC `stp-dev-session-hmac-secret-change-me`.
- [ ] `cookie-secure=true` mặc định khi prod; cân nhắc bind loopback mặc định.
- [ ] Minor: dashboard "Transit" card badge DOWN — `transitDetail` chưa được
      cập nhật trong apply path (`setTransitDetail` chưa có call site).
- [ ] Rate-limit/lockout cho /admin/login (hiện không có).
- [ ] TLS cho admin RA socket (hiện plain HTTP).

## B6 — Packaging versioned
- [ ] Version stamp vào dist (hiện 1.0.0-SNAPSHOT, không có build stamp).
- [ ] Checksums (sha256) cho artifacts + manifest.
- [ ] systemd unit template (ussdgw build/systemd có mẫu).
- [ ] dist/configs seed refresh strategy: `install-config` giữ bản cũ có chủ
      đích — cần cơ chế báo operator khi seed mới khác bản đang chạy.

## B7 — Tests + Prove-the-Artifact mở rộng
- [ ] Wire-level integration tests (unblock hop-counter `@Disabled` — chờ
      jvm-harness profile test harness).
- [ ] Multi-ASP loadshare soak với node-2 thật (active-active, RUNBOOK §B drill
      rút gọn; defer từ P1 theo plan).
- [ ] Active-standby đầy đủ (lease renewal + priority election + failback
      command) — defer P2; hiện RUNBOOK §F.2 mô tả control chưa tồn tại.
- [ ] Event producers cho 3 events (AspStateChange/TransitRejected/HaLeaseLost)
      — hiện chỉ wired consumer-side.
- [ ] Regression suite: simulator-driven smoke tự động hoá (hiện manual).

## Đã biết / chấp nhận có chủ đích (không phải backlog)
- KPI `relay.forwarded` vs `gtt.translated` có thể lệch ±1: relay hook fire ở
  send() (gồm route trực tiếp không qua GTT).
- `ss7.live=true` khi ≥1 AS route-ready (aggregate) — per-plane keys phản ánh
  chi tiết từng plane.
- Zombie setsid JVM qua tool-timeout: dọn bằng
  `pgrep -af "quarkus-run.jar" | grep -v "bash -c"` + map inode→pid.
