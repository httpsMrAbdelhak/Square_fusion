#!/usr/bin/env python3
"""A test addon for the emulator run: answers any search with one row echoing
the query (so it matches whatever YouTube Music song is playing) and streams a
generated WAV tone. Logs every request to fake-addon.log."""
import io, json, math, struct, sys, wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

def tone(seconds=240, rate=44100):
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(rate)
        frame = b"".join(struct.pack("<hh", int(3000 * math.sin(2 * math.pi * 440 * i / rate)), int(3000 * math.sin(2 * math.pi * 440 * i / rate))) for i in range(rate))
        for _ in range(seconds):
            w.writeframes(frame)
    return buf.getvalue()

TONE = tone()
LOG = open("fake-addon.log", "a", buffering=1)

class Handler(BaseHTTPRequestHandler):
    def _json(self, obj):
        body = json.dumps(obj).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        url = urlparse(self.path)
        q = parse_qs(url.query)
        LOG.write(f"GET {url.path} {dict((k, v[0]) for k, v in q.items())}\n")
        if url.path == "/manifest.json":
            return self._json({"id": "fusion.test", "name": "Fusion test addon", "version": "1.0", "resources": ["search", "stream"]})
        if url.path == "/search":
            text = q.get("q", [""])[0]
            return self._json({"tracks": [{"id": "tone", "title": text, "artist": text, "format": "wav"}]})
        if url.path == "/stream/tone":
            return self._json({"url": "http://10.0.2.2:8765/tone.wav", "codec": "wav", "sampleRate": 44100, "bitDepth": 16})
        if url.path == "/tone.wav":
            start, end = 0, len(TONE) - 1
            rng = self.headers.get("Range")
            if rng and rng.startswith("bytes="):
                a, _, b = rng[6:].partition("-")
                start = int(a or 0); end = int(b) if b else end
            chunk = TONE[start:end + 1]
            self.send_response(206 if rng else 200)
            self.send_header("Content-Type", "audio/wav")
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", str(len(chunk)))
            if rng: self.send_header("Content-Range", f"bytes {start}-{end}/{len(TONE)}")
            self.end_headers()
            try:
                self.wfile.write(chunk)
            except BrokenPipeError:
                pass
            return
        self.send_response(404); self.end_headers()

    def log_message(self, *args):
        pass

ThreadingHTTPServer(("0.0.0.0", 8765), Handler).serve_forever()
