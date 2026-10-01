import csv, json, os, sys
from datetime import date, timedelta


def load(path):
    rows = {}
    with open(path, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            rows[r["order_id"]] = round(float(r["amount"]), 2)
    return rows


orders_path = os.environ.get("RECON_ORDERS_CSV")   # 交易库昨日导出
settle_path = os.environ.get("RECON_SETTLE_CSV")   # 清算库昨日导出

if not orders_path or not settle_path:
    json.dump({"error": "请设置 RECON_ORDERS_CSV 与 RECON_SETTLE_CSV 指向昨日两库导出"},
              sys.stdout, ensure_ascii=False)
    sys.exit(0)

orders, settle, diffs = load(orders_path), load(settle_path), []
for oid in orders.keys() - settle.keys():
    diffs.append({"order_id": oid, "kind": "missing_in_settle",
                  "detail": "交易库有、清算库无（%.2f）" % orders[oid]})
for oid in settle.keys() - orders.keys():
    diffs.append({"order_id": oid, "kind": "missing_in_orders",
                  "detail": "清算库有、交易库无（%.2f）" % settle[oid]})
for oid in orders.keys() & settle.keys():
    if orders[oid] != settle[oid]:
        diffs.append({"order_id": oid, "kind": "amount_mismatch",
                      "detail": "金额不符：交易 %.2f vs 清算 %.2f" % (orders[oid], settle[oid])})

json.dump({
    "date": (date.today() - timedelta(days=1)).isoformat(),
    "orders_count": len(orders), "settle_count": len(settle),
    "orders_amount": round(sum(orders.values()), 2),
    "settle_amount": round(sum(settle.values()), 2),
    "diffs": sorted(diffs, key=lambda d: d["order_id"]),
}, sys.stdout, ensure_ascii=False)
