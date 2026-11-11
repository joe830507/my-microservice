#!/usr/bin/env python3
"""產生 Grafana 儀表板 JSON（infra/observability/dashboards/my-microservice-overview.json）。

儀表板用程式產生，因為 Grafana 匯出的 JSON 很長而且難以審查；要改面板就改這個檔案再執行：
    python infra/observability/generate_dashboard.py
資料來源的 uid 是 grafana/otel-lgtm 內建的（prometheus、loki、tempo）。
"""
import json
import pathlib

PROM = {"type": "prometheus", "uid": "prometheus"}
LOKI = {"type": "loki", "uid": "loki"}
_next_id = 0


def _id():
    global _next_id
    _next_id += 1
    return _next_id


def stat(title, expr, x, y, w=4, h=4, unit="short", thresholds=None, desc=""):
    return {
        "id": _id(), "type": "stat", "title": title, "description": desc, "datasource": PROM,
        "gridPos": {"x": x, "y": y, "w": w, "h": h},
        "targets": [{"expr": expr, "refId": "A", "datasource": PROM, "instant": True}],
        "fieldConfig": {"defaults": {"unit": unit, "noValue": "0",
                                      "thresholds": thresholds or {"mode": "absolute", "steps": [{"color": "green", "value": None}]}}},
        "options": {"reduceOptions": {"calcs": ["lastNotNull"]}, "colorMode": "value"},
    }


def series(title, targets, x, y, w=12, h=8, unit="short", desc="", stack=False):
    return {
        "id": _id(), "type": "timeseries", "title": title, "description": desc, "datasource": PROM,
        "gridPos": {"x": x, "y": y, "w": w, "h": h},
        "targets": [{"expr": e, "legendFormat": l, "refId": chr(65 + i), "datasource": PROM}
                    for i, (e, l) in enumerate(targets)],
        "fieldConfig": {"defaults": {"unit": unit, "custom": {"fillOpacity": 15 if stack else 0,
                                                              "stacking": {"mode": "normal" if stack else "none"}}}},
        "options": {"legend": {"displayMode": "table", "placement": "bottom", "calcs": ["lastNotNull", "max"]}},
    }


def row(title, y):
    return {"id": _id(), "type": "row", "title": title, "collapsed": False, "gridPos": {"x": 0, "y": y, "w": 24, "h": 1}}


# 排除 actuator（探針與指標抓取），否則每個服務永遠有一條基準流量
HTTP = 'http_server_requests_milliseconds_count{uri!~"/actuator.*"}'
HTTP_BUCKET = 'http_server_requests_milliseconds_bucket{uri!~"/actuator.*"}'

panels = []
y = 0
panels.append(row("業務（過去 1 小時）", y)); y += 1
red = {"mode": "absolute", "steps": [{"color": "green", "value": None}, {"color": "red", "value": 1}]}
panels += [
    stat("建單成功", 'sum(increase(orders_created_total{result="created"}[1h]))', 0, y, desc="orders.created{result=created}"),
    stat("建單重送（冪等）", 'sum(increase(orders_created_total{result="replayed"}[1h]))', 4, y,
         desc="同一個 Idempotency-Key 的重送，回原本的訂單"),
    stat("付款成功", 'sum(increase(payments_completed_total{result="succeeded"}[1h]))', 8, y),
    stat("付款失敗", 'sum(increase(payments_completed_total{result="failed"}[1h]))', 12, y),
    stat("預扣被拒（庫存不足）", 'sum(increase(reservations_total{result="rejected"}[1h]))', 16, y, thresholds=red),
    stat("逾時取消", 'sum(increase(orders_cancelled_total{reason="timeout"}[1h]))', 20, y),
]
y += 4
panels.append(series("訂單與付款（每分鐘）", [
    ('sum(rate(orders_created_total{result="created"}[5m])) * 60', "建單"),
    ('sum(rate(orders_paid_total[5m])) * 60', "已付款"),
    ('sum(rate(orders_cancelled_total[5m])) by (reason) * 60', "取消 {{reason}}"),
    ('sum(rate(payments_completed_total[5m])) by (result) * 60', "付款 {{result}}"),
], 0, y, 12, 8))
panels.append(series("庫存預扣結果（每分鐘）", [
    ('sum(rate(reservations_total[5m])) by (result) * 60', "{{result}}"),
], 12, y, 12, 8))
y += 8

panels.append(row("服務（RED：請求數、錯誤率、延遲）", y)); y += 1
panels.append(series("請求數（每秒）", [
    (f'sum by (service_name) (rate({HTTP}[1m]))', "{{service_name}}"),
], 0, y, 8, 8, unit="reqps"))
panels.append(series("5xx 錯誤率", [
    (f'sum by (service_name) (rate({HTTP[:-1]},status=~"5.."}}[1m])) / sum by (service_name) (rate({HTTP}[1m]))', "{{service_name}}"),
], 8, y, 8, 8, unit="percentunit", desc="5xx 佔所有請求的比例；4xx 是呼叫端的問題，不算在這裡"))
panels.append(series("延遲 p95", [
    (f'histogram_quantile(0.95, sum by (le, service_name) (rate({HTTP_BUCKET}[2m])))', "{{service_name}}"),
], 16, y, 8, 8, unit="ms"))
y += 8
panels.append(series("最慢的 API（p95，依 uri）", [
    (f'topk(8, histogram_quantile(0.95, sum by (le, service_name, uri) (rate({HTTP_BUCKET}[5m]))))', "{{service_name}} {{uri}}"),
], 0, y, 12, 8, unit="ms"))
panels.append(series("服務之間的呼叫（出去，p95）", [
    ('histogram_quantile(0.95, sum by (le, service_name, client_name) (rate(http_client_requests_milliseconds_bucket[2m])))',
     "{{service_name}} → {{client_name}}"),
], 12, y, 12, 8, unit="ms", desc="order → product、inventory；payment → order；Gateway → 各服務"))
y += 8

panels.append(row("排程與資源", y)); y += 1
panels.append(series("排程執行（每 5 分鐘）", [
    ('sum by (service_name, code_namespace) (increase(tasks_scheduled_execution_milliseconds_count{outcome="SUCCESS"}[5m]))', "成功 {{service_name}}"),
    ('sum by (service_name) (increase(tasks_scheduled_execution_milliseconds_count{outcome!="SUCCESS"}[5m]))', "失敗 {{service_name}}"),
], 0, y, 8, 8))
panels.append(series("JVM 堆積使用量", [
    ('sum by (service_name) (jvm_memory_used_bytes{area="heap"})', "{{service_name}}"),
], 8, y, 8, 8, unit="bytes"))
panels.append(series("資料庫連線（使用中 / 等待中）", [
    ('hikaricp_connections_active', "使用中 {{service_name}}"),
    ('hikaricp_connections_pending', "等待 {{service_name}}"),
], 16, y, 8, 8))
y += 8

panels.append(row("Log（warn、error）", y)); y += 1
panels.append({
    "id": _id(), "type": "logs", "title": "warn / error", "datasource": LOKI,
    "gridPos": {"x": 0, "y": y, "w": 24, "h": 10},
    "targets": [{"expr": '{service_name=~".+"} | detected_level=~"warn|error"', "refId": "A", "datasource": LOKI}],
    "options": {"showTime": True, "wrapLogMessage": True, "enableLogDetails": True, "sortOrder": "Descending"},
})

dashboard = {
    "uid": "my-microservice-overview", "title": "my-microservice 總覽", "tags": ["my-microservice"],
    "timezone": "browser", "schemaVersion": 39, "version": 1, "refresh": "30s",
    "time": {"from": "now-1h", "to": "now"}, "panels": panels,
    "templating": {"list": []}, "annotations": {"list": []},
    "description": "服務的 RED 指標、業務數字、排程、資源與 warn/error log。log 與追蹤互通：在 Explore 用 trace_id 查。",
}

out = pathlib.Path(__file__).parent / "dashboards" / "my-microservice-overview.json"
out.parent.mkdir(exist_ok=True)
out.write_text(json.dumps(dashboard, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"wrote {out} ({len(panels)} panels)")


# ---------- 告警規則（Grafana provisioning；JSON 是 YAML 的子集，所以直接寫成 .yaml） ----------
def rule(uid, title, expr, op, threshold, for_, summary, severity="warning", no_data="OK"):
    return {
        "uid": uid, "title": title, "condition": "C", "for": for_,
        "noDataState": no_data, "execErrState": "Error",
        "annotations": {"summary": summary}, "labels": {"severity": severity},
        "data": [
            {"refId": "A", "relativeTimeRange": {"from": 900, "to": 0}, "datasourceUid": "prometheus",
             "model": {"refId": "A", "expr": expr, "instant": True, "intervalMs": 1000, "maxDataPoints": 43200}},
            {"refId": "B", "datasourceUid": "__expr__",
             "model": {"refId": "B", "type": "reduce", "expression": "A", "reducer": "last"}},
            {"refId": "C", "datasourceUid": "__expr__",
             "model": {"refId": "C", "type": "threshold", "expression": "B",
                       "conditions": [{"evaluator": {"type": op, "params": [threshold]}}]}},
        ],
    }


alerts = {"apiVersion": 1, "groups": [{
    "orgId": 1, "name": "my-microservice", "folder": "my-microservice", "interval": "1m",
    "rules": [
        rule("mm-5xx-rate", "服務 5xx 錯誤率偏高",
             f'sum by (service_name) (rate({HTTP[:-1]},status=~"5.."}}[5m])) / sum by (service_name) (rate({HTTP}[5m]))',
             "gt", 0.05, "5m", "{{ $labels.service_name }} 的 5xx 超過 5%（持續 5 分鐘）", "critical"),
        rule("mm-p95-latency", "服務延遲偏高（p95 > 2 秒）",
             f'histogram_quantile(0.95, sum by (le, service_name) (rate({HTTP_BUCKET}[5m])))',
             "gt", 2000, "5m", "{{ $labels.service_name }} 的 p95 延遲超過 2 秒（持續 5 分鐘）"),
        rule("mm-scheduled-job-failed", "排程執行失敗",
             'sum by (service_name, code_namespace) (increase(tasks_scheduled_execution_milliseconds_count{outcome!="SUCCESS"}[10m]))',
             "gt", 0, "0s", "{{ $labels.service_name }} 的排程失敗（見 log）"),
        rule("mm-no-metrics", "服務沒有回報指標",
             'time() - max by (service_name) (timestamp(process_cpu_usage))',
             "gt", 120, "1m", "{{ $labels.service_name }} 超過 2 分鐘沒有送指標（服務停了，或到 collector 的連線斷了）",
             "critical"),
        rule("mm-reservation-rejected", "預扣被拒偏多",
             'sum(increase(reservations_total{result="rejected"}[10m]))',
             "gt", 20, "0s", "10 分鐘內超過 20 次預扣被拒：庫存不足或被刷"),
    ],
}]}
out2 = pathlib.Path(__file__).parent / "alerting.yaml"
header = "# 由 generate_dashboard.py 產生，不要手動改\n"
out2.write_text(header + json.dumps(alerts, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"wrote {out2} ({len(alerts['groups'][0]['rules'])} rules)")
