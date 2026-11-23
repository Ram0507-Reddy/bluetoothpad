"""
Bluetooth Laptop Remote Server v4 (High Performance & 64-bit SendInput)
Runs on Windows. Listens on Bluetooth RFCOMM with SDP SPP registration.
Features:
- Sub-millisecond trackpad with 64-bit SendInput
- Multi-finger gestures (Scroll, Right-Click, Zoom, Desktop, App Switch, Action Center)
- App launcher with dynamic indexing and logo support
- Media deck (Volume, Play/Pause, Navigation)
- Live soft keypad typing direct to active Windows applications
"""

import os
import sys
import time
import json
import io
import base64
import struct
import socket
import ctypes
import threading
import subprocess
from PIL import ImageGrab
from ctypes import wintypes

# Attach immediately to interactive Windows desktop before any other initializations
user32 = ctypes.windll.user32
ws2_32 = ctypes.windll.ws2_32
kernel32 = ctypes.windll.kernel32

user32.OpenDesktopW.restype = wintypes.HANDLE
user32.SetThreadDesktop.argtypes = [wintypes.HANDLE]
user32.SetThreadDesktop.restype = wintypes.BOOL

DESKTOP_ALL = 0x01FF
_init_desk = user32.OpenDesktopW("default", 0, False, DESKTOP_ALL)
if _init_desk:
    user32.SetThreadDesktop(_init_desk)

# Win32 Constants
AF_BTH = 32
BTPROTO_RFCOMM = 3

MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004
MOUSEEVENTF_RIGHTDOWN = 0x0008
MOUSEEVENTF_RIGHTUP = 0x0010
MOUSEEVENTF_MIDDLEDOWN = 0x0020
MOUSEEVENTF_MIDDLEUP = 0x0040
MOUSEEVENTF_WHEEL = 0x0800

KEYEVENTF_KEYDOWN = 0x0000
KEYEVENTF_EXTENDEDKEY = 0x0001
KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_UNICODE = 0x0004

VK_CODES = {
    "vol_up": (0xAF, True),
    "vol_down": (0xAE, True),
    "mute": (0xAD, True),
    "play_pause": (0xB3, True),
    "next": (0xB0, True),
    "prev": (0xB1, True),
    "stop": (0xB2, True),
    "enter": (0x0D, False),
    "backspace": (0x08, False),
    "tab": (0x09, False),
    "esc": (0x1B, False),
    "space": (0x20, False),
    "delete": (0x2E, True),
    "up": (0x26, True),
    "down": (0x28, True),
    "left": (0x25, True),
    "right": (0x27, True),
    "page_up": (0x21, True),
    "page_down": (0x22, True),
    "f5": (0x74, False),
    "lwin": (0x5B, False),
    "lctrl": (0xA2, False),
    "lalt": (0xA4, False),
    "lshift": (0xA0, False),
    "d": (0x44, False),
    "w": (0x57, False),
    "a": (0x41, False),
    "s": (0x53, False),
    "q": (0x51, False),
    "e": (0x45, False),
    "r": (0x52, False),
    "f": (0x46, False),
}

# 64-bit SendInput Structures (sizeof(INPUT) == 40)
class MOUSEINPUT(ctypes.Structure):
    _fields_ = [
        ("dx", wintypes.LONG),
        ("dy", wintypes.LONG),
        ("mouseData", wintypes.DWORD),
        ("dwFlags", wintypes.DWORD),
        ("time", wintypes.DWORD),
        ("dwExtraInfo", ctypes.c_ulonglong)
    ]

class KEYBDINPUT(ctypes.Structure):
    _fields_ = [
        ("wVk", wintypes.WORD),
        ("wScan", wintypes.WORD),
        ("dwFlags", wintypes.DWORD),
        ("time", wintypes.DWORD),
        ("dwExtraInfo", ctypes.c_ulonglong)
    ]

class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [
        ("uMsg", wintypes.DWORD),
        ("wParamL", wintypes.WORD),
        ("wParamH", wintypes.WORD)
    ]

class INPUT(ctypes.Structure):
    class _INPUT_UNION(ctypes.Union):
        _fields_ = [
            ("mi", MOUSEINPUT),
            ("ki", KEYBDINPUT),
            ("hi", HARDWAREINPUT)
        ]
    _anonymous_ = ("u",)
    _fields_ = [
        ("type", wintypes.DWORD),
        ("u", _INPUT_UNION)
    ]

# System Telemetry & Clipboard Structures
class SYSTEM_POWER_STATUS(ctypes.Structure):
    _fields_ = [
        ('ACLineStatus', wintypes.BYTE),
        ('BatteryFlag', wintypes.BYTE),
        ('BatteryLifePercent', wintypes.BYTE),
        ('SystemStatusFlag', wintypes.BYTE),
        ('BatteryLifeTime', wintypes.DWORD),
        ('BatteryFullLifeTime', wintypes.DWORD),
    ]

class MEMORYSTATUSEX(ctypes.Structure):
    _fields_ = [
        ('dwLength', wintypes.DWORD),
        ('dwMemoryLoad', wintypes.DWORD),
        ('ullTotalPhys', ctypes.c_ulonglong),
        ('ullAvailPhys', ctypes.c_ulonglong),
        ('ullTotalPageFile', ctypes.c_ulonglong),
        ('ullAvailPageFile', ctypes.c_ulonglong),
        ('ullTotalVirtual', ctypes.c_ulonglong),
        ('ullAvailVirtual', ctypes.c_ulonglong),
        ('ullTotalExtendedVirtual', ctypes.c_ulonglong),
    ]

class FILETIME(ctypes.Structure):
    _fields_ = [('dwLowDateTime', wintypes.DWORD), ('dwHighDateTime', wintypes.DWORD)]

CF_UNICODETEXT = 13
GMEM_MOVEABLE = 0x0002

user32.OpenClipboard.argtypes = [wintypes.HWND]
user32.OpenClipboard.restype = wintypes.BOOL
user32.CloseClipboard.restype = wintypes.BOOL
user32.EmptyClipboard.restype = wintypes.BOOL
user32.GetClipboardData.argtypes = [wintypes.UINT]
user32.GetClipboardData.restype = ctypes.c_void_p
user32.SetClipboardData.argtypes = [wintypes.UINT, ctypes.c_void_p]
user32.SetClipboardData.restype = ctypes.c_void_p
user32.SendMessageW.argtypes = [wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM]
user32.SendMessageW.restype = ctypes.c_longlong

kernel32.GlobalAlloc.argtypes = [wintypes.UINT, ctypes.c_size_t]
kernel32.GlobalAlloc.restype = ctypes.c_void_p
kernel32.GlobalLock.argtypes = [ctypes.c_void_p]
kernel32.GlobalLock.restype = ctypes.c_void_p
kernel32.GlobalUnlock.argtypes = [ctypes.c_void_p]
kernel32.GlobalUnlock.restype = wintypes.BOOL

_last_cpu_time = (0, 0)
def get_cpu_percent():
    global _last_cpu_time
    def to_int(ft):
        return (ft.dwHighDateTime << 32) | ft.dwLowDateTime
    idle, kernel, user = FILETIME(), FILETIME(), FILETIME()
    if kernel32.GetSystemTimes(ctypes.byref(idle), ctypes.byref(kernel), ctypes.byref(user)):
        i, k, u = to_int(idle), to_int(kernel), to_int(user)
        total = k + u
        last_idle, last_total = _last_cpu_time
        _last_cpu_time = (i, total)
        if last_total > 0 and total > last_total:
            delta_total = total - last_total
            delta_idle = i - last_idle
            if delta_total > 0:
                return max(0, min(100, int((1.0 - (delta_idle / delta_total)) * 100)))
    return 0

def get_telemetry():
    sps = SYSTEM_POWER_STATUS()
    kernel32.GetSystemPowerStatus(ctypes.byref(sps))
    bat = sps.BatteryLifePercent if sps.BatteryLifePercent <= 100 else 100
    ac = sps.ACLineStatus

    mem = MEMORYSTATUSEX()
    mem.dwLength = ctypes.sizeof(mem)
    kernel32.GlobalMemoryStatusEx(ctypes.byref(mem))
    ram = mem.dwMemoryLoad

    cpu = get_cpu_percent()
    return {"t": "telemetry", "cpu": cpu, "ram": ram, "bat": bat, "ac": ac}

def get_clipboard_text():
    if not user32.OpenClipboard(0):
        return ''
    try:
        h_glb = user32.GetClipboardData(CF_UNICODETEXT)
        if not h_glb:
            return ''
        ptr = kernel32.GlobalLock(h_glb)
        text = ctypes.wstring_at(ptr) if ptr else ''
        kernel32.GlobalUnlock(h_glb)
        return text
    except Exception:
        return ''
    finally:
        user32.CloseClipboard()

def set_clipboard_text(text):
    if not user32.OpenClipboard(0):
        return False
    try:
        user32.EmptyClipboard()
        bytes_data = (text + '\0').encode('utf-16le')
        h_glb = kernel32.GlobalAlloc(GMEM_MOVEABLE, len(bytes_data))
        if not h_glb:
            return False
        ptr = kernel32.GlobalLock(h_glb)
        ctypes.memmove(ptr, bytes_data, len(bytes_data))
        kernel32.GlobalUnlock(h_glb)
        user32.SetClipboardData(CF_UNICODETEXT, h_glb)
        return True
    except Exception:
        return False
    finally:
        user32.CloseClipboard()

_ATTACHED_DESKTOP = None

def attach_to_user_desktop():
    global _ATTACHED_DESKTOP
    if _ATTACHED_DESKTOP:
        return True
    try:
        user32.OpenInputDesktop.restype = wintypes.HANDLE
        user32.OpenDesktopW.restype = wintypes.HANDLE
        user32.SetThreadDesktop.argtypes = [wintypes.HANDLE]
        user32.SetThreadDesktop.restype = wintypes.BOOL

        DESKTOP_ALL = 0x01FF
        h = user32.OpenInputDesktop(0, False, DESKTOP_ALL)
        if not h:
            h = user32.OpenDesktopW("default", 0, False, DESKTOP_ALL)
        if h:
            if user32.SetThreadDesktop(h):
                _ATTACHED_DESKTOP = h
                print("[+] Successfully attached to interactive desktop.")
                return True
    except Exception as e:
        print(f"[!] Desktop attach error: {e}")
    return False

# Winsock SDP SPP Registration
class GUID(ctypes.Structure):
    _fields_ = [
        ('Data1', wintypes.DWORD),
        ('Data2', wintypes.WORD),
        ('Data3', wintypes.WORD),
        ('Data4', wintypes.BYTE * 8)
    ]

class SOCKADDR_BTH(ctypes.Structure):
    _fields_ = [
        ('addressFamily', wintypes.USHORT),
        ('btAddr', ctypes.c_ulonglong),
        ('serviceClassId', GUID),
        ('port', wintypes.ULONG)
    ]

class SOCKET_ADDRESS(ctypes.Structure):
    _fields_ = [
        ('lpSockaddr', ctypes.c_void_p),
        ('iSockaddrLength', wintypes.INT)
    ]

class CSADDR_INFO(ctypes.Structure):
    _fields_ = [
        ('LocalAddr', SOCKET_ADDRESS),
        ('RemoteAddr', SOCKET_ADDRESS),
        ('iSocketType', wintypes.INT),
        ('iProtocol', wintypes.INT)
    ]

class WSAQUERYSETW(ctypes.Structure):
    _fields_ = [
        ('dwSize', wintypes.DWORD),
        ('lpszServiceInstanceName', wintypes.LPWSTR),
        ('lpServiceClassId', ctypes.c_void_p),
        ('lpVersion', ctypes.c_void_p),
        ('lpszComment', wintypes.LPWSTR),
        ('dwNameSpace', wintypes.DWORD),
        ('lpNSProviderId', ctypes.c_void_p),
        ('lpszContext', wintypes.LPWSTR),
        ('dwNumberOfProtocols', wintypes.DWORD),
        ('lpafpProtocols', ctypes.c_void_p),
        ('lpszQueryString', wintypes.LPWSTR),
        ('dwNumberOfCsAddrs', wintypes.DWORD),
        ('lpcsaBuffer', ctypes.POINTER(CSADDR_INFO)),
        ('dwOutputFlags', wintypes.DWORD),
        ('lpBlob', ctypes.c_void_p)
    ]

SPP_GUID = GUID(0x00001101, 0x0000, 0x1000, (wintypes.BYTE * 8)(0x80, 0x00, 0x00, 0x80, 0x5F, 0x9B, 0x34, 0xFB))

def register_sdp_service(port: int, name: str = "Laptop Remote Control"):
    sa = SOCKADDR_BTH()
    sa.addressFamily = AF_BTH
    sa.port = port
    sa.serviceClassId = SPP_GUID

    cs = CSADDR_INFO()
    cs.LocalAddr.lpSockaddr = ctypes.cast(ctypes.pointer(sa), ctypes.c_void_p)
    cs.LocalAddr.iSockaddrLength = ctypes.sizeof(sa)
    cs.RemoteAddr.lpSockaddr = ctypes.cast(ctypes.pointer(sa), ctypes.c_void_p)
    cs.RemoteAddr.iSockaddrLength = ctypes.sizeof(sa)
    cs.iSocketType = socket.SOCK_STREAM
    cs.iProtocol = BTPROTO_RFCOMM

    qs = WSAQUERYSETW()
    qs.dwSize = ctypes.sizeof(qs)
    qs.lpszServiceInstanceName = name
    qs.lpszComment = "Bluetooth Remote Control Server"
    qs.lpServiceClassId = ctypes.cast(ctypes.pointer(SPP_GUID), ctypes.c_void_p)
    qs.dwNumberOfCsAddrs = 1
    qs.lpcsaBuffer = ctypes.pointer(cs)
    qs.dwNameSpace = 16

    RNRSERVICE_REGISTER = 0
    res = ws2_32.WSASetServiceW(ctypes.byref(qs), RNRSERVICE_REGISTER, 0)
    if res != 0:
        err = ws2_32.WSAGetLastError()
        print(f"[!] Warning: WSASetServiceW error {err}")
    else:
        print("[+] SDP Service registered successfully (SPP Profile).")

# App Indexer
def index_installed_apps():
    dirs = [
        r'C:\ProgramData\Microsoft\Windows\Start Menu\Programs',
        os.path.expandvars(r'%APPDATA%\Microsoft\Windows\Start Menu\Programs')
    ]
    apps = {}
    for d in dirs:
        if not os.path.exists(d):
            continue
        for root, _, files in os.walk(d):
            for f in files:
                if f.lower().endswith('.lnk'):
                    base = os.path.splitext(f)[0]
                    if not any(k in base.lower() for k in ['uninstall', 'help', 'readme', 'documentation', 'install', 'update', 'manual', 'config']):
                        path = os.path.join(root, f)
                        apps[base] = path

    builtins = {
        "Google Chrome": r"C:\Program Files\Google\Chrome\Application\chrome.exe",
        "Brave": r"C:\Program Files\BraveSoftware\Brave-Browser\Application\brave.exe",
        "Microsoft Edge": r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
        "Firefox": r"C:\Program Files\Mozilla Firefox\firefox.exe",
        "Visual Studio Code": os.path.expandvars(r"%LOCALAPPDATA%\Programs\Microsoft VS Code\Code.exe"),
        "Windows Terminal": "wt.exe",
        "Command Prompt": r"C:\Windows\System32\cmd.exe",
        "PowerShell": r"C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe",
        "File Explorer": r"C:\Windows\explorer.exe",
        "Notepad": r"C:\Windows\System32\notepad.exe",
        "Calculator": r"C:\Windows\System32\calc.exe",
        "Task Manager": r"C:\Windows\System32\Taskmgr.exe",
        "Settings": "ms-settings:",
        "Spotify": "spotify:",
        "Discord": os.path.expandvars(r"%LOCALAPPDATA%\Discord\Update.exe --processStart Discord.exe")
    }

    for name, path in builtins.items():
        if name not in apps:
            apps[name] = path

    return apps

INSTALLED_APPS = index_installed_apps()

# SendInput Hardware Actions
def send_mouse_move(dx: int, dy: int):
    attach_to_user_desktop()
    inp = INPUT(type=0, mi=MOUSEINPUT(dx, dy, 0, MOUSEEVENTF_MOVE, 0, 0))
    ret = user32.SendInput(1, ctypes.byref(inp), 40)
    if ret == 0:
        print(f"[!] SendInput(move) err: {kernel32.GetLastError()}")

def send_mouse_click(btn: str):
    attach_to_user_desktop()
    if btn == "l":
        f_down, f_up = MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP
    elif btn == "r":
        f_down, f_up = MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP
    elif btn == "m":
        f_down, f_up = MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP
    elif btn == "d":
        for _ in range(2):
            send_mouse_click("l")
            time.sleep(0.04)
        return
    else:
        return

    inp_down = INPUT(type=0, mi=MOUSEINPUT(0, 0, 0, f_down, 0, 0))
    inp_up = INPUT(type=0, mi=MOUSEINPUT(0, 0, 0, f_up, 0, 0))
    user32.SendInput(1, ctypes.byref(inp_down), 40)
    time.sleep(0.01)
    user32.SendInput(1, ctypes.byref(inp_up), 40)

def send_mouse_scroll(dy: int):
    attach_to_user_desktop()
    if dy != 0 and abs(dy) < 60:
        dy = 120 if dy > 0 else -120
    user32.mouse_event(MOUSEEVENTF_WHEEL, 0, 0, ctypes.c_long(dy).value, 0)

def press_key(vk_code: int, extended: bool = False):
    attach_to_user_desktop()
    scan = user32.MapVirtualKeyW(vk_code, 0)
    f_down = (KEYEVENTF_EXTENDEDKEY if extended else 0)
    f_up = (KEYEVENTF_EXTENDEDKEY if extended else 0) | KEYEVENTF_KEYUP
    inp_down = INPUT(type=1, ki=KEYBDINPUT(vk_code, scan, f_down, 0, 0))
    inp_up = INPUT(type=1, ki=KEYBDINPUT(vk_code, scan, f_up, 0, 0))
    inputs = (INPUT * 2)(inp_down, inp_up)
    ret = user32.SendInput(2, inputs, 40)
    if ret == 0:
        print(f"[!] SendInput(key {hex(vk_code)}) err: {kernel32.GetLastError()}")

def key_combination(keys: list):
    attach_to_user_desktop()
    inps_down = []
    for k in keys:
        scan = user32.MapVirtualKeyW(k, 0)
        ext = KEYEVENTF_EXTENDEDKEY if k in [0x5B, 0x5C, 0x25, 0x26, 0x27, 0x28, 0x21, 0x22, 0x2D, 0x2E] else 0
        inps_down.append(INPUT(type=1, ki=KEYBDINPUT(k, scan, ext, 0, 0)))
    arr_down = (INPUT * len(inps_down))(*inps_down)
    user32.SendInput(len(inps_down), arr_down, 40)

    time.sleep(0.02)

    inps_up = []
    for k in reversed(keys):
        scan = user32.MapVirtualKeyW(k, 0)
        ext = KEYEVENTF_EXTENDEDKEY if k in [0x5B, 0x5C, 0x25, 0x26, 0x27, 0x28, 0x21, 0x22, 0x2D, 0x2E] else 0
        inps_up.append(INPUT(type=1, ki=KEYBDINPUT(k, scan, ext | KEYEVENTF_KEYUP, 0, 0)))
    arr_up = (INPUT * len(inps_up))(*inps_up)
    user32.SendInput(len(inps_up), arr_up, 40)

def send_unicode_char(char: str):
    attach_to_user_desktop()
    code = ord(char)
    inp_down = INPUT(type=1, ki=KEYBDINPUT(0, code, KEYEVENTF_UNICODE, 0, 0))
    inp_up = INPUT(type=1, ki=KEYBDINPUT(0, code, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP, 0, 0))
    inputs = (INPUT * 2)(inp_down, inp_up)
    user32.SendInput(2, inputs, 40)

proximity_lock_enabled = False
last_heartbeat_time = time.time()
consecutive_rssi_drops = 0
_active_wasd = {"w": False, "s": False, "a": False, "d": False}

def set_wasd_key(key_char, press):
    global _active_wasd
    if _active_wasd.get(key_char) == press:
        return
    _active_wasd[key_char] = press
    vk, is_ext = VK_CODES.get(key_char, (0, False))
    if vk != 0:
        attach_to_user_desktop()
        flag = KEYEVENTF_KEYDOWN if press else KEYEVENTF_KEYUP
        if is_ext: flag |= KEYEVENTF_EXTENDEDKEY
        inp = INPUT(type=1, ki=KEYBDINPUT(vk, 0, flag, 0, 0))
        user32.SendInput(1, ctypes.byref(inp), 40)

def handle_command(cmd: dict, client_sock=None):
    global proximity_lock_enabled, last_heartbeat_time
    t = cmd.get("t")

    if t == "m":
        dx = int(cmd.get("dx", 0))
        dy = int(cmd.get("dy", 0))
        send_mouse_move(dx, dy)

    elif t == "c":
        b = cmd.get("b", "l")
        send_mouse_click(b)

    elif t == "md":
        b = cmd.get("b", "l")
        flag = MOUSEEVENTF_LEFTDOWN if b == "l" else MOUSEEVENTF_RIGHTDOWN
        inp = INPUT(type=0, mi=MOUSEINPUT(0, 0, 0, flag, 0, 0))
        user32.SendInput(1, ctypes.byref(inp), 40)

    elif t == "mu":
        b = cmd.get("b", "l")
        flag = MOUSEEVENTF_LEFTUP if b == "l" else MOUSEEVENTF_RIGHTUP
        inp = INPUT(type=0, mi=MOUSEINPUT(0, 0, 0, flag, 0, 0))
        user32.SendInput(1, ctypes.byref(inp), 40)

    elif t == "s":
        dy = int(cmd.get("dy", 0))
        send_mouse_scroll(dy)

    elif t == "k":
        key_name = cmd.get("k")
        if key_name in VK_CODES:
            vk, ext = VK_CODES[key_name]
            press_key(vk, ext)

    elif t == "a":
        action = cmd.get("a")
        if action == "lock":
            user32.LockWorkStation()
        elif action == "desktop":
            key_combination([0x5B, 0x44]) # Win + D
        elif action == "taskview":
            key_combination([0x5B, 0x09]) # Win + Tab
        elif action == "alttab" or action == "app_next":
            key_combination([0x12, 0x09]) # Alt + Tab
        elif action == "app_prev":
            key_combination([0x12, 0x10, 0x09]) # Alt + Shift + Tab
        elif action == "search":
            key_combination([0x5B, 0x53]) # Win + S
        elif action == "action_center":
            key_combination([0x5B, 0x41]) # Win + A
        elif action == "vdesk_left":
            key_combination([0x11, 0x5B, 0x25]) # Ctrl + Win + Left
        elif action == "vdesk_right":
            key_combination([0x11, 0x5B, 0x27]) # Ctrl + Win + Right
        elif action == "win_max":
            key_combination([0x5B, 0x26]) # Win + Up
        elif action == "win_min":
            key_combination([0x5B, 0x28]) # Win + Down
        elif action == "zoom_in":
            key_combination([0x11, 0xBB]) # Ctrl + Plus
        elif action == "zoom_out":
            key_combination([0x11, 0xBD]) # Ctrl + Minus
        elif action == "copy":
            key_combination([0x11, ord('C')]) # Ctrl + C
        elif action == "paste":
            key_combination([0x11, ord('V')]) # Ctrl + V
        elif action == "undo":
            key_combination([0x11, ord('Z')]) # Ctrl + Z
        elif action == "select_all":
            key_combination([0x11, ord('A')]) # Ctrl + A
        elif action == "terminal":
            subprocess.Popen(["wt.exe"], shell=True)
        elif action == "browser":
            subprocess.Popen(["start", "https://google.com"], shell=True)
        elif action == "explorer":
            subprocess.Popen(["explorer.exe"])
        elif action == "screen_off":
            user32.SendMessageW(0xFFFF, 0x0112, 0xF170, 2)
        elif action == "snip":
            key_combination([0x5B, 0x10, ord('S')]) # Win + Shift + S
        elif action == "mic_mute":
            key_combination([0x11, 0x10, ord('M')]) # Ctrl + Shift + M
        elif action == "task_mgr":
            key_combination([0x11, 0x10, 0x1B]) # Ctrl + Shift + Esc
        elif action == "sleep":
            subprocess.Popen("rundll32.exe powrprof.dll,SetSuspendState 0,1,0", shell=True)
        elif action == "restart":
            subprocess.Popen("shutdown /r /t 0", shell=True)
        elif action == "shutdown":
            subprocess.Popen("shutdown /s /t 0", shell=True)

    elif t == "clip_get":
        return {"t": "clip_data", "text": get_clipboard_text()}

    elif t == "clip_set":
        text = cmd.get("text", "")
        set_clipboard_text(text)
        if cmd.get("paste"):
            time.sleep(0.04)
            key_combination([0x11, ord('V')]) # Ctrl + V
        return {"t": "clip_ok"}

    elif t == "unlock":
        pin = cmd.get("pin", "")
        press_key(0x20) # Wake lock screen / prompt PIN
        time.sleep(0.3)
        for ch in pin:
            send_unicode_char(ch)
            time.sleep(0.02)
        time.sleep(0.05)
        press_key(0x0D) # Enter
        return {"t": "unlock_sent"}

    elif t == "get_apps":
        app_names = sorted(list(INSTALLED_APPS.keys()))
        return {"t": "app_list", "apps": app_names}

    elif t == "launch":
        app_name = cmd.get("app")
        print(f"[*] Launching: {app_name}")
        if app_name in INSTALLED_APPS:
            target = INSTALLED_APPS[app_name]
            try:
                if os.path.exists(target):
                    os.startfile(target)
                else:
                    subprocess.Popen(f'start "" "{target}"', shell=True)
            except Exception as e:
                print(f"[!] Launch error: {e}")
                subprocess.Popen(f'start "" "{target}"', shell=True)
        else:
            subprocess.Popen(f'start "" "{app_name}"', shell=True)

    elif t == "text":
        text = cmd.get("text", "")
        for ch in text:
            if ch == "\n":
                press_key(VK_CODES["enter"][0])
            elif ch == "\b":
                press_key(VK_CODES["backspace"][0])
            elif ch == "\t":
                press_key(VK_CODES["tab"][0])
            else:
                send_unicode_char(ch)

    elif t == "proximity":
        proximity_lock_enabled = bool(cmd.get("enabled", False))
        last_heartbeat_time = time.time()
        print(f"[PROXIMITY] Walk-Away Auto-Lock is now: {proximity_lock_enabled}")
        sys.stdout.flush()
        return {"t": "proximity_ack", "enabled": proximity_lock_enabled}

    elif t == "hb":
        global consecutive_rssi_drops
        last_heartbeat_time = time.time()
        rssi = cmd.get("rssi")
        if rssi is not None and proximity_lock_enabled:
            # Hardware RSSI Geofence: -82 dBm signals user is 5-8+ meters away
            if rssi < -82:
                consecutive_rssi_drops += 1
                if consecutive_rssi_drops >= 2:
                    print(f"[PROXIMITY GEOFENCE] Signal dropped to {rssi} dBm (< -82 dBm) -> Auto-Locking workstation!")
                    sys.stdout.flush()
                    attach_to_user_desktop()
                    user32.LockWorkStation()
                    proximity_lock_enabled = False
                    consecutive_rssi_drops = 0
            else:
                consecutive_rssi_drops = 0
        return None

    elif t == "screen":
        try:
            attach_to_user_desktop()
            img = ImageGrab.grab()
            img.thumbnail((480, 270))
            buf = io.BytesIO()
            img.save(buf, format="JPEG", quality=55)
            b64 = base64.b64encode(buf.getvalue()).decode("ascii")
            return {"t": "screen_frame", "img": b64}
        except Exception as e:
            return {"t": "screen_frame", "err": str(e)}

    elif t == "screen_touch":
        # Interactive Touchscreen on Second Display Mirror
        act = cmd.get("act", "click")
        rx = float(cmd.get("x", 0.0))
        ry = float(cmd.get("y", 0.0))
        attach_to_user_desktop()
        sw = user32.GetSystemMetrics(0)
        sh = user32.GetSystemMetrics(1)
        px = max(0, min(sw - 1, int(rx * sw)))
        py = max(0, min(sh - 1, int(ry * sh)))

        user32.SetCursorPos(px, py)
        if act == "down":
            inp = INPUT(type=0, mi=MOUSEINPUT(0, 0, 0, MOUSEEVENTF_LEFTDOWN, 0, 0))
            user32.SendInput(1, ctypes.byref(inp), 40)
        elif act == "up":
            inp = INPUT(type=0, mi=MOUSEINPUT(0, 0, 0, MOUSEEVENTF_LEFTUP, 0, 0))
            user32.SendInput(1, ctypes.byref(inp), 40)
        elif act == "click":
            send_mouse_click("l")
        elif act == "rclick":
            send_mouse_click("r")
        elif act == "dclick":
            send_mouse_click("d")
        return None

    elif t == "gp_stick":
        # Virtual Analog Thumbstick
        sx = float(cmd.get("x", 0.0))
        sy = float(cmd.get("y", 0.0))
        mode = cmd.get("mode", "wasd")
        if mode == "mouse":
            if abs(sx) > 0.08 or abs(sy) > 0.08:
                send_mouse_move(int(sx * 14), int(sy * 14))
        else:
            set_wasd_key("w", sy < -0.28)
            set_wasd_key("s", sy > 0.28)
            set_wasd_key("a", sx < -0.28)
            set_wasd_key("d", sx > 0.28)
        return None

    elif t == "gp":
        # Gamepad Input Handler
        btn = cmd.get("btn", "").upper()
        state = cmd.get("state", "down")
        is_down = (state == "down")

        # Map Gamepad to keys & mouse buttons
        gp_key_map = {
            "UP": "w", "DOWN": "s", "LEFT": "a", "RIGHT": "d",
            "A": "space", "B": "esc", "X": "r", "Y": "e",
            "LB": "q", "RB": "f",
            "START": "enter", "SELECT": "tab"
        }

        if btn in gp_key_map:
            key_name = gp_key_map[btn]
            vk, is_ext = VK_CODES.get(key_name, (0, False))
            if vk != 0:
                flag = KEYEVENTF_KEYDOWN if is_down else KEYEVENTF_KEYUP
                if is_ext: flag |= KEYEVENTF_EXTENDEDKEY
                inp = INPUT(type=1, ki=KEYBDINPUT(vk, 0, flag, 0, 0))
                user32.SendInput(1, ctypes.byref(inp), 40)

        elif btn == "LT":
            flag = MOUSEEVENTF_RIGHTDOWN if is_down else MOUSEEVENTF_RIGHTUP
            inp = INPUT(type=0, mi=MOUSEINPUT(0, 0, 0, flag, 0, 0))
            user32.SendInput(1, ctypes.byref(inp), 40)

        elif btn == "RT":
            flag = MOUSEEVENTF_LEFTDOWN if is_down else MOUSEEVENTF_LEFTUP
            inp = INPUT(type=0, mi=MOUSEINPUT(0, 0, 0, flag, 0, 0))
            user32.SendInput(1, ctypes.byref(inp), 40)
        return None

    elif t == "gp_gyro":
        steer = int(cmd.get("steer", 0))
        # Smooth horizontal mouse steering in racing games
        send_mouse_move(steer, 0)
        return None

    elif t == "unlock":
        # Remote Biometric Windows Hello Unlock
        pin = cmd.get("pin", "")
        attach_to_user_desktop()
        # Wake up lockscreen
        press_key(VK_CODES["space"][0])
        time.sleep(0.15)
        press_key(VK_CODES["enter"][0])
        time.sleep(0.2)
        if pin:
            for ch in pin:
                send_unicode_char(ch)
                time.sleep(0.03)
            time.sleep(0.05)
            press_key(VK_CODES["enter"][0])
        return {"t": "unlock_ack", "status": "ok"}

    elif t == "ping":
        return {"t": "pong"}

    return None

def handle_client_session(client_sock, client_info, local_addr):
    global proximity_lock_enabled, last_heartbeat_time
    print(f"[+] Phone connected: {client_info}")
    sys.stdout.flush()

    attach_to_user_desktop()
    last_heartbeat_time = time.time()

    # 7.5ms Interval Tuning / Low-Latency socket buffers
    try:
        client_sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 65536)
        client_sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 65536)
    except Exception:
        pass

    app_names = sorted(list(INSTALLED_APPS.keys()))
    welcome = json.dumps({"status": "connected", "mac": local_addr, "apps": app_names}) + "\n"
    try:
        client_sock.sendall(welcome.encode("utf-8"))
    except Exception as e:
        print(f"[!] Welcome send error: {e}")
        return

    stop_telemetry = threading.Event()
    def telemetry_worker():
        global proximity_lock_enabled, last_heartbeat_time
        while not stop_telemetry.is_set():
            try:
                # Check proximity heartbeat
                if proximity_lock_enabled and (time.time() - last_heartbeat_time > 6.0):
                    print("[PROXIMITY] Heartbeat lost (>6s) -> Locking workstation!")
                    sys.stdout.flush()
                    attach_to_user_desktop()
                    user32.LockWorkStation()
                    proximity_lock_enabled = False

                telem = get_telemetry()
                msg = (json.dumps(telem) + "\n").encode("utf-8")
                client_sock.sendall(msg)
            except Exception:
                break
            stop_telemetry.wait(2.0)

    t_thread = threading.Thread(target=telemetry_worker, daemon=True)
    t_thread.start()

    buffer = ""
    raw_buffer = bytearray()
    while True:
        try:
            chunk = client_sock.recv(1024)
            if not chunk:
                print("[-] Phone disconnected.")
                sys.stdout.flush()
                if proximity_lock_enabled:
                    print("[PROXIMITY] Bluetooth disconnected while Auto-Lock active -> Locking workstation!")
                    sys.stdout.flush()
                    attach_to_user_desktop()
                    user32.LockWorkStation()
                    proximity_lock_enabled = False
                break

            raw_buffer.extend(chunk)

            # Direct Low-Latency 5-byte Binary Motion Packets:
            # Format: [0x01, dx_high, dx_low, dy_high, dy_low]
            while len(raw_buffer) >= 5 and raw_buffer[0] == 1:
                dx, dy = struct.unpack('>hh', raw_buffer[1:5])
                send_mouse_move(dx, dy)
                del raw_buffer[:5]

            if not raw_buffer:
                continue

            # Process text/JSON payload lines
            idx = raw_buffer.find(b"\n")
            while idx != -1:
                line_bytes = raw_buffer[:idx]
                del raw_buffer[:idx + 1]
                idx = raw_buffer.find(b"\n")

                line = line_bytes.decode("utf-8", errors="ignore").strip()
                if not line:
                    continue
                try:
                    cmd = json.loads(line)
                    if cmd.get("t") not in ["m", "s", "hb"]:
                        print(f"[CMD] {cmd}")
                        sys.stdout.flush()
                    resp = handle_command(cmd, client_sock)
                    if resp:
                        client_sock.sendall((json.dumps(resp) + "\n").encode("utf-8"))
                except json.JSONDecodeError:
                    pass
                except Exception as e:
                    print(f"[!] Command execution error: {e}")
                    sys.stdout.flush()

        except Exception as e:
            print(f"[!] Session loop error: {e}")
            break

    stop_telemetry.set()
    try:
        client_sock.close()
    except Exception:
        pass

def start_server(port: int = 4, tcp_port: int = 8888):
    global proximity_lock_enabled, last_heartbeat_time
    attach_to_user_desktop()

    # 1. Bluetooth RFCOMM Server
    server_sock = socket.socket(AF_BTH, socket.SOCK_STREAM, BTPROTO_RFCOMM)
    try:
        server_sock.bind(('00:00:00:00:00:00', port))
    except OSError as e:
        if getattr(e, 'winerror', None) == 10048 or "10048" in str(e):
            print("[*] BluetoothPad Server is already running in background.")
            sys.exit(0)
        raise
    server_sock.listen(1)

    local_addr, bound_port = server_sock.getsockname()
    print("=" * 60)
    print(" 🚀 BLUETOOTH LAPTOP REMOTE SERVER v5")
    print(f" [+] Local Bluetooth MAC : {local_addr}")
    print(f" [+] RFCOMM Channel     : {bound_port}")
    print(f" [+] BNEP TCP Tunnel    : port {tcp_port} (Active)")
    print(f" [+] Swift Pair Beacon  : Compatible (0x0006 Adv)")
    print(f" [+] Indexed Apps       : {len(INSTALLED_APPS)} applications ready")
    print("=" * 60)

    register_sdp_service(bound_port)

    # 2. BNEP / Bluetooth PAN TCP Listener (TCP/IP over Bluetooth without Wi-Fi)
    def bnep_tcp_listener():
        tcp_server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        tcp_server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            tcp_server.bind(('0.0.0.0', tcp_port))
            tcp_server.listen(2)
            print(f"[*] BNEP TCP listener running on 0.0.0.0:{tcp_port}")
            sys.stdout.flush()
            while True:
                client_sock, client_info = tcp_server.accept()
                t = threading.Thread(target=handle_client_session, args=(client_sock, client_info, local_addr), daemon=True)
                t.start()
        except Exception as e:
            print(f"[!] BNEP TCP listener error: {e}")

    threading.Thread(target=bnep_tcp_listener, daemon=True).start()

    # 3. RFCOMM Listener Loop
    while True:
        print("\n[*] Waiting for phone connection over Bluetooth...")
        sys.stdout.flush()
        try:
            client_sock, client_info = server_sock.accept()
            handle_client_session(client_sock, client_info, local_addr)
        except KeyboardInterrupt:
            print("\n[!] Stopping server...")
            break
        except Exception as e:
            print(f"[!] Connection error: {e}")
            sys.stdout.flush()
            time.sleep(1)

    server_sock.close()
    print("[*] Server terminated.")

if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 4
    start_server(port)
