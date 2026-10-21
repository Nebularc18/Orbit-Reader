#!/usr/bin/env python3
"""Local synthetic audiobook and EPUB fixture for emulator smoke verification."""

import argparse
import io
import json
import math
import pathlib
import re
import struct
import threading
import wave
import zipfile
import zlib
import binascii
import datetime
from urllib.parse import urlparse, parse_qs
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def audio(frequency):
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(8000)
        output.writeframes(b"".join(struct.pack("<h", int(800 * math.sin(2 * math.pi * frequency * i / 8000))) for i in range(20 * 8000)))
    return buffer.getvalue()


def ebook():
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("mimetype", "application/epub+zip", compress_type=zipfile.ZIP_STORED)
        archive.writestr("META-INF/container.xml", '<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>')
        archive.writestr("EPUB/package.opf", '''<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">orbit-synthetic-ebook</dc:identifier><dc:title>A Quiet Orbit</dc:title><dc:language>en</dc:language><dc:creator>Orbit Test Reader</dc:creator><meta property="dcterms:modified">2026-10-20T00:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/><item id="two" href="two.xhtml" media-type="application/xhtml+xml"/><item id="image" href="orbit.svg" media-type="image/svg+xml"/></manifest><spine><itemref idref="one"/><itemref idref="two"/></spine></package>''')
        archive.writestr("EPUB/nav.xhtml", '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="one.xhtml">Departure</a></li><li><a href="two.xhtml#arrival">Arrival</a></li></ol></nav></body></html>')
        for name, title in [("one", "Departure"), ("two", "Arrival")]:
            paragraphs = "".join(f'<p id="p{n}">Passage {n + 1}. The little observatory watched a quiet moon rise above the hills. This synthetic story tests page turns, text size, images, and a reading position that survives closing the app.</p>' for n in range(36))
            archive.writestr(f"EPUB/{name}.xhtml", f'<html xmlns="http://www.w3.org/1999/xhtml"><head><title>{title}</title></head><body><h1 id="arrival">{title}</h1><img src="orbit.svg" alt="A small orbit diagram"/>{paragraphs}</body></html>')
        archive.writestr("EPUB/orbit.svg", '<svg xmlns="http://www.w3.org/2000/svg" width="320" height="120" viewBox="0 0 320 120"><rect width="320" height="120" fill="#e2ebdf"/><ellipse cx="160" cy="60" rx="115" ry="35" fill="none" stroke="#304f47" stroke-width="3"/><circle cx="160" cy="60" r="13" fill="#765b35"/></svg>')
    return buffer.getvalue()


def pdf():
    objects = []
    def obj(value):
        objects.append(value.encode())
    obj('<< /Type /Catalog /Pages 2 0 R >>')
    obj('<< /Type /Pages /Kids [3 0 R 5 0 R 7 0 R] /Count 3 >>')
    for page in range(3):
        obj(f'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 800] /Resources << /Font << /F1 9 0 R >> >> /Contents {4+page*2} 0 R >>')
        text = f'BT /F1 28 Tf 60 680 Td (Orbit PDF page {page+1}) Tj 0 -80 Td /F1 16 Tf (Synthetic offline reading test.) Tj ET'
        obj(f'<< /Length {len(text)} >>\nstream\n{text}\nendstream')
    obj('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>')
    data=bytearray(b'%PDF-1.4\n'); offsets=[0]
    for index, value in enumerate(objects,1):
        offsets.append(len(data)); data.extend(f'{index} 0 obj\n'.encode()+value+b'\nendobj\n')
    xref=len(data); data.extend(f'xref\n0 {len(offsets)}\n0000000000 65535 f \n'.encode())
    for offset in offsets[1:]: data.extend(f'{offset:010d} 00000 n \n'.encode())
    data.extend(f'trailer << /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF'.encode())
    return bytes(data)


def comic_image(page):
    width,height=600,800
    colors=[(85,130,105),(130,95,155),(180,125,65)]
    color=colors[page]
    pixels=b''.join(b'\x00'+b''.join(bytes(color if 50<x<550 and 100<y<650 and (x//100+page)%3 else (245,240,225)) for x in range(width)) for y in range(height))
    def chunk(name,value): return struct.pack('>I',len(value))+name+value+struct.pack('>I',binascii.crc32(name+value)&0xffffffff)
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',width,height,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(pixels))+chunk(b'IEND',b'')


PDF=pdf()
COMIC_IMAGES=[comic_image(i) for i in range(3)]
comic_buffer=io.BytesIO()
with zipfile.ZipFile(comic_buffer,'w') as archive:
    for page in [2,1,0]: archive.writestr(f'page{[1,2,10][page]}.png',COMIC_IMAGES[page])
CBZ=comic_buffer.getvalue()
CATALOG_SIZE = 0
READING_PROGRESS={}


EPUB = ebook()
EBOOK_FILE = {"id": 101, "format": "epub", "filename": "A Quiet Orbit.epub", "role": "main", "sizeBytes": len(EPUB), "fileHash": "orbit-epub-v1"}
EBOOK = {"id": 2, "title": "A Quiet Orbit · Reading Test", "authors": [{"name": "Orbit Test Reader"}], "files": [EBOOK_FILE]}

PDF_BOOK={"id":3,"title":"Orbit PDF Test","authors":[{"name":"Orbit Test Reader"}],"files":[{"id":102,"format":"pdf","filename":"Orbit.pdf","sizeBytes":len(PDF),"fileHash":"pdf-v1"}]}
COMIC_BOOK={"id":4,"title":"Orbit Comic Test","authors":[{"name":"Orbit Test Reader"}],"files":[{"id":103,"format":"cbz","filename":"Orbit.cbz","sizeBytes":len(CBZ),"fileHash":"cbz-v1"}]}
CONVERTED_BOOK={"id":5,"title":"Orbit Converted Comic Test","authors":[{"name":"Orbit Test Reader"}],"files":[{"id":104,"format":"cbr","filename":"Orbit.cbr","fileHash":"cbr-v1"},{"id":105,"format":"cb7","filename":"Orbit.cb7","fileHash":"cb7-v1"}]}
DOCUMENTS={2:EBOOK,3:PDF_BOOK,4:COMIC_BOOK,5:CONVERTED_BOOK}
ANNOTATIONS = {}
NEXT_ANNOTATION = 1


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
        match = re.fullmatch(r"/api/v1/books/([2-5])/annotations", self.path)
        if match:
            global NEXT_ANNOTATION
            allowed = {"bookFileId", "text", "cfi", "pdf", "note", "color", "style", "chapterTitle"}
            if set(data) - allowed or not data.get("text") or bool(data.get("cfi")) == bool(data.get("pdf")):
                return self.send_json({}, 400)
            row = {"id": NEXT_ANNOTATION, "bookId": int(match[1]), "jumpFileId": data['bookFileId'], "text": data['text'], "cfi": data.get('cfi'), "pdf": data.get('pdf'), "note": data.get('note'), "color": data.get('color', '#ffff00'), "style": data.get('style', 'highlight')}
            ANNOTATIONS[NEXT_ANNOTATION] = row
            NEXT_ANNOTATION += 1
            return self.send_json(row, 201)
        if self.path == "/api/v1/books/query":
            formats = next((rule.get("value", []) for rule in data.get("filter", {}).get("rules", []) if rule.get("field") == "format"), [])
            books = []
            if not formats or any(value in formats for value in ["mp3", "m4b", "m4a"]):
                books.append(MANIFEST["book"])
            if not formats or "epub" in formats or "kepub" in formats:
                books.append({"id": 2, "title": EBOOK["title"], "authors": ["Orbit Test Reader"]})
            for entry in [PDF_BOOK, COMIC_BOOK, CONVERTED_BOOK]:
                if not formats or any(file['format'] in formats for file in entry['files']):
                    books.append({"id":entry['id'],"title":entry['title'],"authors":["Orbit Test Reader"]})
            if any(value in formats for value in ["mp3", "m4b", "m4a"]):
                books.extend({'id': 1000 + i, 'title': f'Orbit Audio {i:04}', 'authors': ['Synthetic test audio']} for i in range(CATALOG_SIZE))
            if 'epub' in formats:
                books.extend({'id': 2000 + i, 'title': f'Orbit Ebook {i:04}', 'authors': ['Orbit Test Reader']} for i in range(CATALOG_SIZE))
            query = data.get("q", "").casefold()
            items = [{"id": book["id"], "title": book["title"], "authors": book["authors"], "coverVersion": "fixture"} for book in books if query in (book["title"] + " " + " ".join(book["authors"])).casefold()]
            page, size = data['pagination']['page'], data['pagination']['size']
            return self.send_json({"items": items[page*size:(page+1)*size], "total": len(items), "page": page, "size": size})
        match=re.fullmatch(r"/api/v1/books/files/(10[1-5])/progress",self.path)
        if match:
            if set(data)-{"percentage","pageNumber","cfi","source"} or not 0<=data.get("percentage",-1)<=100:
                return self.send_json({},400)
            READING_PROGRESS[int(match[1])] = {"percentage":data['percentage'],"pageNumber":data.get('pageNumber'),"cfi":data.get('cfi'),"textUpdatedAt":datetime.datetime.now(datetime.timezone.utc).isoformat()}
            return self.send_json({})
        self.send_json({}, 404)

    def annotation_mutation(self, delete=False):
        if not self.authorized(): return
        match = re.fullmatch(r"/api/v1/books/([2-5])/annotations/([0-9]+)", self.path)
        row = ANNOTATIONS.get(int(match[2])) if match else None
        if not row or row['bookId'] != int(match[1]): return self.send_json({}, 404)
        if delete:
            del ANNOTATIONS[row['id']]
            return self.send_json({}, 204)
        data = self.body()
        if set(data) - {'note'}: return self.send_json({}, 400)
        row['note'] = data.get('note')
        return self.send_json(row)

    def do_PATCH(self): self.annotation_mutation()
    def do_DELETE(self): self.annotation_mutation(delete=True)

    def do_GET(self):
        if not self.authorized():
            return
        parsed = urlparse(self.path)
        match = re.fullmatch(r"/api/v1/dashboard/scrollers/(continue-listening|continue-reading|up-next-in-series)", parsed.path)
        if match:
            kind = match[1]
            if kind == "continue-listening":
                rows = [dict(MANIFEST['book'], files=[{'id': 1, 'format': 'mp3'}], coverVersion='fixture')]
            else:
                book = EBOOK if kind == "continue-reading" else COMIC_BOOK
                rows = [dict(book, authors=['Orbit Test Reader'], coverVersion='fixture', seriesName='Quiet Orbit')]
            limit = min(10, max(1, int(parse_qs(parsed.query).get('limit', ['10'])[0])))
            return self.send_json({'books': rows[:limit], 'total': len(rows)})
        match = re.fullmatch(r"/api/v1/books/([0-9]+)/thumbnail", parsed.path)
        if match and not BOOK_DIR:
            data = COMIC_IMAGES[int(match[1]) % len(COMIC_IMAGES)]
            self.send_response(200)
            self.send_header('Content-Type', 'image/png')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        match = re.fullmatch(r"/api/v1/books/([2-5])/annotations", parsed.path)
        if match:
            query = parse_qs(parsed.query)
            page = int(query.get('page', ['1'])[0])
            size = int(query.get('pageSize', ['25'])[0])
            file_id = int(query['bookFileId'][0])
            rows = [row for row in ANNOTATIONS.values() if row['bookId'] == int(match[1]) and row['jumpFileId'] == file_id]
            rows.sort(key=lambda row: row['id'], reverse=True)
            return self.send_json({'items': rows[(page-1)*size:page*size], 'total': len(rows), 'page': page})
        match=re.fullmatch(r"/api/v1/books/([1-5])",self.path)
        if match:
            if int(match[1]) == 1:
                book = dict(MANIFEST['book'], authors=[{'name': name} for name in MANIFEST['book']['authors']], files=[{'id': asset['fileId'], 'format': asset['format'], 'filename': f'Part {asset["sequence"]+1}.mp3', 'sizeBytes': asset['sizeBytes']} for asset in ASSETS])
            else:
                book = DOCUMENTS[int(match[1])]
            return self.send_json(dict(book, description='<p>A synthetic journey through a quiet observatory.</p><p>Explore the next chapter.</p><script>throw new Error("blocked")</script>', subtitle='Synthetic verification edition', seriesName='Quiet Orbit', seriesIndex='1', publisher='Orbit Test Press', publishedYear=2026, language='en', pageCount=120, isbn13='9780000000000', rating=4.5, genres=['Fiction', 'Adventure'], tags=['Synthetic'], collections=[{'id':1,'name':'Test shelf'}], readStatus={'status':'reading'}, libraryName='Test library'))
        match=re.fullmatch(r"/api/v1/books/files/(10[1-5])/progress",self.path)
        if match: return self.send_json(READING_PROGRESS.get(int(match[1]),{"percentage":0,"cfi":None,"pageNumber":None,"textUpdatedAt":None}))
        match=re.fullmatch(r"/api/v1/cbz/files/(104|105)/pages(?:/([0-2]))?",self.path)
        if match:
            if match[2] is None: return self.send_json({"pageCount":3})
            return self.send_bytes(COMIC_IMAGES[int(match[2])],"image/png")
        if self.path == "/api/v1/books/files/102/serve": return self.send_bytes(PDF,"application/pdf")
        if self.path == "/api/v1/books/files/103/serve": return self.send_bytes(CBZ,"application/zip")
        if self.path == "/api/v1/books/files/101/serve":
            self.send_response(200)
            self.send_header("Content-Type", "application/epub+zip")
            self.send_header("Content-Length", len(EPUB))
            self.end_headers()
            return self.wfile.write(EPUB)
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
            etag = f'"{asset["etag"]}"'
            if self.headers.get("Range") and self.headers.get("If-Range", etag) == etag:
                start = int(self.headers["Range"].split("=")[1].split("-")[0])
            if start >= len(content):
                return self.send_json({}, 416)
            self.send_response(206 if start else 200)
            self.send_header("Content-Type", "audio/wav")
            self.send_header("ETag", etag)
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", len(content) - start)
            if start:
                self.send_header("Content-Range", f"bytes {start}-{len(content) - 1}/{len(content)}")
            self.end_headers()
            return self.wfile.write(content[start:])
        self.send_json({}, 404)

    def send_bytes(self, payload, mime):
        self.send_response(200)
        self.send_header("Content-Type",mime)
        self.send_header("Content-Length",len(payload))
        self.end_headers()
        self.wfile.write(payload)

    def send_file(self, path, mime):
        if not path.is_file():
            return self.send_json({}, 404)
        size = path.stat().st_size
        start, end = 0, size - 1
        etag = f'"{size}-{path.stat().st_mtime_ns}"'
        ranged = self.headers.get("Range") if self.headers.get("If-Range", etag) == etag else None
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
        self.send_header("ETag", etag)
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
    parser.add_argument("--host", default="127.0.0.1", help="Bind address. Use a trusted LAN interface only for emulator access.")
    parser.add_argument("--port", type=int, default=4890)
    parser.add_argument("--catalog-size", type=int, default=0, help="Additional synthetic books per format for scrolling tests")
    parser.add_argument("--book-dir", type=pathlib.Path, help="Private fixture directory with manifest.json, cover.jpg, and <fileId>.<format> audio files")
    args = parser.parse_args()
    if not 0 <= args.catalog_size <= 10000: parser.error("catalog-size must be between 0 and 10000")
    CATALOG_SIZE = args.catalog_size
    if args.book_dir:
        BOOK_DIR = args.book_dir.resolve()
        MANIFEST = json.loads((BOOK_DIR / "manifest.json").read_text())
        ASSETS = MANIFEST["assets"]
        for asset in ASSETS:
            if not isinstance(asset["fileId"], int) or asset["format"] not in ["m4b", "m4a", "mp3"]:
                parser.error("Invalid fixture asset")
            if (BOOK_DIR / f"{asset['fileId']}.{asset['format']}").stat().st_size != asset["sizeBytes"]:
                parser.error("Fixture audio size does not match its manifest")
    print(f"Fixture listening on {args.host}:{args.port}", flush=True)
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()
