#!/usr/bin/env python3
"""AzurPilot for Android 虚拟屏专用性能基准测试与诊断套件。

本工具用于全方位评估 AzurPilot 在 Android 虚拟屏（azurpilot_android 架构）下的
运行性能与通信开销，涵盖：
1. 本地特权桥协议往返延迟 (Ping RTT)
2. 1280x720 RGB 全分辨率单帧捕获与解析耗时 (Screencap Latency)
3. 极限连续捕获吞吐帧率 (Saturated Capture FPS)
4. Linux 内核回环套接字传输带宽 (Loopback Data Bandwidth in MB/s, 100% 内存缓冲，零闪存磨损)
5. 虚拟屏触控点击与手势滑动注入延迟 (Click / Swipe Injection Overhead)
6. 虚拟屏底层渲染刷新与帧冻结检测 (Surface Freshness & Frame Freeze Monitor)

支持两种评测模式：
- 直连模式 (Direct Socket)：直接通过 127.0.0.1:22301 与特权桥通信，轻量快速，无多余依赖。
- AP 栈集成模式 (AzurPilot Stack)：通过 AzurPilot Device 与 Benchmark 驱动，评测完整运行链路。
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import socket
import sys
import time
from typing import Any, Dict, List, Optional, Tuple

try:
    import numpy as np
except ImportError:
    np = None

try:
    from rich.console import Console
    from rich.table import Table
    from rich.text import Text
    HAS_RICH = True
except ImportError:
    HAS_RICH = False

FRAME_WIDTH = 1280
FRAME_HEIGHT = 720
FRAME_CHANNELS = 3
FRAME_BYTES = FRAME_WIDTH * FRAME_HEIGHT * FRAME_CHANNELS  # 2,764,800 bytes (~2.6367 MB)


def evaluate_ping_rating(cost: float) -> Tuple[str, str]:
    """评估特权桥 Ping RTT 等级 (耗时为秒)。"""
    if cost < 0.002:
        return "Insane Fast", "bold green"
    if cost < 0.005:
        return "Ultra Fast", "bold green"
    if cost < 0.010:
        return "Very Fast", "green"
    if cost < 0.020:
        return "Fast", "green"
    if cost < 0.050:
        return "Medium", "yellow"
    return "Slow", "red"


def evaluate_screencap_rating(cost: float) -> Tuple[str, str]:
    """评估单帧截取耗时等级 (耗时为秒)。"""
    if cost < 0.025:
        return "Insane Fast (>40 FPS)", "bold green"
    if cost < 0.050:
        return "Ultra Fast (>20 FPS)", "bold green"
    if cost < 0.100:
        return "Very Fast (>10 FPS)", "green"
    if cost < 0.200:
        return "Fast", "green"
    if cost < 0.400:
        return "Medium", "yellow"
    return "Slow", "red"


def evaluate_fps_rating(fps: float) -> Tuple[str, str]:
    """评估截图极限吞吐帧率等级。"""
    if fps >= 40.0:
        return "Insane Fast", "bold green"
    if fps >= 25.0:
        return "Ultra Fast", "bold green"
    if fps >= 15.0:
        return "Very Fast", "green"
    if fps >= 10.0:
        return "Fast", "green"
    return "Medium", "yellow"


def evaluate_bandwidth_rating(mb_s: float) -> Tuple[str, str]:
    """评估回环数据传输带宽等级。"""
    if mb_s >= 100.0:
        return "Insane High", "bold green"
    if mb_s >= 60.0:
        return "Ultra High", "bold green"
    if mb_s >= 30.0:
        return "Very High", "green"
    if mb_s >= 15.0:
        return "High", "green"
    return "Medium", "yellow"


def evaluate_injection_rating(overhead_ms: float) -> Tuple[str, str]:
    """评估触控协议净通信开销等级。"""
    if overhead_ms < 5.0:
        return "Insane Fast", "bold green"
    if overhead_ms < 15.0:
        return "Ultra Fast", "bold green"
    if overhead_ms < 30.0:
        return "Fast", "green"
    if overhead_ms < 60.0:
        return "Medium", "yellow"
    return "Slow", "red"


class DirectBridgeClient:
    """与 Android 特权桥通信的直连客户端。"""

    def __init__(self, host: str = "127.0.0.1", port: int = 22301, timeout: float = 10.0):
        self.host = host
        self.port = port
        self.timeout = timeout
        self.sock: Optional[socket.socket] = None
        self.req_id = 0

    def connect(self) -> None:
        """建立套接字连接。"""
        if self.sock is not None:
            try:
                self.sock.close()
            except OSError:
                pass
        self.sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
        self.sock.settimeout(self.timeout)

    def close(self) -> None:
        """关闭连接。"""
        if self.sock:
            try:
                self.sock.close()
            except OSError:
                pass
            self.sock = None

    def call(self, method: str, **kwargs: Any) -> Tuple[Dict[str, Any], float]:
        """发送 JSON 请求并接收单行响应，返回 (响应字典, 耗时秒)。"""
        self.req_id += 1
        payload = {"id": self.req_id, "method": method, **kwargs}
        data = json.dumps(payload, separators=(",", ":")).encode("utf-8") + b"\n"

        if self.sock is None:
            self.connect()

        t0 = time.perf_counter()
        assert self.sock is not None
        self.sock.sendall(data)

        # 读取单行 JSON 响应
        buf = bytearray()
        while not buf.endswith(b"\n"):
            chunk = self.sock.recv(1)
            if not chunk:
                raise ConnectionResetError("特权桥服务端意外关闭了连接")
            buf.extend(chunk)
            if len(buf) > 65536:
                raise ValueError("响应头超过 64KB 限制")

        cost = time.perf_counter() - t0
        resp = json.loads(buf.decode("utf-8"))
        if not resp.get("ok"):
            raise RuntimeError(f"特权桥返回错误: {resp.get('error', 'unknown error')}")
        return resp, cost

    def read_exact(self, n: int) -> bytes:
        """从套接字精确读取指定字节数。"""
        assert self.sock is not None
        chunks: List[bytes] = []
        rem = n
        while rem > 0:
            chunk = self.sock.recv(min(1048576, rem))
            if not chunk:
                raise ConnectionResetError("读取图像帧过程中连接意外中断")
            chunks.append(chunk)
            rem -= len(chunk)
        return b"".join(chunks)

    def capture_frame(self) -> Tuple[Dict[str, Any], bytes, float]:
        """抓取一帧完整图像，返回 (响应头, 原始字节, 耗时秒)。"""
        t0 = time.perf_counter()
        resp, _ = self.call("screencap")
        length = int(resp.get("length", FRAME_BYTES))
        raw = self.read_exact(length)
        cost = time.perf_counter() - t0
        return resp, raw, cost


class VirtualDisplayBenchmarkSuite:
    """虚拟屏综合性能评测引擎。"""

    def __init__(self, host: str = "127.0.0.1", port: int = 22301, test_controls: bool = True):
        self.host = host
        self.port = port
        self.test_controls = test_controls
        self.client = DirectBridgeClient(host=host, port=port)

    def run_benchmark(self, rounds: int = 15, stress_rounds: int = 0) -> Dict[str, Any]:
        """执行全套虚拟屏基准测试。"""
        results: Dict[str, Any] = {
            "timestamp": time.time(),
            "target": f"{self.host}:{self.port}",
            "rounds": rounds,
        }

        self.client.connect()
        try:
            # 1. 握手与诊断元数据
            info_resp, _ = self.client.call("ping")
            results["metadata"] = {
                "displayId": info_resp.get("displayId"),
                "uptime": info_resp.get("uptime"),
                "capabilities": info_resp.get("capabilities", {}),
                "captureInfo": info_resp.get("capture", ""),
            }

            # 2. Ping RTT 测试
            ping_latencies: List[float] = []
            for _ in range(rounds):
                _, cost = self.client.call("ping")
                ping_latencies.append(cost)

            sorted_ping = sorted(ping_latencies)
            best_ping_n = max(1, int(rounds * 0.8))
            results["ping"] = {
                "records": ping_latencies,
                "mean_ms": float(sum(sorted_ping[:best_ping_n]) / best_ping_n * 1000),
                "min_ms": float(min(ping_latencies) * 1000),
                "max_ms": float(max(ping_latencies) * 1000),
                "jitter_ms": float((max(ping_latencies) - min(ping_latencies)) * 1000),
            }

            # 3. 连续单帧截取与极速吞吐 (Saturated FPS & Bandwidth)
            sc_latencies: List[float] = []
            frames_tracked: List[int] = []
            t_sat_start = time.perf_counter()
            for _ in range(rounds):
                resp, raw, cost = self.client.capture_frame()
                sc_latencies.append(cost)
                if "frames" in resp:
                    frames_tracked.append(int(resp["frames"]))

            t_sat_total = time.perf_counter() - t_sat_start
            sorted_sc = sorted(sc_latencies)
            best_sc_n = max(1, int(rounds * 0.8))

            fps = float(rounds / t_sat_total) if t_sat_total > 0 else 0.0
            bandwidth_mb_s = float((rounds * FRAME_BYTES) / (1024 * 1024 * t_sat_total)) if t_sat_total > 0 else 0.0

            results["screencap"] = {
                "records": sc_latencies,
                "mean_ms": float(sum(sorted_sc[:best_sc_n]) / best_sc_n * 1000),
                "min_ms": float(min(sc_latencies) * 1000),
                "max_ms": float(max(sc_latencies) * 1000),
                "saturated_fps": fps,
                "bandwidth_mb_s": bandwidth_mb_s,
                "total_time_s": t_sat_total,
            }

            # 4. 触控与手势注入延迟 (安全区域点击与滑动)
            if self.test_controls:
                click_latencies: List[float] = []
                safe_x, safe_y = 200, 20
                for _ in range(min(5, rounds)):
                    _, cost = self.client.call("click", x=safe_x, y=safe_y)
                    click_latencies.append(cost)

                swipe_latencies: List[float] = []
                for _ in range(min(5, rounds)):
                    _, cost = self.client.call(
                        "swipe", x1=safe_x, y1=safe_y, x2=safe_x + 10, y2=safe_y, duration=50
                    )
                    swipe_latencies.append(cost)

                avg_click_ms = float(sum(click_latencies) / len(click_latencies) * 1000) if click_latencies else 0.0
                avg_swipe_ms = float(sum(swipe_latencies) / len(swipe_latencies) * 1000) if swipe_latencies else 0.0

                results["control"] = {
                    "click_mean_ms": avg_click_ms,
                    "click_overhead_ms": max(0.0, avg_click_ms - 50.0),  # 扣除 bridge 内部 50ms hold
                    "swipe_mean_ms": avg_swipe_ms,
                    "swipe_overhead_ms": max(0.0, avg_swipe_ms - 50.0),  # 扣除手势 50ms duration
                }

            # 5. 帧新鲜度检测 (Surface Freshness)
            if len(frames_tracked) >= 2:
                frame_delta = frames_tracked[-1] - frames_tracked[0]
                results["freshness"] = {
                    "initial_frame": frames_tracked[0],
                    "final_frame": frames_tracked[-1],
                    "frame_delta": frame_delta,
                    "is_active": frame_delta > 0,
                }
            else:
                results["freshness"] = {"is_active": False, "frame_delta": 0}

            # 6. 可选高压压力测试 (Stress Test)
            if stress_rounds > 0:
                stress_latencies: List[float] = []
                t_stress_start = time.perf_counter()
                for _ in range(stress_rounds):
                    _, _, cost = self.client.capture_frame()
                    stress_latencies.append(cost)
                t_stress_total = time.perf_counter() - t_stress_start

                stress_fps = float(stress_rounds / t_stress_total)
                stress_bw = float((stress_rounds * FRAME_BYTES) / (1024 * 1024 * t_stress_total))
                results["stress"] = {
                    "rounds": stress_rounds,
                    "total_time_s": t_stress_total,
                    "fps": stress_fps,
                    "bandwidth_mb_s": stress_bw,
                    "mean_ms": float(sum(stress_latencies) / len(stress_latencies) * 1000),
                    "min_ms": float(min(stress_latencies) * 1000),
                    "max_ms": float(max(stress_latencies) * 1000),
                }

        finally:
            self.client.close()

        return results

    def print_report(self, res: Dict[str, Any]) -> None:
        """打印格式化性能报告表格。"""
        ping = res["ping"]
        sc = res["screencap"]
        meta = res.get("metadata", {})
        fresh = res.get("freshness", {})
        ctrl = res.get("control", {})

        ping_rating, ping_color = evaluate_ping_rating(ping["mean_ms"] / 1000)
        sc_rating, sc_color = evaluate_screencap_rating(sc["mean_ms"] / 1000)
        fps_rating, fps_color = evaluate_fps_rating(sc["saturated_fps"])
        bw_rating, bw_color = evaluate_bandwidth_rating(sc["bandwidth_mb_s"])

        if HAS_RICH:
            console = Console()
            table = Table(
                title="AzurPilot for Android 虚拟屏性能综合评测报告",
                show_lines=True,
                header_style="bold bright_cyan",
            )
            table.add_column("测试项目 (Benchmark Item)", style="cyan", no_wrap=True)
            table.add_column("实测数值 (Value)", style="magenta", justify="right")
            table.add_column("性能评级 (Rating)", justify="center")
            table.add_column("详细说明 / 架构上下文 (Details & Architecture)", style="white")

            vid = meta.get("displayId", "N/A")
            table.add_row(
                "Bridge Ping RTT",
                f"{ping['mean_ms']:.2f} ms",
                Text(ping_rating, style=ping_color),
                f"127.0.0.1:22301 本地回环协议交换 | 虚拟屏 ID={vid} (抖动 {ping['jitter_ms']:.2f}ms)",
            )
            table.add_row(
                "Screencap Latency",
                f"{sc['mean_ms']:.2f} ms",
                Text(sc_rating, style=sc_color),
                f"1280x720 RGB 单帧截取与解析 (极值: {sc['min_ms']:.1f}ms ~ {sc['max_ms']:.1f}ms)",
            )
            table.add_row(
                "Saturated FPS",
                f"{sc['saturated_fps']:.1f} FPS",
                Text(fps_rating, style=fps_color),
                f"极速连续捕获吞吐能力 (总耗时: {sc['total_time_s']:.2f}s, {res['rounds']} 帧)",
            )
            table.add_row(
                "Loopback Bandwidth",
                f"{sc['bandwidth_mb_s']:.1f} MB/s",
                Text(bw_rating, style=bw_color),
                "Linux 内核套接字纯内存缓冲传输 (100% 内存交换，零物理闪存磨损)",
            )

            if ctrl:
                click_rate, click_color = evaluate_injection_rating(ctrl["click_overhead_ms"])
                swipe_rate, swipe_color = evaluate_injection_rating(ctrl["swipe_overhead_ms"])
                table.add_row(
                    "Click Injection",
                    f"{ctrl['click_mean_ms']:.2f} ms",
                    Text(click_rate, style=click_color),
                    f"含特权桥标称 50ms 保持时长 (净网络注入开销: ~{ctrl['click_overhead_ms']:.1f}ms)",
                )
                table.add_row(
                    "Swipe Injection",
                    f"{ctrl['swipe_mean_ms']:.2f} ms",
                    Text(swipe_rate, style=swipe_color),
                    f"含手势标称 50ms 持续时长 (净网络注入开销: ~{ctrl['swipe_overhead_ms']:.1f}ms)",
                )

            if fresh.get("is_active"):
                table.add_row(
                    "Surface Freshness",
                    f"+{fresh['frame_delta']} 帧",
                    Text("Healthy", style="bold green"),
                    f"ImageReader 持续推送新帧 (帧序 {fresh['initial_frame']} -> {fresh['final_frame']})",
                )
            else:
                table.add_row(
                    "Surface Freshness",
                    "静止 (+0 帧)",
                    Text("Static", style="yellow"),
                    "画面内容无变化或被系统节电冻结",
                )

            if "stress" in res:
                st = res["stress"]
                st_fps_rate, st_fps_color = evaluate_fps_rating(st["fps"])
                table.add_row(
                    f"Stress Test ({st['rounds']}F)",
                    f"{st['fps']:.1f} FPS",
                    Text(st_fps_rate, style=st_fps_color),
                    f"高压连续抓帧: 均值 {st['mean_ms']:.1f}ms | 吞吐 {st['bandwidth_mb_s']:.1f} MB/s",
                )

            console.print()
            console.print(table)
            console.print()
        else:
            print("\n" + "=" * 78)
            print("         AzurPilot for Android 虚拟屏性能综合评测报告")
            print("=" * 78)
            print(f"[*] Bridge Ping RTT     : {ping['mean_ms']:6.2f} ms  [{ping_rating}]")
            print(f"[*] Screencap Latency   : {sc['mean_ms']:6.2f} ms  [{sc_rating}]")
            print(f"[*] Saturated FPS       : {sc['saturated_fps']:6.1f} FPS [{fps_rating}]")
            print(f"[*] Loopback Bandwidth  : {sc['bandwidth_mb_s']:6.1f} MB/s [{bw_rating}] (纯内存回环传输)")
            if ctrl:
                print(f"[*] Click Latency       : {ctrl['click_mean_ms']:6.2f} ms  (净开销: ~{ctrl['click_overhead_ms']:.1f}ms)")
                print(f"[*] Swipe Latency       : {ctrl['swipe_mean_ms']:6.2f} ms  (净开销: ~{ctrl['swipe_overhead_ms']:.1f}ms)")
            if fresh.get("is_active"):
                print(f"[*] Surface Freshness   : 活跃 (+{fresh['frame_delta']} 帧, 持续推送新帧)")
            else:
                print(f"[*] Surface Freshness   : 静止/冻结 (+0 帧)")
            if "stress" in res:
                st = res["stress"]
                print(f"[*] Stress Test ({st['rounds']}F)  : {st['fps']:.1f} FPS, {st['bandwidth_mb_s']:.1f} MB/s")
            print("=" * 78 + "\n")


def run_alas_benchmark_mode(rounds: int = 15) -> None:
    """通过 AzurLaneAutoScript 内部 Benchmark 模块运行评测。"""
    print("[*] 正在装载 AzurLaneAutoScript 运行环境...")
    candidates = [
        Path(r"C:\Users\AzurLane\Desktop\Projects\AzurLaneAutoScript"),
        Path.cwd() / "AzurLaneAutoScript",
        Path.cwd().parent / "AzurLaneAutoScript",
    ]
    alas_dir = None
    for c in candidates:
        if (c / "module" / "daemon" / "benchmark.py").is_file():
            alas_dir = c.resolve()
            break

    if not alas_dir:
        raise RuntimeError("未找到 AzurLaneAutoScript 项目根目录，请指定路径。")

    if str(alas_dir) not in sys.path:
        sys.path.insert(0, str(alas_dir))

    import module.config.server as server
    server.server = "cn"
    from module.config.config import AzurLaneConfig
    from module.daemon.benchmark import Benchmark

    cfg = AzurLaneConfig("template")
    cfg.override(
        Emulator_Serial="azurpilot_android",
        Emulator_ScreenshotMethod="azurpilot_android",
        Emulator_ControlMethod="azurpilot_android",
        Benchmark_DeviceType="azurpilot_android",
        Benchmark_TestScene="screenshot_click",
    )
    bm = Benchmark(cfg, task="Benchmark")
    print("[*] 正在执行 Benchmark.benchmark_virtual_display()...")
    bm.benchmark_virtual_display(rounds=rounds)


def main() -> int:
    parser = argparse.ArgumentParser(description="AzurPilot for Android 虚拟屏专项性能评测工具")
    parser.add_argument("--host", default="127.0.0.1", help="特权桥监听地址 (默认 127.0.0.1)")
    parser.add_argument("--port", type=int, default=22301, help="特权桥端口 (默认 22301)")
    parser.add_argument("--rounds", type=int, default=15, help="评测轮数 (默认 15 次)")
    parser.add_argument("--stress", type=int, default=0, help="执行指定帧数的高压连续抓帧测试 (默认 0 关闭)")
    parser.add_argument("--no-control", action="store_true", help="跳过触控与滑动注入测试")
    parser.add_argument("--json", action="store_true", help="输出原始 JSON 数据")
    parser.add_argument("--export", type=str, default="", help="导出结果至指定 JSON 文件")
    parser.add_argument("--mode", choices=["direct", "alas", "auto"], default="auto", help="运行模式: direct (直连桥), alas (走 AP 栈), auto (自动探测)")

    args = parser.parse_args()

    if args.mode == "alas":
        run_alas_benchmark_mode(rounds=args.rounds)
        return 0

    suite = VirtualDisplayBenchmarkSuite(
        host=args.host,
        port=args.port,
        test_controls=not args.no_control,
    )

    try:
        results = suite.run_benchmark(rounds=args.rounds, stress_rounds=args.stress)
    except Exception as e:
        if args.mode == "auto":
            # 尝试通过 AP 栈回退
            try:
                run_alas_benchmark_mode(rounds=args.rounds)
                return 0
            except Exception:
                pass
        print(f"[-] 基准测试失败: {e}", file=sys.stderr)
        return 1

    if args.json:
        print(json.dumps(results, indent=2, ensure_ascii=False))
    else:
        suite.print_report(results)

    if args.export:
        export_path = Path(args.export)
        export_path.parent.mkdir(parents=True, exist_ok=True)
        export_path.write_text(json.dumps(results, indent=2, ensure_ascii=False), encoding="utf-8")
        print(f"[+] 测试报告已导出至: {export_path}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
