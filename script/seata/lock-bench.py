#!/usr/bin/env python3
"""
Seata AT 全局锁竞争压测脚本

用法：
  python3 lock-bench.py --mode same     --n 40 --c 10   # 所有请求打同一个账户（同一行 → 全局锁竞争）
  python3 lock-bench.py --mode distinct --n 40 --c 10   # 每个请求打不同账户（无锁竞争）

原理：AT 在分支注册时按 lockKey = 表名:主键 申请全局锁。mode=same 时所有事务抢同一把锁，
后到的事务必须等待，因此尾延迟会明显抬升、吞吐下降；mode=distinct 时锁不冲突，作为对照组。

依赖：aiohttp（pip install aiohttp）
"""

import argparse
import asyncio
import json
import statistics
import time

import aiohttp

LOGIN_URL = "http://127.0.0.1:48080/admin-api/system/auth/login"
ORDER_URL = "http://127.0.0.1:48080/admin-api/system/seata-demo/place-order"
NO_TX_URL = "http://127.0.0.1:48080/admin-api/system/seata-demo/place-order-no-tx"


def percentile(sorted_values, p):
    if not sorted_values:
        return 0.0
    k = (len(sorted_values) - 1) * p / 100.0
    lower = int(k)
    upper = min(lower + 1, len(sorted_values) - 1)
    return sorted_values[lower] + (sorted_values[upper] - sorted_values[lower]) * (k - lower)


async def login(session):
    async with session.post(
        LOGIN_URL,
        json={"username": "admin", "password": "admin123"},
        headers={"Content-Type": "application/json", "tenant-id": "1"},
    ) as resp:
        data = await resp.json()
        return data["data"]["accessToken"]


async def one_order(session, token, order_id, user_id, sem, latencies, errors, url):
    async with sem:
        start = time.perf_counter()
        try:
            async with session.post(
                url,
                params={"orderId": order_id, "userId": user_id, "amount": 1},
                headers={"Authorization": f"Bearer {token}", "tenant-id": "1"},
                timeout=aiohttp.ClientTimeout(total=60),
            ) as resp:
                body = await resp.json()
                cost = (time.perf_counter() - start) * 1000
                latencies.append(cost)
                if body.get("code") != 0:
                    errors.append(body.get("msg") or body.get("code"))
        except Exception as ex:  # noqa: BLE001
            errors.append(str(ex))


async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--n", type=int, default=40)
    parser.add_argument("--c", type=int, default=10)
    parser.add_argument("--mode", choices=["same", "distinct"], default="same")
    parser.add_argument("--user-id", type=int, default=1)
    parser.add_argument("--users", type=int, default=20, help="distinct 模式下可用的账户数")
    parser.add_argument("--endpoint", choices=["tx", "no-tx"], default="tx",
                        help="tx=开启全局事务(AT)；no-tx=同样操作但不开全局事务，用于对照")
    args = parser.parse_args()

    connector = aiohttp.TCPConnector(limit=args.c)
    async with aiohttp.ClientSession(connector=connector) as session:
        token = await login(session)
        sem = asyncio.Semaphore(args.c)
        latencies, errors = [], []
        base = int(time.time() * 1000)
        started = time.perf_counter()
        url = ORDER_URL if args.endpoint == "tx" else NO_TX_URL
        tasks = []
        for i in range(args.n):
            user_id = args.user_id if args.mode == "same" else (i % args.users) + 1
            tasks.append(one_order(session, token, base + i, user_id, sem, latencies, errors, url))
        await asyncio.gather(*tasks)
        wall = time.perf_counter() - started

    latencies.sort()
    result = {
        "endpoint": args.endpoint,
        "mode": args.mode,
        "requests": args.n,
        "concurrency": args.c,
        "success": len(latencies) - len(errors),
        "errors": len(errors),
        "error_sample": errors[:3],
        "wall_seconds": round(wall, 3),
        "throughput_rps": round(args.n / wall, 2),
        "latency_ms": {
            "mean": round(statistics.mean(latencies), 1) if latencies else 0,
            "p50": round(percentile(latencies, 50), 1),
            "p95": round(percentile(latencies, 95), 1),
            "p99": round(percentile(latencies, 99), 1),
            "max": round(max(latencies), 1) if latencies else 0,
        },
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    asyncio.run(main())
