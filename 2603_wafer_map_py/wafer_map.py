import tkinter as tk
from tkinter import ttk, colorchooser, messagebox, filedialog
import math
import random
import json
import os
import datetime

# ── 팔레트 ──────────────────────────────────────────────
COLORS = {
    "Pass":     "#00e676",
    "Fail":     "#ff1744",
    "Marginal": "#ffea00",
    "Edge":     "#ff6d00",
    "Void":     "#78909c",
    "Custom":   "#ce93d8",
}

BG         = "#0a0e1a"
PANEL_BG   = "#111827"
BORDER     = "#1e2d45"
TEXT_COLOR = "#e2e8f0"
ACCENT     = "#00bcd4"
ACCENT2    = "#7c3aed"
GRID_COLOR = "#1a2535"
WAFER_EDGE = "#2a4060"
WAFER_FILL = "#0d1b2a"
DIE_BLANK  = "#1c2e44"

CANVAS_SIZE = 420

WAFER_INCH_OPTIONS = [2, 4, 6, 8, 12]
WAFER_DIAMETER_MM  = {2: 50.8, 4: 100, 6: 150, 8: 200, 12: 300}

DIE_PRESETS_W = [1, 2, 3, 5, 8, 10, 15, 20]
DIE_PRESETS_H = [1, 2, 3, 5, 8, 10, 15, 20]


# ── 다이 생성 ────────────────────────────────────────────
def generate_dies(wafer_r_px: float, cell_w: float, cell_h: float):
    dies = []
    cols = int(wafer_r_px * 2 / cell_w)
    rows = int(wafer_r_px * 2 / cell_h)
    ox   = wafer_r_px - (cols * cell_w) / 2
    oy   = wafer_r_px - (rows * cell_h) / 2
    for r in range(rows):
        for c in range(cols):
            cx = ox + c * cell_w + cell_w / 2
            cy = oy + r * cell_h + cell_h / 2
            margin = min(cell_w, cell_h) * 0.35
            dist   = math.hypot(cx - wafer_r_px, cy - wafer_r_px)
            if dist + margin <= wafer_r_px * 0.97:
                dies.append({
                    "row": r, "col": c,
                    "cx": cx, "cy": cy,
                    "color": DIE_BLANK,
                    "label": None,
                    "id": None,
                })
    for i, d in enumerate(dies):
        d["id"] = i
    return dies


# ── 메인 앱 ──────────────────────────────────────────────
class WaferApp(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Semiconductor Wafer Map")
        self.configure(bg=BG)
        self.resizable(False, False)

        self.wafer_inch  = tk.IntVar(value=8)
        self.die_w_mm    = tk.DoubleVar(value=5.0)
        self.die_h_mm    = tk.DoubleVar(value=5.0)
        self.sel_color   = tk.StringVar(value="#00e676")
        self.sel_label   = tk.StringVar(value="Pass")
        self.custom_hex  = "#ce93d8"

        # 저장 경로: 기본값 = 현재 스크립트 위치
        _script_dir = os.path.dirname(os.path.abspath(__file__))
        self.save_dir = tk.StringVar(value=_script_dir)

        self._wafer_r_px = 0.0
        self._cell_w_px  = 0.0
        self._cell_h_px  = 0.0
        self.dies = []
        self._recalc_geometry()

        self._build_ui()
        self._draw_all()

    # ── 기하 계산 ─────────────────────────────────────────
    def _recalc_geometry(self, keep_colors=False):
        wafer_mm = WAFER_DIAMETER_MM[self.wafer_inch.get()]
        self._wafer_r_px = (CANVAS_SIZE - 4) / 2
        mm2px = (self._wafer_r_px * 2) / wafer_mm

        self._cell_w_px = max(self.die_w_mm.get() * mm2px, 3.0)
        self._cell_h_px = max(self.die_h_mm.get() * mm2px, 3.0)

        old_map = {}
        if keep_colors and self.dies:
            for d in self.dies:
                old_map[(d["row"], d["col"])] = (d["color"], d["label"])

        self.dies = generate_dies(self._wafer_r_px, self._cell_w_px, self._cell_h_px)

        if keep_colors:
            for d in self.dies:
                key = (d["row"], d["col"])
                if key in old_map:
                    d["color"], d["label"] = old_map[key]

    # ── 파일명 미리보기 생성 ──────────────────────────────
    def _make_filename(self):
        ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
        return f"wafer_{ts}.json"

    def _make_full_path(self):
        return os.path.join(self.save_dir.get(), self._make_filename())

    def _update_fname_preview(self, *_):
        ts  = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
        fname = f"wafer_{ts}.json"
        dirp  = self.save_dir.get()
        # 경로 줄이기: 너무 길면 앞부분 생략
        disp = dirp if len(dirp) <= 26 else "…" + dirp[-25:]
        self._fname_preview.config(
            text=f"저장 파일:\n{disp}/\n{fname}")

    # ── 저장 경로 선택 ────────────────────────────────────
    def _browse_save_dir(self):
        chosen = filedialog.askdirectory(
            title="저장 폴더 선택",
            initialdir=self.save_dir.get()
        )
        if chosen:
            self.save_dir.set(chosen)
            self._update_fname_preview()

    def _reset_save_dir(self):
        _script_dir = os.path.dirname(os.path.abspath(__file__))
        self.save_dir.set(_script_dir)
        self._update_fname_preview()

    # ── UI 빌드 ───────────────────────────────────────────
    def _build_ui(self):
        left = tk.Frame(self, bg=BG)
        left.pack(side=tk.LEFT, padx=(20, 10), pady=20)

        hdr = tk.Frame(left, bg=BG)
        hdr.pack(fill=tk.X, pady=(0, 6))
        tk.Label(hdr, text="WAFER MAP", font=("Courier", 18, "bold"),
                 fg=ACCENT, bg=BG).pack(side=tk.LEFT)
        self.title_info = tk.Label(hdr, text="", font=("Courier", 9),
                                   fg="#4a6080", bg=BG)
        self.title_info.pack(side=tk.LEFT, padx=12, pady=(4, 0))

        self.die_count_lbl = tk.Label(left, text="", font=("Courier", 10),
                                      fg="#4a6080", bg=BG)
        self.die_count_lbl.pack(anchor="w", pady=(0, 8))

        self.canvas = tk.Canvas(left, width=CANVAS_SIZE, height=CANVAS_SIZE,
                                bg=BG, highlightthickness=0)
        self.canvas.pack()
        self.canvas.bind("<Button-1>", self._on_click)
        self.canvas.bind("<B1-Motion>", self._on_click)

        self.stat_var = tk.StringVar(value="")
        tk.Label(left, textvariable=self.stat_var, font=("Courier", 9),
                 fg="#4a6080", bg=BG, justify=tk.LEFT).pack(
                     anchor="w", pady=(10, 0))

        # ── 오른쪽 스크롤 패널 ──
        outer = tk.Frame(self, bg=PANEL_BG,
                         highlightbackground=BORDER, highlightthickness=1)
        outer.pack(side=tk.LEFT, padx=(10, 20), pady=20, fill=tk.Y)

        sc = tk.Canvas(outer, bg=PANEL_BG, highlightthickness=0, width=210)
        sb = ttk.Scrollbar(outer, orient="vertical", command=sc.yview)
        sc.configure(yscrollcommand=sb.set)
        sb.pack(side=tk.RIGHT, fill=tk.Y)
        sc.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)

        right = tk.Frame(sc, bg=PANEL_BG)
        win_id = sc.create_window((0, 0), window=right, anchor="nw")

        def _frame_cfg(e):
            sc.configure(scrollregion=sc.bbox("all"))
        def _canvas_cfg(e):
            sc.itemconfig(win_id, width=e.width)
        right.bind("<Configure>", _frame_cfg)
        sc.bind("<Configure>", _canvas_cfg)

        def _wheel(e):
            sc.yview_scroll(int(-1 * (e.delta / 120)), "units")
        sc.bind_all("<MouseWheel>", _wheel)

        # ════════ 컨트롤 내용 ════════

        tk.Label(right, text="CONTROLS", font=("Courier", 12, "bold"),
                 fg=ACCENT, bg=PANEL_BG).pack(pady=(16, 4))
        ttk.Separator(right, orient="horizontal").pack(
            fill=tk.X, padx=12, pady=4)

        # ① 웨이퍼 크기
        self._section_label(right, "웨이퍼 크기 (inch)")
        inch_frame = tk.Frame(right, bg=PANEL_BG)
        inch_frame.pack(padx=14, fill=tk.X)
        self._inch_btns = {}
        for inch in WAFER_INCH_OPTIONS:
            diam = WAFER_DIAMETER_MM[inch]
            b = tk.Button(inch_frame,
                          text=f'{inch}"  ({diam:.0f} mm)',
                          font=("Courier", 9),
                          bg="#1c2e44", fg=TEXT_COLOR,
                          activebackground="#253550",
                          bd=0, cursor="hand2", pady=4,
                          command=lambda v=inch: self._set_wafer_inch(v))
            b.pack(fill=tk.X, pady=1)
            self._inch_btns[inch] = b
        self._highlight_inch_btn()

        ttk.Separator(right, orient="horizontal").pack(
            fill=tk.X, padx=12, pady=8)

        # ② 다이 크기 W / H
        self._section_label(right, "다이 크기 (W × H, mm)")
        die_frame = tk.Frame(right, bg=PANEL_BG)
        die_frame.pack(padx=14, fill=tk.X)

        # W 프리셋
        tk.Label(die_frame, text="W (가로):", font=("Courier", 8, "bold"),
                 fg=ACCENT, bg=PANEL_BG).pack(anchor="w", pady=(2, 1))
        w_grid = tk.Frame(die_frame, bg=PANEL_BG)
        w_grid.pack(fill=tk.X)
        self._die_w_btns = {}
        for idx, mm in enumerate(DIE_PRESETS_W):
            b = tk.Button(w_grid, text=str(mm), font=("Courier", 8),
                          bg="#1c2e44", fg=TEXT_COLOR,
                          activebackground="#253550",
                          bd=0, cursor="hand2", pady=3,
                          command=lambda v=mm: self._set_die_w_preset(v))
            b.grid(row=idx // 4, column=idx % 4,
                   padx=1, pady=1, sticky="ew")
            self._die_w_btns[mm] = b
        for c in range(4):
            w_grid.columnconfigure(c, weight=1)

        wr = tk.Frame(die_frame, bg=PANEL_BG)
        wr.pack(fill=tk.X, pady=(3, 6))
        tk.Label(wr, text="W:", font=("Courier", 8), fg="#7a90a8",
                 bg=PANEL_BG).pack(side=tk.LEFT)
        self._die_w_entry = tk.Entry(wr, width=5, font=("Courier", 9),
                                     bg="#1c2e44", fg=TEXT_COLOR,
                                     insertbackground=ACCENT,
                                     relief=tk.FLAT, bd=2)
        self._die_w_entry.insert(0, "5")
        self._die_w_entry.pack(side=tk.LEFT, padx=3)
        tk.Label(wr, text="mm", font=("Courier", 8), fg="#7a90a8",
                 bg=PANEL_BG).pack(side=tk.LEFT)
        tk.Button(wr, text="적용", font=("Courier", 8),
                  bg=ACCENT, fg="#000", activebackground="#00e5f5",
                  bd=0, cursor="hand2", padx=5,
                  command=self._apply_w).pack(side=tk.LEFT, padx=(4, 0))

        # H 프리셋
        tk.Label(die_frame, text="H (세로):", font=("Courier", 8, "bold"),
                 fg="#ff6d00", bg=PANEL_BG).pack(anchor="w", pady=(2, 1))
        h_grid = tk.Frame(die_frame, bg=PANEL_BG)
        h_grid.pack(fill=tk.X)
        self._die_h_btns = {}
        for idx, mm in enumerate(DIE_PRESETS_H):
            b = tk.Button(h_grid, text=str(mm), font=("Courier", 8),
                          bg="#1c2e44", fg=TEXT_COLOR,
                          activebackground="#253550",
                          bd=0, cursor="hand2", pady=3,
                          command=lambda v=mm: self._set_die_h_preset(v))
            b.grid(row=idx // 4, column=idx % 4,
                   padx=1, pady=1, sticky="ew")
            self._die_h_btns[mm] = b
        for c in range(4):
            h_grid.columnconfigure(c, weight=1)

        hr = tk.Frame(die_frame, bg=PANEL_BG)
        hr.pack(fill=tk.X, pady=(3, 4))
        tk.Label(hr, text="H:", font=("Courier", 8), fg="#7a90a8",
                 bg=PANEL_BG).pack(side=tk.LEFT)
        self._die_h_entry = tk.Entry(hr, width=5, font=("Courier", 9),
                                     bg="#1c2e44", fg=TEXT_COLOR,
                                     insertbackground=ACCENT,
                                     relief=tk.FLAT, bd=2)
        self._die_h_entry.insert(0, "5")
        self._die_h_entry.pack(side=tk.LEFT, padx=3)
        tk.Label(hr, text="mm", font=("Courier", 8), fg="#7a90a8",
                 bg=PANEL_BG).pack(side=tk.LEFT)
        tk.Button(hr, text="적용", font=("Courier", 8),
                  bg="#ff6d00", fg="#000", activebackground="#ff8f00",
                  bd=0, cursor="hand2", padx=5,
                  command=self._apply_h).pack(side=tk.LEFT, padx=(4, 0))

        self._die_size_lbl = tk.Label(die_frame, text="",
                                      font=("Courier", 8),
                                      fg=ACCENT, bg=PANEL_BG)
        self._die_size_lbl.pack(anchor="w", pady=(2, 0))
        self._update_die_size_lbl()
        self._highlight_die_w_btn()
        self._highlight_die_h_btn()

        ttk.Separator(right, orient="horizontal").pack(
            fill=tk.X, padx=12, pady=8)

        # ③ 색상 팔레트
        self._section_label(right, "색상 선택")
        pal_frame = tk.Frame(right, bg=PANEL_BG)
        pal_frame.pack(padx=14, pady=2, fill=tk.X)
        self.color_btns = {}
        for name, hex_col in COLORS.items():
            b = tk.Button(pal_frame, text=name, font=("Courier", 9),
                          bg=hex_col,
                          fg="#000" if name in ("Pass", "Marginal") else "#fff",
                          activebackground=hex_col,
                          width=9, bd=0, cursor="hand2",
                          command=lambda n=name, h=hex_col:
                              self._select_preset(n, h))
            b.pack(pady=2, fill=tk.X)
            self.color_btns[name] = b

        tk.Button(pal_frame, text="+ 커스텀 색상", font=("Courier", 9),
                  bg="#1e2d45", fg=ACCENT, activebackground="#253550",
                  bd=0, cursor="hand2",
                  command=self._pick_custom).pack(pady=(6, 2), fill=tk.X)

        prev_row = tk.Frame(right, bg=PANEL_BG)
        prev_row.pack(padx=14, pady=6, fill=tk.X)
        tk.Label(prev_row, text="선택:", font=("Courier", 9),
                 fg=TEXT_COLOR, bg=PANEL_BG).pack(side=tk.LEFT)
        self.preview_box = tk.Label(prev_row, text="  Pass  ",
                                    font=("Courier", 9, "bold"),
                                    bg="#00e676", fg="#000", padx=6)
        self.preview_box.pack(side=tk.LEFT, padx=6)

        fill_frame = tk.Frame(right, bg=PANEL_BG)
        fill_frame.pack(padx=14, fill=tk.X, pady=(2, 4))
        tk.Button(fill_frame,
                  text="▣  선택 색상으로 전체 채우기",
                  font=("Courier", 9, "bold"),
                  bg=ACCENT2, fg="#fff",
                  activebackground="#6d28d9",
                  bd=0, cursor="hand2", pady=7,
                  command=self._fill_all).pack(fill=tk.X)

        ttk.Separator(right, orient="horizontal").pack(
            fill=tk.X, padx=12, pady=8)

        # ④ 랜덤 생성
        self._section_label(right, "랜덤 생성")
        rand_frame = tk.Frame(right, bg=PANEL_BG)
        rand_frame.pack(padx=14, fill=tk.X)
        self.sliders = {}
        for name, default, color in [
            ("Pass",     60, "#00e676"),
            ("Fail",     20, "#ff1744"),
            ("Marginal", 10, "#ffea00"),
            ("Edge",     10, "#ff6d00"),
        ]:
            row = tk.Frame(rand_frame, bg=PANEL_BG)
            row.pack(fill=tk.X, pady=2)
            tk.Label(row, text="●", fg=color, bg=PANEL_BG,
                     font=("Courier", 8)).pack(side=tk.LEFT)
            tk.Label(row, text=f"{name[:4]:4s}", font=("Courier", 8),
                     fg=TEXT_COLOR, bg=PANEL_BG,
                     width=5).pack(side=tk.LEFT)
            var = tk.IntVar(value=default)
            tk.Scale(row, from_=0, to=100, orient=tk.HORIZONTAL,
                     variable=var, bg=PANEL_BG, fg=TEXT_COLOR,
                     troughcolor=GRID_COLOR, activebackground=color,
                     highlightthickness=0, sliderlength=12, width=8,
                     length=95, showvalue=False).pack(side=tk.LEFT)
            tk.Label(row, textvariable=var, font=("Courier", 8),
                     fg=color, bg=PANEL_BG, width=3).pack(side=tk.LEFT)
            self.sliders[name] = var

        tk.Button(rand_frame, text="▶  랜덤 생성",
                  font=("Courier", 10, "bold"),
                  bg=ACCENT, fg="#000", activebackground="#00e5f5",
                  bd=0, cursor="hand2", pady=6,
                  command=self._randomize).pack(fill=tk.X, pady=(8, 2))

        ttk.Separator(right, orient="horizontal").pack(
            fill=tk.X, padx=12, pady=8)

        # ⑤ 저장 경로 설정
        self._section_label(right, "저장 경로 설정")

        path_outer = tk.Frame(right, bg=PANEL_BG)
        path_outer.pack(padx=14, fill=tk.X, pady=(0, 4))

        # 현재 경로 표시 (읽기 전용 Entry)
        self._path_entry = tk.Entry(
            path_outer, textvariable=self.save_dir,
            font=("Courier", 7), bg="#0f1e30", fg="#7ab8d8",
            insertbackground=ACCENT, relief=tk.FLAT, bd=2,
            state="readonly", readonlybackground="#0f1e30")
        self._path_entry.pack(fill=tk.X, pady=(0, 5))

        btn_row = tk.Frame(path_outer, bg=PANEL_BG)
        btn_row.pack(fill=tk.X)

        tk.Button(btn_row, text="📁  폴더 선택",
                  font=("Courier", 8),
                  bg="#1e3a5a", fg=ACCENT,
                  activebackground="#1e4a6a",
                  bd=0, cursor="hand2", pady=5,
                  command=self._browse_save_dir).pack(
                      side=tk.LEFT, fill=tk.X, expand=True, padx=(0, 2))

        tk.Button(btn_row, text="↺  기본값",
                  font=("Courier", 8),
                  bg="#1e2d45", fg="#7a90a8",
                  activebackground="#253550",
                  bd=0, cursor="hand2", pady=5,
                  command=self._reset_save_dir).pack(
                      side=tk.LEFT, fill=tk.X, expand=True, padx=(2, 0))

        # 파일명 미리보기
        self._fname_preview = tk.Label(
            path_outer, text="",
            font=("Courier", 7), fg="#3a7a55",
            bg=PANEL_BG, wraplength=185, justify=tk.LEFT)
        self._fname_preview.pack(anchor="w", pady=(6, 0))
        self._update_fname_preview()

        ttk.Separator(right, orient="horizontal").pack(
            fill=tk.X, padx=12, pady=8)

        # ⑥ 기타 버튼
        btn_frame = tk.Frame(right, bg=PANEL_BG)
        btn_frame.pack(padx=14, fill=tk.X, pady=(0, 18))

        tk.Button(btn_frame, text="초기화",
                  font=("Courier", 9),
                  bg="#1e2d45", fg=TEXT_COLOR,
                  activebackground="#253550",
                  bd=0, cursor="hand2", pady=5,
                  command=self._clear_all).pack(fill=tk.X, pady=2)

        tk.Button(btn_frame, text="💾  JSON 내보내기",
                  font=("Courier", 10, "bold"),
                  bg="#1a3a28", fg="#00e676",
                  activebackground="#1e4a30",
                  bd=0, cursor="hand2", pady=8,
                  command=self._export_json).pack(fill=tk.X, pady=(6, 2))

    # ── 섹션 레이블 헬퍼 ─────────────────────────────────
    def _section_label(self, parent, text):
        tk.Label(parent, text=text, font=("Courier", 9, "bold"),
                 fg=TEXT_COLOR, bg=PANEL_BG).pack(
                     anchor="w", padx=14, pady=(6, 4))

    # ── 웨이퍼 인치 ──────────────────────────────────────
    def _set_wafer_inch(self, inch):
        self.wafer_inch.set(inch)
        self._highlight_inch_btn()
        self._recalc_geometry(keep_colors=False)
        self._draw_all()

    def _highlight_inch_btn(self):
        cur = self.wafer_inch.get()
        for inch, btn in self._inch_btns.items():
            btn.config(bg=ACCENT if inch == cur else "#1c2e44",
                       fg="#000"  if inch == cur else TEXT_COLOR)

    # ── 다이 W ───────────────────────────────────────────
    def _set_die_w_preset(self, mm):
        self.die_w_mm.set(mm)
        self._die_w_entry.delete(0, tk.END)
        self._die_w_entry.insert(0, str(mm))
        self._highlight_die_w_btn()
        self._update_die_size_lbl()
        self._recalc_geometry(keep_colors=False)
        self._draw_all()

    def _apply_w(self):
        try:
            val = float(self._die_w_entry.get())
            if val <= 0:
                raise ValueError
        except ValueError:
            messagebox.showerror("입력 오류", "W: 양수 값을 입력하세요.")
            return
        self.die_w_mm.set(val)
        self._highlight_die_w_btn()
        self._update_die_size_lbl()
        self._recalc_geometry(keep_colors=False)
        self._draw_all()

    def _highlight_die_w_btn(self):
        cur = self.die_w_mm.get()
        for mm, btn in self._die_w_btns.items():
            btn.config(bg=ACCENT if mm == cur else "#1c2e44",
                       fg="#000"  if mm == cur else TEXT_COLOR)

    # ── 다이 H ───────────────────────────────────────────
    def _set_die_h_preset(self, mm):
        self.die_h_mm.set(mm)
        self._die_h_entry.delete(0, tk.END)
        self._die_h_entry.insert(0, str(mm))
        self._highlight_die_h_btn()
        self._update_die_size_lbl()
        self._recalc_geometry(keep_colors=False)
        self._draw_all()

    def _apply_h(self):
        try:
            val = float(self._die_h_entry.get())
            if val <= 0:
                raise ValueError
        except ValueError:
            messagebox.showerror("입력 오류", "H: 양수 값을 입력하세요.")
            return
        self.die_h_mm.set(val)
        self._highlight_die_h_btn()
        self._update_die_size_lbl()
        self._recalc_geometry(keep_colors=False)
        self._draw_all()

    def _highlight_die_h_btn(self):
        cur = self.die_h_mm.get()
        for mm, btn in self._die_h_btns.items():
            btn.config(bg="#ff6d00" if mm == cur else "#1c2e44",
                       fg="#000"    if mm == cur else TEXT_COLOR)

    def _update_die_size_lbl(self):
        w = self.die_w_mm.get()
        h = self.die_h_mm.get()
        self._die_size_lbl.config(text=f"현재: {w} × {h} mm")

    # ── 저장 경로 ─────────────────────────────────────────
    def _browse_save_dir(self):
        chosen = filedialog.askdirectory(
            title="저장 폴더 선택",
            initialdir=self.save_dir.get()
        )
        if chosen:
            self.save_dir.set(chosen)
            self._update_fname_preview()

    def _reset_save_dir(self):
        _script_dir = os.path.dirname(os.path.abspath(__file__))
        self.save_dir.set(_script_dir)
        self._update_fname_preview()

    def _make_filename(self):
        ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
        return f"wafer_{ts}.json"

    def _update_fname_preview(self, *_):
        ts    = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
        fname = f"wafer_{ts}.json"
        dirp  = self.save_dir.get()
        disp  = dirp if len(dirp) <= 24 else "…" + dirp[-23:]
        self._fname_preview.config(
            text=f"저장 예정 파일:\n{disp}{os.sep}{fname}")

    # ── 전체 채우기 ───────────────────────────────────────
    def _fill_all(self):
        color = self.sel_color.get()
        label = self.sel_label.get()
        for d in self.dies:
            d["color"] = color
            d["label"] = label
        self._draw_all()

    # ── 캔버스 그리기 ─────────────────────────────────────
    def _draw_all(self):
        self.canvas.delete("all")
        r   = self._wafer_r_px
        pad = 2

        self.canvas.create_oval(pad, pad, r*2-pad, r*2-pad,
                                 outline="#1a3050", width=3,
                                 fill=WAFER_FILL)
        self.canvas.create_oval(pad+6, pad+6, r*2-pad-6, r*2-pad-6,
                                 outline=WAFER_EDGE, width=1, fill="")

        notch_y = r*2 - pad - 6
        self.canvas.create_arc(r-12, notch_y-12, r+12, notch_y+12,
                                start=210, extent=120,
                                outline=WAFER_EDGE, fill=WAFER_FILL,
                                width=1)

        hw = self._cell_w_px / 2
        hh = self._cell_h_px / 2
        for d in self.dies:
            x0 = d["cx"] - hw
            y0 = d["cy"] - hh
            self.canvas.create_rectangle(
                x0, y0, x0 + self._cell_w_px, y0 + self._cell_h_px,
                fill=d["color"], outline=GRID_COLOR, width=1,
                tags=(f"die_{d['id']}",))

        self.canvas.create_line(r-8, r, r+8, r, fill="#1a3050", width=1)
        self.canvas.create_line(r, r-8, r, r+8, fill="#1a3050", width=1)

        inch = self.wafer_inch.get()
        dmm  = WAFER_DIAMETER_MM[inch]
        w    = self.die_w_mm.get()
        h    = self.die_h_mm.get()
        self.title_info.config(
            text=f'{inch}" ({dmm:.0f} mm)  |  die: {w}×{h} mm')
        self.die_count_lbl.config(text=f"Total dies: {len(self.dies)}")
        self._update_stats()

    def _update_stats(self):
        counts = {}
        for d in self.dies:
            c = d["color"]
            counts[c] = counts.get(c, 0) + 1

        hex2name = {v: k for k, v in COLORS.items()}
        lines = []
        for hex_col, cnt in sorted(counts.items(), key=lambda x: -x[1]):
            if hex_col == DIE_BLANK:
                continue
            pct  = cnt / len(self.dies) * 100
            name = hex2name.get(hex_col, "Custom")
            lines.append(f"  {name:<10} {cnt:>4}  ({pct:.1f}%)")
        lines.append(f"  {'Total':<10} {len(self.dies):>4}")
        self.stat_var.set("\n".join(lines))

    # ── 색상 선택 ─────────────────────────────────────────
    def _select_preset(self, name, hex_col):
        self.sel_color.set(hex_col)
        self.sel_label.set(name)
        fg = "#000" if name in ("Pass", "Marginal") else "#fff"
        self.preview_box.config(text=f"  {name}  ", bg=hex_col, fg=fg)

    def _pick_custom(self):
        result = colorchooser.askcolor(color=self.custom_hex,
                                       title="커스텀 색상 선택")
        if result and result[1]:
            self.custom_hex = result[1]
            COLORS["Custom"] = self.custom_hex
            self.color_btns["Custom"].config(bg=self.custom_hex)
            self._select_preset("Custom", self.custom_hex)

    # ── 클릭/드래그 ───────────────────────────────────────
    def _on_click(self, event):
        x, y = event.x, event.y
        hw = self._cell_w_px / 2
        hh = self._cell_h_px / 2
        for d in self.dies:
            if abs(x - d["cx"]) <= hw and abs(y - d["cy"]) <= hh:
                d["color"] = self.sel_color.get()
                d["label"] = self.sel_label.get()
                x0  = d["cx"] - hw
                y0  = d["cy"] - hh
                tag = f"die_{d['id']}"
                self.canvas.delete(tag)
                self.canvas.create_rectangle(
                    x0, y0, x0 + self._cell_w_px, y0 + self._cell_h_px,
                    fill=d["color"], outline=GRID_COLOR, width=1,
                    tags=(tag,))
                self._update_stats()
                break

    # ── 랜덤 생성 ─────────────────────────────────────────
    def _randomize(self):
        weights = {k: self.sliders[k].get()
                   for k in ("Pass", "Fail", "Marginal", "Edge")}
        total = sum(weights.values()) or 1
        pool  = list(weights.keys())
        probs = [weights[k] / total for k in pool]
        for d in self.dies:
            ch = random.choices(pool, weights=probs, k=1)[0]
            d["color"] = COLORS[ch]
            d["label"] = ch
        self._draw_all()

    # ── 초기화 ────────────────────────────────────────────
    def _clear_all(self):
        for d in self.dies:
            d["color"] = DIE_BLANK
            d["label"] = None
        self._draw_all()

    # ── JSON 내보내기 ──────────────────────────────────────
    def _export_json(self):
        inch  = self.wafer_inch.get()
        w     = self.die_w_mm.get()
        h     = self.die_h_mm.get()
        ts    = datetime.datetime.now()
        fname = f"wafer_{ts.strftime('%Y%m%d_%H%M%S')}.json"
        dirp  = self.save_dir.get()

        # 경로 유효성 확인
        if not os.path.isdir(dirp):
            if messagebox.askyesno(
                    "경로 없음",
                    f"폴더가 존재하지 않습니다:\n{dirp}\n\n폴더를 생성할까요?"):
                try:
                    os.makedirs(dirp, exist_ok=True)
                except Exception as e:
                    messagebox.showerror("오류", f"폴더 생성 실패:\n{e}")
                    return
            else:
                return

        full_path = os.path.join(dirp, fname)

        data = {
            "saved_at":    ts.strftime("%Y-%m-%d %H:%M:%S"),
            "wafer_inch":  inch,
            "wafer_mm":    WAFER_DIAMETER_MM[inch],
            "die_w_mm":    w,
            "die_h_mm":    h,
            "total_dies":  len(self.dies),
            "dies": [
                {"id": d["id"], "row": d["row"], "col": d["col"],
                 "color": d["color"], "label": d["label"]}
                for d in self.dies if d["color"] != DIE_BLANK
            ]
        }

        try:
            with open(full_path, "w", encoding="utf-8") as f:
                json.dump(data, f, indent=2, ensure_ascii=False)
        except Exception as e:
            messagebox.showerror("저장 오류", f"파일 저장 실패:\n{e}")
            return

        n = len(data["dies"])
        messagebox.showinfo(
            "저장 완료",
            f"파일명:  {fname}\n"
            f"경로:    {dirp}\n\n"
            f"웨이퍼:  {inch}\" ({WAFER_DIAMETER_MM[inch]:.0f} mm)\n"
            f"다이:    W={w} × H={h} mm\n"
            f"색상 지정: {n} / {len(self.dies)} 개\n"
            f"저장 시각: {ts.strftime('%Y-%m-%d %H:%M:%S')}")

        # 미리보기 갱신 (다음 저장명 표시)
        self._update_fname_preview()


if __name__ == "__main__":
    app = WaferApp()
    app.mainloop()
