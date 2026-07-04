import json
import os
import re
import socket
import sys
import threading
import time
import tkinter as tk
import tkinter.font as tkfont
import ctypes
from http.cookiejar import CookieJar
from pathlib import Path
from tkinter import ttk
from urllib.parse import quote, urlencode
from urllib.request import HTTPCookieProcessor, Request, build_opener


PORTAL_HOST = "http://10.10.102.50"
PORTAL_LOGIN = "http://10.10.102.50:801/eportal/portal/login"
PORTAL_LOGOUT = "http://10.10.102.50:801/eportal/portal/logout"
PORTAL_ONLINE_LIST = "http://10.10.102.50:801/eportal/portal/online_list"
USS_LOGIN = "http://uss.tust.edu.cn/login/?302=LI"
USS_HOST = "http://uss.tust.edu.cn"
AC_IP = "10.10.102.49"
AC_NAME = ""

OPERATORS = [
    ("校园网", ""),
    ("中国联通", "@unicom"),
    ("中国移动", "@cmcc"),
    ("中国电信", "@telecom"),
]

APP_DIR = Path(os.environ.get("APPDATA", str(Path.home()))) / "TustCampusLogin"
CONFIG_PATH = APP_DIR / "config.json"


def enable_dpi_awareness():
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(2)
    except Exception:
        try:
            ctypes.windll.user32.SetProcessDPIAware()
        except Exception:
            pass


def resource_path(relative_path):
    base_path = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
    return base_path / relative_path


class CampusClient:
    def __init__(self):
        self.opener = build_opener(HTTPCookieProcessor(CookieJar()))

    def get(self, url, timeout=10):
        req = Request(url, headers={"User-Agent": "Mozilla/5.0 Windows TustCampusLogin/1.0"})
        with self.opener.open(req, timeout=timeout) as res:
            return res.read().decode("utf-8", errors="replace")

    def post_form(self, url, data, timeout=12):
        body = urlencode(data).encode("utf-8")
        req = Request(
            url,
            data=body,
            headers={
                "User-Agent": "Mozilla/5.0 Windows TustCampusLogin/1.0",
                "Content-Type": "application/x-www-form-urlencoded",
                "Referer": USS_LOGIN,
            },
        )
        with self.opener.open(req, timeout=timeout) as res:
            return res.read().decode("utf-8", errors="replace")

    def portal_url(self, ip):
        return (
            f"{PORTAL_HOST}/a79.htm?"
            f"wlanuserip={quote(ip)}&wlanacname={quote(AC_NAME)}&wlanacip={quote(AC_IP)}"
        )

    def login_url(self, ip, account, password, operator_index):
        suffix = OPERATORS[operator_index][1]
        params = {
            "callback": "dr1239",
            "login_method": "1",
            "user_account": f",0,{account}{suffix}",
            "user_password": password,
            "wlan_user_ip": ip,
            "wlan_user_ipv6": get_local_ipv6(),
            "wlan_user_mac": "000000000000",
            "wlan_ac_ip": AC_IP,
            "wlan_ac_name": AC_NAME,
            "jsVersion": "4.1.3",
            "terminal_type": "1",
            "lang": "zh-cn",
            "v": "1873",
        }
        return PORTAL_LOGIN + "?" + urlencode(params)

    def logout_url(self, ip):
        params = {
            "callback": "dr1003",
            "login_method": "1",
            "user_account": "drcom",
            "user_password": "123",
            "ac_logout": "1",
            "register_mode": "1",
            "wlan_user_ip": ip,
            "wlan_user_ipv6": "",
            "wlan_vlan_id": "1",
            "wlan_user_mac": "000000000000",
            "wlan_ac_ip": "",
            "wlan_ac_name": "",
            "jsVersion": "4.1.3",
            "v": "9016",
            "lang": "zh",
        }
        return PORTAL_LOGOUT + "?" + urlencode(params)

    def online_list_url(self, ip):
        ip_number = str(ip_to_unsigned_int(ip))
        params = {
            "callback": "dr1019",
            "user_account": "",
            "user_password": "123",
            "wlan_user_mac": "000000000000",
            "wlan_user_ip": ip_number,
            "curr_user_ip": ip_number,
            "jsVersion": "4.1.3",
            "v": "3344",
            "lang": "zh",
        }
        return PORTAL_ONLINE_LIST + "?" + urlencode(params)

    def query_uss_flow(self, account, password):
        login_page = self.get(USS_LOGIN, timeout=10)
        checkcode = first_regex(login_page, r'name="checkcode"\s+value="([^"]*)"')
        action = first_regex(login_page, r'<form\s+action="([^"]*login/verify[^"]*)"') or "/login/verify"
        if not checkcode:
            raise RuntimeError("USS checkcode not found")
        self.get(f"{USS_HOST}/login/randomCode?t={int(time.time() * 1000)}", timeout=10)
        dashboard = self.post_form(
            absolute_uss_url(action),
            {
                "foo": "",
                "bar": "",
                "checkcode": checkcode,
                "account": account,
                "password": password,
                "code": "",
            },
            timeout=12,
        )
        if "leftFlow" not in dashboard and "useFlow" not in dashboard:
            dashboard = self.get(f"{USS_HOST}/dashboard", timeout=10)
        if "leftFlow" not in dashboard and "useFlow" not in dashboard:
            raise RuntimeError("USS dashboard has no flow")
        return dashboard

    def login(self, account, password, operator_index):
        ensure_campus_network()
        ip = get_local_ipv4()
        source = ""
        try:
            source = self.get(self.portal_url(ip), timeout=8)
        except Exception:
            pass
        if contains_any(source, "\u6ce8\u9500\u9875", "olflow="):
            return "\u5f53\u524d\u8bbe\u5907\u5df2\u8fde\u63a5", format_flow(parse_flow(source)), True

        result = self.get(self.login_url(ip, account, password, operator_index), timeout=10)
        if contains_any(result, "\u534f\u8bae\u8ba4\u8bc1\u6210\u529f", '"result":"1"', "\u8ba4\u8bc1\u6210\u529f"):
            flow_source = result + self.safe_extra_flow(ip, account, password)
            return f"\u767b\u5f55\u6210\u529f\uff1a\u8bbe\u5907 IP {ip}", format_flow(parse_flow(flow_source)), True
        if is_already_online(result):
            flow_source = result + self.safe_extra_flow(ip, account, password)
            return f"\u8be5\u8d26\u53f7\u5df2\u767b\u5f55\uff1a\u8bbe\u5907 IP {ip}", format_flow(parse_flow(flow_source)), True
        return classify_login_error(result), "\u6d41\u91cf\uff1a\u672a\u77e5", False

    def refresh(self, account, password):
        ensure_campus_network()
        ip = get_local_ipv4()
        flow_source = ""
        try:
            flow_source += self.get(self.portal_url(ip), timeout=8)
        except Exception:
            pass
        flow_source += self.safe_extra_flow(ip, account, password)
        if account and is_current_account(flow_source, account):
            return "\u6d41\u91cf\u5df2\u5237\u65b0", format_flow(parse_flow(flow_source)), True
        return "\u5df2\u8fde\u63a5\u6821\u56ed\u7f51\uff0c\u4f46\u4e0d\u662f\u5f53\u524d\u5b66\u53f7", "\u6d41\u91cf\uff1a\u7b49\u5f85\u767b\u5f55\u7ed3\u679c", False

    def logout(self):
        ensure_campus_network()
        ip = get_local_ipv4()
        result = self.get(self.logout_url(ip), timeout=10)
        if contains_any(result, "\u6ce8\u9500\u6210\u529f", "logout", '"result":"1"', "\u4e0a\u7f51\u767b\u5f55\u9875"):
            return "\u5df2\u6ce8\u9500", "\u6d41\u91cf\uff1a\u7b49\u5f85\u767b\u5f55\u7ed3\u679c", False
        return "\u6ce8\u9500\u8bf7\u6c42\u5df2\u53d1\u9001\uff1a" + trim_for_display(result), "\u6d41\u91cf\uff1a\u7b49\u5f85\u767b\u5f55\u7ed3\u679c", False

    def detect(self, account, password):
        ensure_campus_network()
        ip = get_local_ipv4()
        flow_source = ""
        logged_in = False
        try:
            page = self.get(self.portal_url(ip), timeout=8)
            flow_source += page
            logged_in = logged_in or contains_any(page, "\u6ce8\u9500\u9875", "olflow=")
        except Exception:
            pass
        try:
            online = self.get(self.online_list_url(ip), timeout=8)
            flow_source += "\n" + online
            logged_in = logged_in or contains_any(online, '"loginTime"', '"ip"')
        except Exception:
            pass
        try:
            uss = self.query_uss_flow(account, password)
            flow_source += "\n" + uss
            logged_in = True
        except Exception:
            pass
        if logged_in and is_current_account(flow_source, account):
            return "\u5f53\u524d\u8d26\u53f7\u5df2\u8fde\u63a5", format_flow(parse_flow(flow_source)), True
        if logged_in:
            return "\u5df2\u8fde\u63a5\u6821\u56ed\u7f51\uff0c\u4f46\u4e0d\u662f\u5f53\u524d\u5b66\u53f7", "\u6d41\u91cf\uff1a\u7b49\u5f85\u767b\u5f55\u7ed3\u679c", False
        return f"\u672a\u767b\u5f55\uff1a\u8bbe\u5907 IP {ip}", "\u6d41\u91cf\uff1a\u7b49\u5f85\u767b\u5f55\u7ed3\u679c", False

    def safe_extra_flow(self, ip, account, password):
        parts = []
        try:
            parts.append(self.get(self.online_list_url(ip), timeout=8))
        except Exception:
            pass
        try:
            parts.append(self.query_uss_flow(account, password))
        except Exception as exc:
            parts.append(f"USS\u67e5\u8be2\u5931\u8d25\uff1a{safe_error_message(exc)}")
        return "\n" + "\n".join(parts)




UI_BG = "#F5FAFD"
CARD_BG = "#FFFFFF"
TEXT = "#0F172A"
MUTED = "#64748B"
BORDER = "#D7E2EA"
PRIMARY = "#008C95"
PRIMARY_HOVER = "#007B86"
BLUE = "#2563EB"
BLUE_HOVER = "#1D4ED8"
SOFT_BLUE = "#EFF6FF"
SOFT_CYAN = "#E0F7FA"
WARNING = "#F59E0B"


def _widget_bg(widget, fallback=UI_BG):
    try:
        return widget.cget("bg")
    except Exception:
        return fallback


class RoundedCanvas(tk.Canvas):
    def __init__(self, parent, radius=16, fill=CARD_BG, outline=BORDER, width=1, **kwargs):
        super().__init__(parent, highlightthickness=0, bd=0, bg=_widget_bg(parent), **kwargs)
        self.radius = radius
        self.fill = fill
        self.outline = outline
        self.outline_width = width
        self.bind("<Configure>", self._redraw)

    def _redraw(self, event=None):
        self.delete("shape")
        w = max(2, self.winfo_width())
        h = max(2, self.winfo_height())
        self.round_rect(1, 1, w - 2, h - 2, self.radius,
                        fill=self.fill, outline=self.outline,
                        width=self.outline_width, tags="shape")
        self.tag_lower("shape")

    def round_rect(self, x1, y1, x2, y2, r, **kwargs):
        points = [
            x1+r, y1, x2-r, y1, x2, y1, x2, y1+r,
            x2, y2-r, x2, y2, x2-r, y2, x1+r, y2,
            x1, y2, x1, y2-r, x1, y1+r, x1, y1,
        ]
        return self.create_polygon(points, smooth=True, **kwargs)


class RoundButton(tk.Canvas):
    def __init__(self, parent, text, command, bg, fg, hover=None,
                 outline=None, radius=12, height=48, font_size=14, icon=None):
        super().__init__(parent, height=height, highlightthickness=0, bd=0,
                         bg=_widget_bg(parent), cursor="hand2")
        self.text = text
        self.command = command
        self.default_bg = bg
        self.default_fg = fg
        self.hover = hover or bg
        self.outline = outline or bg
        self.radius = radius
        self.height = height
        self.font_size = font_size
        self.icon = icon
        self.enabled = True
        self.hovered = False
        self.bind("<Configure>", lambda event: self.draw())
        self.bind("<Button-1>", self._click)
        self.bind("<Return>", self._click)
        self.bind("<space>", self._click)
        self.bind("<Enter>", lambda event: self._set_hover(True))
        self.bind("<Leave>", lambda event: self._set_hover(False))
        self.configure(takefocus=1)

    def _set_hover(self, hovered):
        self.hovered = hovered
        self.draw()

    def _click(self, event=None):
        if self.enabled and self.command:
            self.command()
        return "break"

    def set_enabled(self, enabled):
        self.enabled = bool(enabled)
        self.configure(cursor="hand2" if enabled else "arrow")
        self.draw()

    def draw(self):
        self.delete("all")
        w = max(2, self.winfo_width())
        h = max(2, self.winfo_height())
        fill = "#CBD5E1" if not self.enabled else (self.hover if self.hovered else self.default_bg)
        outline = "#CBD5E1" if not self.enabled else self.outline
        fg = "#64748B" if not self.enabled else self.default_fg
        self.round_rect(1, 1, w - 1, h - 1, self.radius, fill=fill, outline=outline, width=1.4)
        font = ("Microsoft YaHei UI", self.font_size, "bold")
        text_x = w / 2
        if self.icon:
            text_w = tkfont.Font(font=font).measure(self.text)
            icon_w = 34 if self.icon == "wifi" else 30
            gap = 12
            group_w = icon_w + gap + text_w
            icon_x = w / 2 - group_w / 2 + icon_w / 2
            text_x = w / 2 - group_w / 2 + icon_w + gap + text_w / 2
            self._draw_icon(icon_x, h / 2, fg)
        self.create_text(text_x, h/2, text=self.text, fill=fg,
                         font=font)

    def _draw_icon(self, x, y, color):
        if self.icon == "wifi":
            self.create_arc(x - 20, y - 18, x + 20, y + 20, start=35, extent=110,
                            style="arc", outline=color, width=3)
            self.create_arc(x - 13, y - 9, x + 13, y + 16, start=35, extent=110,
                            style="arc", outline=color, width=3)
            self.create_arc(x - 6, y, x + 6, y + 12, start=35, extent=110,
                            style="arc", outline=color, width=3)
            self.create_oval(x - 2.5, y + 12, x + 2.5, y + 17, fill=color, outline=color)
        elif self.icon == "user":
            self.create_oval(x - 5, y - 12, x + 5, y - 2, fill=color, outline=color)
            self.create_polygon(x - 11, y + 12, x + 11, y + 12, x + 8, y, x - 8, y,
                                fill=color, outline=color)
        elif self.icon == "refresh":
            self.create_arc(x - 11, y - 11, x + 11, y + 11, start=35, extent=280,
                            style="arc", outline=color, width=3)
            self.create_line(x + 10, y - 10, x + 16, y - 10, x + 14, y - 4,
                             fill=color, width=3, capstyle="round", joinstyle="round")

    def round_rect(self, x1, y1, x2, y2, r, **kwargs):
        points = [
            x1+r, y1, x2-r, y1, x2, y1, x2, y1+r,
            x2, y2-r, x2, y2, x2-r, y2, x1+r, y2,
            x1, y2, x1, y2-r, x1, y1+r, x1, y1,
        ]
        return self.create_polygon(points, smooth=True, **kwargs)


class VectorIcon(tk.Canvas):
    def __init__(self, parent, kind, size=24, color=PRIMARY, bg=None):
        super().__init__(parent, width=size, height=size, bg=bg or _widget_bg(parent),
                         highlightthickness=0, bd=0)
        self.kind = kind
        self.size = size
        self.color = color
        self.bind("<Configure>", lambda event: self.draw())
        self.draw()

    def draw(self):
        self.delete("all")
        s = min(self.winfo_width() or self.size, self.winfo_height() or self.size)
        c = self.color
        if self.kind == "user":
            self.create_oval(.34*s, .10*s, .66*s, .42*s, fill=c, outline=c)
            self.create_polygon(.16*s, .88*s, .84*s, .88*s, .74*s, .50*s, .26*s, .50*s,
                                fill=c, outline=c)
        elif self.kind == "lock":
            self.create_arc(.26*s, .12*s, .74*s, .62*s, start=0, extent=180,
                            style="arc", outline=c, width=max(2, int(.09*s)))
            self.create_rectangle(.18*s, .45*s, .82*s, .88*s, fill=c, outline=c)
            self.create_oval(.46*s, .62*s, .54*s, .70*s, fill="#FFFFFF", outline="#FFFFFF")
            self.create_rectangle(.485*s, .68*s, .515*s, .78*s, fill="#FFFFFF", outline="#FFFFFF")
        elif self.kind == "globe":
            w = max(2, int(.08*s))
            self.create_oval(.14*s, .14*s, .86*s, .86*s, outline=c, width=w)
            self.create_line(.14*s, .50*s, .86*s, .50*s, fill=c, width=w)
            self.create_arc(.30*s, .14*s, .70*s, .86*s, start=90, extent=180,
                            style="arc", outline=c, width=w)
            self.create_arc(.30*s, .14*s, .70*s, .86*s, start=-90, extent=180,
                            style="arc", outline=c, width=w)
        elif self.kind == "warning":
            self.create_oval(1, 1, s-1, s-1, fill=WARNING, outline=WARNING)
            self.create_rectangle(.46*s, .22*s, .54*s, .62*s, fill="#FFFFFF", outline="#FFFFFF")
            self.create_oval(.45*s, .72*s, .55*s, .82*s, fill="#FFFFFF", outline="#FFFFFF")
        elif self.kind == "flow":
            self.create_oval(1, 1, s-1, s-1, fill=BLUE, outline=BLUE)
            self.create_polygon(.50*s, .16*s, .72*s, .52*s, .61*s, .78*s,
                                .39*s, .78*s, .28*s, .52*s, fill="#FFFFFF", outline="#FFFFFF")
        elif self.kind == "notice":
            self.create_oval(2, 2, s-2, s-2, fill="#FFF2D8", outline="#FFF2D8")
            self.create_rectangle(.46*s, .22*s, .54*s, .62*s, fill=WARNING, outline=WARNING)
            self.create_oval(.44*s, .72*s, .56*s, .84*s, fill=WARNING, outline=WARNING)
        elif self.kind == "exit":
            self.create_oval(2, 2, s-2, s-2, fill="#E1F7FA", outline="#E1F7FA")
            self.create_rectangle(.22*s, .24*s, .48*s, .78*s, fill=PRIMARY, outline=PRIMARY)
            self.create_oval(.38*s, .50*s, .43*s, .55*s, fill="#FFFFFF", outline="#FFFFFF")
            self.create_line(.52*s, .50*s, .78*s, .50*s, fill=PRIMARY, width=max(3, int(.07*s)))
            self.create_polygon(.78*s, .50*s, .64*s, .38*s, .64*s, .62*s,
                                fill=PRIMARY, outline=PRIMARY)


class EyeButton(tk.Canvas):
    def __init__(self, parent, command):
        super().__init__(parent, width=54, height=48, bg="#FBFDFF", highlightthickness=0,
                         bd=0, cursor="hand2")
        self.command = command
        self.visible = False
        self.bind("<Button-1>", self._click)
        self.bind("<Return>", self._click)
        self.bind("<space>", self._click)
        self.configure(takefocus=1)
        self.draw()

    def _click(self, event=None):
        if self.command:
            self.command()
        return "break"

    def set_visible(self, visible):
        self.visible = bool(visible)
        self.draw()

    def draw(self):
        self.delete("all")
        c = PRIMARY
        self.create_arc(14, 17, 40, 31, start=18, extent=144, style="arc", outline=c, width=2)
        self.create_arc(14, 17, 40, 31, start=198, extent=144, style="arc", outline=c, width=2)
        self.create_oval(25, 21, 29, 25, fill=c, outline=c)
        if not self.visible:
            self.create_line(16, 32, 39, 16, fill=c, width=2, capstyle="round")


class ToggleCheck(tk.Canvas):
    def __init__(self, parent, variable, text):
        super().__init__(parent, height=38, highlightthickness=0, bd=0,
                         bg=_widget_bg(parent), cursor="hand2")
        self.variable = variable
        self.text = text
        self.configure(takefocus=1)
        self.bind("<Configure>", lambda event: self.draw())
        self.bind("<Button-1>", self.toggle)
        self.bind("<space>", self.toggle)
        self.bind("<Return>", self.toggle)

    def toggle(self, event=None):
        self.variable.set(not self.variable.get())
        self.draw()
        return "break"

    def draw(self):
        self.delete("all")
        checked = self.variable.get()
        box = 24
        x, y = 1, 7
        fill = PRIMARY if checked else CARD_BG
        outline = PRIMARY if checked else BORDER
        self.round_rect(x, y, x + box, y + box, 6, fill=fill, outline=outline, width=2)
        if checked:
            self.create_line(x+6, y+13, x+11, y+18, x+19, y+7,
                             fill="#FFFFFF", width=3, capstyle="round", joinstyle="round")
        self.create_text(36, 19, text=self.text, fill="#334155", anchor="w",
                         font=("Microsoft YaHei UI", 11))

    def round_rect(self, x1, y1, x2, y2, r, **kwargs):
        points = [
            x1+r, y1, x2-r, y1, x2, y1, x2, y1+r,
            x2, y2-r, x2, y2, x2-r, y2, x1+r, y2,
            x1, y2, x1, y2-r, x1, y1+r, x1, y1,
        ]
        return self.create_polygon(points, smooth=True, **kwargs)


class PlaceholderEntry(tk.Entry):
    def __init__(self, parent, placeholder="", show_char="", **kwargs):
        self.placeholder = placeholder
        self.placeholder_color = kwargs.pop("placeholder_color", "#94A3B8")
        self.normal_color = kwargs.get("fg", TEXT)
        self.show_char = show_char
        self.placeholder_active = False
        super().__init__(parent, **kwargs)
        self.bind("<FocusIn>", self._clear_placeholder, add=True)
        self.bind("<FocusOut>", self._maybe_placeholder, add=True)
        self.put_placeholder()

    def put_placeholder(self):
        if not self.get():
            self.placeholder_active = True
            self.configure(fg=self.placeholder_color, show="")
            self.delete(0, "end")
            self.insert(0, self.placeholder)

    def _clear_placeholder(self, event=None):
        if self.placeholder_active:
            self.placeholder_active = False
            self.configure(fg=self.normal_color, show=self.show_char)
            self.delete(0, "end")

    def _maybe_placeholder(self, event=None):
        if not self.get():
            self.put_placeholder()

    def value(self):
        return "" if self.placeholder_active else self.get()

    def set_value(self, value):
        self.placeholder_active = False
        self.configure(fg=self.normal_color, show=self.show_char)
        self.delete(0, "end")
        if value:
            self.insert(0, value)
        else:
            self.put_placeholder()

    def set_show_char(self, show_char):
        self.show_char = show_char
        if not self.placeholder_active:
            self.configure(show=show_char)


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("天科校园网")
        self._set_initial_geometry()
        self.minsize(620, 720)
        self.configure(bg=UI_BG)
        self.option_add("*Font", "{Microsoft YaHei UI} 10")
        self.client = CampusClient()
        self.password_visible = False
        self.icon_image = None
        self.eye_open_image = None
        self.eye_closed_image = None
        self.ui_images = {}
        self._set_window_icon()
        self._load_ui_images()
        self._build_ui()
        self._load_config()
        self.protocol("WM_DELETE_WINDOW", self.confirm_exit)
        self.after(120, self._fit_initial_window)
        self.after(300, self.detect)

    def _set_initial_geometry(self):
        screen_w = self.winfo_screenwidth()
        screen_h = self.winfo_screenheight()
        width = min(720, max(680, screen_w - 220))
        height = min(1020, max(860, screen_h - 70))
        x = max(20, (screen_w - width) // 2)
        y = max(20, (screen_h - height) // 2)
        self.geometry(f"{width}x{height}+{x}+{y}")

    def _fit_initial_window(self):
        self.update_idletasks()
        req_h = self.content.winfo_reqheight() + 44
        max_h = self.winfo_screenheight() - 80
        target_h = min(max_h, max(self.winfo_height(), req_h, 900))
        target_w = max(self.winfo_width(), 680)
        x = max(20, (self.winfo_screenwidth() - target_w) // 2)
        y = max(20, (self.winfo_screenheight() - target_h) // 2)
        self.geometry(f"{target_w}x{target_h}+{x}+{y}")
        self.after_idle(self._update_scroll_region)

    def _set_window_icon(self):
        ico = resource_path("assets/app_icon.ico")
        try:
            if ico.exists():
                self.iconbitmap(str(ico))
        except Exception:
            pass

        # Windows title-bar/taskbar icons look sharper when Tk receives multiple
        # explicit bitmap sizes instead of one large PNG being downscaled.
        self.icon_images = []
        for name in (
            "app_icon_16.png",
            "app_icon_24.png",
            "app_icon_32.png",
            "app_icon_48.png",
            "app_icon_64.png",
            "app_icon_128.png",
            "app_icon_256.png",
            "app_icon.png",
        ):
            path = resource_path(f"assets/{name}")
            try:
                if path.exists():
                    self.icon_images.append(tk.PhotoImage(file=str(path)))
            except Exception:
                pass
        if self.icon_images:
            try:
                self.iconphoto(True, *self.icon_images)
                self.icon_image = self.icon_images[-1]
            except Exception:
                pass

    def _load_ui_images(self):
        try:
            self.eye_open_image = tk.PhotoImage(file=str(resource_path("assets/eye_open.png")))
            self.eye_closed_image = tk.PhotoImage(file=str(resource_path("assets/eye_closed.png")))
        except Exception:
            self.eye_open_image = None
            self.eye_closed_image = None
        for name in (
            "ui_user", "ui_lock", "ui_globe", "ui_wifi", "ui_logout",
            "ui_refresh", "ui_warning", "ui_flow", "ui_notice", "ui_exit",
        ):
            try:
                path = resource_path(f"assets/{name}.png")
                if path.exists():
                    self.ui_images[name] = tk.PhotoImage(file=str(path))
            except Exception:
                pass

    def _build_ui(self):
        self.grid_columnconfigure(0, weight=1)
        self.grid_rowconfigure(0, weight=1)
        self.canvas = tk.Canvas(self, bg=UI_BG, highlightthickness=0)
        self.canvas.grid(row=0, column=0, sticky="nsew")
        self.content = tk.Frame(self.canvas, bg=UI_BG)
        self.content_window = self.canvas.create_window((0, 0), window=self.content, anchor="nw")
        self.content.bind("<Configure>", self._update_scroll_region)
        self.canvas.bind("<Configure>", self._resize_content)
        self.canvas.bind_all("<MouseWheel>", self._on_mousewheel)
        self.content.grid_columnconfigure(0, weight=1)

        self._header(self.content, 0)
        self.form_canvas, self.form_panel = self._card(self.content, row=1, pady=(6, 18), auto_height=True)
        self.form_panel.grid_columnconfigure(0, weight=1)

        self.account_entry = self._entry_row(self.form_panel, "学号", "请输入学号", 0, "user")
        self.password_entry = self._password_row(self.form_panel, 2)
        self.operator_var = tk.StringVar(value=OPERATORS[0][0])
        self.operator_combo = self._operator_row(self.form_panel, 4)

        self.save_var = tk.BooleanVar(value=True)
        self.save_check = ToggleCheck(self.form_panel, self.save_var, "保存账号密码到本机")
        self.save_check.grid(row=6, column=0, sticky="w", padx=30, pady=(2, 12))

        self.login_button = self._button(self.form_panel, "联网", PRIMARY, "#FFFFFF", self.login, 7,
                                        hover=PRIMARY_HOVER, font_size=15)
        self.logout_button = self._button(self.form_panel, "注销", "#F8FBFF", BLUE, self.logout, 8,
                                         hover=SOFT_BLUE, outline="#7AA7FF", font_size=15)
        self.refresh_button = self._button(self.form_panel, "刷新流量", "#F0FAFF", "#0369A1", self.refresh, 9,
                                          hover="#E0F2FE", outline="#8BD5EA", font_size=15)

        self.status_canvas, self.status_panel = self._card(self.content, row=2, pady=(0, 24), auto_height=True)
        self.status_panel.grid_columnconfigure(2, weight=1)
        self.status_msg_var = tk.StringVar(value="填写学号和密码后，点击联网即可连接校园网")
        self.flow_msg_var = tk.StringVar(value="等待登录结果")
        self._build_status_panel()
        self.bind("<Configure>", self._on_resize)

    def _header(self, parent, row):
        header = tk.Frame(parent, bg=UI_BG)
        header.grid(row=row, column=0, sticky="ew", padx=42, pady=(30, 18))
        header.grid_columnconfigure(0, weight=1)
        tk.Label(header, text="天科校园网", bg=UI_BG, fg=TEXT,
                 font=("Microsoft YaHei UI", 36, "bold")).grid(row=0, column=0, sticky="ew")
        sub = tk.Frame(header, bg=UI_BG)
        sub.grid(row=1, column=0, pady=(8, 0))
        tk.Frame(sub, bg="#BFE6EB", width=84, height=1).grid(row=0, column=0, padx=(0, 10), pady=10)
        tk.Label(sub, text="校园网一键连接", bg=UI_BG, fg=MUTED,
                 font=("Microsoft YaHei UI", 14)).grid(row=0, column=1)
        tk.Frame(sub, bg="#BFE6EB", width=84, height=1).grid(row=0, column=2, padx=(10, 0), pady=10)

    def _card(self, parent, row, pady, auto_height=False):
        outer = RoundedCanvas(parent, radius=18, fill=CARD_BG, outline=BORDER, width=1, height=100)
        outer.grid(row=row, column=0, sticky="ew", padx=48, pady=pady)
        frame = tk.Frame(outer, bg=CARD_BG)
        window = outer.create_window((0, 0), window=frame, anchor="nw")
        def place_inner(event=None):
            outer.coords(window, 1, 1)
            outer.itemconfigure(window, width=max(2, outer.winfo_width() - 2), height=max(2, outer.winfo_height() - 2))
        outer.bind("<Configure>", place_inner, add=True)
        if auto_height:
            def fit_height(event=None):
                outer.configure(height=max(80, frame.winfo_reqheight() + 2))
                self.after_idle(self._update_scroll_region)
            frame.bind("<Configure>", fit_height)
        return outer, frame

    def _section_label(self, parent, text, row, icon):
        holder = tk.Frame(parent, bg=CARD_BG)
        holder.grid(row=row, column=0, sticky="ew", padx=30,
                    pady=(26 if row == 0 else 18, 8))
        VectorIcon(holder, icon, size=24, color=PRIMARY, bg=CARD_BG).grid(row=0, column=0, padx=(0, 10))
        tk.Label(holder, text=text, bg=CARD_BG, fg="#12233D", anchor="w",
                 font=("Microsoft YaHei UI", 12, "bold")).grid(row=0, column=1, sticky="w")

    def _draw_section_icon(self, parent, icon):
        c = tk.Canvas(parent, width=24, height=24, bg=CARD_BG, highlightthickness=0)
        if icon == "user":
            c.create_oval(8, 3, 16, 11, fill=PRIMARY, outline=PRIMARY)
            c.create_polygon(4, 22, 20, 22, 18, 14, 6, 14, fill=PRIMARY, outline=PRIMARY)
        elif icon == "lock":
            c.create_arc(7, 3, 17, 15, start=0, extent=180, style="arc", outline=PRIMARY, width=2)
            c.create_rectangle(5, 11, 19, 22, fill=PRIMARY, outline=PRIMARY)
            c.create_oval(11, 15, 13, 17, fill="#FFFFFF", outline="#FFFFFF")
        else:
            c.create_oval(3, 3, 21, 21, outline=PRIMARY, width=2)
            c.create_line(12, 3, 12, 21, fill=PRIMARY, width=2)
            c.create_arc(6, 3, 18, 21, start=90, extent=180, outline=PRIMARY, width=2)
            c.create_arc(6, 3, 18, 21, start=-90, extent=180, outline=PRIMARY, width=2)
            c.create_line(4, 12, 20, 12, fill=PRIMARY, width=2)
        return c

    def _entry_row(self, parent, label, placeholder, row, icon):
        self._section_label(parent, label, row, icon)
        frame = RoundedCanvas(parent, radius=10, fill="#FBFDFF", outline="#B8C7D8", width=1, height=54)
        frame.grid(row=row + 1, column=0, sticky="ew", padx=30, pady=(0, 6))
        entry = PlaceholderEntry(frame, placeholder=placeholder, relief="flat", bd=0, bg="#FBFDFF",
                                 fg=TEXT, insertbackground=TEXT, font=("Microsoft YaHei UI", 13))
        win = frame.create_window((16, 27), window=entry, anchor="w")
        def resize(event=None):
            frame.itemconfigure(win, width=max(50, frame.winfo_width() - 32))
        frame.bind("<Configure>", resize, add=True)
        entry.bind("<FocusIn>", lambda event: self._focus_input(frame, entry, True), add=True)
        entry.bind("<FocusOut>", lambda event: self._focus_input(frame, entry, False), add=True)
        entry.bind("<Return>", lambda event: self.password_entry.focus_set())
        return entry

    def _password_row(self, parent, row):
        self._section_label(parent, "密码", row, "lock")
        frame = RoundedCanvas(parent, radius=10, fill="#FBFDFF", outline="#B8C7D8", width=1, height=54)
        frame.grid(row=row + 1, column=0, sticky="ew", padx=30, pady=(0, 6))
        entry = PlaceholderEntry(frame, placeholder="请输入密码", show_char="*", relief="flat", bd=0,
                                 bg="#FBFDFF", fg=TEXT, insertbackground=TEXT,
                                 font=("Microsoft YaHei UI", 13))
        win = frame.create_window((16, 27), window=entry, anchor="w")
        self.eye_button = EyeButton(frame, self.toggle_password)
        eye_win = frame.create_window((1, 1), window=self.eye_button, anchor="nw")
        def resize(event=None):
            width = max(50, frame.winfo_width() - 78)
            frame.itemconfigure(win, width=width)
            frame.coords(eye_win, max(2, frame.winfo_width() - 56), 3)
            frame.delete("eye-separator")
            frame.create_line(frame.winfo_width() - 58, 1, frame.winfo_width() - 58,
                              frame.winfo_height() - 2, fill="#D7E2EA", width=1,
                              tags="eye-separator")
            frame.tag_raise("eye-separator")
        frame.bind("<Configure>", resize, add=True)
        entry.bind("<Return>", lambda event: self.focus_operator())
        entry.bind("<FocusIn>", lambda event: self._focus_input(frame, entry, True), add=True)
        entry.bind("<FocusOut>", lambda event: self._focus_input(frame, entry, False), add=True)
        return entry

    def _operator_row(self, parent, row):
        self._section_label(parent, "联网方式", row, "globe")
        style = ttk.Style(self)
        try:
            style.theme_use("clam")
        except Exception:
            pass
        style.configure("Campus.TCombobox", fieldbackground="#FBFDFF", background="#FBFDFF",
                        foreground=TEXT, bordercolor="#B8C7D8", lightcolor="#B8C7D8",
                        darkcolor="#B8C7D8", arrowcolor=PRIMARY, padding=9)
        combo = ttk.Combobox(parent, textvariable=self.operator_var,
                             values=[item[0] for item in OPERATORS], state="readonly",
                             style="Campus.TCombobox", font=("Microsoft YaHei UI", 12))
        combo.grid(row=row + 1, column=0, sticky="ew", padx=30, pady=(0, 8), ipady=9)
        return combo

    def _button(self, parent, text, bg, fg, command, row, hover=None, outline=None, font_size=12, icon=None):
        btn = RoundButton(parent, text=text, command=command, bg=bg, fg=fg, hover=hover,
                          outline=outline, height=50, radius=12, font_size=font_size,
                          icon=icon)
        btn.grid(row=row, column=0, sticky="ew", padx=30, pady=(6, 6))
        return btn

    def _focus_input(self, canvas, entry, active):
        canvas.fill = "#F4FBFF" if active else "#FBFDFF"
        canvas.outline = PRIMARY if active else "#B8C7D8"
        canvas.outline_width = 2 if active else 1
        canvas._redraw()
        entry.configure(bg=canvas.fill)

    def _build_status_panel(self):
        accent = tk.Frame(self.status_panel, bg=PRIMARY, width=6)
        accent.grid(row=0, column=0, rowspan=3, sticky="nsw")
        self.status_icon = self._mini_icon(self.status_panel, "!", WARNING, row=0)
        status_text = tk.Frame(self.status_panel, bg=CARD_BG)
        status_text.grid(row=0, column=2, sticky="ew", padx=(10, 28), pady=(20, 12))
        status_text.grid_columnconfigure(1, weight=1, minsize=120)
        tk.Label(status_text, text="操作状态：", bg=CARD_BG, fg=TEXT,
                 font=("Microsoft YaHei UI", 10, "bold")).grid(row=0, column=0, sticky="nw")
        self.status_label = tk.Label(status_text, textvariable=self.status_msg_var, bg=CARD_BG,
                                     fg=TEXT, font=("Microsoft YaHei UI", 10),
                                     justify="left", wraplength=520)
        self.status_label.grid(row=0, column=1, sticky="w")

        sep = tk.Frame(self.status_panel, bg="#E5EDF4", height=1)
        sep.grid(row=1, column=1, columnspan=2, sticky="ew", padx=36, pady=(0, 0))

        self.flow_icon = self._mini_icon(self.status_panel, "◆", BLUE, row=2)
        flow_text = tk.Frame(self.status_panel, bg=CARD_BG)
        flow_text.grid(row=2, column=2, sticky="ew", padx=(10, 28), pady=(14, 20))
        flow_text.grid_columnconfigure(1, weight=1, minsize=120)
        tk.Label(flow_text, text="流量：", bg=CARD_BG, fg=TEXT,
                 font=("Microsoft YaHei UI", 10, "bold")).grid(row=0, column=0, sticky="nw")
        self.flow_label = tk.Label(flow_text, textvariable=self.flow_msg_var, bg=CARD_BG,
                                   fg="#334155", font=("Microsoft YaHei UI", 10),
                                   justify="left", wraplength=520)
        self.flow_label.grid(row=0, column=1, sticky="w")

    def _mini_icon(self, parent, symbol, color, row):
        kind = "warning" if symbol == "!" else "flow"
        icon = VectorIcon(parent, kind, size=32, color=color, bg=CARD_BG)
        icon.grid(row=row, column=1, sticky="nw", padx=(24, 0), pady=(20 if row == 0 else 14, 0))
        return icon

    def _update_scroll_region(self, event=None):
        self.canvas.configure(scrollregion=self.canvas.bbox("all"))
        if self.content.winfo_reqheight() <= self.canvas.winfo_height() + 40:
            self.canvas.yview_moveto(0)

    def _resize_content(self, event):
        width = max(620, event.width)
        self.canvas.itemconfigure(self.content_window, width=width)

    def _on_mousewheel(self, event):
        try:
            if self.canvas.winfo_height() < self.content.winfo_reqheight():
                self.canvas.yview_scroll(int(-1 * (event.delta / 120)), "units")
        except Exception:
            pass

    def _on_resize(self, event):
        if event.widget is not self:
            return
        wrap = max(180, min(540, event.width - 330))
        if hasattr(self, "status_label"):
            self.status_label.configure(wraplength=wrap)
        if hasattr(self, "flow_label"):
            self.flow_label.configure(wraplength=wrap)

    def focus_operator(self):
        self.operator_combo.focus_set()
        return "break"

    def toggle_password(self):
        self.password_visible = not self.password_visible
        self.password_entry.set_show_char("" if self.password_visible else "*")
        if hasattr(self.eye_button, "set_visible"):
            self.eye_button.set_visible(self.password_visible)

    def _account(self):
        return self.account_entry.value().strip()

    def _password(self):
        return self.password_entry.value()

    def login(self):
        account, password = self._account(), self._password()
        if not account or not password:
            self._set_result("请先填写学号和密码", "未知")
            self.show_notice("请先填写学号和密码")
            return
        self._save_config()
        self._run("联网中，请稍候...", lambda: self.client.login(account, password, self.operator_index()))

    def logout(self):
        self._run("注销中，请稍候...", self.client.logout)

    def refresh(self):
        self._run("刷新流量中，请稍候...", lambda: self.client.refresh(self._account(), self._password()))

    def detect(self):
        account, password = self._account(), self._password()
        if not account or not password:
            return
        self._run("正在检测登录状态...", lambda: self.client.detect(account, password), quiet=True)

    def operator_index(self):
        label = self.operator_var.get()
        for index, item in enumerate(OPERATORS):
            if item[0] == label:
                return index
        return 0

    def _run(self, busy_text, func, quiet=False):
        self._set_busy(True, busy_text)
        def worker():
            try:
                status, flow, logged_in = func()
            except Exception as exc:
                status, flow, logged_in = "操作失败：" + safe_error_message(exc), self.flow_msg_var.get(), False
            self.after(0, lambda: self._finish(status, flow, logged_in, quiet))
        threading.Thread(target=worker, daemon=True).start()

    def _set_busy(self, busy, text):
        for button in (self.login_button, self.logout_button, self.refresh_button):
            button.set_enabled(not busy)
        self.status_msg_var.set(text)
        self._settle_layout()

    def _finish(self, status, flow, logged_in, quiet):
        self._set_busy(False, status)
        self.flow_msg_var.set(self._clean_flow(flow))
        self._settle_layout()
        if not quiet:
            self.show_notice(status)

    def _set_result(self, status, flow):
        self.status_msg_var.set(status)
        self.flow_msg_var.set(self._clean_flow(flow))
        self._settle_layout()

    def _clean_flow(self, flow):
        flow = str(flow or "等待登录结果").strip()
        for prefix in ("流量：", "流量:"):
            if flow.startswith(prefix):
                flow = flow[len(prefix):].strip()
        return flow or "等待登录结果"

    def _settle_layout(self):
        self.after_idle(self._grow_to_content)
        self.after_idle(self._update_scroll_region)

    def _grow_to_content(self):
        self.update_idletasks()
        req_h = self.content.winfo_reqheight() + 32
        max_h = self.winfo_screenheight() - 80
        cur_w, cur_h = self.winfo_width(), self.winfo_height()
        new_h = min(max_h, max(cur_h, req_h))
        if new_h != cur_h:
            self.geometry(f"{cur_w}x{int(new_h)}")
        self._update_scroll_region()

    def _load_config(self):
        try:
            data = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
        except Exception:
            return
        self.account_entry.set_value(data.get("account", ""))
        self.password_entry.set_value(data.get("password", ""))
        operator = data.get("operator", OPERATORS[0][0])
        if operator in [item[0] for item in OPERATORS]:
            self.operator_var.set(operator)
        self.save_var.set(bool(data.get("save", True)))
        self.save_check.draw()

    def _save_config(self):
        if not self.save_var.get():
            try:
                CONFIG_PATH.unlink(missing_ok=True)
            except Exception:
                pass
            return
        APP_DIR.mkdir(parents=True, exist_ok=True)
        CONFIG_PATH.write_text(json.dumps({
            "account": self._account(),
            "password": self._password(),
            "operator": self.operator_var.get(),
            "save": self.save_var.get(),
        }, ensure_ascii=False, indent=2), encoding="utf-8")

    def show_notice(self, message):
        self._modal("提示", str(message), confirm_text="确定", cancel_text=None, on_confirm=None, kind="notice")

    def confirm_exit(self):
        self._modal("退出应用？", "确定要退出天科校园网吗", confirm_text="退出",
                    cancel_text="取消", on_confirm=self.destroy, kind="exit")

    def _modal(self, title, message, confirm_text="\u786e\u5b9a", cancel_text="\u53d6\u6d88", on_confirm=None, kind="notice"):
        message = str(message or "")

        overlay = tk.Canvas(self, bg=self.cget("bg"), highlightthickness=0, bd=0)
        overlay.place(x=0, y=0, relwidth=1, relheight=1)
        overlay.tk.call("raise", overlay._w)

        shade = overlay.create_rectangle(0, 0, 1, 1, fill="#000000", outline="", stipple="gray50")
        w = min(520, max(430, self.winfo_width() - 160))
        box = RoundedCanvas(overlay, radius=22, fill=CARD_BG, outline="#DDE7EF", width=1, height=320)
        box_window = overlay.create_window(self.winfo_width() / 2, self.winfo_height() / 2,
                                           window=box, width=w, height=320)

        inner = tk.Frame(box, bg=CARD_BG)
        win = box.create_window((1, 1), window=inner, anchor="nw")
        box.bind(
            "<Configure>",
            lambda e: box.itemconfigure(win, width=max(2, e.width - 2), height=max(2, e.height - 2)),
            add=True,
        )
        inner.grid_columnconfigure(0, weight=1)

        VectorIcon(inner, "exit" if kind == "exit" else "notice",
                   size=72, color=PRIMARY, bg=CARD_BG).grid(row=0, column=0, pady=(28, 10))
        tk.Label(
            inner,
            text=title,
            bg=CARD_BG,
            fg=TEXT,
            font=("Microsoft YaHei UI", 18, "bold"),
        ).grid(row=1, column=0, sticky="ew", padx=38, pady=(0, 12))

        msg_label = tk.Label(
            inner,
            text=message,
            bg=CARD_BG,
            fg="#1E293B",
            font=("Microsoft YaHei UI", 12),
            justify="center",
            wraplength=max(280, w - 90),
        )
        msg_label.grid(row=2, column=0, sticky="ew", padx=42, pady=(0, 26))

        btn_row = tk.Frame(inner, bg=CARD_BG)
        btn_row.grid(row=3, column=0, sticky="ew", padx=38, pady=(0, 30))
        if cancel_text:
            btn_row.grid_columnconfigure(0, weight=1)
            btn_row.grid_columnconfigure(1, weight=1)
        else:
            btn_row.grid_columnconfigure(0, weight=1)

        def close(confirm=False):
            try:
                overlay.grab_release()
            except Exception:
                pass
            overlay.destroy()
            if confirm and on_confirm:
                on_confirm()

        if cancel_text:
            cancel = RoundButton(
                btn_row,
                cancel_text,
                lambda: close(False),
                "#F8FBFF",
                BLUE,
                hover=SOFT_BLUE,
                outline="#8BB7FF",
                height=46,
                radius=10,
                font_size=12,
            )
            cancel.grid(row=0, column=0, sticky="ew", padx=(0, 10))
            ok_col = 1
        else:
            ok_col = 0

        ok = RoundButton(
            btn_row,
            confirm_text,
            lambda: close(True),
            PRIMARY,
            "#FFFFFF",
            hover=PRIMARY_HOVER,
            outline=PRIMARY,
            height=46,
            radius=10,
            font_size=12,
        )
        ok.grid(row=0, column=ok_col, sticky="ew", padx=(10 if cancel_text else 0, 0))

        def layout(event=None):
            overlay.coords(shade, 0, 0, overlay.winfo_width(), overlay.winfo_height())
            final_h = min(max(270, inner.winfo_reqheight() + 2), max(270, overlay.winfo_height() - 90))
            overlay.itemconfigure(box_window, width=w, height=final_h)
            overlay.coords(box_window, overlay.winfo_width() / 2, overlay.winfo_height() / 2)

        overlay.bind("<Configure>", layout, add=True)
        overlay.bind("<Escape>", lambda event: close(False))
        overlay.bind("<Return>", lambda event: close(True))
        self.update_idletasks()
        layout()
        overlay.focus_set()
        overlay.grab_set()
        ok.focus_set()

def get_local_ipv4():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("8.8.8.8", 80))
        return sock.getsockname()[0]
    finally:
        sock.close()


def get_local_ipv6():
    try:
        infos = socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET6)
        for info in infos:
            ip = info[4][0]
            if ip and not ip.startswith("fe80") and ip != "::1":
                return ip.split("%", 1)[0]
    except Exception:
        pass
    return ""


def ensure_campus_network():
    ip = get_local_ipv4()
    if not ip:
        raise RuntimeError("\u672a\u83b7\u53d6\u5230\u672c\u673a IP")
    try:
        socket.create_connection(("10.10.102.50", 80), timeout=2).close()
    except Exception as exc:
        raise TimeoutError("\u6821\u56ed\u7f51\u7f51\u5173\u8fde\u63a5\u8d85\u65f6\uff0c\u8bf7\u786e\u8ba4\u5df2\u8fde\u63a5\u5230\u6b63\u786e\u7684\u6821\u56ed\u7f51") from exc


def ip_to_unsigned_int(ip):
    parts = [int(part) for part in ip.split(".")]
    return ((parts[0] << 24) + (parts[1] << 16) + (parts[2] << 8) + parts[3]) & 0xFFFFFFFF


def absolute_uss_url(action):
    if action.startswith(("http://", "https://")):
        return action
    if not action.startswith("/"):
        action = "/" + action
    return USS_HOST + action


def first_regex(source, pattern):
    match = re.search(pattern, source or "", re.IGNORECASE)
    return match.group(1) if match else ""


def parse_flow(source):
    remaining = first_positive(extract_kb_as_mb(source, "olflow"), extract_kb_as_mb(source, "remainflow"), extract_kb_as_mb(source, "leftflow"), extract_json_mb(source, "leftFlow"))
    used = first_positive(extract_kb_as_mb(source, "flow"), extract_kb_as_mb(source, "usedflow"), extract_kb_as_mb(source, "useflow"), extract_json_mb(source, "useFlow"), extract_json_mb(source, "internetDownFlow"))
    total = first_positive(extract_kb_as_mb(source, "allflow"), extract_kb_as_mb(source, "totalflow"), extract_kb_as_mb(source, "sumflow"), extract_kb_as_mb(source, "monthflow"), extract_json_mb(source, "flowStart"), extract_json_mb(source, "totalFlow"))
    if total < 0 and remaining >= 0 and used >= 0:
        total = remaining + used
    if used < 0 and total > 0 and remaining >= 0:
        used = max(0, total - remaining)
    online = first_positive(extract_json_mb(source, "ipCount"), count_online_items(source))
    return total, remaining, used, online


def format_flow(flow):
    total, remaining, used, online = flow
    return "\n".join([
        f"\u603b\u6d41\u91cf\uff1a{total} MB" if total >= 0 else "\u603b\u6d41\u91cf\uff1a\u672a\u77e5",
        f"\u5269\u4f59\u6d41\u91cf\uff1a{remaining} MB" if remaining >= 0 else "\u5269\u4f59\u6d41\u91cf\uff1a\u672a\u77e5",
        f"\u5df2\u7528\u6d41\u91cf\uff1a{used} MB" if used >= 0 else "\u5df2\u7528\u6d41\u91cf\uff1a\u672a\u77e5",
        f"\u5f53\u524d\u5728\u7ebf\uff1a{online} \u53f0" if online >= 0 else "\u5f53\u524d\u5728\u7ebf\uff1a\u672a\u77e5",
    ])


def extract_kb_as_mb(source, key):
    match = re.search(rf"{re.escape(key)}=(\d+);", source or "", re.IGNORECASE)
    return int(int(match.group(1)) / 1024) if match else -1


def extract_json_mb(source, key):
    match = re.search(rf'"{re.escape(key)}"\s*:\s*"?([0-9]+(?:\.[0-9]+)?)"?', source or "", re.IGNORECASE)
    return int(float(match.group(1))) if match else -1


def first_positive(*values):
    for value in values:
        if value >= 0:
            return value
    return -1


def count_online_items(source):
    count = len(re.findall(r'"loginTime"', source or ""))
    return count if count > 0 else -1


def contains_any(source, *needles):
    return any(needle in (source or "") for needle in needles)


def is_already_online(source):
    return contains_any(source, "\u5df2\u7ecf\u5728\u7ebf", "\u5df2\u5728\u7ebf", "already online", '"ret_code":2', '"ret_code":"2"')


def is_current_account(source, account):
    if not source or not account:
        return False
    account = account.strip()
    return contains_any(source, f'"userName":"{account}"', f'"userIdNumber":"{account}"', f'"user_account":"{account}"', f'"userAccount":"{account}"', f'"user_name":"{account}"', f"{account}@tust")


def classify_login_error(source):
    if contains_any(source, "\u7edf\u4e00\u8eab\u4efd\u8ba4\u8bc1\u5bc6\u7801\u9519\u8bef", "\u5bc6\u7801\u9519\u8bef"):
        return "\u767b\u5f55\u5931\u8d25\uff1a\u5bc6\u7801\u9519\u8bef"
    if contains_any(source, "domain error", "@unicom", "@cmcc", "@telecom"):
        return "\u767b\u5f55\u5931\u8d25\uff1a\u4e0a\u7f51\u65b9\u5f0f\u9009\u62e9\u9519\u8bef"
    return "\u767b\u5f55\u5931\u8d25\uff1a" + trim_for_display(source)


def safe_error_message(exc):
    message = str(exc)
    lowered = message.lower()
    if isinstance(exc, TimeoutError) or "timed out" in lowered or "timeout" in lowered:
        return "\u6821\u56ed\u7f51\u7f51\u5173\u8fde\u63a5\u8d85\u65f6\uff0c\u8bf7\u786e\u8ba4\u5df2\u8fde\u63a5\u6821\u56ed\u7f51\u6216\u7a0d\u540e\u91cd\u8bd5"
    if "urlopen error" in lowered:
        return "\u7f51\u7edc\u8bf7\u6c42\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u662f\u5426\u5df2\u8fde\u63a5\u5230\u6821\u56ed\u7f51"
    if not message:
        return "\u65e0\u8be6\u7ec6\u4fe1\u606f"
    return trim_for_display(message)


def trim_for_display(value):
    if not value:
        return "\u6821\u56ed\u7f51\u5173\u6ca1\u6709\u8fd4\u56de\u660e\u786e\u7ed3\u679c"
    compact = re.sub(r"\s+", " ", str(value)).strip()
    return compact[:120] + "..." if len(compact) > 120 else compact


if __name__ == "__main__":
    enable_dpi_awareness()
    App().mainloop()
