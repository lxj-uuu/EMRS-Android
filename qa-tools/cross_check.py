# -*- coding: utf-8 -*-
"""
EMRS-Android QA 交叉验证脚本（QA 独立实现，不依赖 Kotlin 工程）。
以 Delphi 源码（LX1/LX3/LX5/LX6）为基准逐公式重写，输出与 Kotlin 实现
的预期结果对照。运行：python cross_check.py
"""
import math

PASS = []
FAIL = []

def check(name, cond, detail=""):
    (PASS if cond else FAIL).append((name, detail))
    print(("PASS " if cond else "FAIL ") + name + ("  | " + detail if detail else ""))

# ---------------- 1. 下行组帧（FrameCodec） ----------------
def self_test_frame():
    return bytes([0xA0, 0xA0, 0x01, 0x01, 0xAF, 0xAF])

def self_test_stop_frame():
    return bytes([0xA0, 0xA0, 0x02, 0x02, 0xAF, 0xAF])

def supply_frame(times, prg=3):
    # LX5.Button4Click: send[3]=3, send[4]=3, send[5]=times, chk=3^3^N=N
    return bytes([0xA0, 0xA0, 0x03, prg, times, 0x03 ^ prg ^ times, 0xAF, 0xAF])

def charge_enter_frame():
    # LX1.Button8Click: H=0, L=0, chk=4
    return bytes([0xA0, 0xA0, 0x04, 0x00, 0x00, 0x04, 0xAF, 0xAF])

def pascal_trunc(d):
    return int(d) if d >= 0 else -int(-d)  # 向零取整

def charge_frame(target_vab):
    # LX6.Button1Click: sheding=trunc(vab/50*1023); H=(sheding div 256) and 3; L=sheding mod 256
    sheding = pascal_trunc(target_vab / 50.0 * 1023.0)
    h = (sheding // 256) & 3
    l = sheding % 256
    return bytes([0xA0, 0xA0, 0x04, h, l, 0x04 ^ h ^ l, 0xAF, 0xAF])

check("frame.selfTest", self_test_frame() == bytes([0xA0,0xA0,0x01,0x01,0xAF,0xAF]))
check("frame.selfTestStop", self_test_stop_frame() == bytes([0xA0,0xA0,0x02,0x02,0xAF,0xAF]))
check("frame.supply(N=4)", supply_frame(4) == bytes([0xA0,0xA0,0x03,0x03,0x04,0x04,0xAF,0xAF]))
check("frame.chargeEnter", charge_enter_frame() == bytes([0xA0,0xA0,0x04,0x00,0x00,0x04,0xAF,0xAF]))
f30 = charge_frame(30.0)
check("frame.charge(30V)", f30 == bytes([0xA0,0xA0,0x04,0x02,101,99,0xAF,0xAF]),
      "sheding=trunc(613.8)=613 H=2 L=101 chk=4^2^101=99")
f295 = charge_frame(29.5)  # 滑块奇数档
check("frame.charge(29.5V) H/L", f295[3] == ((pascal_trunc(29.5/50*1023)//256)&3) and f295[4] == pascal_trunc(29.5/50*1023)%256,
      "sheding=%d" % pascal_trunc(29.5/50*1023))

# ---------------- 2. 上行组帧状态机：Delphi 语义 vs Kotlin 语义 ----------------
def delphi_parse(chunks):
    """LX1.Comm1RxChar 语义：每块先扫整块内最后一个 A0A0，从其后累积；遇 AFAF 出帧后 exit（L627，
    同批剩余字节丢弃）。返回 (valid_types, invalid_types)。"""
    valid, invalid = [], []
    have_head = False
    msg = []
    for chunk in chunks:
        # 阶段A waiting_ack 略（与组帧无关）
        # 扫描整块最后一个 A0A0（L408-415，循环不提前退出，最后一对生效）
        last = -1
        for i in range(len(chunk) - 1):
            if chunk[i] == 0xA0 and chunk[i+1] == 0xA0:
                last = i
        if last >= 0:
            msg = []
            have_head = True
            buf_ii = last + 2
        else:
            buf_ii = 0 if have_head else len(chunk)
        if have_head:
            for ij in range(buf_ii, len(chunk)):
                msg.append(chunk[ij])
                if len(msg) >= 2 and msg[-2] == 0xAF and msg[-1] == 0xAF:
                    have_head = False
                    chk = 0
                    for b in msg[:-2]:
                        chk ^= b
                    t = msg[0]
                    if chk in (0x00, 0x80):
                        valid.append(t)
                    else:
                        invalid.append(t)
                    break   # L627 exit：同批剩余字节丢弃
    return valid, invalid

def kotlin_parse(chunks, strict=False):
    """RxFrameParser.kt（第 2 轮修复版 L48-88）语义：
    ①每次 feed 整块扫描最后一个 A0A0（不提前退出），命中即 msgLen:=0 丢弃累积、have_head:=true、
      从该对之后开始累积；②累积态相邻两字节均 A0 重启（跨块可达，块内不可达）；③have_head 为假
    整块丢弃；④无跨块 prevByte 记忆。strict=True 复刻源码 L627 exit。"""
    valid, invalid = [], []
    have_head = False
    msg = [None]   # 1 基：msg[0] 废弃；len(msg)-1 == msgLen
    for chunk in chunks:
        start = 0
        for i in range(len(chunk) - 1):
            if chunk[i] == 0xA0 and chunk[i+1] == 0xA0:
                have_head = True
                msg = [None]
                start = i + 2
        if not have_head:
            continue
        stop = False
        for i in range(start, len(chunk)):
            b = chunk[i]
            # 累积态相邻两字节均 A0 → 重启（防御分支，跨块 A0|A0 可达）
            if b == 0xA0 and len(msg) >= 2 and msg[-1] == 0xA0:
                msg = [None]
                continue
            msg.append(b)
            if len(msg) >= 3 and msg[-2] == 0xAF and msg[-1] == 0xAF:
                have_head = False
                chk = 0
                for x in msg[1:-2]:
                    chk ^= x
                (valid if chk in (0x00, 0x80) else invalid).append(msg[1])
                msg = [None]
                if strict:
                    stop = True
                    break
        # 非 strict：帧尾后同块剩余字节继续累积（robust 模式既定行为）
        if stop:
            continue
    return valid, invalid

# 用例 A：垃圾后接新帧头（同一块）—— 模拟 newHeaderResetsAccumulation（P0-1 反例）
chunksA = [bytes([0xA0,0xA0,0x07,0x07,0xA0,0xA0,0x01,0x01,0xAF,0xAF])]
dv, di = delphi_parse(chunksA)
kv, ki = kotlin_parse(chunksA)
check("parser.A.delphi", dv == [1], "Delphi 语义: valid types=%s" % dv)
check("parser.A.kotlin", kv == dv, "Kotlin 语义(修复版): valid types=%s（第1轮为 [7]，修复后应=[1]）" % kv)

# 用例 B：跨块续帧，第二块内含数据 A0A0（1/65536 概率×每帧2900B）
chunksB = [bytes([0xA0,0xA0,0x02,0x02]) , bytes([0x00]*10 + [0xA0,0xA0] + [0x00]*8 + [0xAF,0xAF])]
dv, di = delphi_parse(chunksB)
kv, ki = kotlin_parse(chunksB)
check("parser.B.delphi", dv == [0], "Delphi 语义: 数据A0A0重启累积→零块校验=0 判合法帧 type=%s（原版怪癖）" % dv)
check("parser.B.kotlin", kv == dv, "Kotlin 语义(修复版): valid=%s invalid=%s（应与 Delphi 一致）" % (kv, ki))

# 用例 C：跨块续帧 + 第 2 块中部混入 A0A0 后跟完整合法帧（工程师新增 JUnit 场景）
chunksC = [bytes([0xA0,0xA0,0x02,0x02]), bytes([0x03,0x03,0xA0,0xA0,0x05,0x05,0xAF,0xAF])]
dv, di = delphi_parse(chunksC)
kv, ki = kotlin_parse(chunksC)
check("parser.C.delphi", dv == [5], "Delphi 语义: type=%s" % dv)
check("parser.C.kotlin", kv == dv, "Kotlin 语义(修复版): type=%s（应=5，对应 crossBlockFeed_headerInsideDataResetsAccumulation）" % kv)

# 用例 D：跨块 A0|A0 相邻（帧头对恰好骑跨块边界，且此前已有累积垃圾）
# Delphi：扫描只在本块内找 A0A0 对 → 跨块对不识别 → 垃圾+A0A0 全进帧体；
# Kotlin：防御分支 ② 重启累积（丢弃既有垃圾与当前 A0）
chunksD = [bytes([0xA0,0xA0,0x02,0x02,0xA0]), bytes([0xA0,0x05,0x05,0xAF,0xAF])]
dv, di = delphi_parse(chunksD)
kv, ki = kotlin_parse(chunksD)
print("INFO  parser.D 跨块A0|A0: Delphi valid=%s invalid=%s | Kotlin valid=%s invalid=%s"
      % (dv, di, kv, ki))
check("parser.D.delphi", dv == [2], "Delphi 语义: 垃圾累积+A0A0 全入帧体 → 校验=0 误判合法 type=%s（原版病态）" % dv)
check("parser.D.kotlin-diff", kv != dv, "Kotlin 防御分支重启 → valid=%s（与 Delphi 有差异，属既定修法②的已知边界，P2 记录）" % kv)

# 用例 E：帧头对完整落在块 1，帧体跨块（正常拆包场景）
chunksE = [bytes([0xA0,0xA0]), bytes([0x01,0x01,0x02]), bytes([0xAF,0xAF])]
dv, di = delphi_parse(chunksE)
kv, ki = kotlin_parse(chunksE)
check("parser.E.delphi", dv == [] and di == [1], "Delphi 语义: 跨块累积出帧，校验坏 → invalid type=%s" % di)
check("parser.E.kotlin", kv == dv and ki == di, "Kotlin 语义(修复版): valid=%s invalid=%s" % (kv, ki))

# 用例 F：孤字节 A0 单独成块（帧头对被拆散）→ 源码不跨块记忆单字节，整块丢弃，帧丢失
chunksF = [bytes([0xA0]), bytes([0xA0,0x01,0x01,0xAF,0xAF])]
dv, di = delphi_parse(chunksF)
kv, ki = kotlin_parse(chunksF)
check("parser.F.delphi", dv == [] and di == [], "Delphi 语义: 单A0块丢弃 → 帧丢失=%s/%s（原版怪癖，看门狗重发兜底）" % (dv, di))
check("parser.F.kotlin", kv == dv and ki == di, "Kotlin 语义(修复版): 同样丢弃 → %s/%s" % (kv, ki))

# 用例 G：strict 模式复刻 L627 exit；robust 默认模式帧尾后残余字节的差异说明
chunksG = [bytes([0xA0,0xA0,0x01,0x01,0xAF,0xAF,0x00,0xAF,0xAF])]
dvG, _ = delphi_parse(chunksG)
kvGs, _ = kotlin_parse(chunksG, strict=True)
kvGr, _ = kotlin_parse(chunksG, strict=False)
check("parser.G.strict-eq-source", kvGs == dvG, "strict=True 与源码 exit 语义一致: %s" % kvGs)
print("INFO  parser.G robust(默认)=%s vs 源码=%s —— robust 模式帧尾后残余(00,AF,AF)会再拼出幽灵帧，"
      "与源码 exit 不同（架构既定开关的已知差异，P2 记录；Engine 未显式设 strict）" % (kvGr, dvG))

# 用例 H：同块两帧（对应 JUnit robustMode_blockScanTakesLastHeader / strictMode_dropsBytesAfterFrame）
chunksH = [bytes([0xA0,0xA0,0x01,0x01,0xAF,0xAF,0xA0,0xA0,0x01,0x01,0xAF,0xAF])]
dvH, _ = delphi_parse(chunksH)
kvHs, _ = kotlin_parse(chunksH, strict=True)
kvHr, _ = kotlin_parse(chunksH, strict=False)
check("parser.H.twoFrames", dvH == [1] and kvHs == dvH and kvHr == dvH,
      "整块扫描取最后一个头 → 只解出后一帧 type=1，strict/robust 一致: %s/%s/%s" % (dvH, kvHs, kvHr))

# ---------------- 3. 14位补码+增益 / 数据帧解码 ----------------
def scale14(h, l):
    x = (h & 63) * 256 + l
    xy = x
    if h & 32:
        xy = -(((x | 0xC000) ^ 0xFFFF) + 1)
    v = xy / 8191.0 * 10000000.0
    sgn = h & 192
    if sgn == 0:
        pass
    elif sgn == 192:
        v /= 16384.0
    else:
        v /= 128.0
    return v

check("scale14.zero", scale14(0, 0) == 0.0)
check("scale14.neg/128", abs(scale14(0x60, 0) - (-8192/8191.0*1e7/128.0)) < 1e-6)
check("scale14.pos/128", abs(scale14(0x80, 5) - (5/8191.0*1e7/128.0)) < 1e-9)
check("scale14.neg/16384", abs(scale14(0xE0, 5) - (-(((((0xE0 & 63)*256+5)|0xC000)^0xFFFF)+1))/8191.0*1e7/16384.0) < 1e-6)

def decode_frame(payload, mddl, bl):
    """payload: msg[1..]（含类型2与gdcs）。返回 (iab_ave, v2[1]) """
    gdcs = payload[1]
    mi = 2  # 0 基指针，指向 msg[3]
    v250 = [0.0]*251
    for i in range(1, 251):
        h, l = payload[mi], payload[mi+1]
        v250[i] = scale14(h, l)
        mi += 2
    s = 0.0
    for _ in range(4):
        h, l = payload[mi], payload[mi+1]
        s += ((h & 3)*256 + l)/1023.0*100.0 * mddl/100.0*5.0/4.0
        mi += 2
    modify = s/4.0
    modify = modify - 0.000068*modify*modify
    iab = float(pascal_trunc(modify*bl*100))/100.0
    v2 = [0.0]*1201
    j = 2
    for i in range(1, 1201):
        h, l = payload[mi], payload[mi+1]
        v2[i] = scale14(h, l) - v250[j]
        j = 1 if j+1 > 250 else j+1
        mi += 2
    return gdcs, iab, v2

# 满码电流样例（与 AlgoTest.iabAve_nonlinearCorrection 相同输入）
pay = bytearray([2, 1]) + bytearray(500) + bytearray([3, 255]*4) + bytearray(2400) + bytearray([0])
g, iab, v2 = decode_frame(bytes(pay), 2000, 2.0)
check("decode.iabAve", iab == 4150.0, "modify=2500-0.000068*2500^2=2075, trunc(2075*2*100)/100=4150")

# ---------------- 4. replace_ave 迭代（复现 [100,900] 收敛路径） ----------------
def replace_ave_col(values):
    """LX5.replace_ave 单列。values: 供电次数列。返回 v2[j]"""
    n = len(values)
    ave = sum(values)/n
    dlt = math.sqrt(sum((x-ave)**2 for x in values)/(n-1))
    rplc = [x for x in values if abs(x-ave) <= 3*dlt]
    k = len(rplc)
    if k < 2:
        return ave
    while True:
        ave = sum(rplc)/k
        dlt = 0.0
        i_bad = 0
        for i in range(k):
            d = abs(rplc[i]-ave)
            if d > dlt:
                dlt = d
                i_bad = i
        if dlt > abs(ave)*0.02:
            rplc[i_bad] = ave
            continue
        return ave

res = replace_ave_col([100.0, 900.0])
# 平局裁决：i=1 的 400 先登记 → rplc[1](100) 被替换 → 序列 500,700,800,850,875,887.5
check("replaceAve.tiebreak", abs(res - 887.5) < 1e-9,
      "两实现(Delphi/Kotlin)同序 tie-break → %s（JUnit 期望 300.0 为错误断言）" % res)
res2 = replace_ave_col([900.0, 100.0])  # 交换顺序 → 收敛方向相反（源码怪癖）
check("replaceAve.orderDep", abs(res2 - 101.5625) < 1e-9, "交换输入顺序 → %s（结果依赖输入顺序，源码怪癖）" % res2)

# ---------------- 5. smooth5_3 粗去毛刺 + 分段滤波 ----------------
def smooth_glitch(row, n):
    row = row[:]
    for j in range(2, n):  # 1 基 j=2..n-1
        a, b, c = row[j-1], row[j], row[j+1]
        aaa = a + b + c - min(a, b, c) - max(a, b, c)
        if abs(row[j]-aaa) > 0.6*abs(aaa):
            row[j] = aaa
    return row

r = smooth_glitch([0.0, 10.0, 100.0, 10.0, 0.0], 4)  # j=2: 3点{10,100,10}→中值10? aaa=10+100+10-10-100=10; |100-10|>6 → 100→10
check("smooth.glitch", r[2] == 10.0, "孤立尖峰被剔除 → %s" % r[2])

# ---------------- 6. 疏密平均 / 道时间 ----------------
def sparse_ave(v2, points):
    v2i = [0.0]*(points+1)
    k = 1
    v2i[1] = v2[1]
    for i in range(2, points+1):
        s = 0.0
        for _ in range(i):
            k += 1
            s += v2[k]
        v2i[i] = s/i
    return v2i

v = [0.0]*7
v[1], v[2], v[3], v[4], v[5], v[6] = 10, 20, 40, 30, 50, 70
r = sparse_ave(v, 3)
check("sparseAve", r[1] == 10 and r[2] == 30 and r[3] == 50)

def shjian(points):
    out = [0.0]*(points+1)
    k = 1
    out[1] = 0.08
    for i in range(2, points+1):
        s = 0
        for _ in range(i):
            k += 1
            s += k
        out[i] = s/i*0.08
    return out

r = shjian(3)
check("shjian", r[1] == 0.08 and r[2] == 0.2 and r[3] == 0.4)

# ---------------- 7. 显示规整阶梯 ----------------
def ladder_measure(d):
    if d >= 1e6: return float(pascal_trunc(d))
    if d >= 1e5: return pascal_trunc(d*1)/1.0
    if d >= 1e4: return pascal_trunc(d*10)/10.0
    if d >= 1e3: return pascal_trunc(d*100)/100.0
    if d >= 1e2: return pascal_trunc(d*1000)/1000.0
    if d >= 1e1: return pascal_trunc(d*1e4)/1e4
    return pascal_trunc(d*1e6)/1e6

check("ladder.measure", ladder_measure(12345.67) == 12345.6 and ladder_measure(1.2345678) == 1.234567)

# ---------------- 8. Delphi real 文本格式 ----------------
def delphi_real(d):
    if d == 0.0:
        return "0.00000000000000E+0000"
    neg = d < 0
    a = abs(d)
    e = int(math.floor(math.log10(a)))
    m = a/10.0**e
    ms = "%.*f" % (14, m)
    if ms.startswith("10."):
        ms = ms[1:]
        e += 1
    return ("-" if neg else "") + ms + "E" + ("-" if e < 0 else "+") + "%04d" % abs(e)

check("real.0.08", delphi_real(0.08) == "8.00000000000000E-0002", delphi_real(0.08))
check("real.100", delphi_real(100.0) == "1.00000000000000E+0002", delphi_real(100.0))
check("real.-0.352", delphi_real(-0.352) == "-3.52000000000000E-0001", delphi_real(-0.352))

# ---------------- 9. VAB 公式 / 电流截断怪癖 ----------------
def vab_of(h, l):
    return ((h & 3)*256 + l)/1023.0*50.0
check("vab.formula", abs(vab_of(2, 101) - 30.0/50.0*50.0*613/613*613.0/613.0* (613/613)) < 1e-9 or abs(vab_of(2,101)-613/1023.0*50.0) < 1e-9)
check("vab.613", abs(vab_of(2, 101) - 613/1023.0*50.0) < 1e-12)

# 供电电流（s 文件）怪癖：trunc((average_i*100)/100) == trunc(average_i)
avg = 12.3456
check("quirk.sFileCurrent", pascal_trunc((avg*100)/100) == 12, "s 文件供电电流写入整截断 12（非 12.34）")

print()
print("=== 结果: %d PASS, %d FAIL ===" % (len(PASS), len(FAIL)))
for n, d in FAIL:
    print("FAIL DETAIL:", n, d)
