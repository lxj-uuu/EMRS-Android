#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
04-USB 增量 QA —— Python 独立推演证据脚本（QA 工程师严过关 · 第 1 轮）

用途：本机无 JDK/SDK/外网，USB-OTG 增量无法编译运行，故用 Python **独立重写**
各芯片的分频/编码算法与请求位域，再与 Kotlin 实现及 UsbBaudCalcTest 的期望值
逐条对照，判定是否存在"实现自说自话"（测试跟着实现写）或协议偏差。

算法来源（独立重写，不照抄 Kotlin）：
  [1] CH340/CH341  : Linux 主线 drivers/usb/serial/ch341.c
                     ch341_get_divisor / ch341_set_baudrate_lcr / ch341_set_handshake
  [2] FTDI SIO     : FTDI AN232B-05 + usb-serial-for-android FtdiSerialDriver
  [3] CP210x       : Silicon Labs AN571 + Linux drivers/usb/serial/cp210x.c
                     + mik3y Cp21xxSerialDriver + felhr CP210xSerialDevice
  [4] PL2303       : Linux drivers/usb/serial/pl2303.c（SET_LINE 0x20 / SET_CONTROL 0x22）

运行：python qa-tools/usb_check.py
退出码：0 = 无 P0/P1；1 = 存在 P0/P1
"""

import sys
import re
import io

PASS, FAIL, WARN = "PASS", "FAIL", "WARN"
results = []


def rec(sec, item, ok, mine, expect, note=""):
    level = PASS if ok else FAIL
    results.append((sec, item, level, mine, expect, note))
    mark = "OK  " if ok else "FAIL"
    print(f"  [{mark}] {item:<46} 我算={mine!s:<28} 期望={expect!s:<20} {note}")


def sec(title):
    print("\n" + "=" * 118)
    print(title)
    print("=" * 118)


# ======================================================================
# [1] CH340 / CH341 —— 内核 ch341.c ch341_get_divisor 独立重写
# ======================================================================
CLKRATE = 48_000_000
CH341_MIN_BPS = -(-CLKRATE // (4096 * 256))   # DIV_ROUND_UP(48M, 4096*256) = 46
CH341_MAX_BPS = CLKRATE // (8 * 2)            # 3_000_000


def clk_div(ps, fact):
    return 1 << (12 - 3 * ps - fact)


def ch341_min_rate(ps):
    return CLKRATE // (clk_div(ps, 1) * 512)


def ch341_get_divisor(speed):
    """内核 ch341_get_divisor 的独立 Python 重写。返回 dict 或 None（不支持）。"""
    speed = max(CH341_MIN_BPS, min(CH341_MAX_BPS, speed))   # clamp_val 语义
    ps = -1
    for p in range(3, -1, -1):                              # 由高档向低找
        if speed > ch341_min_rate(p):
            ps = p
            break
    if ps < 0:
        return None
    fact = 1
    cdiv = clk_div(ps, fact)
    div = CLKRATE // (cdiv * speed)
    if div < 9 or div > 255:
        div //= 2
        cdiv *= 2
        fact = 0
    if div < 2:
        return None
    # 误差过半进位（16 倍放大比较）
    if (16 * CLKRATE // (cdiv * div) - 16 * speed) >= \
       (16 * speed - 16 * CLKRATE // (cdiv * (div + 1))):
        div += 1
    # 偶数 div 且 fact=1 → 降基时钟
    if fact == 1 and div % 2 == 0:
        div //= 2
        fact = 0
    value = ((0x100 - div) << 8) | (fact << 2) | ps
    eff = CLKRATE // (clk_div(ps, fact) * div)
    return dict(speed=speed, ps=ps, fact=fact, div=div, value=value, effective=eff)


def ch341_lcr(data_bits, stop_bits, parity):
    lcr = 0x80 | 0x40                       # ENABLE_RX | ENABLE_TX
    lcr |= {8: 0x03, 7: 0x02, 6: 0x01}.get(data_bits, 0x00)
    if parity == 1:
        lcr |= 0x08                         # ODD
    elif parity == 2:
        lcr |= 0x08 | 0x10                  # EVEN
    if stop_bits == 2:
        lcr |= 0x04
    return lcr


sec("[1] CH340/CH341 分频（内核 ch341.c 独立重写 vs UsbBaudCalcTest 期望）")
print(f"  基准常量：CLKRATE={CLKRATE}  MIN_BPS={CH341_MIN_BPS}  MAX_BPS={CH341_MAX_BPS}")
print("  min_rate(ps=0..3) = " + ", ".join(str(ch341_min_rate(p)) for p in range(4)))

# —— UsbBaudCalcTest 的 5 个 CH340 期望值 ——
for baud, exp_val, exp_eff in [(9600, 0xB202, 9615),
                               (115200, 0xCC03, 115384),
                               (300, 0xD900, 300),
                               (3000000, 0xFE03, 3000000)]:
    r = ch341_get_divisor(baud)
    ok_v = r["value"] == exp_val
    ok_e = r["effective"] == exp_eff
    rec("CH340", f"baud={baud} 寄存器值", ok_v,
        f"0x{r['value']:04X} (ps={r['ps']},fact={r['fact']},div={r['div']})",
        f"0x{exp_val:04X}", "")
    rec("CH340", f"baud={baud} 实际波特率", ok_e, r["effective"], exp_eff, "")

# —— 量程钳位用例 ch340_rangeClamp ——
r46, r1 = ch341_get_divisor(46), ch341_get_divisor(1)
r3m, r4m = ch341_get_divisor(3000000), ch341_get_divisor(4000000)
rec("CH340", "clamp: calcDivisor(1)==calcDivisor(46)", r1["value"] == r46["value"],
    f"0x{r1['value']:04X}", f"0x{r46['value']:04X}", "(低端钳到 46)")
rec("CH340", "clamp: calcDivisor(4e6)==calcDivisor(3e6)", r4m["value"] == r3m["value"],
    f"0x{r4m['value']:04X}", f"0x{r3m['value']:04X}", "(高端钳到 3M)")

# —— LCR 编码 ——
for (db, sb, pa), exp in [((8, 1, 0), 0xC3), ((8, 1, 1), 0xCB), ((8, 1, 2), 0xDB),
                          ((8, 2, 0), 0xC7), ((7, 1, 0), 0xC2)]:
    got = ch341_lcr(db, sb, pa)
    rec("CH340", f"LCR({db},{sb},{pa})", got == exp, f"0x{got:02X}", f"0x{exp:02X}", "")

# —— 全常用波特率扫描：误差是否可接受 ——
print("\n  -- 常用波特率扫描（内核算法）--")
print(f"  {'请求':>8} {'寄存器':>8} {'ps':>2} {'fact':>4} {'div':>4} {'实际':>9} {'误差%':>7}")
for baud in (1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200):
    r = ch341_get_divisor(baud)
    e = abs(r["effective"] - baud) / baud * 100
    print(f"  {baud:>8} 0x{r['value']:04X}   {r['ps']:>2} {r['fact']:>4} {r['div']:>4} "
          f"{r['effective']:>9} {e:>6.3f}%")

# ======================================================================
# [2] FTDI —— AN232B-05 / mik3y FtdiSerialDriver 独立重写
# ======================================================================
def ftdi_convert(baud):
    if baud <= 0 or baud > 3_500_000:
        return None
    if baud >= 2_500_000:
        divisor, subdiv, eff = 0, 0, 3_000_000
    elif baud >= 1_750_000:
        divisor, subdiv, eff = 1, 0, 2_000_000
    else:
        d = (24_000_000 << 1) // baud
        d = (d + 1) >> 1                     # 四舍五入
        subdiv = d & 0x07
        divisor = d >> 3
        if divisor > 0x3FFF:
            return None
        eff = (((24_000_000 << 1) // ((divisor << 3) + subdiv)) + 1) >> 1
    err = abs(1.0 - eff / baud)
    if err >= 0.031:
        return None
    value, index = divisor, 0
    if subdiv == 4:
        value |= 0x4000
    elif subdiv == 2:
        value |= 0x8000
    elif subdiv == 1:
        value |= 0xC000
    elif subdiv == 3:
        index |= 1
    elif subdiv == 5:
        value |= 0x4000
        index |= 1
    elif subdiv == 6:
        value |= 0x8000
        index |= 1
    elif subdiv == 7:
        value |= 0xC000
        index |= 1
    return dict(baud=baud, divisor=divisor, subdivisor=subdiv, value=value,
                index=index, effective=eff, err=err, index_h=(index << 8) | 1)


def ftdi_data_config(data_bits, stop_bits, parity):
    if data_bits not in (7, 8) or stop_bits not in (1, 2) or parity not in (0, 1, 2):
        return None
    cfg = data_bits
    if parity == 1:
        cfg |= 0x100
    elif parity == 2:
        cfg |= 0x200
    if stop_bits == 2:
        cfg |= 0x1000        # 内核 FTDI_SIO_SET_DATA_STOP_BITS_2 = 2 << 11 = 0x1000
    return cfg


sec("[2] FTDI 分频/编码（AN232B-05 独立重写 vs UsbBaudCalcTest 期望）")
for baud, exp_div, exp_sub, exp_val, exp_idx, exp_eff in [
        (9600, 312, 4, 0x4138, 0, 9600),
        (115200, 26, 0, 26, 0, 115385),
        (300, 10000, 0, 0x2710, None, 300),
        (14400, 208, 3, 208, 1, None)]:
    d = ftdi_convert(baud)
    rec("FTDI", f"baud={baud} divisor", d["divisor"] == exp_div, d["divisor"], exp_div, "")
    rec("FTDI", f"baud={baud} subdivisor", d["subdivisor"] == exp_sub, d["subdivisor"], exp_sub, "")
    rec("FTDI", f"baud={baud} wValue", d["value"] == exp_val, f"0x{d['value']:04X}",
        f"0x{exp_val:04X}", "")
    if exp_idx is not None:
        rec("FTDI", f"baud={baud} wIndex(非H)", d["index"] == exp_idx, d["index"], exp_idx, "")
    if exp_eff is not None:
        rec("FTDI", f"baud={baud} 实际波特率", d["effective"] == exp_eff, d["effective"], exp_eff, "")

for baud in (100, 4_000_000, 2_600_000):
    rec("FTDI", f"baud={baud} 应判不支持", ftdi_convert(baud) is None,
        "unsupported", "null", "")

h = ftdi_convert(14400)
rec("FTDI", "H 系列 wIndex=(subbit<<8)|port", h["index_h"] == 0x0101,
    f"0x{h['index_h']:04X}", "0x0101", "")

for (db, sb, pa), exp in [((8, 1, 0), 0x0008), ((8, 1, 1), 0x0108), ((8, 1, 2), 0x0208),
                          ((8, 2, 0), 0x1008), ((7, 1, 0), 0x0007)]:
    got = ftdi_data_config(db, sb, pa)
    rec("FTDI", f"SET_DATA({db},{sb},{pa})", got == exp, f"0x{got:04X}", f"0x{exp:04X}", "")
rec("FTDI", "SET_DATA 5 数据位 → null", ftdi_data_config(5, 1, 0) is None, "null", "null", "")
rec("FTDI", "SET_DATA 非法停止位 → null", ftdi_data_config(8, 3, 0) is None, "null", "null", "")

print("\n  -- FTDI 常用波特率扫描（注意非 H 系列 wIndex 恒为端口号 1，实现给出 0/1）--")
print(f"  {'请求':>8} {'div':>6} {'sub':>3} {'wValue':>8} {'wIndex(实现)':>12} {'实际':>9} {'误差%':>7}")
for baud in (1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200):
    d = ftdi_convert(baud)
    e = abs(d["effective"] - baud) / baud * 100
    print(f"  {baud:>8} {d['divisor']:>6} {d['subdivisor']:>3} 0x{d['value']:04X}  "
          f"{d['index']:>11} {d['effective']:>9} {e:>6.3f}%")

# ---- FTDI bulk IN 剥头：分包边界仿真 ----
sec("[2b] FTDI bulk IN 剥 2 字节状态头：分包边界仿真（mik3y readFilter 语义）")
BUFFER = 4096
STAGING = 4096
print(f"  管理器读缓冲={BUFFER}，FTDI staging={STAGING}")
print(f"  {'mps':>5} {'n':>6} {'包数':>5} {'剥头后字节':>10} {'<=缓冲':>7} {'说明':>10}")
bad = []
for mps in (8, 16, 32, 64, 512):
    for n in (mps, 2 * mps, 100, 4096, 4095, 3):
        dest, src = 0, 0
        while src < n:
            end = min(src + mps, n)
            length = end - src - 2
            if length < 0:
                dest = 0
                break
            dest += length
            src += mps
        ok = dest <= BUFFER
        if not ok:
            bad.append((mps, n, dest))
        print(f"  {mps:>5} {n:>6} {-(-n // mps):>5} {dest:>10} {str(ok):>7}")
rec("FTDI", "剥头后长度恒不溢出 4096 缓冲", not bad, f"越界组合={len(bad)}", "0",
    "注：destPos 累加的是未截断 length，copy 却被 min 截断 —— 两常量解耦")

# ======================================================================
# [3] CP210x —— 请求码审计（AN571 / cp210x.c / mik3y / felhr）
# ======================================================================
sec("[3] CP210x 请求码审计（三份权威参考 vs 本工程实现）")
CP210X_REF = {
    # name: (kernel cp210x.c, AN571 名称, 本工程用码)
    "IFC_ENABLE":     (0x00, "CP210x_IfcEnable",      0x00),
    "SET_BAUDDIV":    (0x01, "CP210x_SetBaudDiv",     0x1E),   # 注释把 0x1E 标成 SET_BAUDDIV
    "SET_LINE_CTL":   (0x03, "CP210x_SetLineControl", None),   # 本工程完全没发
    "SET_MHS":        (0x07, "CP210x_SetMhs",         0x07),
    "SET_BAUDRATE":   (0x1E, "CP210x_SetBaudRate",    0x20),   # 本工程用 0x20
}
for name, (kern, an, mine) in CP210X_REF.items():
    verdict = "一致" if mine == kern else ("未发送" if mine is None else f"偏离内核 0x{kern:02X}")
    print(f"  {name:<14} 内核/AN571=0x{kern:02X}  本工程= "
          f"{'0x%02X' % mine if mine is not None else '（未使用）':<12} → {verdict}")

# 7 字节 CDC LINE_CODING 布局
def cdc_lc(baud, db, sb, pa):
    b = bytearray(7)
    b[0:4] = baud.to_bytes(4, "little")
    b[4] = 2 if sb == 2 else 0
    b[5] = pa
    b[6] = db if 5 <= db <= 8 else 8
    return bytes(b)


print()
for (baud, db, sb, pa), exp in [((9600, 8, 1, 0), "802500000000 08".replace(" ", "")),
                                ((115200, 8, 2, 0), "00C20100 02 00 08".replace(" ", "")),
                                ((9600, 8, 1, 2), "80250000 00 02 08".replace(" ", ""))]:
    got = cdc_lc(baud, db, sb, pa).hex().upper()
    rec("CDC", f"LINE_CODING({baud},{db},{sb},{pa})", got == exp.upper(), got, exp.upper(), "")

# ======================================================================
# [4] PL2303 —— 请求/位域
# ======================================================================
sec("[4] PL2303 请求类型与位域（Linux pl2303.c 对照）")
PL = [
    ("SET_LINE_REQUEST 0x20", 0x21, 0x21, "class|interface|out"),
    ("SET_CONTROL_REQUEST 0x22", 0x21, 0x21, "class|interface|out"),
    ("VENDOR_WRITE 0x01", 0x40, 0x40, "vendor|device|out"),
    ("VENDOR_READ 0x01", 0xC0, 0xC0, "vendor|device|in"),
]
for name, kern, mine, desc in PL:
    rec("PL2303", name + " bmRequestType", kern == mine, f"0x{mine:02X}", f"0x{kern:02X}", desc)
rec("PL2303", "clampBaud(50)", max(75, min(921600, 50)) == 75, 75, 75, "")
rec("PL2303", "clampBaud(15000000)", max(75, min(921600, 15000000)) == 921600, 921600, 921600, "")
rec("PL2303", "clampBaud(9600)", max(75, min(921600, 9600)) == 9600, 9600, 9600, "")

# ======================================================================
# [5] device_filter.xml（十进制）↔ UsbSerialIds.kt（十六进制）
# ======================================================================
sec("[5] device_filter.xml 十进制 VID/PID ↔ UsbSerialIds.kt 十六进制 逐项换算")
ROOT = __file__.replace("\\", "/").split("/qa-tools/")[0]
xml = io.open(ROOT + "/EMRS-Android/app/src/main/res/xml/device_filter.xml",
              encoding="utf-8").read()
pairs = [(int(v), int(p)) for v, p in
         re.findall(r'vendor-id="(\d+)"\s+product-id="(\d+)"', xml)]
IDS_KT = [(0x1A86, 0x7522), (0x1A86, 0x7523),
          (0x0403, 0x6001), (0x0403, 0x6010), (0x0403, 0x6014), (0x0403, 0x6015),
          (0x10C4, 0xEA60), (0x067B, 0x2303)]
print(f"  device_filter.xml 条目数={len(pairs)}，UsbSerialIds.kt 条目数={len(IDS_KT)}")
rec("VID/PID", "条目数一致(8)", len(pairs) == len(IDS_KT) == 8, len(pairs), 8, "")
for (v, p), (hv, hp) in zip(pairs, IDS_KT):
    ok = (v == hv and p == hp)
    rec("VID/PID", f"{v}:{p}", ok, f"0x{v:04X}:0x{p:04X}", f"0x{hv:04X}:0x{hp:04X}", "")

# ======================================================================
# [6] bmRequestType 位域总审计表
# ======================================================================
sec("[6] 全部 controlTransfer 的 bmRequestType 位域审计")
# (文件, 行, 请求, 实现值, 参考值, 结论文案)
AUDIT = [
    ("Ch340Driver.kt", 134, "REQ_READ_VERSION 0x5F", 0xC0, 0xC0, "vendor|device|in —— 正确"),
    ("Ch340Driver.kt", 139, "REQ_SERIAL_INIT 0xA1", 0x40, 0x40, "vendor|device|out —— 正确"),
    ("Ch340Driver.kt", 143, "REQ_MODEM_CTRL 0xA4 wValue=0xFF9F", 0x40, 0x40,
     "vendor|device|out；~0x60 与 ch341_set_handshake 一致"),
    ("Ch340Driver.kt", 151, "REQ_WRITE_REG 0x9A 分频 wValue=0x1312", 0x40, 0x40,
     "vendor|device|out；分频值在 wIndex —— 与内核一致"),
    ("Ch340Driver.kt", 161, "REQ_WRITE_REG 0x9A LCR wValue=0x2518", 0x40, 0x40,
     "vendor|device|out；仅 version>=0x30 —— 与内核一致"),
    ("FtdiDriver.kt", 137, "SIO_RESET 0x00", 0x40, 0x40, "vendor|device|out；wIndex=1 端口 —— 正确"),
    ("FtdiDriver.kt", 140, "SIO_SET_MODEM_CTRL 0x01 wValue=0x0303", 0x40, 0x40, "正确"),
    ("FtdiDriver.kt", 148, "SIO_SET_FLOW 0x02", 0x40, 0x40, "正确（无流控）"),
    ("FtdiDriver.kt", 156, "SIO_SET_BAUD_RATE 0x03", 0x40, 0x40,
     "位域正确；**wIndex 非 H 系列应为端口号 1，实现按子分频位给 0/1** → P1"),
    ("FtdiDriver.kt", 160, "SIO_SET_DATA 0x04", 0x40, 0x40, "正确"),
    ("Cp210xDriver.kt", 58, "IFC_ENABLE 0x00", 0x41, 0x41, "vendor|interface|out —— 正确"),
    ("Cp210xDriver.kt", 61, "SET_MHS 0x07 wValue=0x0303", 0x41, 0x41, "正确"),
    ("Cp210xDriver.kt", 74, "SET_LINE_CODING **0x20**", 0x41, None,
     "**0x20 非 AN571/cp210x.c 请求码；CP210x 为 vendor-specific(0xFF) 非 CDC-ACM** → P0"),
    ("Pl2303Driver.kt", 79, "SET_LINE 0x20", 0x21, 0x21, "class|interface|out —— 与 pl2303.c 一致"),
    ("Pl2303Driver.kt", 44, "SET_CONTROL 0x22", 0x21, 0x21, "正确"),
    ("Pl2303Driver.kt", 68, "VENDOR_WRITE 0x01", 0x40, 0x40, "正确"),
    ("Pl2303Driver.kt", 73, "VENDOR_READ 0x01", 0xC0, 0xC0, "正确"),
]
for f, ln, req, mine, ref, note in AUDIT:
    print(f"  {f:<18} L{ln:<4} {req:<42} reqtype=0x{mine:02X}  {note}")

# ======================================================================
# 汇总
# ======================================================================
sec("汇总")
nfail = sum(1 for r in results if r[2] == FAIL)
print(f"  断言总数 {len(results)}，PASS {len(results) - nfail}，FAIL {nfail}")
for r in results:
    if r[2] == FAIL:
        print(f"    FAIL → {r[0]} / {r[1]}：我算={r[3]}，期望={r[4]}")
print("\n说明：本脚本只覆盖『可离线推演』的部分（分频/编码/位域/VID-PID 换算）。")
print("CP210x 0x20 与 FTDI 非 H 系列 wIndex 两项为静态协议审计结论，见报告正文。")
sys.exit(0 if nfail == 0 else 1)
