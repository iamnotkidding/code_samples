"""Excel Trend Analyzer — Temperature & Humidity
win32com 기반, config.json 포맷 자동 인식, 결과 컬럼 직접 기록
"""

import json
import os
import sys
import tkinter as tk
from tkinter import ttk, filedialog, messagebox

import threading
from datetime import datetime, timedelta
import win32com.client

# ══════════════════════════════════════════════
# CONFIG
# ══════════════════════════════════════════════
# exe 빌드(PyInstaller)면 sys.executable, .py면 __file__ 기준
if getattr(sys, "frozen", False):
    CONFIG_FILE = os.path.splitext(os.path.abspath(sys.executable))[0] + ".json"
else:
    CONFIG_FILE = os.path.splitext(os.path.abspath(__file__))[0] + ".json"

def load_config() -> dict:
    if not os.path.exists(CONFIG_FILE):
        raise FileNotFoundError(
            f"{os.path.basename(CONFIG_FILE)} 파일이 없습니다.\n"
            f"실행 경로에 {os.path.basename(CONFIG_FILE)} 을 생성한 후 다시 시작하세요.\n"
            f"경로: {os.path.abspath(CONFIG_FILE)}")
    with open(CONFIG_FILE, "r", encoding="utf-8") as f:
        return json.load(f)

def save_config(cfg: dict):
    with open(CONFIG_FILE, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2, ensure_ascii=False)

# ══════════════════════════════════════════════
# 추세 분석
# ══════════════════════════════════════════════
def analyze_trends(values: list,
                   min_rate: float    = 0.0,
                   final_min_rows: int = 3,
                   timestamps: list   = None) -> list:
    """
    UP/DOWN 그룹 감지 — 피크/밸리 기반 경계 결정.

    DOWN 그룹:
      시작: 직전 FLAT 구간의 최고값 행 (하강 시작 직전 피크)
      끝  : 하강 후 분당 변화량 >= +min_rate 로 반전되기 직전의 최저값 행

    UP 그룹:
      시작: DOWN 끝 이후 첫 번째 rate >= +min_rate 행
      끝  : 연속 rate >= +min_rate 구간의 마지막 행

    파라미터:
      min_rate      : 유효 변화로 인정하는 최소 분당 변화율
      final_min_rows: 최종 유효 그룹 최소 행 수
    """
    n = len(values)
    if n == 0:
        return []

    ts_n = len(timestamps) if timestamps else 0

    def get_rate(i):
        if i == 0: return 0.0
        dv = values[i] - values[i - 1]
        if i < ts_n and i - 1 < ts_n:
            ti, tj = timestamps[i - 1], timestamps[i]
            if ti and tj and tj != ti:
                dt = (tj - ti).total_seconds() / 60.0
                return dv / dt if dt != 0 else 0.0
        return float(dv)

    rates = [get_rate(i) for i in range(n)]
    result = [0] * n

    i = 0
    while i < n:
        # DOWN 시작 탐지: rate <= -min_rate
        if rates[i] <= -min_rate:
            # DOWN 시작: 현재 행(i) 직전 FLAT/안정 구간의 최고값 행
            j = i - 1
            while j > 0 and abs(rates[j]) < min_rate:
                j -= 1
            # j+1 ~ i-1 사이에서 최고값 행 탐색
            search_start = j + 1 if j >= 0 else 0
            search_end   = i      # i-1까지 (i 포함 시 하강 행 포함되므로)
            if search_start < search_end:
                peak_idx = max(range(search_start, search_end),
                               key=lambda x: values[x])
            else:
                peak_idx = i - 1 if i > 0 else 0

            # DOWN 활성 구간 끝 탐색: rate <= -min_rate 연속
            k = i
            while k < n and rates[k] <= -min_rate:
                k += 1
            # k = 활성 하강 종료 직후

            # 밸리 탐색: k 이후 rate >= +min_rate 가 나오기 전까지 최저값
            valley_idx = k - 1
            m = k
            while m < n and rates[m] < min_rate:
                if values[m] < values[valley_idx]:
                    valley_idx = m
                m += 1

            down_start = peak_idx
            down_end   = valley_idx

            if down_end > down_start and (down_end - down_start + 1) >= final_min_rows:
                for idx in range(down_start, down_end + 1):
                    result[idx] = -1

            # UP 탐지: down_end 이후 첫 번째 rate >= +min_rate 행 탐색
            p = down_end + 1
            while p < n and rates[p] < min_rate:
                p += 1
            # p = UP 시작 (rate >= min_rate 첫 행)

            if p < n and rates[p] >= min_rate:
                up_start = p
                up_end   = p
                while p < n and rates[p] >= min_rate:
                    up_end = p
                    p += 1

                if (up_end - up_start + 1) >= final_min_rows:
                    for idx in range(up_start, up_end + 1):
                        result[idx] = 1

                i = up_end + 1
            else:
                i = down_end + 1
        else:
            i += 1

    _MAP = {1: "UP", -1: "DOWN", 0: ""}
    return [_MAP[c] for c in result]


def count_groups(trends: list, direction: str) -> int:
    """연속 direction 구간 개수"""
    return sum(1 for i, v in enumerate(trends)
               if v == direction and (i == 0 or trends[i-1] != direction))

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
        ws = None
        for sh in wb.Sheets:
            if sh.Name != RESULT_SHEET:
                ws = sh
                break
        if ws is None:
            ws = wb.Sheets(1)
        ur = ws.UsedRange
        last_col = ur.Column + ur.Columns.Count - 1

        best_key, best_score = None, -1
        fmt_headers = {}   # fmt_key → headers_orig (재사용)

        for fmt_key, fmt in formats.items():
            header_row = int(fmt.get("header_row", 1))
            col_map    = fmt.get("columns", {})

            hdr_raw = ws.Range(
                ws.Cells(header_row, 1),
                ws.Cells(header_row, last_col)
            ).Value
            headers_orig = []
            if hdr_raw:
                row = hdr_raw[0] if isinstance(hdr_raw[0], tuple) else hdr_raw
                for v in row:
                    headers_orig.append(str(v).strip() if v is not None else "")
            fmt_headers[fmt_key] = headers_orig

            score = 0
            for cfg_val in col_map.values():
                if cfg_val is None:
                    cfg_val = ""
                cfg_name = cfg_val.strip().lower()

                if cfg_name == "":
                    if any(h == "" for h in headers_orig):
                        score += 1
                else:
                    if any(cfg_name in h.lower() or h.lower() in cfg_name
                           for h in headers_orig if h != ""):
                        score += 1

            if score > best_score:
                best_score, best_key = score, fmt_key

        wb.Close(False)

        if not best_key:
            best_key = next(iter(formats))

        fmt            = formats[best_key]
        col_map        = fmt.get("columns", {})
        headers_orig   = fmt_headers.get(best_key, [])
        matched        = {}

        for role in ("temp", "humid"):
            cfg_val  = col_map.get(role, "") or ""
            cfg_name = cfg_val.strip().lower()
            found    = None

            if cfg_name == "":
                for orig in headers_orig:
                    if orig == "":
                        found = ""
                        break
            else:
                for orig in headers_orig:
                    if orig == "":
                        continue
                    if cfg_name in orig.lower() or orig.lower() in cfg_name:
                        found = orig
                        break

            matched[role] = found

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
    wb = xl.Workbooks.Open(os.path.abspath(filepath),
                           UpdateLinks=False, ReadOnly=False)
    return xl, wb

RESULT_SHEET = "Result"

def get_sheet_names(filepath: str) -> list:
    """Result 시트는 자동 생성 시트이므로 목록에서 제외"""
    xl, wb = _xl_open(filepath)
    try:
        names = [sh.Name for sh in wb.Sheets if sh.Name != RESULT_SHEET]
        return names
    finally:
        wb.Close(False); xl.Quit()

def _excel_serial_to_dt(serial: float) -> datetime:
    """Excel serial number → datetime (1899-12-30 기준)"""
    return datetime(1899, 12, 30) + timedelta(days=serial)

def _parse_val_to_dt(v) -> datetime:
    """셀 값(datetime/serial/str) → datetime. 실패 시 None."""
    if v is None:
        return None
    if isinstance(v, datetime):
        return v
    if isinstance(v, (float, int)):
        try:
            return _excel_serial_to_dt(float(v))
        except Exception:
            return None
    if isinstance(v, str):
        for fmt in ("%Y/%m/%d %H:%M:%S", "%Y-%m-%d %H:%M:%S",
                    "%Y/%m/%d %H:%M",    "%Y-%m-%d %H:%M",
                    "%Y/%m/%d",          "%Y-%m-%d",
                    "%H:%M:%S",          "%H:%M"):
            try:
                return datetime.strptime(v.strip(), fmt)
            except ValueError:
                pass
    return None

def _parse_excel_dt(date_val, time_val):
    """날짜열+시간열 → datetime. 둘 다 있으면 연/월/일 + 시/분/초 조합."""
    dt_date = _parse_val_to_dt(date_val)
    dt_time = _parse_val_to_dt(time_val)

    if dt_date and dt_time:
        return datetime(dt_date.year, dt_date.month, dt_date.day,
                        dt_time.hour, dt_time.minute, dt_time.second,
                        dt_time.microsecond)

    return dt_date or dt_time

def read_sheet(filepath: str, sheet_name: str,
               header_row: int, data_start_row: int,
               temp_col_name: str, humid_col_name: str,
               date_col_name: str = "", time_col_name: str = ""):
    """
    win32com으로 시트 읽기 (대용량 최적화).
    반환: (temp_vals, humid_vals, timestamps)
      timestamps: list[datetime | None]  — 행별 타임스탬프
    """
    xl, wb = _xl_open(filepath)
    try:
        ws       = wb.Sheets(sheet_name)
        ur       = ws.UsedRange
        last_col = ur.Column + ur.Columns.Count - 1
        last_row = ur.Row    + ur.Rows.Count    - 1

        hdr_raw = ws.Range(
            ws.Cells(header_row, 1),
            ws.Cells(header_row, last_col)
        ).Value
        headers = [
            str(v).strip() if v is not None else ""
            for v in (hdr_raw[0] if hdr_raw else [])
        ]

        def col_of(name: str) -> int:
            name_stripped = name.strip()
            if name_stripped == "":
                for i, h in enumerate(headers):
                    if h.strip() == "":
                        return i + 1
                return -1
            name_low = name_stripped.lower()
            for i, h in enumerate(headers):
                if h.strip() == "":
                    continue
                hl = h.lower()
                if name_low in hl or hl in name_low:
                    return i + 1
            return -1

        t_ci    = col_of(temp_col_name)
        h_ci    = col_of(humid_col_name)
        date_ci = col_of(date_col_name)
        time_ci = col_of(time_col_name)

        def read_col_float(ci: int) -> list:
            if ci < 1: return []
            raw = ws.Range(ws.Cells(data_start_row, ci),
                           ws.Cells(last_row, ci)).Value or []
            out = []
            for row_tuple in raw:
                v = row_tuple[0] if isinstance(row_tuple, tuple) else row_tuple
                try:    out.append(float(v) if v is not None else 0.0)
                except: out.append(0.0)
            return out

        def read_col_raw(ci: int) -> list:
            """원본 값 그대로 반환 (datetime/float/str 혼용)"""
            if ci < 1: return []
            raw = ws.Range(ws.Cells(data_start_row, ci),
                           ws.Cells(last_row, ci)).Value or []
            return [r[0] if isinstance(r, tuple) else r for r in raw]

        temp_vals  = read_col_float(t_ci)
        humid_vals = read_col_float(h_ci)

        n = max(len(temp_vals), len(humid_vals))
        dates = read_col_raw(date_ci) if date_ci > 0 else []
        times = read_col_raw(time_ci) if time_ci > 0 else []

        timestamps = []
        for i in range(n):
            d_val = dates[i] if i < len(dates) else None
            t_val = times[i] if i < len(times) else None
            timestamps.append(_parse_excel_dt(d_val, t_val))

        return temp_vals, humid_vals, timestamps
    finally:
        wb.Close(False); xl.Quit()

def calc_rate_per_min(values: list, timestamps: list, trends: list) -> list:
    """각 UP/DOWN 구간의 분당 변화량. 시간 없으면 "". 구간 내 모든 행 동일값."""
    n = len(values)
    if n == 0:
        return []
    ts_n  = len(timestamps) if timestamps else 0
    result = [""] * n
    i = 0
    while i < n:
        d = trends[i]; j = i
        while j < n and trends[j] == d: j += 1
        s, e = i, j - 1; i = j
        if d not in ("UP", "DOWN"): continue
        ts_s = timestamps[s] if s < ts_n else None
        ts_e = timestamps[e] if e < ts_n else None
        if not ts_s or not ts_e or ts_s == ts_e: continue
        dt = (ts_e - ts_s).total_seconds() / 60.0
        if dt == 0: continue
        rate = round((values[e] - values[s]) / dt, 4)
        result[s:e + 1] = [rate] * (e - s + 1)
    return result

def make_updown_vals(values: list, trends: list) -> tuple:
    """UP→원본값, 아니면 "" (차트 빈칸 처리)."""
    up_vals   = [v if t == "UP"   else "" for v, t in zip(values, trends)]
    down_vals = [v if t == "DOWN" else "" for v, t in zip(values, trends)]
    return up_vals, down_vals

def add_chart(ws,
              sheet_name: str,
              header_row: int, data_start_row: int, data_rows: int,
              time_col: int,
              val_col: int, up_col: int, dn_col: int,
              chart_title: str, chart_left: float, chart_top: float,
              rate_up_col: int = -1, rate_dn_col: int = -1,
              chart_width: float = 420, chart_height: float = 280,
              config_text: str = "",
              src_ws=None,
              up_count: int = 0, dn_count: int = 0):
    """
    XY Scatter 차트를 ws에 추가.
    src_ws: 데이터 소스 시트 (None=ws 자신)
    up_count/dn_count: 텍스트박스에 표시할 UP/DOWN 그룹 수
    """
    data_ws  = src_ws if src_ws is not None else ws
    data_end = data_start_row + data_rows - 1

    # 동일 타이틀의 차트/그룹(차트+텍스트박스) 모두 삭제
    title_to_remove = f"{sheet_name} {chart_title}"
    to_delete = []
    for sh in ws.Shapes:
        try:
            t = sh.Type
            if t == 3:     # msoChart
                if sh.Chart.ChartTitle.Text == title_to_remove:
                    to_delete.append(sh.Name)
            elif t == 6:   # msoGroup — 차트+텍스트박스 그룹
                for item in sh.GroupItems:
                    try:
                        if item.Chart.ChartTitle.Text == title_to_remove:
                            to_delete.append(sh.Name); break
                    except Exception:
                        pass
        except Exception:
            pass
    for name in set(to_delete):
        try:
            ws.Shapes(name).Delete()
        except Exception:
            pass

    chart_obj = ws.ChartObjects().Add(chart_left, chart_top,
                                      chart_width, chart_height)
    chart = chart_obj.Chart
    chart.ChartType = -4169   # xlXYScatter

    def rng(col):
        return data_ws.Range(data_ws.Cells(data_start_row, col),
                             data_ws.Cells(data_end, col))

    def add_scatter(name, x_col, y_col, color_rgb, marker_size=3, axis_group=1):
        sr = chart.SeriesCollection().NewSeries()
        sr.Name      = name
        sr.XValues   = rng(x_col)
        sr.Values    = rng(y_col)
        sr.ChartType             = -4169
        sr.Format.Line.Visible   = 0
        sr.MarkerStyle           = 2
        sr.MarkerSize            = marker_size
        sr.MarkerForegroundColor = color_rgb
        sr.MarkerBackgroundColor = color_rgb
        sr.AxisGroup             = axis_group

    # 주 Y축
    add_scatter("원본", time_col, val_col, 0xAAAAAA, marker_size=2)
    add_scatter("UP",   time_col, up_col,  0x00AA44, marker_size=4)
    add_scatter("DOWN", time_col, dn_col,  0xCC2222, marker_size=4)

    chart.DisplayBlanksAs = 0

    chart.HasTitle = True
    chart.ChartTitle.Text = f"{sheet_name} {chart_title}"
    chart.Axes(1).HasTitle = True
    chart.Axes(1).AxisTitle.Text = "시간"
    chart.Axes(2).HasTitle = True
    chart.Axes(2).AxisTitle.Text = chart_title

    # X축 날짜/시간 포맷 설정
    # 데이터 셀의 NumberFormat을 읽어 그대로 차트 X축에 적용
    try:
        ax1 = chart.Axes(1)
        sample_cell = data_ws.Cells(data_start_row, time_col)
        cell_fmt = sample_cell.NumberFormat or ""
        # 날짜/시간 포맷인지 확인 (d, m, y, h, s 포함 여부)
        is_dt_fmt = any(c in cell_fmt.lower() for c in ("y", "d", "h", "s", ":"))
        if is_dt_fmt:
            ax1.NumberFormat = cell_fmt
        else:
            # 셀 포맷이 숫자인 경우 → 날짜+시간 기본 포맷 적용
            # Excel serial이면 날짜+시간으로 표시
            ax1.NumberFormat = "yyyy/mm/dd hh:mm"
    except Exception:
        pass

    # ── 텍스트박스(설정 + 그룹 개수) + 차트 그룹화 ─────────
    if config_text:
        try:
            full_text = (
                f"{config_text}\n"
                f"UP:   {up_count}그룹\n"
                f"DOWN: {dn_count}그룹"
            )
            TB_W, TB_H = 155, 90
            tb = ws.Shapes.AddTextbox(
                1,
                chart_obj.Left + chart_width - TB_W - 2,
                chart_obj.Top + 2,
                TB_W, TB_H)
            tb.TextFrame.Characters().Text = full_text
            tb.TextFrame.Characters().Font.Size  = 7
            tb.TextFrame.Characters().Font.Name  = "Consolas"
            tb.Fill.Visible          = True
            tb.Fill.ForeColor.RGB    = 0xF5F5F5
            tb.Line.Visible          = True
            tb.Line.ForeColor.RGB    = 0x999999
            ws.Shapes.Range([chart_obj.Name, tb.Name]).Group()
        except Exception:
            pass

    return chart_obj

def _extract_groups_all(values: list, trends: list, rates: list) -> list:
    """
    UP/DOWN 그룹을 출현 순서대로 반환.
    각 항목: {"direction": str, "start": int, "length": int, "rate": float|""}
    """
    groups = []
    n = len(trends)
    i = 0
    while i < n:
        d = trends[i]
        if d in ("UP", "DOWN"):
            j = i
            while j < n and trends[j] == d: j += 1
            rate = ""
            for k in range(i, j):
                if k < len(rates) and isinstance(rates[k], (int, float)):
                    rate = rates[k]; break
            groups.append({"direction": d, "start": i,
                           "length": j - i, "rate": rate})
            i = j
        else:
            i += 1
    return groups

def _build_result_sheet(wb, sheet_chart_infos: list, log=None):
    """
    Result 시트를 생성(또는 덮어쓰기).

    컬럼: 시트명 | 센서 | 방향 | 그룹번호 | 길이(행) | 변화량(/min)
    그룹번호: UP/DOWN 출현 순서 통합 카운트 (1,2,3,...)
    """
    def llog(msg):
        if log: log(msg)

    RESULT_NAME = "Result"

    # 기존 Result 시트 제거
    for sh in wb.Sheets:
        if sh.Name == RESULT_NAME:
            sh.Delete()
            break

    ws_r = wb.Sheets.Add(After=wb.Sheets(wb.Sheets.Count))
    ws_r.Name = RESULT_NAME

    # ── 헤더 ─────────────────────────────────────────────────
    HDR = ["시트명", "센서", "방향", "그룹번호", "길이(행)", "변화량(/min)"]
    for ci, h in enumerate(HDR, 1):
        ws_r.Cells(1, ci).Value = h

    row = 2

    for info in sheet_chart_infos:
        sname    = info["sheet_name"]
        rates_t  = info["rates_t"]
        rates_h  = info["rates_h"]
        trends_t = info["trends_t"]
        trends_h = info["trends_h"]

        # 온도: 출현 순서로 정렬, 그룹번호 통합 카운트
        t_groups = _extract_groups_all(info["temp_vals"], trends_t, rates_t)
        for gi, g in enumerate(t_groups, 1):
            ws_r.Cells(row, 1).Value = sname
            ws_r.Cells(row, 2).Value = "온도"
            ws_r.Cells(row, 3).Value = g["direction"]
            ws_r.Cells(row, 4).Value = gi
            ws_r.Cells(row, 5).Value = g["length"]
            ws_r.Cells(row, 6).Value = g["rate"]
            row += 1

        # 습도: 출현 순서로 정렬, 그룹번호 통합 카운트
        h_groups = _extract_groups_all(info["humid_vals"], trends_h, rates_h)
        for gi, g in enumerate(h_groups, 1):
            ws_r.Cells(row, 1).Value = sname
            ws_r.Cells(row, 2).Value = "습도"
            ws_r.Cells(row, 3).Value = g["direction"]
            ws_r.Cells(row, 4).Value = gi
            ws_r.Cells(row, 5).Value = g["length"]
            ws_r.Cells(row, 6).Value = g["rate"]
            row += 1

    table_last_row = row - 1

    # 열 너비 자동 조정
    ws_r.Columns("A:F").AutoFit()
    llog(f"  [Result] 변화량 테이블 작성 완료 (총 {table_last_row - 1}행)")

    # ── 차트를 Result 시트 위쪽에 추가 (테이블 위) ──────────
    CHART_W = 420; CHART_H = 280
    GAP     = 10
    chart_top_start = 5   # 시트 최상단부터 차트 배치

    chart_count = 0
    for si, info in enumerate(sheet_chart_infos):
        ws_src = None
        try:
            ws_src = wb.Sheets(info["sheet_name"])
        except Exception:
            continue

        row_top = chart_top_start + si * (CHART_H + GAP)
        left_t  = 5
        left_h  = left_t + CHART_W + GAP

        if (info["time_col"] > 0 and info["val_col_t"] > 0
                and info["t_up_col"] > 0 and info["t_dn_col"] > 0):
            try:
                add_chart(ws_r,
                          info["sheet_name"],
                          info["header_row"], info["data_start_row"],
                          info["data_rows"],
                          info["time_col"],
                          info["val_col_t"], info["t_up_col"], info["t_dn_col"],
                          "온도", left_t, row_top,
                          rate_up_col=info.get("t_rate_up_col", -1),
                          rate_dn_col=info.get("t_rate_dn_col", -1),
                          chart_width=CHART_W, chart_height=CHART_H,
                          config_text=info.get("t_cfg_text", ""),
                          src_ws=ws_src,
                          up_count=info.get("t_up_count", 0),
                          dn_count=info.get("t_dn_count", 0))
                chart_count += 1
            except Exception as e:
                llog(f"  [Result] {info['sheet_name']} 온도 차트 실패: {e}")

        if (info["time_col"] > 0 and info["val_col_h"] > 0
                and info["h_up_col"] > 0 and info["h_dn_col"] > 0):
            try:
                add_chart(ws_r,
                          info["sheet_name"],
                          info["header_row"], info["data_start_row"],
                          info["data_rows"],
                          info["time_col"],
                          info["val_col_h"], info["h_up_col"], info["h_dn_col"],
                          "습도", left_h, row_top,
                          rate_up_col=info.get("h_rate_up_col", -1),
                          rate_dn_col=info.get("h_rate_dn_col", -1),
                          chart_width=CHART_W, chart_height=CHART_H,
                          config_text=info.get("h_cfg_text", ""),
                          src_ws=ws_src,
                          up_count=info.get("h_up_count", 0),
                          dn_count=info.get("h_dn_count", 0))
                chart_count += 1
            except Exception as e:
                llog(f"  [Result] {info['sheet_name']} 습도 차트 실패: {e}")

    # 테이블을 차트 아래에 배치 (차트가 시트를 덮지 않도록 행 위치 조정)
    # 이미 ws_r.Cells에 데이터가 기록됐으므로 차트가 위에 겹쳐도 OK
    llog(f"  [Result] 차트 {chart_count}개 추가 완료")

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
    return col   # 기록된 절대 열 번호 반환

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

    # 포맷 자동 인식 (반환값: (fmt_key, matched_cols) 튜플)
    fmt_key, _ = detect_format(filepath, config["formats"])
    fmt            = config["formats"][fmt_key]
    header_row     = int(fmt["header_row"])
    data_start_row = int(fmt["data_start_row"])
    col_map        = fmt["columns"]
    temp_col_name  = col_map.get("temp",  "")
    humid_col_name = col_map.get("humid", "")
    date_col_name  = col_map.get("date",  "")
    time_col_name  = col_map.get("time",  "")

    t_cfg = config["temp"]
    h_cfg = config["humid"]

    log(f"포맷 자동 인식: [{fmt_key}] {fmt.get('description','')}")
    log(f"헤더={header_row}행, 데이터 시작={data_start_row}행")
    log(f"온도='{temp_col_name}', 습도='{humid_col_name}'")

    # 원본 파일 직접 열기 (복사 없음)
    xl, wb = _xl_open(filepath)
    processed = []
    sheet_chart_infos = []   # Result 시트용 차트 정보

    try:
        for sheet_name, scfg in sheet_cfg_map.items():
            if not scfg.get("enabled", True):
                log(f"  [{sheet_name}] 스킵"); continue

            log(f"  [{sheet_name}] 읽는 중...")
            try:
                temp_vals, humid_vals, timestamps = read_sheet(
                    filepath, sheet_name,
                    header_row, data_start_row,
                    temp_col_name, humid_col_name,
                    date_col_name, time_col_name)
            except Exception as e:
                log(f"  [{sheet_name}] 읽기 실패: {e}"); continue

            ws = wb.Sheets(sheet_name)
            sheet_done = False
            _t_up = _t_down = _h_up = _h_down = 0

            # 시간 컬럼 번호 조회 (차트 X축용)
            ur_last = ws.UsedRange.Column + ws.UsedRange.Columns.Count - 1
            hdr_raw_ws = ws.Range(ws.Cells(header_row, 1),
                                  ws.Cells(header_row, ur_last)).Value
            hdr_ws = []
            if hdr_raw_ws:
                row0 = hdr_raw_ws[0] if isinstance(hdr_raw_ws[0], tuple) else hdr_raw_ws
                hdr_ws = [str(v).strip() if v is not None else "" for v in row0]

            def find_col_ws(name):
                name = (name or "").strip().lower()
                if name == "":
                    for i, h in enumerate(hdr_ws):
                        if h == "": return i + 1
                    return -1
                for i, h in enumerate(hdr_ws):
                    if h == "": continue
                    hl = h.lower()
                    if name in hl or hl in name: return i + 1
                return -1

            # 시간축: date+time 합산 컬럼이 없으면 time 컬럼 사용
            time_axis_col = find_col_ws(time_col_name) if time_col_name else -1
            if time_axis_col < 1:
                time_axis_col = find_col_ws(date_col_name)
            data_rows = max(len(temp_vals), len(humid_vals), 1)

            t_trend_col = h_trend_col = -1
            t_up_col = t_dn_col = h_up_col = h_dn_col = -1
            t_rate_up_col = t_rate_dn_col = -1
            h_rate_up_col = h_rate_dn_col = -1
            rates_t = rates_h = []

            if temp_vals:
                t_trends = analyze_trends(temp_vals,
                                          float(t_cfg.get("min_rate",       0.0)),
                                          int(t_cfg.get("final_min_rows",  3)),
                                          timestamps)
                t_trend_col = write_trend_col(ws, header_row, data_start_row,
                                              "Temp_Trend", t_trends)
                rates_t = calc_rate_per_min(temp_vals, timestamps, t_trends)
                write_trend_col(ws, header_row, data_start_row, "Temp_Rate", rates_t)
                t_rate_up_vals  = [r if isinstance(r,(int,float)) and r > 0 else "" for r in rates_t]
                t_rate_dn_vals  = [r if isinstance(r,(int,float)) and r < 0 else "" for r in rates_t]
                write_trend_col(ws, header_row, data_start_row, "Temp_Rate_UP",   t_rate_up_vals)
                write_trend_col(ws, header_row, data_start_row, "Temp_Rate_DOWN", t_rate_dn_vals)
                t_up_vals, t_dn_vals = make_updown_vals(temp_vals, t_trends)
                t_up_col = write_trend_col(ws, header_row, data_start_row,
                                           "Temp_UP", t_up_vals)
                t_dn_col = write_trend_col(ws, header_row, data_start_row,
                                           "Temp_DOWN", t_dn_vals)
                _t_up   = count_groups(t_trends, 'UP')
                _t_down = count_groups(t_trends, 'DOWN')
                log(f"  [{sheet_name}] 🌡 온도  UP그룹={_t_up}, DOWN그룹={_t_down}")
                sheet_done = True
            else:
                log(f"  [{sheet_name}] 🌡 온도 컬럼 없음")

            if humid_vals:
                h_trends = analyze_trends(humid_vals,
                                          float(h_cfg.get("min_rate",       0.0)),
                                          int(h_cfg.get("final_min_rows",  3)),
                                          timestamps)
                h_trend_col = write_trend_col(ws, header_row, data_start_row,
                                              "Humid_Trend", h_trends)
                rates_h = calc_rate_per_min(humid_vals, timestamps, h_trends)
                write_trend_col(ws, header_row, data_start_row, "Humid_Rate", rates_h)
                h_rate_up_vals  = [r if isinstance(r,(int,float)) and r > 0 else "" for r in rates_h]
                h_rate_dn_vals  = [r if isinstance(r,(int,float)) and r < 0 else "" for r in rates_h]
                write_trend_col(ws, header_row, data_start_row, "Humid_Rate_UP",   h_rate_up_vals)
                write_trend_col(ws, header_row, data_start_row, "Humid_Rate_DOWN", h_rate_dn_vals)
                h_up_vals, h_dn_vals = make_updown_vals(humid_vals, h_trends)
                h_up_col = write_trend_col(ws, header_row, data_start_row,
                                           "Humid_UP", h_up_vals)
                h_dn_col = write_trend_col(ws, header_row, data_start_row,
                                           "Humid_DOWN", h_dn_vals)
                _h_up   = count_groups(h_trends, 'UP')
                _h_down = count_groups(h_trends, 'DOWN')
                log(f"  [{sheet_name}] 💧 습도  UP그룹={_h_up}, DOWN그룹={_h_down}")
                sheet_done = True
            else:
                log(f"  [{sheet_name}] 💧 습도 컬럼 없음")

            # ── 차트 추가 ─────────────────────────────────────
            val_col_t = find_col_ws(temp_col_name)
            val_col_h = find_col_ws(humid_col_name)

            t_rate_up_col = find_col_ws("Temp_Rate_UP")
            t_rate_dn_col = find_col_ws("Temp_Rate_DOWN")
            h_rate_up_col = find_col_ws("Humid_Rate_UP")
            h_rate_dn_col = find_col_ws("Humid_Rate_DOWN")

            # 시트별 차트 정보 저장 (Result 시트용)
            chart_info = {
                "sheet_name":    sheet_name,
                "header_row":    header_row,
                "data_start_row": data_start_row,
                "data_rows":     data_rows,
                "time_col":      time_axis_col,
                "val_col_t":     val_col_t,
                "t_up_col":      t_up_col,
                "t_dn_col":      t_dn_col,
                "t_rate_up_col": t_rate_up_col,
                "t_rate_dn_col": t_rate_dn_col,
                "val_col_h":     val_col_h,
                "h_up_col":      h_up_col,
                "h_dn_col":      h_dn_col,
                "h_rate_up_col": h_rate_up_col,
                "h_rate_dn_col": h_rate_dn_col,
                "rates_t":       list(rates_t) if temp_vals else [],
                "rates_h":       list(rates_h) if humid_vals else [],
                "trends_t":      list(t_trends) if temp_vals else [],
                "trends_h":      list(h_trends) if humid_vals else [],
                "temp_vals":     list(temp_vals),
                "humid_vals":    list(humid_vals),
                "t_up_count":    _t_up,
                "t_dn_count":    _t_down,
                "h_up_count":    _h_up,
                "h_dn_count":    _h_down,
            }

            # config 설정 텍스트 — chart_info 저장 전에 생성
            def _cfg_text(cfg):
                return (
                    f"min_rate:       {cfg.get('min_rate',0.0)}\n"
                    f"final_min_rows: {cfg.get('final_min_rows',3)}"
                )
            t_cfg_text = _cfg_text(t_cfg)
            h_cfg_text = _cfg_text(h_cfg)

            chart_info["t_cfg_text"] = t_cfg_text if temp_vals else ""
            chart_info["h_cfg_text"] = h_cfg_text if humid_vals else ""
            sheet_chart_infos.append(chart_info)

            if time_axis_col > 0 and temp_vals and val_col_t > 0                     and t_up_col > 0 and t_dn_col > 0:
                add_chart(ws, sheet_name,
                          header_row, data_start_row, data_rows,
                          time_axis_col, val_col_t, t_up_col, t_dn_col,
                          "온도", 10, 20,
                          rate_up_col=t_rate_up_col, rate_dn_col=t_rate_dn_col,
                          config_text=t_cfg_text, src_ws=ws,
                          up_count=_t_up, dn_count=_t_down)
                log(f"  [{sheet_name}] 🌡 온도 차트 추가")

            if time_axis_col > 0 and humid_vals and val_col_h > 0                     and h_up_col > 0 and h_dn_col > 0:
                add_chart(ws, sheet_name,
                          header_row, data_start_row, data_rows,
                          time_axis_col, val_col_h, h_up_col, h_dn_col,
                          "습도", 440, 20,
                          rate_up_col=h_rate_up_col, rate_dn_col=h_rate_dn_col,
                          config_text=h_cfg_text, src_ws=ws,
                          up_count=_h_up, dn_count=_h_down)
                log(f"  [{sheet_name}] 💧 습도 차트 추가")

            if sheet_done:
                processed.append(sheet_name)
                if sheet_result_cb:
                    sheet_result_cb(sheet_name, _t_up, _t_down, _h_up, _h_down)

        # ── Result 시트 생성 ──────────────────────────────
        log("  [Result] 시트 생성 중...")
        _build_result_sheet(wb, sheet_chart_infos, log)

        xl.ScreenUpdating = True
        wb.Save()
        xl.Visible = True
        wb.Activate()
        xl.WindowState = -4137   # xlMaximized
        log(f"\n완료: {os.path.basename(filepath)}  ({len(processed)}개 시트)")

    except Exception as e:
        xl.ScreenUpdating = True
        xl.Visible = True
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
        self.min_rate_var       = tk.StringVar(value=str(init.get("min_rate",       0.0)))
        self.final_min_rows_var = tk.StringVar(value=str(init.get("final_min_rows", 3)))
        for i, (lbl, var) in enumerate([
            ("min_rate       (유효 분당 변화율 기준)", self.min_rate_var),
            ("final_min_rows (최종 최소 행)",          self.final_min_rows_var),
        ]):
            tk.Label(self, text=lbl, font=lf, bg=bg, fg=fg,
                     anchor="e", width=26).grid(
                row=i, column=0, sticky="e", padx=(0, 6), pady=3)
            tk.Entry(self, textvariable=var, width=8,
                     bg=ebg, fg=fg, insertbackground=fg,
                     relief="flat").grid(row=i, column=1, sticky="w", pady=3)

    def get(self):
        return {"min_rate":       float(self.min_rate_var.get()),
                "final_min_rows": int(self.final_min_rows_var.get())}

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
            f"🌡UP그룹={t_up} DOWN그룹={t_down}  💧UP그룹={h_up} DOWN그룹={h_down}")
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
        self.geometry("1100x720")
        self.minsize(900, 450)
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
        tk.Label(self, text=f"⚙ 설정 ({os.path.basename(CONFIG_FILE)})",
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
                  command=self._run).pack(fill="x", padx=P, pady=(0, 6), ipady=6)

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
            messagebox.showinfo("알림", f"{os.path.basename(CONFIG_FILE)}에 formats가 없습니다."); return
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
            initialdir=os.path.dirname(os.path.abspath(
                sys.executable if getattr(sys, "frozen", False) else __file__)),
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
        skipped = [n for n in names if n == "Result"]
        shown   = [n for n in names if n != "Result"]
        self._log(f"로드 완료: {len(shown)}개 시트 — " + ", ".join(shown))
        if skipped:
            self._log(f"  ('{', '.join(skipped)}' 시트는 자동 생성 시트로 제외됨)")
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

        # 시트 1개이면 자동 분석 실행
        if len(names) == 1:
            self._log("시트 1개 감지 → 자동 분석 시작")
            self.after(100, self._run)

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
                f"  🌡 temp : min_rate={cfg['temp']['min_rate']}  final_min_rows={cfg['temp']['final_min_rows']}\n"
                f"  💧 humid: min_rate={cfg['humid']['min_rate']}  final_min_rows={cfg['humid']['final_min_rows']}")
        except ValueError:
            messagebox.showerror("오류", "final_min_rows는 정수로 입력하세요.")

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
            messagebox.showerror("오류", "final_min_rows 값을 확인하세요."); return

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
