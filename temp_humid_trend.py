"""
Excel Trend Analyzer — Temperature & Humidity
- win32com.client 단독 사용 (openpyxl 미사용)
- 셀 색깔 / 폰트 / 정렬 없음 — 값만 기록
- 3가지 입력 포맷 지원 (config.json)
- 전체 시트 각각 독립 처리
- 온도 / 습도 각각 독립 min_rows, min_val
- 결과: 입력 파일에 직접 Temp_Trend / Humid_Trend 컬럼 추가 후 저장
"""

import json
import os
import tkinter as tk
from tkinter import ttk, filedialog, messagebox

import win32com.client


# ══════════════════════════════════════════════
# CONFIG
# ══════════════════════════════════════════════
CONFIG_FILE = "config.json"


def load_config() -> dict:
    if not os.path.exists(CONFIG_FILE):
        raise FileNotFoundError(
            f"config.json 파일이 없습니다.\n"
            f"실행 경로에 config.json 을 생성한 후 다시 시작하세요.\n"
            f"경로: {os.path.abspath(CONFIG_FILE)}")
    with open(CONFIG_FILE, "r", encoding="utf-8") as f:
        return json.load(f)


def save_config(cfg: dict):
    with open(CONFIG_FILE, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2, ensure_ascii=False)


# ══════════════════════════════════════════════
# 추세 분석
# ══════════════════════════════════════════════
def analyze_trends(values: list, min_rows: int, min_val: float) -> list:
    """
    UP/DOWN 추세 분석.

    노이즈 조건:
      1) 값이 min_val 이하인 구간 전체
      2) UP/DOWN 길이 < min_rows
      3) flat(동일값) 길이 >= min_rows → BARRIER (해당 구간 "", 앞뒤 전파 차단)

    flat 구간(noise 포함) 처리 원칙:
      - flat은 방향이 없으므로 앞 방향을 계승하지 않음
      - 뒤에 유효한 UP/DOWN 구간이 있으면 그 방향을 따름
      - 뒤에도 없고 앞에도 없으면 ""
    """
    n = len(values)
    if n == 0:
        return []

    # Step1: 인접 비교 → UP / DOWN / "" (flat)
    raw = [""] * n
    for i in range(1, n):
        if   values[i] > values[i - 1]: raw[i] = "UP"
        elif values[i] < values[i - 1]: raw[i] = "DOWN"

    # Step2: 연속 구간 추출
    segments, i = [], 0
    while i < n:
        d = raw[i]; j = i
        while j < n and raw[j] == d: j += 1
        segments.append((i, j - 1, d)); i = j
    S = len(segments)

    low_set = {k for k, v in enumerate(values) if v <= min_val}

    def seg_len(si): return segments[si][1] - segments[si][0] + 1
    def seg_dir(si): return segments[si][2]

    def is_low(si):
        s, e, _ = segments[si]
        return all(k in low_set for k in range(s, e + 1))

    def is_flat_barrier(si):
        """동일값 구간이 min_rows 이상 → 방향 전파 차단"""
        return seg_dir(si) == "" and seg_len(si) >= min_rows

    def is_valid(si):
        """유효한 UP/DOWN 구간 (노이즈 아님, min_val 아님)"""
        d = seg_dir(si)
        return d in ("UP", "DOWN") and not is_low(si) and seg_len(si) >= min_rows

    def is_flat_noise(si):
        """flat 이지만 barrier 아닌 짧은 구간"""
        return seg_dir(si) == "" and not is_flat_barrier(si)

    def is_short_noise(si):
        """UP/DOWN 이지만 너무 짧거나 min_val 이하"""
        return not is_valid(si) and not is_flat_barrier(si) and not is_flat_noise(si)

    # Step3: 각 구간 분류
    #   BARRIER   : flat >= min_rows  → "" 확정, 전파 차단
    #   VALID     : 유효 UP/DOWN      → 방향 확정
    #   NOISE     : 짧은 UP/DOWN, low → 인접 채움
    #   FLAT_NOISE: 짧은 flat         → 뒤 방향 우선, 없으면 앞

    BARRIER = "__BARRIER__"
    resolved = [None] * S
    for si in range(S):
        if is_flat_barrier(si):  resolved[si] = BARRIER
        elif is_valid(si):       resolved[si] = seg_dir(si)
        # else: None — 나중에 채움

    # Step4: 뒤→앞 전파 (flat_noise는 뒤 방향 우선)
    #   barrier를 만나면 역방향 초기화
    nxt = ""
    for si in range(S - 1, -1, -1):
        if resolved[si] == BARRIER:
            nxt = ""
        elif resolved[si] in ("UP", "DOWN"):
            nxt = resolved[si]
        elif resolved[si] is None:
            # flat_noise 또는 short_noise: 뒤 방향으로 채움
            resolved[si] = nxt

    # Step5: 앞→뒤 전파 (뒤에 방향이 없어서 "" 로 남은 구간 채움)
    #   barrier를 만나면 전방향 초기화
    last = ""
    for si in range(S):
        if resolved[si] == BARRIER:
            last = ""
        elif resolved[si] in ("UP", "DOWN"):
            last = resolved[si]
        elif resolved[si] == "":
            # 뒤→앞에서도 채워지지 않은 경우 (앞에 유효값이 있으면 계승)
            resolved[si] = last

    # Step6: 결과 배열
    result = [""] * n
    for si, (s, e, _) in enumerate(segments):
        val = "" if resolved[si] == BARRIER else (resolved[si] or "")
        for k in range(s, e + 1):
            result[k] = val
    return result

# ══════════════════════════════════════════════
# WIN32 헬퍼
# ══════════════════════════════════════════════
def _xl_open(filepath: str, visible=False):
    """Excel Application 열기 — DispatchEx로 독립 프로세스 실행"""
    xl = win32com.client.DispatchEx("Excel.Application")
    xl.Visible        = False
    xl.DisplayAlerts  = False
    xl.ScreenUpdating = False
    wb = xl.Workbooks.Open(
        os.path.abspath(filepath),
        UpdateLinks=False,             # 외부 링크 갱신 안 함
        ReadOnly=False,
    )
    return xl, wb


def get_sheet_names(filepath: str) -> list:
    xl, wb = _xl_open(filepath)
    try:
        return [sh.Name for sh in wb.Sheets]
    finally:
        wb.Close(False); xl.Quit()


def read_sheet(filepath: str, sheet_name: str,
               header_row: int, data_start_row: int,
               temp_col_name: str, humid_col_name: str):
    """
    win32com으로 시트 읽기 (대용량 최적화).
    - 헤더: Range 1행 일괄 읽기
    - 데이터: 컬럼 단위 Range.Value 일괄 읽기 (셀 루프 없음)
    반환: (temp_vals: list[float], humid_vals: list[float])
    """
    xl, wb = _xl_open(filepath)
    try:
        ws       = wb.Sheets(sheet_name)
        ur       = ws.UsedRange
        last_col = ur.Column + ur.Columns.Count - 1
        last_row = ur.Row    + ur.Rows.Count    - 1

        # 헤더 행 일괄 읽기
        hdr_range = ws.Range(
            ws.Cells(header_row, 1),
            ws.Cells(header_row, last_col)
        )
        hdr_raw = hdr_range.Value  # tuple of tuples (1 row)
        headers = [
            str(v).strip() if v is not None else ""
            for v in (hdr_raw[0] if hdr_raw else [])
        ]

        def col_of(name: str) -> int:
            name = name.strip()
            if not name: return -1
            for i, h in enumerate(headers):
                if h == name: return i + 1
            return -1

        t_ci = col_of(temp_col_name)
        h_ci = col_of(humid_col_name)

        def read_col(ci: int) -> list:
            """컬럼 전체를 Range.Value 단일 호출로 일괄 읽기"""
            if ci < 1:
                return []
            col_range = ws.Range(
                ws.Cells(data_start_row, ci),
                ws.Cells(last_row, ci)
            )
            raw = col_range.Value  # tuple of 1-element tuples
            out = []
            for row_tuple in (raw or []):
                v = row_tuple[0] if isinstance(row_tuple, tuple) else row_tuple
                try:    out.append(float(v) if v is not None else 0.0)
                except: out.append(0.0)
            return out

        return read_col(t_ci), read_col(h_ci)
    finally:
        wb.Close(False); xl.Quit()


def write_trend_col(ws, header_row: int, data_start_row: int,
                    col_name: str, trends: list):
    """결과 컬럼을 값만 기록 (대용량 최적화: Range 일괄 쓰기)"""
    ur       = ws.UsedRange
    last_col = ur.Column + ur.Columns.Count - 1

    # 기존 동일 헤더 컬럼 찾기 (헤더 행 일괄 읽기)
    hdr_range = ws.Range(ws.Cells(header_row, 1),
                         ws.Cells(header_row, last_col))
    hdr_raw   = hdr_range.Value
    headers   = [str(v).strip() if v is not None else ""
                 for v in (hdr_raw[0] if hdr_raw else [])]
    col = None
    for i, h in enumerate(headers):
        if h == col_name:
            col = i + 1; break
    if col is None:
        col = last_col + 1

    # 헤더 쓰기
    ws.Cells(header_row, col).Value = col_name

    # 데이터 일괄 쓰기 — 2D 튜플로 Range.Value 에 한 번에 할당
    data_2d = tuple((v,) for v in trends)
    ws.Range(
        ws.Cells(data_start_row, col),
        ws.Cells(data_start_row + len(trends) - 1, col)
    ).Value = data_2d


# ══════════════════════════════════════════════
# 전체 파일 처리
# ══════════════════════════════════════════════
def process_all_sheets(filepath: str, sheet_cfg_map: dict,
                       config: dict, status_cb=None):
    def log(msg):
        if status_cb: status_cb(msg)

    fmt_key        = config.get("input_format", "format1")
    fmt            = config["formats"][fmt_key]
    header_row     = int(fmt.get("header_row", 1))
    data_start_row = int(fmt.get("data_start_row", 2))
    col_map        = fmt.get("columns", {})
    temp_col_name  = col_map.get("temp",  "")
    humid_col_name = col_map.get("humid", "")

    t_cfg = config["temp"]
    h_cfg = config["humid"]

    log(f"포맷: [{fmt_key}] {fmt['description']}")
    log(f"헤더={header_row}행, 데이터 시작={data_start_row}행")
    log(f"온도='{temp_col_name}', 습도='{humid_col_name}'")

    # 원본 파일 직접 열기 (복사 없음)
    xl, wb = _xl_open(filepath)
    processed = []

    try:
        for sheet_name, scfg in sheet_cfg_map.items():
            if not scfg.get("enabled", True):
                log(f"  [{sheet_name}] 스킵"); continue

            log(f"  [{sheet_name}] 읽는 중...")
            try:
                temp_vals, humid_vals = read_sheet(
                    filepath, sheet_name,
                    header_row, data_start_row,
                    temp_col_name, humid_col_name)
            except Exception as e:
                log(f"  [{sheet_name}] 읽기 실패: {e}"); continue

            ws = wb.Sheets(sheet_name)
            sheet_done = False

            if temp_vals:
                trends = analyze_trends(temp_vals,
                                        int(t_cfg["min_rows"]),
                                        float(t_cfg["min_val"]))
                write_trend_col(ws, header_row, data_start_row,
                                "Temp_Trend", trends)
                log(f"  [{sheet_name}] 🌡 온도  "
                    f"UP={trends.count('UP')}, DOWN={trends.count('DOWN')}")
                sheet_done = True
            else:
                log(f"  [{sheet_name}] 🌡 온도 컬럼 없음")

            if humid_vals:
                trends = analyze_trends(humid_vals,
                                        int(h_cfg["min_rows"]),
                                        float(h_cfg["min_val"]))
                write_trend_col(ws, header_row, data_start_row,
                                "Humid_Trend", trends)
                log(f"  [{sheet_name}] 💧 습도  "
                    f"UP={trends.count('UP')}, DOWN={trends.count('DOWN')}")
                sheet_done = True
            else:
                log(f"  [{sheet_name}] 💧 습도 컬럼 없음")

            if sheet_done:
                processed.append(sheet_name)

        xl.ScreenUpdating = True
        wb.Save()
        xl.Visible = True
        log(f"\n완료: {os.path.basename(filepath)}  ({len(processed)}개 시트)")

    except Exception as e:
        xl.ScreenUpdating = True
        wb.Close(False); xl.Quit(); raise e

    return filepath, processed


# ══════════════════════════════════════════════
# GUI: 포맷 편집 다이얼로그
# ══════════════════════════════════════════════
class FormatEditorDialog(tk.Toplevel):
    def __init__(self, parent, fmt_key, fmt_data, bg, fg, ebg, lf):
        super().__init__(parent)
        self.title(f"포맷 편집 — {fmt_key}")
        self.configure(bg=bg); self.resizable(False, False)
        self.result = None
        self._vars = {}; self._col_vars = {}
        P = 10

        tk.Label(self, text=f"[{fmt_key}]",
                 font=(lf[0], 11, "bold"), bg=bg, fg=fg).pack(pady=(P, 4), padx=P)

        frm = tk.Frame(self, bg=bg); frm.pack(padx=P, pady=4)

        def add(label, key, val, r):
            tk.Label(frm, text=label, font=lf, bg=bg, fg=fg,
                     anchor="e", width=18).grid(row=r, column=0, padx=(0,6), pady=3)
            v = tk.StringVar(value=str(val))
            tk.Entry(frm, textvariable=v, width=26, bg=ebg, fg=fg,
                     insertbackground=fg, relief="flat").grid(row=r, column=1, pady=3)
            self._vars[key] = v

        add("description",    "description",    fmt_data.get("description",""), 0)
        add("header_row",     "header_row",     fmt_data.get("header_row",1),   1)
        add("data_start_row", "data_start_row", fmt_data.get("data_start_row",2), 2)

        tk.Frame(self, bg="#45475A", height=1).pack(fill="x", padx=P, pady=6)
        tk.Label(self, text="columns 매핑",
                 font=(lf[0], 10, "bold"), bg=bg, fg="#A6E3A1").pack()

        cf = tk.Frame(self, bg=bg); cf.pack(padx=P, pady=4)
        col_defs = fmt_data.get("columns", {})
        keys = list(dict.fromkeys(
            ["time","date","temp","humid","index"] + list(col_defs.keys())))
        for ri, k in enumerate(keys):
            tk.Label(cf, text=k, font=lf, bg=bg, fg=fg,
                     anchor="e", width=10).grid(row=ri, column=0, padx=(0,6), pady=2)
            v = tk.StringVar(value=col_defs.get(k, ""))
            tk.Entry(cf, textvariable=v, width=26, bg=ebg, fg=fg,
                     insertbackground=fg, relief="flat").grid(row=ri, column=1, pady=2)
            self._col_vars[k] = v

        tk.Frame(self, bg="#45475A", height=1).pack(fill="x", padx=P, pady=6)
        bf = tk.Frame(self, bg=bg); bf.pack(pady=(0, P))
        tk.Button(bf, text="저장",  bg="#89B4FA", fg="#1E1E2E",
                  font=lf, relief="flat", cursor="hand2",
                  command=self._save).pack(side="left", ipadx=10, padx=4)
        tk.Button(bf, text="취소", bg="#45475A", fg=fg,
                  font=lf, relief="flat", cursor="hand2",
                  command=self.destroy).pack(side="left", ipadx=10, padx=4)

        self.grab_set(); self.wait_window()

    def _save(self):
        try:
            self.result = {
                "description":    self._vars["description"].get(),
                "header_row":     int(self._vars["header_row"].get()),
                "data_start_row": int(self._vars["data_start_row"].get()),
                "columns": {k: v.get() for k, v in self._col_vars.items()
                            if v.get() != "" or k in ("time", "temp", "humid")}
            }
        except ValueError:
            messagebox.showerror("오류", "header_row / data_start_row 는 정수로 입력하세요.")
            return
        self.destroy()


# ══════════════════════════════════════════════
# GUI: 센서 설정 프레임
# ══════════════════════════════════════════════
class SensorConfigFrame(tk.LabelFrame):
    def __init__(self, parent, label, init, bg, fg, ebg, lf):
        super().__init__(parent, text=label, font=(lf[0], 10, "bold"),
                         bg=bg, fg=fg, bd=1, relief="groove",
                         labelanchor="nw", padx=8, pady=6)
        self.configure(background=bg)
        self.min_rows_var = tk.StringVar(value=str(init.get("min_rows", 3)))
        self.min_val_var  = tk.StringVar(value=str(init.get("min_val",  0.0)))
        for i, (lbl, var) in enumerate([
            ("min_rows  (노이즈 최소 행)", self.min_rows_var),
            ("min_val   (노이즈 임계값)",  self.min_val_var),
        ]):
            tk.Label(self, text=lbl, font=lf, bg=bg, fg=fg).grid(
                row=i, column=0, sticky="e", padx=(0, 6), pady=3)
            tk.Entry(self, textvariable=var, width=10,
                     bg=ebg, fg=fg, insertbackground=fg,
                     relief="flat").grid(row=i, column=1, sticky="w", pady=3)

    def get(self):
        return {"min_rows": int(self.min_rows_var.get()),
                "min_val":  float(self.min_val_var.get())}


# ══════════════════════════════════════════════
# GUI: 시트 행
# ══════════════════════════════════════════════
class SheetRow:
    def __init__(self, parent, sheet_name, bg, fg, lf, row_idx):
        self.sheet_name = sheet_name
        self.enabled = tk.BooleanVar(value=True)
        tk.Checkbutton(parent, variable=self.enabled, bg=bg, fg=fg,
                       selectcolor="#313244", activebackground=bg).grid(
            row=row_idx, column=0, padx=(6, 2))
        tk.Label(parent, text=sheet_name, font=lf, bg=bg, fg=fg,
                 width=28, anchor="w").grid(row=row_idx, column=1, padx=6)

    def get(self): return {"enabled": self.enabled.get()}


# ══════════════════════════════════════════════
# GUI: 메인 앱
# ══════════════════════════════════════════════
class App(tk.Tk):
    BG  = "#1E1E2E"; FG  = "#CDD6F4"
    EBG = "#313244"; BBG = "#89B4FA"; BFG = "#1E1E2E"
    SEP = "#45475A"
    LF  = ("Segoe UI", 10)
    TF  = ("Segoe UI", 13, "bold")
    PAD = 12

    def __init__(self):
        super().__init__()
        self.title("Trend Analyzer — Temperature & Humidity")
        self.configure(bg=self.BG)
        self.resizable(True, False)
        try:
            self.config_data = load_config()
        except (FileNotFoundError, json.JSONDecodeError) as e:
            messagebox.showerror("설정 파일 오류", str(e))
            self.destroy()
            return
        self._sheet_rows: list[SheetRow] = []
        self._build_ui()

    def _build_ui(self):
        P = self.PAD

        tk.Label(self, text="🌡💧 Temperature & Humidity Trend Analyzer",
                 font=self.TF, bg=self.BG, fg=self.BBG).pack(pady=(P, 4))

        # 파일 선택
        ff = tk.Frame(self, bg=self.BG); ff.pack(fill="x", padx=P, pady=4)
        tk.Label(ff, text="Excel 파일", font=self.LF,
                 bg=self.BG, fg=self.FG, width=10, anchor="e").pack(side="left")
        self.file_var = tk.StringVar()
        tk.Entry(ff, textvariable=self.file_var, bg=self.EBG, fg=self.FG,
                 insertbackground=self.FG, relief="flat").pack(
            side="left", fill="x", expand=True, padx=6)
        tk.Button(ff, text="찾아보기", bg=self.BBG, fg=self.BFG,
                  font=self.LF, relief="flat", cursor="hand2",
                  command=self._browse).pack(side="left")

        self._sep()

        # 입력 포맷
        tk.Label(self, text="📂 입력 포맷",
                 font=("Segoe UI", 10, "bold"),
                 bg=self.BG, fg="#A6E3A1").pack(pady=(0, 4))

        fo = tk.Frame(self, bg=self.BG); fo.pack(fill="x", padx=P, pady=4)

        fsel = tk.Frame(fo, bg=self.BG); fsel.pack(fill="x")
        tk.Label(fsel, text="사용 포맷", font=self.LF,
                 bg=self.BG, fg=self.FG, width=10, anchor="e").pack(side="left")
        self.fmt_var = tk.StringVar(
            value=self.config_data.get("input_format", "format1"))
        self.fmt_cb = ttk.Combobox(fsel, textvariable=self.fmt_var,
                                   width=12, state="readonly")
        self.fmt_cb.pack(side="left", padx=6)
        self.fmt_cb.bind("<<ComboboxSelected>>", lambda _: self._refresh_fmt_info())
        tk.Button(fsel, text="편집", bg="#CBA6F7", fg=self.BFG,
                  font=self.LF, relief="flat", cursor="hand2",
                  command=self._edit_fmt).pack(side="left", padx=4)

        self.fmt_info_var = tk.StringVar()
        tk.Label(fo, textvariable=self.fmt_info_var,
                 font=("Consolas", 9), bg=self.BG, fg="#A6ADC8",
                 justify="left", anchor="w").pack(fill="x", pady=(4, 0))

        self._refresh_fmt_combo()
        self._refresh_fmt_info()

        self._sep()

        # 시트 목록
        tk.Label(self, text="📋 시트 활성화",
                 font=("Segoe UI", 10, "bold"),
                 bg=self.BG, fg="#A6E3A1").pack(pady=(0, 2))

        hf = tk.Frame(self, bg=self.BG); hf.pack(fill="x", padx=P)
        for col, txt, w in [(0,"적용",4),(1,"시트명",28)]:
            tk.Label(hf, text=txt, font=("Segoe UI", 9, "bold"),
                     bg="#313244", fg="#A6ADC8", width=w,
                     padx=4, pady=2).grid(row=0, column=col, padx=2, pady=(0,2))

        self.canvas = tk.Canvas(self, bg=self.BG, highlightthickness=0, height=130)
        vsb = ttk.Scrollbar(self, orient="vertical", command=self.canvas.yview)
        self.canvas.configure(yscrollcommand=vsb.set)
        self.canvas.pack(side="left", fill="both", expand=True, padx=(P, 0))
        vsb.pack(side="left", fill="y", padx=(0, P))

        self.sf = tk.Frame(self.canvas, bg=self.BG)
        self._cw = self.canvas.create_window((0, 0), window=self.sf, anchor="nw")
        self.sf.bind("<Configure>",
                     lambda e: self.canvas.configure(scrollregion=self.canvas.bbox("all")))
        self.canvas.bind("<Configure>",
                         lambda e: self.canvas.itemconfig(self._cw, width=e.width))
        tk.Label(self.sf, text="← Excel 파일을 선택하면 시트 목록이 표시됩니다",
                 font=self.LF, bg=self.BG, fg="#585B70").grid(
            row=0, column=0, columnspan=2, pady=14)

        self._sep()

        # 센서 설정
        tk.Label(self, text="⚙ 설정 (config.json)",
                 font=("Segoe UI", 10, "bold"),
                 bg=self.BG, fg="#A6E3A1").pack(pady=(0, 4))

        cr = tk.Frame(self, bg=self.BG); cr.pack(padx=P, pady=4)
        self.temp_cfg  = SensorConfigFrame(cr, "🌡 온도 (temp)",
                                           self.config_data["temp"],
                                           self.BG, self.FG, self.EBG, self.LF)
        self.temp_cfg.grid(row=0, column=0, padx=(0, 12), sticky="n")
        self.humid_cfg = SensorConfigFrame(cr, "💧 습도 (humid)",
                                           self.config_data["humid"],
                                           self.BG, self.FG, self.EBG, self.LF)
        self.humid_cfg.grid(row=0, column=1, sticky="n")
        tk.Button(cr, text="설정\n저장", bg="#A6E3A1", fg=self.BFG,
                  font=self.LF, relief="flat", cursor="hand2",
                  command=self._save_cfg).grid(row=0, column=2,
                                               padx=(12,0), ipadx=6, ipady=4,
                                               sticky="ns")

        self._sep()

        tk.Button(self, text="▶  전체 시트 분석 실행",
                  bg=self.BBG, fg=self.BFG,
                  font=("Segoe UI", 11, "bold"), relief="flat", cursor="hand2",
                  command=self._run).pack(pady=(0, 6), ipadx=20, ipady=4)

        self.log_box = tk.Text(self, height=8, bg="#181825", fg="#BAC2DE",
                               font=("Consolas", 9), relief="flat", state="disabled")
        self.log_box.pack(fill="x", padx=P, pady=(0, P))

    def _sep(self):
        tk.Frame(self, bg=self.SEP, height=1).pack(fill="x", padx=self.PAD, pady=6)

    def _refresh_fmt_combo(self):
        keys = list(self.config_data["formats"].keys())
        self.fmt_cb["values"] = keys
        if self.fmt_var.get() not in keys and keys:
            self.fmt_var.set(keys[0])

    def _refresh_fmt_info(self):
        key = self.fmt_var.get()
        fmt = self.config_data["formats"].get(key, {})
        cols = fmt.get("columns", {})
        col_str = "  |  ".join(f"{k}='{v}'" for k, v in cols.items() if v)
        self.fmt_info_var.set(
            f"  헤더={fmt['header_row']}행  "
            f"데이터 시작={fmt['data_start_row']}행\n"
            f"  {col_str}")

    def _edit_fmt(self):
        key = self.fmt_var.get()
        dlg = FormatEditorDialog(self, key,
                                 self.config_data["formats"].get(key, {}),
                                 self.BG, self.FG, self.EBG, self.LF)
        if dlg.result:
            self.config_data["formats"][key] = dlg.result
            self._refresh_fmt_info()
            self._log(f"포맷 [{key}] 수정됨")

    def _browse(self):
        path = filedialog.askopenfilename(
            title="Excel 파일 선택",
            filetypes=[("Excel files", "*.xlsx *.xlsm *.xls"), ("All files", "*.*")])
        if not path: return
        self.file_var.set(path)
        self._load_sheets(path)

    def _load_sheets(self, path):
        for w in self.sf.winfo_children(): w.destroy()
        self._sheet_rows.clear()
        self._log("시트 목록 로드 중 (win32)...")
        self.update_idletasks()
        try:
            names = get_sheet_names(path)
        except Exception as e:
            messagebox.showerror("오류", f"파일 읽기 실패:\n{e}"); return
        for i, sname in enumerate(names):
            sr = SheetRow(self.sf, sname, self.BG, self.FG, self.LF, i)
            self._sheet_rows.append(sr)
        self._log(f"로드 완료: {len(names)}개 시트 — " + ", ".join(names))

    def _save_cfg(self):
        try:
            cfg = self.config_data.copy()
            cfg["input_format"] = self.fmt_var.get()
            cfg["temp"]  = self.temp_cfg.get()
            cfg["humid"] = self.humid_cfg.get()
            save_config(cfg)
            self.config_data = cfg
            self._log(
                f"설정 저장  포맷=[{cfg['input_format']}]\n"
                f"  🌡 temp : min_rows={cfg['temp']['min_rows']}, min_val={cfg['temp']['min_val']}\n"
                f"  💧 humid: min_rows={cfg['humid']['min_rows']}, min_val={cfg['humid']['min_val']}")
        except ValueError:
            messagebox.showerror("오류", "min_rows는 정수, min_val은 실수로 입력하세요.")

    def _run(self):
        filepath = self.file_var.get().strip()
        if not filepath or not os.path.exists(filepath):
            messagebox.showerror("오류", "유효한 Excel 파일을 선택하세요."); return
        if not self._sheet_rows:
            messagebox.showerror("오류", "시트 정보가 없습니다. 파일을 다시 선택하세요."); return
        try:
            cfg = self.config_data.copy()
            cfg["input_format"] = self.fmt_var.get()
            cfg["temp"]  = self.temp_cfg.get()
            cfg["humid"] = self.humid_cfg.get()
        except ValueError:
            messagebox.showerror("오류", "min_rows / min_val 값을 확인하세요."); return

        sheet_cfg_map = {sr.sheet_name: sr.get() for sr in self._sheet_rows}

        self._log("═" * 56)
        self._log(f"파일: {os.path.basename(filepath)}")

        def status(msg):
            self._log(msg); self.update_idletasks()

        try:
            _, done = process_all_sheets(
                filepath, sheet_cfg_map, cfg, status_cb=status)
            messagebox.showinfo(
                "완료",
                f"분석 완료!\n처리 시트: {len(done)}개\n저장: {os.path.basename(filepath)}")
        except Exception as e:
            self._log(f"[오류] {e}"); messagebox.showerror("오류", str(e))

    def _log(self, msg):
        self.log_box.configure(state="normal")
        self.log_box.insert("end", msg + "\n")
        self.log_box.see("end")
        self.log_box.configure(state="disabled")


# ══════════════════════════════════════════════
if __name__ == "__main__":
    app = App()
    app.mainloop()
