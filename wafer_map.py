import tkinter as tk
from tkinter import ttk, colorchooser, messagebox
import math
import random
import json

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
GRID_COLOR = "#1a2535"
WAFER_EDGE = "#2a4060"
WAFER_FILL = "#0d1b2a"

CANVAS_SIZE = 420   # 고정 캔버스 크기 (px)

# 웨이퍼 인치 → 실제 직경(mm)
WAFER_INCH_OPTIONS = [2, 4, 6, 8, 12]
WAFER_DIAMETER_MM  = {2: 50.8, 4: 100, 6: 150, 8: 200, 12: 300}

# 다이 크기 프리셋 (mm)
DIE_SIZE_OPTIONS = [1, 2, 3, 5, 8, 10, 15, 20]


# ── 다이 생성 ────────────────────────────────────────────
def generate_dies(wafer_r_px: float, cell_px: float):
    dies = []
    cols = int(wafer_r_px * 2 / cell_px)
    rows = cols
    ox   = wafer_r_px - (cols * cell_px) / 2
    oy   = wafer_r_px - (rows * cell_px) / 2
    for r in range(rows):
        for c in range(cols):
            cx = ox + c * cell_px + cell_px / 2
            cy = oy + r * cell_px + cell_px / 2
            dist = math.hypot(cx - wafer_r_px, cy - wafer_r_px)
            if dist + cell_px * 0.35 <= wafer_r_px * 0.97:
                dies.append({
                    "row": r, "col": c,
                    "cx": cx, "cy": cy,
                    "color": "#1c2e44",
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
        self.die_size_mm = tk.DoubleVar(value=5)
        self.sel_color   = tk.StringVar(value="#00e676")
        self.sel_label   = tk.StringVar(value="Pass")
        self.custom_hex  = "#ce93d8"

        self._wafer_r_px = 0.0
        self._cell_px    = 0.0
        self.dies = []
        self._recalc_geometry()

        self._build_ui()
        self._draw_all()

    # ── 기하 계산 ─────────────────────────────────────────
    def _recalc_geometry(self, keep_colors=False):
        wafer_mm = WAFER_DIAMETER_MM[self.wafer_inch.get()]
        die_mm   = self.die_size_mm.get()

        self._wafer_r_px = (CANVAS_SIZE - 4) / 2
        mm2px = (self._wafer_r_px * 2) / wafer_mm
        self._cell_px = max(die_mm * mm2px, 3.0)

        old_map = {}
        if keep_colors and self.dies:
            for d in self.dies:
                old_map[(d["row"], d["col"])] = (d["color"], d["label"])

        self.dies = generate_dies(self._wafer_r_px, self._cell_px)

        if keep_colors:
            for d in self.dies:
                key = (d["row"], d["col"])
                if key in old_map:
                    d["color"], d["label"] = old_map[key]

    # ── UI 빌드 ───────────────────────────────────────────
    def _build_ui(self):
        # ── 왼쪽 캔버스 영역 ──
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
                 fg="#4a6080", bg=BG, justify=tk.LEFT).pack(anchor="w", pady=(10, 0))

        # ── 오른쪽 패널 ──
        right = tk.Frame(self, bg=PANEL_BG, relief=tk.FLAT,
                         highlightbackground=BORDER, highlightthickness=1)
        right.pack(side=tk.LEFT, padx=(10, 20), pady=20, fill=tk.Y)

        tk.Label(right, text="CONTROLS", font=("Courier", 12, "bold"),
                 fg=ACCENT, bg=PANEL_BG).pack(pady=(16, 4))
        ttk.Separator(right, orient="horizontal").pack(fill=tk.X, padx=12, pady=4)

        # ─────────────────────────────────
        # ① 웨이퍼 크기
        # ─────────────────────────────────
        tk.Label(right, text="웨이퍼 크기 (inch)", font=("Courier", 9, "bold"),
                 fg=TEXT_COLOR, bg=PANEL_BG).pack(anchor="w", padx=14, pady=(10, 4))

        inch_frame = tk.Frame(right, bg=PANEL_BG)
        inch_frame.pack(padx=14, fill=tk.X)

        self._inch_btns = {}
        for inch in WAFER_INCH_OPTIONS:
            diam = WAFER_DIAMETER_MM[inch]
            b = tk.Button(
                inch_frame,
                text=f'{inch}"  ({diam:.0f} mm)',
                font=("Courier", 9),
                bg="#1c2e44", fg=TEXT_COLOR,
                activebackground="#253550",
                bd=0, cursor="hand2", pady=4,
                command=lambda v=inch: self._set_wafer_inch(v)
            )
            b.pack(fill=tk.X, pady=1)
            self._inch_btns[inch] = b
        self._highlight_inch_btn()

        ttk.Separator(right, orient="horizontal").pack(fill=tk.X, padx=12, pady=8)

        # ─────────────────────────────────
        # ② 다이 크기
        # ─────────────────────────────────
        tk.Label(right, text="다이 크기 (mm × mm)", font=("Courier", 9, "bold"),
                 fg=TEXT_COLOR, bg=PANEL_BG).pack(anchor="w", padx=14, pady=(0, 4))

        die_frame = tk.Frame(right, bg=PANEL_BG)
        die_frame.pack(padx=14, fill=tk.X)

        preset_grid = tk.Frame(die_frame, bg=PANEL_BG)
        preset_grid.pack(fill=tk.X, pady=(0, 4))

        self._die_btns = {}
        for idx, mm in enumerate(DIE_SIZE_OPTIONS):
            b = tk.Button(
                preset_grid,
                text=f"{mm}×{mm}",
                font=("Courier", 8),
                bg="#1c2e44", fg=TEXT_COLOR,
                activebackground="#253550",
                bd=0, cursor="hand2", pady=3,
                command=lambda v=mm: self._set_die_preset(v)
            )
            b.grid(row=idx // 2, column=idx % 2, padx=2, pady=2, sticky="ew")
            self._die_btns[mm] = b
        preset_grid.columnconfigure(0, weight=1)
        preset_grid.columnconfigure(1, weight=1)

        # 직접 입력
        cdr = tk.Frame(die_frame, bg=PANEL_BG)
        cdr.pack(fill=tk.X, pady=(4, 0))
        tk.Label(cdr, text="직접 입력:", font=("Courier", 8),
                 fg="#7a90a8", bg=PANEL_BG).pack(side=tk.LEFT)
        self._die_entry = tk.Entry(cdr, width=6, font=("Courier", 9),
                                   bg="#1c2e44", fg=TEXT_COLOR,
                                   insertbackground=ACCENT,
                                   relief=tk.FLAT, bd=2)
        self._die_entry.insert(0, "5")
        self._die_entry.pack(side=tk.LEFT, padx=4)
        tk.Label(cdr, text="mm", font=("Courier", 8),
                 fg="#7a90a8", bg=PANEL_BG).pack(side=tk.LEFT)
        tk.Button(cdr, text="적용", font=("Courier", 8),
                  bg=ACCENT, fg="#000", activebackground="#00e5f5",
                  bd=0, cursor="hand2", padx=6,
                  command=self._apply_custom_die).pack(side=tk.LEFT, padx=(4, 0))

        self._highlight_die_btn()

        ttk.Separator(right, orient="horizontal").pack(fill=tk.X, padx=12, pady=8)

        # ─────────────────────────────────
        # ③ 색상 팔레트
        # ─────────────────────────────────
        tk.Label(right, text="색상 선택", font=("Courier", 9, "bold"),
                 fg=TEXT_COLOR, bg=PANEL_BG).pack(anchor="w", padx=14, pady=(0, 4))

        pal_frame = tk.Frame(right, bg=PANEL_BG)
        pal_frame.pack(padx=14, pady=2, fill=tk.X)

        self.color_btns = {}
        for name, hex_col in COLORS.items():
            b = tk.Button(
                pal_frame, text=name, font=("Courier", 9),
                bg=hex_col, fg="#000" if name in ("Pass", "Marginal") else "#fff",
                activebackground=hex_col,
                width=9, bd=0, cursor="hand2",
                command=lambda n=name, h=hex_col: self._select_preset(n, h)
            )
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

        ttk.Separator(right, orient="horizontal").pack(fill=tk.X, padx=12, pady=6)

        # ─────────────────────────────────
        # ④ 랜덤 생성
        # ─────────────────────────────────
        tk.Label(right, text="랜덤 생성", font=("Courier", 9, "bold"),
                 fg=TEXT_COLOR, bg=PANEL_BG).pack(anchor="w", padx=14, pady=(0, 4))

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
                     fg=TEXT_COLOR, bg=PANEL_BG, width=5).pack(side=tk.LEFT)
            var = tk.IntVar(value=default)
            tk.Scale(row, from_=0, to=100, orient=tk.HORIZONTAL,
                     variable=var, bg=PANEL_BG, fg=TEXT_COLOR,
                     troughcolor=GRID_COLOR, activebackground=color,
                     highlightthickness=0, sliderlength=12, width=8,
                     length=100, showvalue=False).pack(side=tk.LEFT)
            tk.Label(row, textvariable=var, font=("Courier", 8),
                     fg=color, bg=PANEL_BG, width=3).pack(side=tk.LEFT)
            self.sliders[name] = var

        tk.Button(rand_frame, text="▶  랜덤 생성", font=("Courier", 10, "bold"),
                  bg=ACCENT, fg="#000", activebackground="#00e5f5",
                  bd=0, cursor="hand2", pady=6,
                  command=self._randomize).pack(fill=tk.X, pady=(8, 2))

        ttk.Separator(right, orient="horizontal").pack(fill=tk.X, padx=12, pady=6)

        # ─────────────────────────────────
        # ⑤ 기타 버튼
        # ─────────────────────────────────
        btn_frame = tk.Frame(right, bg=PANEL_BG)
        btn_frame.pack(padx=14, fill=tk.X, pady=(0, 14))

        tk.Button(btn_frame, text="초기화", font=("Courier", 9),
                  bg="#1e2d45", fg=TEXT_COLOR, activebackground="#253550",
                  bd=0, cursor="hand2", pady=5,
                  command=self._clear_all).pack(fill=tk.X, pady=2)

        tk.Button(btn_frame, text="JSON 내보내기", font=("Courier", 9),
                  bg="#1e2d45", fg=TEXT_COLOR, activebackground="#253550",
                  bd=0, cursor="hand2", pady=5,
                  command=self._export_json).pack(fill=tk.X, pady=2)

    # ── 웨이퍼 인치 변경 ──────────────────────────────────
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

    # ── 다이 크기 변경 ────────────────────────────────────
    def _set_die_preset(self, mm):
        self.die_size_mm.set(mm)
        self._die_entry.delete(0, tk.END)
        self._die_entry.insert(0, str(mm))
        self._highlight_die_btn()
        self._recalc_geometry(keep_colors=False)
        self._draw_all()

    def _apply_custom_die(self):
        try:
            val = float(self._die_entry.get())
            if val <= 0:
                raise ValueError
        except ValueError:
            messagebox.showerror("입력 오류", "양수 값을 입력하세요.")
            return
        self.die_size_mm.set(val)
        self._highlight_die_btn()
        self._recalc_geometry(keep_colors=False)
        self._draw_all()

    def _highlight_die_btn(self):
        cur = self.die_size_mm.get()
        for mm, btn in self._die_btns.items():
            btn.config(bg=ACCENT if mm == cur else "#1c2e44",
                       fg="#000"  if mm == cur else TEXT_COLOR)

    # ── 캔버스 그리기 ─────────────────────────────────────
    def _draw_all(self):
        self.canvas.delete("all")
        r   = self._wafer_r_px
        pad = 2

        self.canvas.create_oval(pad, pad, r*2-pad, r*2-pad,
                                 outline="#1a3050", width=3, fill=WAFER_FILL)
        self.canvas.create_oval(pad+6, pad+6, r*2-pad-6, r*2-pad-6,
                                 outline=WAFER_EDGE, width=1, fill="")

        notch_y = r*2 - pad - 6
        self.canvas.create_arc(r-12, notch_y-12, r+12, notch_y+12,
                                start=210, extent=120,
                                outline=WAFER_EDGE, fill=WAFER_FILL, width=1)

        half = self._cell_px / 2
        for d in self.dies:
            x0 = d["cx"] - half
            y0 = d["cy"] - half
            self.canvas.create_rectangle(
                x0, y0, x0 + self._cell_px, y0 + self._cell_px,
                fill=d["color"], outline=GRID_COLOR, width=1,
                tags=(f"die_{d['id']}",))

        self.canvas.create_line(r-8, r, r+8, r, fill="#1a3050", width=1)
        self.canvas.create_line(r, r-8, r, r+8, fill="#1a3050", width=1)

        inch = self.wafer_inch.get()
        dmm  = WAFER_DIAMETER_MM[inch]
        die  = self.die_size_mm.get()
        self.title_info.config(
            text=f'{inch}" ({dmm:.0f} mm)  |  die: {die}×{die} mm')
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
            if hex_col == "#1c2e44":
                continue
            pct = cnt / len(self.dies) * 100
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

    # ── 클릭/드래그 ──────────────────────────────────────
    def _on_click(self, event):
        x, y = event.x, event.y
        half = self._cell_px / 2
        for d in self.dies:
            if abs(x - d["cx"]) <= half and abs(y - d["cy"]) <= half:
                d["color"] = self.sel_color.get()
                d["label"] = self.sel_label.get()
                x0  = d["cx"] - half
                y0  = d["cy"] - half
                tag = f"die_{d['id']}"
                self.canvas.delete(tag)
                self.canvas.create_rectangle(
                    x0, y0, x0 + self._cell_px, y0 + self._cell_px,
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

    # ── 초기화 ───────────────────────────────────────────
    def _clear_all(self):
        for d in self.dies:
            d["color"] = "#1c2e44"
            d["label"] = None
        self._draw_all()

    # ── JSON 내보내기 ─────────────────────────────────────
    def _export_json(self):
        inch = self.wafer_inch.get()
        die  = self.die_size_mm.get()
        data = {
            "wafer_inch":  inch,
            "wafer_mm":    WAFER_DIAMETER_MM[inch],
            "die_size_mm": die,
            "total_dies":  len(self.dies),
            "dies": [
                {"id": d["id"], "row": d["row"], "col": d["col"],
                 "color": d["color"], "label": d["label"]}
                for d in self.dies if d["color"] != "#1c2e44"
            ]
        }
        path = "/mnt/user-data/outputs/wafer_data.json"
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, ensure_ascii=False)
        n = len(data["dies"])
        messagebox.showinfo(
            "내보내기 완료",
            f"저장 완료: {path}\n\n"
            f"웨이퍼: {inch}\" ({WAFER_DIAMETER_MM[inch]:.0f} mm)\n"
            f"다이: {die}×{die} mm  |  색상 지정 다이: {n}개")


if __name__ == "__main__":
    app = WaferApp()
    app.mainloop()
