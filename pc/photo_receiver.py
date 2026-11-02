"""PhotoSync: принимает фотки с телефона по Wi-Fi или через интернет и складывает их в папку.

Запуск: py photo_receiver.py
"""
import hashlib
import hmac
import json
import secrets
import socket
import sys
import threading
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HTTP_PORT = 8765
BEACON_PORT = 8766
MAX_SIZE = 4 * 1024 * 1024 * 1024  # 4 ГБ, чтобы проходили длинные видео
SAVE_DIR = Path.home() / "С телефона"  # закреплена в Быстром доступе Проводника
# в собранном PhotoSync.exe __file__ лежит во временной папке, поэтому настройки храним рядом с exe
APP_DIR = Path(sys.executable).parent if getattr(sys, "frozen", False) else Path(__file__).parent
CONFIG_FILE = APP_DIR / "photosync_config.json"
PC_NAME = socket.gethostname()
# Публичная «труба»: телефон пишет в неё файл, комп в это же время читает.
# Сервер ничего не хранит и видит только зашифрованные байты.
RELAY_BASE = "https://ppng.io/"


def load_config():
    """Код подключения и ключ для интернета создаются один раз и дальше не меняются."""
    try:
        config = json.loads(CONFIG_FILE.read_text("utf-8"))
    except (OSError, ValueError):
        config = {}
    changed = False
    if "code" not in config:
        config["code"] = f"{secrets.randbelow(10000):04d}"
        changed = True
    if "relay_key" not in config:
        config["relay_key"] = secrets.token_hex(32)
        changed = True
    if changed:
        CONFIG_FILE.write_text(json.dumps(config), "utf-8")
    return config


CONFIG = load_config()
CODE = CONFIG["code"]
RELAY_KEY = CONFIG["relay_key"]
LOCK = threading.Lock()


def now():
    return time.strftime("%H:%M:%S")


def local_ips():
    """Адреса компа в домашних сетях. Сначала 192.168.*, потом 10.* и 172.16-31.*."""
    try:
        ips = set(socket.gethostbyname_ex(PC_NAME)[2])
    except OSError:
        ips = set()

    def rank(ip):
        a, b = map(int, ip.split(".")[:2])
        if a == 192 and b == 168:
            return 0
        if a == 10:
            return 1
        if a == 172 and 16 <= b <= 31:
            return 2  # часто это VPN или виртуалки, поэтому в конце
        return None  # 127.*, 169.254.* и прочее нам не подходит

    return sorted((ip for ip in ips if rank(ip) is not None), key=lambda ip: (rank(ip), ip))


def clawd_event(kind, name=""):
    """Сообщает Clawd на компе (clawd_desktop.py), что файл пришёл или улетел. Без него — тихо ничего."""
    def send():
        try:
            data = json.dumps({"type": kind, "name": name}, ensure_ascii=False).encode("utf-8")
            req = urllib.request.Request("http://127.0.0.1:8767/event", data=data, method="POST",
                                         headers={"Content-Type": "application/json"})
            urllib.request.urlopen(req, timeout=0.5).close()
        except Exception:
            pass
    threading.Thread(target=send, daemon=True).start()


def safe_name(raw):
    name = Path(urllib.parse.unquote_plus(raw)).name
    name = "".join(c for c in name if c not in '<>:"/\\|?*' and ord(c) >= 32).strip(" .")
    return name or f"file_{int(time.time())}"


def target_path(name, size):
    """Куда сохранить файл. None, если точно такой же уже есть."""
    path = SAVE_DIR / name
    n = 1
    while path.exists():
        if path.stat().st_size == size:
            return None
        path = SAVE_DIR / f"{Path(name).stem} ({n}){Path(name).suffix}"
        n += 1
    return path


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass  # стандартный лог слишком шумный, пишем свой

    def send_text(self, status, text):
        body = text.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def authorized(self):
        if self.headers.get("X-Code") == CODE:
            return True
        print(f"[{now()}] Попытка подключения с неверным кодом: {self.client_address[0]}")
        self.send_text(403, "wrong code")
        return False

    def do_GET(self):
        if self.path != "/ping":
            return self.send_text(404, "not found")
        if self.authorized():
            print(f"[{now()}] Телефон подключился: {self.client_address[0]}")
            body = PC_NAME.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            # ключ отдаём только по Wi-Fi и только с верным кодом: с ним телефон шлёт через интернет
            self.send_header("X-Relay-Key", RELAY_KEY)
            self.end_headers()
            self.wfile.write(body)

    def do_POST(self):
        if self.path != "/upload":
            return self.send_text(404, "not found")
        if not self.authorized():
            return
        size = int(self.headers.get("Content-Length", 0))
        if not 0 < size <= MAX_SIZE:
            return self.send_text(413, "bad size")
        name = safe_name(self.headers.get("X-Filename", ""))

        tmp = SAVE_DIR / f".upload_{secrets.token_hex(6)}.part"
        try:
            left = size
            with open(tmp, "wb") as f:
                while left:
                    chunk = self.rfile.read(min(left, 1024 * 1024))
                    if not chunk:
                        raise ConnectionError("связь оборвалась")
                    f.write(chunk)
                    left -= len(chunk)
            with LOCK:
                path = target_path(name, size)
                if path is None:
                    tmp.unlink()
                    print(f"[{now()}] Уже есть, пропускаю: {name}")
                    return self.send_text(200, "exists")
                tmp.rename(path)
        except Exception:
            tmp.unlink(missing_ok=True)
            raise
        print(f"[{now()}] Получено: {path.name} ({size / 1024 / 1024:.1f} МБ)")
        clawd_event("recv", path.name)
        self.send_text(200, "ok")


class RelayCipher:
    """Шифр для трубы, только на стандартной библиотеке (такой же в приложении, Relay.kt).

    Поток: iv(16) | зашифровано[ длина заголовка(4) | заголовок JSON | файл ] | метка HMAC(32).
    Шифрование — XOR с HMAC-SHA256(enc_key, iv | номер блока), проверка — HMAC-SHA256(mac_key, iv | шифр).
    """

    def __init__(self, key_hex, iv):
        key = bytes.fromhex(key_hex)
        self.enc_key = hmac.digest(key, b"enc", "sha256")
        self.iv = iv
        self.pos = 0
        self.mac = hmac.new(hmac.digest(key, b"mac", "sha256"), iv, "sha256")

    def encrypt(self, data):
        data = self._xor(data)
        self.mac.update(data)
        return data

    def decrypt(self, data):
        self.mac.update(data)
        return self._xor(data)

    def _xor(self, data):
        n = len(data)
        first, last = self.pos // 32, (self.pos + n - 1) // 32
        stream = b"".join(
            hmac.digest(self.enc_key, self.iv + i.to_bytes(8, "big"), "sha256") for i in range(first, last + 1)
        )
        start = self.pos % 32
        self.pos += n
        return (int.from_bytes(data, "big") ^ int.from_bytes(stream[start:start + n], "big")).to_bytes(n, "big")

    def tag(self):
        return self.mac.digest()

    def check(self, tag):
        return hmac.compare_digest(self.mac.digest(), tag)


def relay_url(key_hex):
    # адрес трубы получается из ключа, сам ключ на сервер не уходит
    return RELAY_BASE + hashlib.sha256(f"photosync|{key_hex}".encode()).hexdigest()[:40]


def read_exact(stream, n):
    data = b""
    while len(data) < n:
        chunk = stream.read(n - len(data))
        if not chunk:
            raise ConnectionError("связь оборвалась")
        data += chunk
    return data


def receive_from_relay(resp):
    """Принимает один файл из трубы. Сохраняет, только если метка сошлась."""
    cipher = RelayCipher(RELAY_KEY, read_exact(resp, 16))
    tail = b""  # последние 32 байта — это метка, а не данные
    pending = b""  # расшифрованное, пока не разобрали заголовок
    header = None
    written = 0
    tmp = SAVE_DIR / f".upload_{secrets.token_hex(6)}.part"
    f = None
    try:
        while True:
            chunk = resp.read(256 * 1024)
            if not chunk:
                break
            buf = tail + chunk
            if len(buf) <= 32:
                tail = buf
                continue
            plain, tail = cipher.decrypt(buf[:-32]), buf[-32:]
            if header is None:
                pending += plain
                if len(pending) < 4:
                    continue
                size = int.from_bytes(pending[:4], "big")
                if size > 4096:
                    raise ValueError("слишком длинный заголовок")
                if len(pending) < 4 + size:
                    continue
                header = json.loads(pending[4:4 + size].decode("utf-8"))
                plain, pending = pending[4 + size:], b""
                f = open(tmp, "wb")
            f.write(plain)
            written += len(plain)
            if written > MAX_SIZE:
                raise ValueError("слишком большой файл")
        if header is None or not cipher.check(tail) or written != int(header["size"]):
            raise ValueError("файл повреждён или чужой")
        f.close()
        name = safe_name(urllib.parse.quote(str(header["name"])))
        with LOCK:
            path = target_path(name, written)
            if path is None:
                tmp.unlink()
                print(f"[{now()}] Уже есть, пропускаю: {name}")
                return
            tmp.rename(path)
        print(f"[{now()}] Получено через интернет: {path.name} ({written / 1024 / 1024:.1f} МБ)")
        clawd_event("recv", path.name)
    finally:
        if f and not f.closed:
            f.close()
        tmp.unlink(missing_ok=True)


def relay_loop():
    """Всё время ждёт файл в трубе. Пока телефона нет, соединение просто висит и переоткрывается."""
    url = relay_url(RELAY_KEY)
    warned = False
    while True:
        try:
            with urllib.request.urlopen(url, timeout=120) as resp:
                warned = False
                receive_from_relay(resp)
        except (TimeoutError, socket.timeout):
            pass  # никто ничего не слал, ждём заново
        except Exception as e:
            if not warned:
                print(f"[{now()}] Приём через интернет: {e}. Пробую дальше...")
                warned = True
            time.sleep(5)


def beacon(ip):
    """Каждые 2 секунды кричит в сеть «я тут», чтобы телефон сам нашёл комп."""
    msg = f"PHOTOSYNC|{PC_NAME}|{HTTP_PORT}".encode("utf-8")
    targets = {"255.255.255.255", ip.rsplit(".", 1)[0] + ".255"}
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        s.bind((ip, 0))  # чтобы сигнал ушёл через нужный адаптер, а не через VPN
        while True:
            for target in targets:
                try:
                    s.sendto(msg, (target, BEACON_PORT))
                except OSError:
                    pass
            time.sleep(2)


def main():
    SAVE_DIR.mkdir(parents=True, exist_ok=True)
    ips = local_ips()
    server = ThreadingHTTPServer(("0.0.0.0", HTTP_PORT), Handler)
    for ip in ips:
        threading.Thread(target=beacon, args=(ip,), daemon=True).start()
    threading.Thread(target=relay_loop, daemon=True).start()

    print("=" * 50)
    print("  PhotoSync запущен")
    print(f"  Код для телефона:  {CODE}")
    print(f"  Адрес компа:       {ips[0] + ':' + str(HTTP_PORT) if ips else 'не найден, подключи Wi-Fi'}")
    for ip in ips[1:]:
        print(f"  (ещё адрес:        {ip}:{HTTP_PORT})")
    print("  Через интернет:    включено (подключи телефон один раз по Wi-Fi)")
    print(f"  Фото сохраняются:  {SAVE_DIR}")
    print("=" * 50)
    print("Жду фото... (закрыть: Ctrl+C или крестик)\n")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("Остановлено.")


if __name__ == "__main__":
    main()
