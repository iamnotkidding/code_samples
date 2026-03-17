"""
Excel Trend Analyzer — Temperature & Humidity
- win32com.client 단독 사용 (openpyxl 미사용)
- 셀 색깔 / 폰트 / 정렬 없음 — 값만 기록
- 3가지 입력 포맷 지원 (config.json)
- 전체 시트 각각 독립 처리
- 온도 / 습도 각각 독립 min_rows
- 결과: 입력 파일에 직접 Temp_Trend / Humid_Trend 컬럼 추가 후 저장
"""

import json
import os
import tkinter as tk
from tkinter import ttk, filedialog, messagebox

import threading
import win32com.client


# ══════════════════════════════════════════════
# CONFIG
# ══════════════════════════════════════════════
CONFIG_FILE = os.path.splitext(os.path.abspath(__file__))[0] + ".json"


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
def analyze_trends(values: list, min_rows: int, fill_rows: int) -> list:
    """
    UP/DOWN 추세 분석 (4단계).

    Step1: 값 비교로 raw 방향 결정
           values[i] > values[i-1] → UP
           values[i] < values[i-1] → DOWN
           values[i] == values[i-1] → "" (flat)
           index 0 은 항상 ""

    Step2: UP 구간 사이에 flat이 fill_rows 이하면 UP으로 채움
           (UP ... flat×N ... UP  →  N <= fill_rows 이면 flat을 UP으로)

    Step3: DOWN 구간 사이에 flat이 fill_rows 이하면 DOWN으로 채움

    Step4: 연속 UP/DOWN 길이가 min_rows 미만이면 "" (ignore)
    """
    n = len(values)
    if n == 0:
        return []

    # ── Step1: 인접 비교로 raw 방향 ─────────────────────────
    raw = [""] * n
    for i in range(1, n):
        if   values[i] > values[i - 1]: raw[i] = "UP"
        elif values[i] < values[i - 1]: raw[i] = "DOWN"

    # ── Step2 & 3: 방향 사이 flat 채움 ──────────────────────
    def fill_flat_between(direction: str, data: list) -> list:
        """
        같은 direction 구간 사이에 끼인 flat("") 구간이
        fill_rows 이하면 해당 방향으로 채움.
        """
        result = data[:]
        i = 0
        while i < n:
            if result[i] != direction:
                i += 1
                continue
            # direction 구간 끝 찾기
            j = i
            while j < n and result[j] == direction:
                j += 1
            # j부터 flat 구간 길이 측정
            k = j
            while k < n and result[k] == "":
                k += 1
            flat_len = k - j
            # flat 뒤가 같은 direction 이고 flat 길이 <= fill_rows 이면 채움
            if k < n and result[k] == direction and flat_len <= fill_rows:
                for idx in range(j, k):
                    result[idx] = direction
                i = k  # 채운 구간 이후부터 재탐색
            else:
                i = j
        return result

    # UP 사이 flat 채움
    filled = fill_flat_between("UP",   raw)
    # DOWN 사이 flat 채움
    filled = fill_flat_between("DOWN", filled)

    # ── Step4: min_rows 미만 구간 → "" (ignore) ─────────────
    # 연속 구간 추출
    segments = []
    i = 0
    while i < n:
        d = filled[i]; j = i
        while j < n and filled[j] == d:
            j += 1
        segments.append((i, j - 1, d))
        i = j

    result = [""] * n
    for s, e, d in segments:
        if d in ("UP", "DOWN") and (e - s + 1) >= min_rows:
            for k in range(s, e + 1):
                result[k] = d
        # min_rows 미만 또는 flat → "" 유지

    return result



def count_groups(trends: list, direction: str) -> int:
    """연속된 direction 구간(그룹)의 개수를 반환"""
    count = 0
    in_group = False
    for v in trends:
        if v == direction:
            if not in_group:
                count += 1
                in_group = True
        else:
            in_group = False
    return count

# ══════════════════════════════════════════════
# 포맷 자동 인식
# ══════════════════════════════════════════════
def detect_format(filepath: str, formats: dict) -> tuple:
    """
    첫 번째 시트 헤더를 읽어 formats 중 가장 일치하는 포맷 키와
    실제 매칭된 컬럼 전체 이름을 반환.

    반환: (fmt_key: str, matched: dict)
      matched = {
          "temp":  "실제 헤더 셀 값" or None,
          "humid": "실제 헤더 셀 값" or None,
      }
    컬럼명 매칭: 부분 문자열 포함, 대소문자 무시.
    모두 불일치하면 formats 첫 번째 키 반환.
    """
    xl = win32com.client.DispatchEx("Excel.Application")
    xl.Visible = False; xl.DisplayAlerts = False; xl.ScreenUpdating = False
    try:
        wb = xl.Workbooks.Open(os.path.abspath(filepath),
                               UpdateLinks=False, ReadOnly=True)
        ws = wb.Sheets(1)
        ur = ws.UsedRange
        last_col = ur.Column + ur.Columns.Count - 1

        best_key, best_score, best_headers_raw = None, -1, []

        for fmt_key, fmt in formats.items():
            header_row = int(fmt.get("header_row", 1))
            col_map    = fmt.get("columns", {})
            required   = [v.strip().lower() for v in col_map.values()
                          if v and v.strip()]
            if not required:
                continue
            hdr_raw = ws.Range(
                ws.Cells(header_row, 1),
                ws.Cells(header_row, last_col)
            ).Value
            # 원본 헤더(대소문자 보존) 와 소문자 버전 함께 보관
            headers_orig = []
            headers_low  = []
            if hdr_raw:
                for v in hdr_raw[0]:
                    orig = str(v).strip() if v is not None else ""
                    headers_orig.append(orig)
                    headers_low.append(orig.lower())

            score = 0
            for req in required:
                if any(req in h or h in req for h in headers_low if h):
                    score += 1

            if score > best_score:
                best_score, best_key = score, fmt_key
                best_headers_raw = headers_orig

        wb.Close(False)

        if not best_key:
            best_key = next(iter(formats))

        # 선택된 포맷의 temp/humid config 명으로 실제 헤더명 매칭
        fmt     = formats[best_key]
        col_map = fmt.get("columns", {})
        matched = {}
        for role in ("temp", "humid"):
            cfg_val = col_map.get(role, "")
            if cfg_val is None:
                cfg_val = ""
            cfg_name = cfg_val.strip().lower()

            found = None
            if cfg_name == "":
                # config 값이 빈 문자열 → 헤더도 빈 문자열인 첫 번째 셀
                for orig in best_headers_raw:
                    if orig.strip() == "":
                        found = ""   # 빈 셀 찾음 (None과 구분하기 위해 "" 반환)
                        break
            else:
                # config 값이 있으면 부분 문자열 매칭
                for orig in best_headers_raw:
                    lo = orig.lower()
                    if cfg_name in lo or lo in cfg_name:
                        found = orig
                        break
            matched[role] = found   # None 이면 못 찾은 것

        return best_key, matched
    finally:
        xl.Quit()

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
            """
            config 값이 "" → 헤더가 빈 문자열인 첫 번째 열 반환
            config 값이 있음 → 부분 문자열 포함 매칭 (대소문자 무시)
            찾지 못하면 -1 반환
            """
            name_stripped = name.strip()
            if name_stripped == "":
                # 빈 문자열 config → 헤더가 빈 셀인 첫 번째 열
                for i, h in enumerate(headers):
                    if h.strip() == "":
                        return i + 1
                return -1
            name_low = name_stripped.lower()
            for i, h in enumerate(headers):
                hl = h.lower()
                if name_low in hl or hl in name_low:
                    return i + 1
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
    """결과 컬럼을 값만 기록 (대용량 최적화: Range 일괄 쓰기)

    기존 col_name 헤더 컬럼 검색 범위:
      - header_row 행의 A열(1)부터 실제 사용 열 끝까지 읽되
      - UsedRange 컬럼 오프셋을 보정하여 정확한 절대 열 번호 사용
    """
    # UsedRange 절대 열 범위 계산 (UsedRange.Column은 1-based 절대 열)
    ur          = ws.UsedRange
    ur_first_col = ur.Column                            # 절대 열 시작
    ur_last_col  = ur.Column + ur.Columns.Count - 1     # 절대 열 끝

    # header_row 행 전체를 1열부터 ur_last_col까지 읽기
    hdr_range = ws.Range(ws.Cells(header_row, 1),
                         ws.Cells(header_row, ur_last_col))
    hdr_raw   = hdr_range.Value
    # Value는 행이 1개여도 2D 튜플((v1,v2,...),) 형태로 반환
    if hdr_raw and isinstance(hdr_raw[0], tuple):
        hdr_list = list(hdr_raw[0])
    elif hdr_raw:
        hdr_list = list(hdr_raw)
    else:
        hdr_list = []

    headers = [str(v).strip() if v is not None else "" for v in hdr_list]

    # col_name 과 정확히 일치하는 열 찾기 (1-based 절대 열)
    col = None
    for i, h in enumerate(headers):
        if h == col_name:
            col = i + 1   # headers 인덱스 → 1-based 절대 열
            break
    if col is None:
        col = ur_last_col + 1   # 없으면 UsedRange 오른쪽 다음 열

    # 헤더 쓰기
    ws.Cells(header_row, col).Value = col_name

    # 데이터 일괄 쓰기
    if trends:
        data_2d = tuple((v,) for v in trends)
        ws.Range(
            ws.Cells(data_start_row, col),
            ws.Cells(data_start_row + len(trends) - 1, col)
        ).Value = data_2d


# ══════════════════════════════════════════════
# 전체 파일 처리
# ══════════════════════════════════════════════
def process_all_sheets(filepath: str, sheet_cfg_map: dict,
                       config: dict, status_cb=None, sheet_result_cb=None):
    """
    sheet_result_cb(sheet_name, t_up, t_down, h_up, h_down):
        각 시트 처리 완료 시 UP/DOWN 카운트를 UI로 전달
    """
    def log(msg):
        if status_cb: status_cb(msg)

    # 포맷 자동 인식
    fmt_key = detect_format(filepath, config["formats"])
    fmt            = config["formats"][fmt_key]
    header_row     = int(fmt["header_row"])
    data_start_row = int(fmt["data_start_row"])
    col_map        = fmt["columns"]
    temp_col_name  = col_map.get("temp",  "")
    humid_col_name = col_map.get("humid", "")

    t_cfg = config["temp"]
    h_cfg = config["humid"]

    log(f"포맷 자동 인식: [{fmt_key}] {fmt.get('description','')}")
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
            _t_up = _t_down = _h_up = _h_down = 0

            if temp_vals:
                trends = analyze_trends(temp_vals,
                                        int(t_cfg["min_rows"]),
                                        int(t_cfg["fill_rows"]))
                write_trend_col(ws, header_row, data_start_row,
                                "Temp_Trend", trends)
                _t_up   = count_groups(trends, 'UP')
                _t_down = count_groups(trends, 'DOWN')
                log(f"  [{sheet_name}] 🌡 온도  UP그룹={_t_up}, DOWN그룹={_t_down}")
                sheet_done = True
            else:
                log(f"  [{sheet_name}] 🌡 온도 컬럼 없음")

            if humid_vals:
                trends = analyze_trends(humid_vals,
                                        int(h_cfg["min_rows"]),
                                        int(h_cfg["fill_rows"]))
                write_trend_col(ws, header_row, data_start_row,
                                "Humid_Trend", trends)
                _h_up   = count_groups(trends, 'UP')
                _h_down = count_groups(trends, 'DOWN')
                log(f"  [{sheet_name}] 💧 습도  UP그룹={_h_up}, DOWN그룹={_h_down}")
                sheet_done = True
            else:
                log(f"  [{sheet_name}] 💧 습도 컬럼 없음")

            if sheet_done:
                processed.append(sheet_name)
                if sheet_result_cb:
                    sheet_result_cb(sheet_name, _t_up, _t_down, _h_up, _h_down)

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
        self.min_rows_var  = tk.StringVar(value=str(init.get("min_rows",  3)))
        self.fill_rows_var = tk.StringVar(value=str(init.get("fill_rows", 1)))
        for i, (lbl, var) in enumerate([
            ("min_rows  (UP/DOWN 최소 연속 행)", self.min_rows_var),
            ("fill_rows (flat 허용 연속 행)",    self.fill_rows_var),
        ]):
            tk.Label(self, text=lbl, font=lf, bg=bg, fg=fg).grid(
                row=i, column=0, sticky="e", padx=(0, 6), pady=3)
            tk.Entry(self, textvariable=var, width=10,
                     bg=ebg, fg=fg, insertbackground=fg,
                     relief="flat").grid(row=i, column=1, sticky="w", pady=3)

    def get(self):
        return {"min_rows":  int(self.min_rows_var.get()),
                "fill_rows": int(self.fill_rows_var.get())}


# ══════════════════════════════════════════════
# GUI: 시트 행
# ══════════════════════════════════════════════
class SheetRow:
    def __init__(self, parent, sheet_name, bg, fg, lf, row_idx):
        self.sheet_name = sheet_name
        self.enabled = tk.BooleanVar(value=True)
        self._result_var = tk.StringVar(value="")

        r = row_idx + 1   # row=0은 헤더 행
        tk.Checkbutton(parent, variable=self.enabled, bg=bg, fg=fg,
                       selectcolor="#313244", activebackground=bg).grid(
            row=r, column=0, padx=(6, 2), sticky="w")
        tk.Label(parent, text=sheet_name, font=lf, bg=bg, fg=fg,
                 width=20, anchor="w").grid(row=r, column=1, padx=4, sticky="w")
        self._result_lbl = tk.Label(parent, textvariable=self._result_var,
                 font=("Consolas", 9), bg=bg, fg="#A6E3A1",
                 anchor="w")
        self._result_lbl.grid(row=r, column=2, padx=4, sticky="ew")
        parent.columnconfigure(2, weight=1)

    def set_result(self, t_up, t_down, h_up, h_down):
        self._result_var.set(
            f"🌡UP그룹={t_up} DN그룹={t_down}  💧UP그룹={h_up} DN그룹={h_down}")
        # UP 또는 DOWN 이 1개 이상이면 파란색, 모두 0이면 기본색
        has_result = (t_up + t_down + h_up + h_down) > 0
        self._result_lbl.configure(fg="#89B4FA" if has_result else "#585B70")
        # 텍스트 변경 후 헤더 동기화 요청
        self._result_lbl.after_idle(self._sync_header)

    def _sync_header(self):
        pass   # sf와 헤더가 같은 grid이므로 자동 동기화

    def clear_result(self):
        self._result_var.set("")

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
        self.geometry("900x700")
        self.minsize(700, 400)
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

        tk.Label(fo, text="파일 선택 시 헤더를 읽어 포맷을 자동 인식합니다.",
                 font=("Consolas", 9), bg=self.BG, fg="#A6ADC8").pack(anchor="w")

        fsel = tk.Frame(fo, bg=self.BG); fsel.pack(fill="x", pady=(4,0))
        self.fmt_info_var = tk.StringVar(value="(파일 미선택)")
        tk.Label(fsel, text="인식 포맷", font=self.LF,
                 bg=self.BG, fg=self.FG, width=10, anchor="e").pack(side="left")
        tk.Label(fsel, textvariable=self.fmt_info_var,
                 font=("Consolas", 9), bg=self.BG, fg="#89DCEB").pack(side="left", padx=6)
        tk.Button(fsel, text="편집", bg="#CBA6F7", fg=self.BFG,
                  font=self.LF, relief="flat", cursor="hand2",
                  command=self._edit_fmt).pack(side="left", padx=4)

        self._sep()

        # 시트 목록
        tk.Label(self, text="📋 시트 활성화",
                 font=("Segoe UI", 10, "bold"),
                 bg=self.BG, fg="#A6E3A1").pack(pady=(0, 2))

        self.canvas = tk.Canvas(self, bg=self.BG, highlightthickness=0, height=160)
        vsb = ttk.Scrollbar(self, orient="vertical", command=self.canvas.yview)
        self.canvas.configure(yscrollcommand=vsb.set)
        self.canvas.pack(side="left", fill="both", expand=True, padx=(P, 0))
        vsb.pack(side="left", fill="y", padx=(0, P))

        self.sf = tk.Frame(self.canvas, bg=self.BG)
        self._cw = self.canvas.create_window((0, 0), window=self.sf, anchor="nw")
        self.canvas.bind("<Configure>", self._on_canvas_resize)
        self.canvas.bind("<MouseWheel>", self._on_mousewheel)
        self.canvas.bind("<Button-4>",   self._on_mousewheel)
        self.canvas.bind("<Button-5>",   self._on_mousewheel)
        self.sf.bind("<Configure>", self._on_sf_configure)

        # 헤더를 sf row=0에 배치 — 시트 행과 같은 grid, 컬럼 너비 자동 동기화
        for col, txt, w in [(0,"적용",4),(1,"시트명",20),(2,"분석 결과",0)]:
            lbl = tk.Label(self.sf, text=txt, font=("Segoe UI", 9, "bold"),
                     bg="#313244", fg="#A6ADC8", padx=4, pady=2)
            if w: lbl.configure(width=w)
            lbl.grid(row=0, column=col, padx=2, pady=(0, 2),
                     sticky="ew" if col == 2 else "w")
        self.sf.columnconfigure(2, weight=1)

        self._hint_lbl = tk.Label(
            self.sf, text="← Excel 파일을 선택하면 시트 목록이 표시됩니다",
            font=self.LF, bg=self.BG, fg="#585B70")
        self._hint_lbl.grid(row=1, column=0, columnspan=3, pady=14)

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

    def _on_sf_configure(self, event):
        """sf 크기 변경 시: 스크롤 영역 갱신 + 하위 휠 바인딩"""
        self.canvas.configure(scrollregion=self.canvas.bbox("all"))
        self._bind_scroll(self.sf)

    def _on_canvas_resize(self, event):
        self.canvas.itemconfig(self._cw, width=event.width)
        self.sf.columnconfigure(2, weight=1)

    def _on_mousewheel(self, event):
        """Windows / macOS 공통 마우스 휠 스크롤"""
        if event.num == 4:        # Linux scroll up
            self.canvas.yview_scroll(-1, "units")
        elif event.num == 5:      # Linux scroll down
            self.canvas.yview_scroll(1, "units")
        else:                     # Windows / macOS
            self.canvas.yview_scroll(int(-1 * (event.delta / 120)), "units")

    def _bind_scroll(self, widget):
        """위젯과 그 하위 위젯에 마우스 휠 이벤트 바인딩"""
        widget.bind("<MouseWheel>", self._on_mousewheel)   # Windows/macOS
        widget.bind("<Button-4>",   self._on_mousewheel)   # Linux up
        widget.bind("<Button-5>",   self._on_mousewheel)   # Linux down
        for child in widget.winfo_children():
            self._bind_scroll(child)

    def _sep(self):
        tk.Frame(self, bg=self.SEP, height=1).pack(fill="x", padx=self.PAD, pady=6)

    def _edit_fmt(self):
        keys = list(self.config_data["formats"].keys())
        if not keys:
            messagebox.showinfo("알림", "config.json에 formats가 없습니다."); return
        # 현재 인식된 포맷을 기본 선택
        cur = getattr(self, "_detected_fmt", keys[0])
        if cur not in keys: cur = keys[0]
        # 포맷 선택 다이얼로그
        dlg_sel = tk.Toplevel(self)
        dlg_sel.title("편집할 포맷 선택"); dlg_sel.configure(bg=self.BG)
        dlg_sel.resizable(False, False)
        tk.Label(dlg_sel, text="편집할 포맷", font=self.LF,
                 bg=self.BG, fg=self.FG).pack(padx=12, pady=(12,4))
        sel_var = tk.StringVar(value=cur)
        cb = ttk.Combobox(dlg_sel, textvariable=sel_var, values=keys,
                          width=16, state="readonly")
        cb.pack(padx=12, pady=4)
        chosen = [None]
        def on_ok():
            chosen[0] = sel_var.get(); dlg_sel.destroy()
        tk.Button(dlg_sel, text="선택", bg=self.BBG, fg=self.BFG,
                  font=self.LF, relief="flat", cursor="hand2",
                  command=on_ok).pack(pady=(4,12), ipadx=10)
        dlg_sel.grab_set(); dlg_sel.wait_window()
        if not chosen[0]: return
        key = chosen[0]
        dlg = FormatEditorDialog(self, key,
                                 self.config_data["formats"].get(key, {}),
                                 self.BG, self.FG, self.EBG, self.LF)
        if dlg.result:
            self.config_data["formats"][key] = dlg.result
            self._log(f"포맷 [{key}] 수정됨")

    def _browse(self):
        path = filedialog.askopenfilename(
            title="Excel 파일 선택",
            filetypes=[("Excel files", "*.xlsx *.xlsm *.xls"), ("All files", "*.*")])
        if not path: return
        self.file_var.set(path)
        self._load_sheets(path)

    def _load_sheets(self, path):
        for w in list(self.sf.winfo_children()):
            info = w.grid_info()
            if info and int(info.get("row", 0)) >= 1:
                w.destroy()
        self._sheet_rows.clear()
        self._hint_lbl = tk.Label(
            self.sf, text="← Excel 파일을 선택하면 시트 목록이 표시됩니다",
            font=self.LF, bg=self.BG, fg="#585B70")
        self._hint_lbl.grid(row=1, column=0, columnspan=3, pady=14)
        self._log("시트 목록 로드 중 (win32)...")
        self._set_running(True)

        def worker():
            try:
                names                = get_sheet_names(path)
                detected, matched_cols = detect_format(path, self.config_data["formats"])
                self.after(0, lambda: self._on_load_done(path, names, detected, matched_cols))
            except Exception as e:
                self.after(0, lambda err=e: self._on_load_error(err))

        threading.Thread(target=worker, daemon=True).start()

    def _on_load_done(self, path, names, detected, matched_cols):
        self._set_running(False)
        if hasattr(self, "_hint_lbl") and self._hint_lbl.winfo_exists():
            self._hint_lbl.destroy()
        for i, sname in enumerate(names):
            sr = SheetRow(self.sf, sname, self.BG, self.FG, self.LF, i)
            self._sheet_rows.append(sr)
        self._log(f"로드 완료: {len(names)}개 시트 — " + ", ".join(names))
        try:
            fmt = self.config_data["formats"][detected]
            self.fmt_info_var.set(
                f"[{detected}]  헤더={fmt['header_row']}행  "
                f"데이터={fmt['data_start_row']}행")
            self._detected_fmt = detected
            self._log(f"포맷 자동 인식: [{detected}] {fmt.get('description','')}")

            cols_cfg = fmt.get("columns", {})

            def log_col(role, icon):
                found    = matched_cols.get(role)
                cfg_val  = cols_cfg.get(role, "")
                if found is None:
                    # 못 찾음
                    self._log_error(
                        f"  {icon} {role} 컬럼: 찾을 수 없음"
                        f" (config: '{cfg_val}')")
                elif found == "":
                    # 빈 셀 매칭 성공
                    self._log(f"  {icon} {role} 컬럼: (빈 셀)")
                else:
                    self._log(f"  {icon} {role} 컬럼: '{found}'")

            log_col("temp",  "🌡")
            log_col("humid", "💧")

        except Exception as e:
            self.fmt_info_var.set("(인식 실패)")
            self._log_error(f"포맷 인식 실패: {e}")

    def _on_load_error(self, err):
        self._set_running(False)
        messagebox.showerror("오류", f"파일 읽기 실패:\n{err}")

    def _save_cfg(self):
        try:
            cfg = self.config_data.copy()
            cfg["temp"]  = self.temp_cfg.get()
            cfg["humid"] = self.humid_cfg.get()
            save_config(cfg)
            self.config_data = cfg
            self._log(
                f"설정 저장\n"
                f"  🌡 temp : min_rows={cfg['temp']['min_rows']}, fill_rows={cfg['temp']['fill_rows']}\n"
                f"  💧 humid: min_rows={cfg['humid']['min_rows']}, fill_rows={cfg['humid']['fill_rows']}")
        except ValueError:
            messagebox.showerror("오류", "min_rows는 정수로 입력하세요.")

    def _run(self):
        filepath = self.file_var.get().strip()
        if not filepath or not os.path.exists(filepath):
            messagebox.showerror("오류", "유효한 Excel 파일을 선택하세요."); return
        if not self._sheet_rows:
            messagebox.showerror("오류", "시트 정보가 없습니다. 파일을 다시 선택하세요."); return
        try:
            cfg = self.config_data.copy()
            cfg["temp"]  = self.temp_cfg.get()
            cfg["humid"] = self.humid_cfg.get()
        except ValueError:
            messagebox.showerror("오류", "min_rows 값을 확인하세요."); return

        sheet_cfg_map = {sr.sheet_name: sr.get() for sr in self._sheet_rows}

        # 결과 표시 초기화
        for sr in self._sheet_rows:
            sr.clear_result()

        self._log("═" * 56)
        self._log(f"파일: {os.path.basename(filepath)}")
        self._set_running(True)

        # 시트별 결과를 UI 스레드에 안전하게 반영
        sr_map = {sr.sheet_name: sr for sr in self._sheet_rows}

        def on_sheet_result(sheet_name, t_up, t_down, h_up, h_down):
            sr = sr_map.get(sheet_name)
            if sr:
                self.after(0, lambda: sr.set_result(t_up, t_down, h_up, h_down))

        def on_log(msg):
            self.after(0, lambda m=msg: self._log(m))

        def worker():
            try:
                _, done = process_all_sheets(
                    filepath, sheet_cfg_map, cfg,
                    status_cb=on_log,
                    sheet_result_cb=on_sheet_result)
                self.after(0, lambda: self._on_done(len(done), filepath))
            except Exception as e:
                self.after(0, lambda err=e: self._on_error(err))

        threading.Thread(target=worker, daemon=True).start()

    def _set_running(self, running: bool):
        state = "disabled" if running else "normal"
        for w in self.winfo_children():
            try: w.configure(state=state)
            except: pass

    def _on_done(self, count: int, filepath: str):
        self._set_running(False)
        self._log(f"✅ 완료: {count}개 시트 처리")
        messagebox.showinfo(
            "완료",
            f"분석 완료!\n처리 시트: {count}개\n저장: {os.path.basename(filepath)}")

    def _on_error(self, err: Exception):
        self._set_running(False)
        self._log(f"[오류] {err}")
        messagebox.showerror("오류", str(err))

    def _log(self, msg):
        self.log_box.configure(state="normal")
        self.log_box.insert("end", msg + "\n")
        self.log_box.see("end")
        self.log_box.configure(state="disabled")

    def _log_error(self, msg):
        """빨간색으로 에러 메시지 출력"""
        self.log_box.configure(state="normal")
        self.log_box.insert("end", msg + "\n", "error")
        self.log_box.tag_configure("error", foreground="#F38BA8")
        self.log_box.see("end")
        self.log_box.configure(state="disabled")


# ══════════════════════════════════════════════
if __name__ == "__main__":
    app = App()
    app.mainloop()
