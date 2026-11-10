#!/usr/bin/env python3
"""Minimal UPnP AVTransport renderer for testing: logs SetAVTransportURI, then on Play fetches the URL
like a Samsung TV (HEAD, open-ended Range from 0, a tail Range for the mp4 index, then reads ahead).
Usage: fake_renderer.py PORT RESULT_JSON [MAX_BYTES]"""
import sys, json, re, threading, time, html, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1]); OUT = sys.argv[2]; MAX = int(sys.argv[3]) if len(sys.argv) > 3 else 12_000_000
state = {"uri": None, "events": [], "bytes_head": 0, "bytes_tail": 0, "status": []}
lock = threading.Lock()

def save():
    with lock:
        json.dump(state, open(OUT, "w"))

def log(msg):
    state["events"].append(f"{time.strftime('%H:%M:%S')} {msg}"); save()

def fetch(uri, rng, limit):
    req = urllib.request.Request(uri, headers={"Range": rng, "User-Agent": "SEC_HHP_[TV] Samsung/1.0 DLNADOC/1.50", "Connection": "close"})
    got = 0
    with urllib.request.urlopen(req, timeout=150) as r:
        state["status"].append(f"{rng} -> {r.status} {r.headers.get('Content-Range')} {r.headers.get('Content-Type')}"); save()
        while got < limit:
            d = r.read(min(65536, limit - got))
            if not d: break
            got += len(d)
    return got

def play():
    uri = state["uri"]
    try:
        req = urllib.request.Request(uri, method="HEAD"); r = urllib.request.urlopen(req, timeout=60)
        state["status"].append(f"HEAD -> {r.status} len={r.headers.get('Content-Length')}"); save()
        total = int(r.headers.get("Content-Length") or r.headers.get("Content-Range", "/0").split("/")[-1])
        log(f"fetch head (open range)")
        state["bytes_head"] = fetch(uri, "bytes=0-", MAX); log(f"head done {state['bytes_head']}")
        log("fetch tail")
        state["bytes_tail"] = fetch(uri, f"bytes={max(total-1_000_000,0)}-", 1_000_000); log(f"tail done {state['bytes_tail']}")
        state["ok"] = True
    except Exception as e:
        state["ok"] = False; log(f"ERROR {e!r}")
    save()

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_GET(self):
        body = b"<root xmlns='urn:schemas-upnp-org:device-1-0'><device><friendlyName>Fake TV</friendlyName></device></root>"
        self.send_response(200); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0)); body = self.rfile.read(n).decode()
        action = self.headers.get("SOAPAction", "").strip('"').split("#")[-1]
        resp = ""
        if action == "SetAVTransportURI":
            m = re.search(r"<CurrentURI>(.*?)</CurrentURI>", body, re.S); state["uri"] = html.unescape(m.group(1)); log(f"SetAVTransportURI {state['uri']}")
        elif action == "Play":
            log("Play"); threading.Thread(target=play, daemon=True).start()
        elif action == "GetPositionInfo":
            resp = "<TrackDuration>00:02:00</TrackDuration><RelTime>00:00:05</RelTime><TransportState>PLAYING</TransportState>"
        else: log(action)
        out = f"<?xml version='1.0'?><s:Envelope xmlns:s='http://schemas.xmlsoap.org/soap/envelope/'><s:Body><u:{action}Response xmlns:u='x'>{resp}</u:{action}Response></s:Body></s:Envelope>".encode()
        self.send_response(200); self.send_header("Content-Type", "text/xml"); self.send_header("Content-Length", str(len(out))); self.end_headers(); self.wfile.write(out)

save()
ThreadingHTTPServer(("0.0.0.0", PORT), H).serve_forever()
