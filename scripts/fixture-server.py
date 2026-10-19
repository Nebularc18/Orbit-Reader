#!/usr/bin/env python3
"""Local synthetic audiobook fixture for emulator smoke verification."""

import argparse
import io
import json
import math
import pathlib
import re
import struct
import threading
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def audio(frequency):
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(8000)
        output.writeframes(b"".join(struct.pack("<h", int(800 * math.sin(2 * math.pi * frequency * i / 8000))) for i in range(20 * 8000)))
    return buffer.getvalue()


AUDIO = [audio(220), audio(330)]
ASSETS = [{"assetId": f"aud_00000000-0000-4000-8000-{i + 1:012}", "fileId": i + 1, "sequence": i, "format": "mp3", "durationMs": 20000, "sizeBytes": len(AUDIO[i]), "etag": f"fixture-{i}"} for i in range(2)]
MANIFEST = {
    "schema": "bookorbit.audiobook-manifest", "schemaVersion": 2, "revision": "a" * 64,
    "book": {"id": 1, "title": "A Quiet Orbit · Audio Test", "authors": ["Synthetic test audio"], "narrators": [], "hasCover": False},
    "assets": ASSETS, "totalDurationMs": 40000,
    "chapters": [{"id": f"chapter-{i}", "title": f"Chapter {i + 1}", "assetId": ASSETS[i]["assetId"], "sequence": i, "startMs": i * 20000, "endMs": (i + 1) * 20000, "assetOffsetMs": 0} for i in range(2)],
}
STATE = None
OPERATION = None
LOCK = threading.Lock()
BOOK_DIR = None


class Handler(BaseHTTPRequestHandler):
    def send_json(self, value, status=200):
        payload = json.dumps(value).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", len(payload))
        self.end_headers()
        self.wfile.write(payload)

    def body(self):
        return json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))) or b"{}")

    def authorized(self):
        if self.headers.get("Authorization") != "Bearer fixture-access":
            self.send_json({"message": "Unauthorized"}, 401)
            return False
        return True

    def do_POST(self):
        data = self.body()
        if self.path == "/api/v1/auth/login":
            if data.get("username") != "reader" or data.get("password") != "reader" or data.get("clientKind") != "native":
                return self.send_json({}, 401)
            return self.send_json({"accessToken": "fixture-access", "refreshToken": "a" * 64, "user": {"id": 1, "name": "Reader", "username": "reader", "isSuperuser": False, "permissions": ["library_download"]}})
        if self.path == "/api/v1/auth/refresh":
            return self.send_json({"accessToken": "fixture-access", "refreshToken": "a" * 64})
        if self.path == "/api/v1/auth/logout":
            return self.send_json({})
        if not self.authorized():
            return
        if self.path == "/api/v1/books/query":
            book = MANIFEST["book"]
            matches = data.get("q", "").casefold() in (book["title"] + " " + " ".join(book["authors"])).casefold()
            items = [{"id": book["id"], "title": book["title"], "authors": book["authors"], "coverVersion": MANIFEST["revision"]}] if matches else []
            return self.send_json({"items": items if data["pagination"]["page"] == 1 else [], "total": len(items), "page": data["pagination"]["page"], "size": data["pagination"]["size"]})
        self.send_json({}, 404)

    def do_GET(self):
        if not self.authorized():
            return
        if self.path == f"/api/v1/audiobooks/{MANIFEST['book']['id']}/manifest":
            return self.send_json(MANIFEST)
        if self.path == f"/api/v1/audiobooks/{MANIFEST['book']['id']}/playback-state":
            return self.send_json(STATE)
        if BOOK_DIR and self.path in [f"/api/v1/books/{MANIFEST['book']['id']}/thumbnail", f"/api/v1/books/{MANIFEST['book']['id']}/cover"]:
            return self.send_file(BOOK_DIR / "cover.jpg", "image/jpeg")
        asset = next((item for item in ASSETS if item["assetId"] in self.path or f"/files/{item['fileId']}/download" in self.path), None)
        if asset:
            if BOOK_DIR:
                return self.send_file(BOOK_DIR / f"{asset['fileId']}.{asset['format']}", "audio/mp4" if asset['format'] in ['m4b', 'm4a'] else "audio/mpeg")
            content = AUDIO[asset["sequence"]]
            start = 0
            if self.headers.get("Range"):
                start = int(self.headers["Range"].split("=")[1].split("-")[0])
            if start >= len(content):
                return self.send_json({}, 416)
            self.send_response(206 if start else 200)
            self.send_header("Content-Type", "audio/wav")
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", len(content) - start)
            if start:
                self.send_header("Content-Range", f"bytes {start}-{len(content) - 1}/{len(content)}")
            self.end_headers()
            return self.wfile.write(content[start:])
        self.send_json({}, 404)

    def send_file(self, path, mime):
        if not path.is_file():
            return self.send_json({}, 404)
        size = path.stat().st_size
        start, end = 0, size - 1
        ranged = self.headers.get("Range")
        if ranged:
            match = re.fullmatch(r"bytes=(\d*)-(\d*)", ranged)
            if not match or not any(match.groups()):
                return self.send_json({}, 416)
            if match[1]:
                start = int(match[1])
                end = min(int(match[2]), end) if match[2] else end
            else:
                start = max(0, size - int(match[2]))
            if start >= size or end < start:
                return self.send_json({}, 416)
        self.send_response(206 if ranged else 200)
        self.send_header("Content-Type", mime)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", end - start + 1)
        if ranged:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        try:
            with path.open("rb") as source:
                source.seek(start)
                remaining = end - start + 1
                while remaining:
                    chunk = source.read(min(65536, remaining))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    remaining -= len(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_PUT(self):
        global STATE, OPERATION
        if not self.authorized():
            return
        if self.path != f"/api/v1/audiobooks/{MANIFEST['book']['id']}/playback-state":
            return self.send_json({}, 404)
        data = self.body()
        with LOCK:
            if data["operationId"] == OPERATION:
                return self.send_json(STATE)
            if data["baseRevision"] != (STATE["revision"] if STATE else 0):
                return self.send_json({}, 409)
            STATE = {key: data[key] for key in ["assetId", "positionMs", "capturedAt", "manifestRevision"]}
            STATE["revision"] = data["baseRevision"] + 1
            OPERATION = data["operationId"]
            self.send_json(STATE)

    def log_message(self, format_string, *args):
        print(f"fixture {self.command} {self.path} {args[1] if len(args) > 1 else ''}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=4890)
    parser.add_argument("--book-dir", type=pathlib.Path, help="Private fixture directory with manifest.json, cover.jpg, and <fileId>.<format> audio files")
    args = parser.parse_args()
    if args.book_dir:
        BOOK_DIR = args.book_dir.resolve()
        MANIFEST = json.loads((BOOK_DIR / "manifest.json").read_text())
        ASSETS = MANIFEST["assets"]
        for asset in ASSETS:
            if not isinstance(asset["fileId"], int) or asset["format"] not in ["m4b", "m4a", "mp3"]:
                parser.error("Invalid fixture asset")
            if (BOOK_DIR / f"{asset['fileId']}.{asset['format']}").stat().st_size != asset["sizeBytes"]:
                parser.error("Fixture audio size does not match its manifest")
    print(f"Fixture listening on 127.0.0.1:{args.port}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
