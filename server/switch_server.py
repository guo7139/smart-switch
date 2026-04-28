
#!/usr/bin/env python3
"""
智能开关控制服务端
- TCP 9000: 与开关设备通信（设备主动连接）
- HTTP 8080: 为安卓APP提供API（多线程）
"""

import json
import socket
import threading
import time
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn
from urllib.parse import urlparse

TCP_PORT = 9000
HTTP_PORT = 8080
CONFIG_FILE = "devices.json"
ENTERPRISE_CODE = 0x00C8
POLL_INTERVAL = 3  # 轮询间隔（秒）

# 通道号(1-8) -> 状态位位置
STATUS_BIT_MAP = {1: 1, 2: 2, 3: 3, 4: 5, 5: 6, 6: 10, 7: 9, 8: 8}


def crc16_modbus(data: bytes) -> int:
    crc = 0xFFFF
    for byte in data:
        crc ^= byte
        for _ in range(8):
            if crc & 0x0001:
                crc = (crc >> 1) ^ 0xA001
            else:
                crc >>= 1
    return crc


def build_control_frame(address, channel_bits, turn_on):
    channel_val = (channel_bits << 4) | 0x08
    switch_val = 0xFFFF if turn_on else 0x0000
    frame = bytes([
        address, 0x10,
        0x00, 0x1F, 0x00, 0x03, 0x06,
        (channel_val >> 8) & 0xFF, channel_val & 0xFF,
        (switch_val >> 8) & 0xFF, switch_val & 0xFF,
        (ENTERPRISE_CODE >> 8) & 0xFF, ENTERPRISE_CODE & 0xFF
    ])
    crc = crc16_modbus(frame)
    return frame + bytes([crc & 0xFF, (crc >> 8) & 0xFF])


def build_read_frame(address):
    """构建读取状态帧: 功能码0x03, 读寄存器0x0004, 数量3"""
    frame = bytes([address, 0x03, 0x00, 0x04, 0x00, 0x03])
    crc = crc16_modbus(frame)
    return frame + bytes([crc & 0xFF, (crc >> 8) & 0xFF])



class DeviceManager:
    def __init__(self, config_file):
        with open(config_file, 'r', encoding='utf-8') as f:
            self.config = json.load(f)
        self.connections = {}
        self.conn_lock = threading.Lock()
        self.status = {}
        self.status_lock = threading.Lock()
        for dev in self.config:
            for mod in dev['module']:
                self.status[(dev['ip'], mod['address'])] = {
                             i + 1: False for i in range(mod['number'])}

    def register_connection(self, ip, sock):
        with self.conn_lock:
            old = self.connections.get(ip)
            if old:
                try: old.close()
                except: pass
            self.connections[ip] = sock
        print(f"[INFO] Device connected: {ip}")

    def remove_connection(self, ip):
        with self.conn_lock:
            self.connections.pop(ip, None)
        print(f"[INFO] Device disconnected: {ip}")

    def update_status(self, ip, address, ch_status):
        key = (ip, address)
        with self.status_lock:
            if key in self.status:
                self.status[key].update(ch_status)

    def get_device_status(self):
        result = json.loads(json.dumps(self.config))
        with self.status_lock:
            for dev in result:
                for mod in dev['module']:
                    st = self.status.get((dev['ip'], mod['address']), {})
                    mod['status'] = {
    mod['channel'][i]: st.get(
        i + 1,
        False) for i in range(
            mod['number'])}
        with self.conn_lock:
            for dev in result:
                dev['online'] = dev['ip'] in self.connections
        return result

    def send_control(self, device_ip, address, channel_num, turn_on):
        with self.conn_lock:
            sock = self.connections.get(device_ip)
        if not sock: return False
        frame = build_control_frame(address, 1 << (channel_num - 1), turn_on)
        try:
            sock.sendall(frame)
            print(f"[SEND] {device_ip} addr={address} ch={channel_num} {'ON' if turn_on else 'OFF'}: {frame.hex(' ')}")
            return True
        except Exception as e:
            print(f"[SEND ERROR] {device_ip}: {e}, 清除连接")
            self.remove_connection(device_ip)
            return False

    def send_control_all(self, device_ip, address, number, turn_on):
        with self.conn_lock:
            sock = self.connections.get(device_ip)
        if not sock: return False
        frame = build_control_frame(address, (1 << number) - 1, turn_on)
        try:
            sock.sendall(frame)
            print(f"[SEND] {device_ip} addr={address} ALL {'ON' if turn_on else 'OFF'}: {frame.hex(' ')}")
            return True
        except Exception as e:
            print(f"[SEND ERROR ALL] {device_ip}: {e}, 清除连接")
            self.remove_connection(device_ip)
            return False


dm = None


def handle_device(sock, addr):
    ip = addr[0]
    dm.register_connection(ip, sock)
    sock.settimeout(300)
    # 开启TCP keepalive，及时检测断线
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_KEEPIDLE, 10)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_KEEPINTVL, 5)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_KEEPCNT, 3)
    buf = b""
    try:
        while True:
            data = sock.recv(1024)
            if not data: break
            buf += data

            # 解析所有完整帧
            while len(buf) >= 8:
                consumed = False
                # 状态上报帧: funccode=0x10, reg=0x0004, 15字节
                if len(buf) >= 15 and buf[1] == 0x10:
                    reg = (buf[2] << 8) | buf[3]
                    if reg == 0x0004:
                        r = parse_status_report(buf[:15])
                        if r:
                            dm.update_status(ip, r[0], r[1])
                        buf = buf[15:]
                        consumed = True
                    elif reg == 0x001F:
                        # 控制确认帧，丢弃8字节
                        if len(buf) >= 8:
                            buf = buf[8:]
                            consumed = True
                if not consumed:
                    buf = buf[1:]

    except socket.timeout:
        print(f"[INFO] {ip} timeout")
    except Exception as e:
        print(f"[ERROR] {ip}: {e}")
    finally:
        dm.remove_connection(ip)
        try: sock.close()
        except: pass

def tcp_server():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(('0.0.0.0', TCP_PORT))
    srv.listen(10)
    print(f"[TCP] Listening on :{TCP_PORT}")
    while True:
        c, a = srv.accept()
        threading.Thread(target=handle_device, args=(c, a), daemon=True).start()

def poll_all_devices():
    """定时轮询所有在线设备的状态"""
    while True:
        time.sleep(POLL_INTERVAL)
        for dev in dm.config:
            ip = dev['ip']
            with dm.conn_lock:
                sock = dm.connections.get(ip)
            if not sock:
                continue
            for mod in dev['module']:
                frame = build_read_frame(mod['address'])
                try:
                    sock.sendall(frame)
                except Exception as e:
                    print(f"[POLL_ERROR] {ip} addr={mod['address']}: {e}")
                    break
                time.sleep(0.1)  # 给设备响应时间

# ===== 多线程HTTP服务器 =====
class ThreadingHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True

class APIHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args): pass
    def _cors(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET,POST,OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type')
    def _json(self, code, data):
        body = json.dumps(data, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self._cors()
        self.send_header('Content-Length', len(body))
        self.end_headers()
        self.wfile.write(body)
    def do_OPTIONS(self):
        self.send_response(200); self._cors(); self.end_headers()
    def do_GET(self):
        p = urlparse(self.path).path
        if p == '/api/devices':
            self._json(200, {"code":0, "data": dm.get_device_status()})
        elif p == '/api/config':
            self._json(200, {"code":0, "data": dm.config})
        else:
            self._json(404, {"code":-1, "msg":"Not Found"})
    def do_POST(self):
        p = urlparse(self.path).path
        length = int(self.headers.get('Content-Length', 0))
        body = json.loads(self.rfile.read(length)) if length else {}
        if p == '/api/control':
            ip=body.get('ip'); addr=body.get('address'); ch=body.get('channel'); act=body.get('action')
            if not all([ip,addr,ch,act]):
                self._json(400, {"code":-1,"msg":"缺少参数"}); return
            # channel直接是通道序号(1-based)，addr转int
            ok = dm.send_control(ip, int(addr), int(ch), act=='on')
            self._json(200, {"code":0 if ok else -1, "msg":"OK" if ok else "设备未连接"})
        elif p == '/api/control_all':
            ip=body.get('ip'); addr=body.get('address'); num=body.get('number'); act=body.get('action')
            if not all([ip,addr,num,act]):
                self._json(400, {"code":-1,"msg":"缺少参数"}); return
            ok = dm.send_control_all(ip, addr, num, act=='on')
            self._json(200, {"code":0 if ok else -1, "msg":"OK" if ok else "设备未连接"})
        else:
            self._json(404, {"code":-1,"msg":"Not Found"})

if __name__ == '__main__':
    print("="*50)
    print("  智能开关控制服务端 (ThreadingHTTPServer)")
    print(f"  TCP设备端口: {TCP_PORT}  |  HTTP API端口: {HTTP_PORT}")
    print(f"  轮询间隔: {POLL_INTERVAL}秒")
    print("="*50)
    dm = DeviceManager(CONFIG_FILE)
    print(f"[INFO] 已加载 {len(dm.config)} 个设备")
    threading.Thread(target=tcp_server, daemon=True).start()
    threading.Thread(target=poll_all_devices, daemon=True).start()
    try:
        ThreadingHTTPServer(('0.0.0.0', HTTP_PORT), APIHandler).serve_forever()
    except KeyboardInterrupt:
        print("\n[INFO] Stopped")
            # 解析所有完整帧\n            while len(buf) >= 8:\n                parsed = False\n                # 状态上报帧 (0x10, reg 0x0004, 15字节)\n                if len(buf) >= 15 and buf[1] == 0x10:\n                    reg = (buf[2] << 8) | buf[3]\n                    if reg == 0x0004:\n                        print(f"[状态帧] {ip} addr={buf[0]} reg=0x{reg:04x} data: {buf[:15].hex()}" )\n                


def parse_status_report(data):
    """解析状态上报(0x10功能码, 寄存器0x0004, 15字节)"""
    if len(data) < 15: return None
    if data[1] != 0x10: return None
    reg_start = (data[2] << 8) | data[3]
    if reg_start != 0x0004: return None
    crc_recv = data[13] | (data[14] << 8)
    if crc16_modbus(data[:13]) != crc_recv: return None
    address = data[0]
    status_word = (data[9] << 8) | data[10]
    source = (data[11] << 8) | data[12]
    status = {}
    for ch, bit_pos in STATUS_BIT_MAP.items():
        status[ch] = bool(status_word & (1 << bit_pos))
    return address, status, source
