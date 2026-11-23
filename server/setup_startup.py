import os
import sys
import winreg

def enable_startup():
    server_dir = os.path.dirname(os.path.abspath(__file__))
    server_path = os.path.join(server_dir, "server.py")
    python_dir = os.path.dirname(sys.executable)
    pythonw = os.path.join(python_dir, "pythonw.exe")
    if not os.path.exists(pythonw):
        pythonw = sys.executable

    cmd = f'"{pythonw}" "{server_path}"'
    key_path = r"Software\Microsoft\Windows\CurrentVersion\Run"
    
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, key_path, 0, winreg.KEY_SET_VALUE) as key:
        winreg.SetValueEx(key, "BluetoothPadServer", 0, winreg.REG_SZ, cmd)

    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, key_path, 0, winreg.KEY_READ) as key:
        val, _ = winreg.QueryValueEx(key, "BluetoothPadServer")
        print("[+] Registered in Windows Startup Registry:")
        print("    " + val)

def disable_startup():
    key_path = r"Software\Microsoft\Windows\CurrentVersion\Run"
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, key_path, 0, winreg.KEY_SET_VALUE) as key:
            winreg.DeleteValue(key, "BluetoothPadServer")
        print("[+] Removed BluetoothPadServer from Windows Startup.")
    except FileNotFoundError:
        print("[-] BluetoothPadServer was not found in Startup registry.")

if __name__ == "__main__":
    action = sys.argv[1].lower() if len(sys.argv) > 1 else "enable"
    if action == "disable":
        disable_startup()
    else:
        enable_startup()
