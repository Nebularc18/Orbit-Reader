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
import secrets
import hashlib
import base64
import html
import uuid
from email.parser import BytesParser
from email.policy import default as email_policy
import time
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
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">orbit-synthetic-ebook</dc:identifier><dc:title>A Quiet Orbit</dc:title><dc:language>en</dc:language><dc:creator>Orbit Test Reader</dc:creator><meta property="dcterms:modified">2026-10-20T00:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="one" href="one.xhtml" media-type="application/xhtml+xml" media-overlay="smil"/><item id="two" href="two.xhtml" media-type="application/xhtml+xml"/><item id="image" href="orbit.svg" media-type="image/svg+xml"/><item id="smil" href="one.smil" media-type="application/smil+xml"/><item id="narration" href="narration.wav" media-type="audio/wav"/></manifest><spine><itemref idref="one"/><itemref idref="two"/></spine></package>''')
        archive.writestr("EPUB/one.smil", '<smil xmlns="http://www.w3.org/ns/SMIL" version="3.0"><body><seq><par><text src="one.xhtml#p0"/><audio src="narration.wav" clipBegin="0s" clipEnd="10s"/></par><par><text src="one.xhtml#p1"/><audio src="narration.wav" clipBegin="10s" clipEnd="20s"/></par></seq></body></smil>')
        archive.writestr("EPUB/narration.wav", audio(440))
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
OIDC_FIXTURE = False
OIDC_STATES = {}
OIDC_CODES = {}
BOOK_REQUESTS = {}
CATALOG_SIZE = 0
READING_PROGRESS={}


EPUB = ebook()
EBOOK_FILE = {"id": 101, "format": "epub", "filename": "A Quiet Orbit.epub", "role": "main", "sizeBytes": len(EPUB), "fileHash": "orbit-epub-v2-readalong"}
EBOOK = {"id": 2, "title": "A Quiet Orbit · Reading Test", "authors": [{"name": "Orbit Test Reader"}], "files": [EBOOK_FILE]}

PDF_BOOK={"id":3,"title":"Orbit PDF Test","authors":[{"name":"Orbit Test Reader"}],"files":[{"id":102,"format":"pdf","filename":"Orbit.pdf","sizeBytes":len(PDF),"fileHash":"pdf-v1"}]}
COMIC_BOOK={"id":4,"title":"Orbit Comic Test","authors":[{"name":"Orbit Test Reader"}],"files":[{"id":103,"format":"cbz","filename":"Orbit.cbz","sizeBytes":len(CBZ),"fileHash":"cbz-v1"}]}
CONVERTED_BOOK={"id":5,"title":"Orbit Converted Comic Test","authors":[{"name":"Orbit Test Reader"}],"files":[{"id":104,"format":"cbr","filename":"Orbit.cbr","fileHash":"cbr-v1"},{"id":105,"format":"cb7","filename":"Orbit.cb7","fileHash":"cb7-v1"}]}
FB2 = ('<?xml version="1.0" encoding="UTF-8"?><FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><description><title-info><genre>sf</genre><author><first-name>Orbit</first-name><last-name>Reader</last-name></author><book-title>Orbit FB2 Test</book-title><lang>en</lang></title-info></description><body><section><title><p>Departure</p></title>' + ''.join('<p>Passage %s. A quiet moon crossed the observatory. This is a synthetic FB2 reading test.</p>' % i for i in range(100)) + '</section></body></FictionBook>').encode()
FB2_BOOK={"id":7,"title":"Orbit FB2 Test","authors":[{"name":"Orbit Test Reader"}],"files":[{"id":106,"format":"fb2","filename":"Orbit.fb2","sizeBytes":len(FB2),"fileHash":"fb2-v1"}]}
DOCUMENTS={2:EBOOK,3:PDF_BOOK,4:COMIC_BOOK,5:CONVERTED_BOOK,7:FB2_BOOK}
UPLOADS = {}
UPLOAD_DELAY_SECONDS = 0.0
METADATA = {}
EXTRA_EBOOKS = {}
UPLOAD_CAPS = {"maxFileSizeBytes": 67108864, "chunkSizeBytes": 65536, "supportedFormats": ["epub", "pdf", "fb2", "mobi"], "canUploadToLibrary": True, "libraries": [{"id":1,"name":"Synthetic library","allowedFormats":["epub","pdf","fb2","mobi"],"folders":[{"id":1,"name":"Books"}],"organizationMode":"folder"}]}

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
STATE = {}
OPERATION = {}
BOOK_STATUS = {}
BOOK_RATING = {}
BOOK_COLLECTIONS = {}
LOCK = threading.Lock()
BOOK_DIR = None


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def send_json(self, value, status=200, cookie=None):
        payload = b"" if status == 204 else json.dumps(value).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        if cookie: self.send_header("Set-Cookie", cookie)
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
        chunk = re.fullmatch(r'/api/v1/uploads/([0-9a-f-]+)/chunks', self.path)
        if chunk:
            if not self.authorized(): return
            length = int(self.headers.get('Content-Length', '0'))
            if not 0 < length <= 4198400: return self.send_json({}, 413)
            body = self.rfile.read(length)
            message = BytesParser(policy=email_policy).parsebytes(('Content-Type: '+self.headers.get('Content-Type','')+'\r\nMIME-Version: 1.0\r\n\r\n').encode()+body)
            parts = list(message.iter_parts())
            if len(parts) != 1 or parts[0].get_param('name', header='content-disposition') != 'file': return self.send_json({},400)
            payload = parts[0].get_payload(decode=True)
            with LOCK:
                row = UPLOADS.get(chunk[1])
                if not row: return self.send_json({},404)
                if row['status'] != 'receiving' or self.headers.get('Upload-Offset') != str(row['receivedBytes']): return self.send_json({},409)
                if len(payload) > row['chunkSizeBytes'] or row['receivedBytes'] + len(payload) > row['sizeBytes'] or self.headers.get('Upload-Checksum') != hashlib.sha256(payload).hexdigest(): return self.send_json({},400)
                row['receivedBytes'] += len(payload)
                reply = dict(row)
            if UPLOAD_DELAY_SECONDS: time.sleep(UPLOAD_DELAY_SECONDS)
            return self.send_json(reply)
        data = self.body()
        if self.path == '/api/v1/auth/magic-links/login':
            if data != {'token':'fixture-magic'}: return self.send_json({},401)
            return self.send_json({'accessToken':'fixture-access','user':{'id':1,'name':'Reader','username':'reader','isSuperuser':False,'permissions':['library_download','library_edit_metadata','library_upload','book_request_access']}}, cookie='refresh_token='+'b'*64+'; HttpOnly; Path=/api/v1/auth; SameSite=Lax')
        if self.path == '/api/v1/uploads':
            if not self.authorized(): return
            if set(data) != {'filename','sizeBytes','idempotencyKey','target'} or data['target'].get('libraryId') != 1 or data['target'].get('kind') != 'library' or not 0 < data['sizeBytes'] <= UPLOAD_CAPS['maxFileSizeBytes']: return self.send_json({},400)
            with LOCK:
                existing = next((row for row in UPLOADS.values() if row['idempotencyKey'] == data['idempotencyKey']),None)
                if existing: return self.send_json(existing)
                row = dict(data, id=str(uuid.uuid4()), receivedBytes=0, chunkSizeBytes=65536, status='receiving', bookId=None)
                UPLOADS[row['id']] = row
            return self.send_json(row,201)
        complete = re.fullmatch(r'/api/v1/uploads/([0-9a-f-]+)/complete', self.path)
        if complete:
            if not self.authorized(): return
            with LOCK:
                row = UPLOADS.get(complete[1])
                if not row: return self.send_json({},404)
                if data or row['receivedBytes'] != row['sizeBytes']: return self.send_json({},400)
                row.update(status='completed',bookId=7)
                return self.send_json(row)
        if self.path == "/api/v1/auth/oidc/fixture/state" and OIDC_FIXTURE:
            state = secrets.token_urlsafe(32)
            OIDC_STATES[state] = time.time()
            endpoint = f"http://{self.server.server_address[0]}:{self.server.server_port}/fixture/authorize"
            return self.send_json({"state": state, "authorizationEndpoint": endpoint})
        if self.path == "/api/v1/auth/oidc/callback" and OIDC_FIXTURE:
            expected = {"code", "codeVerifier", "redirectUri", "nonce", "state", "clientKind", "deviceLabel"}
            saved = OIDC_CODES.pop(data.get("code", ""), None)
            if set(data) != expected or not saved or data.get("clientKind") != "native": return self.send_json({}, 400)
            challenge = base64.urlsafe_b64encode(hashlib.sha256(data['codeVerifier'].encode()).digest()).decode().rstrip('=')
            if data['state'] != saved['state'] or data['nonce'] != saved['nonce'] or challenge != saved['challenge'] or data['redirectUri'] != 'bookorbit://oauth2-callback' or time.time() - saved['created'] > 600:
                return self.send_json({}, 401)
            return self.send_json({"mode": "login", "accessToken": "fixture-access", "refreshToken": "a" * 64, "user": {"id": 1, "name": "Reader", "username": "reader", "isSuperuser": False, "permissions": ["library_download", "library_edit_metadata", "library_upload", "book_request_access"]}})
        if self.path == "/api/v1/auth/login":
            if data.get("username") != "reader" or data.get("password") != "reader" or data.get("clientKind") != "native":
                return self.send_json({}, 401)
            return self.send_json({"accessToken": "fixture-access", "refreshToken": "a" * 64, "user": {"id": 1, "name": "Reader", "username": "reader", "isSuperuser": False, "permissions": ["library_download", "library_edit_metadata", "library_upload", "book_request_access"]}})
        if self.path == "/api/v1/auth/refresh":
            if not data and self.headers.get('Cookie') == 'refresh_token='+'b'*64:
                return self.send_json({'accessToken':'fixture-access'}, cookie='refresh_token='+'b'*64+'; HttpOnly; Path=/api/v1/auth; SameSite=Lax')
            return self.send_json({"accessToken": "fixture-access", "refreshToken": "a" * 64})
        if self.path == "/api/v1/auth/logout":
            return self.send_json({})
        if self.path == '/api/v1/books/bulk-set-rating':
            if not self.authorized(): return
            if set(data) != {'bookIds','rating'} or not isinstance(data['bookIds'],list) or (data['rating'] is not None and data['rating'] not in [1,2,3,4,5]): return self.send_json({},400)
            for book_id in data['bookIds']: BOOK_RATING[book_id] = data['rating']
            return self.send_json({},204)
        match = re.fullmatch(r'/api/v1/collections/(\d+)/books',self.path)
        if match:
            if not self.authorized(): return
            if set(data) != {'bookIds'}: return self.send_json({},400)
            for book_id in data['bookIds']: BOOK_COLLECTIONS.setdefault(book_id,{1}).add(int(match[1]))
            return self.send_json({})
        if not self.authorized():
            return
        if self.path == "/api/v1/book-requests":
            if set(data) - {'title', 'authors', 'mediaKind', 'note'} or not isinstance(data.get('title'), str) or not data['title'].strip() or len(data['title']) > 500 or data.get('mediaKind') not in ['ebook', 'audiobook'] or len(data.get('note', '')) > 2000:
                return self.send_json({}, 400)
            with LOCK:
                existing = next((row for row in BOOK_REQUESTS.values() if row['title'].casefold() == data['title'].strip().casefold() and row['mediaKind'] == data['mediaKind'] and row['status'] not in ['cancelled', 'rejected', 'available']), None)
                if existing: return self.send_json({'request': existing, 'subscribed': True})
                request_id = max(BOOK_REQUESTS.keys(), default=0) + 1
                row = {'id': request_id, 'userId': 1, 'title': data['title'].strip(), 'authors': data.get('authors', []), 'mediaKind': data['mediaKind'], 'status': 'pending', 'note': data.get('note') or None, 'decisionNote': None, 'statusReason': None, 'createdAt': datetime.datetime.now(datetime.timezone.utc).isoformat()}
                BOOK_REQUESTS[request_id] = row
            return self.send_json({'request': row, 'subscribed': False}, 201)
        request_match = re.fullmatch(r'/api/v1/book-requests/(\d+)/cancel', self.path)
        if request_match:
            with LOCK:
                row = BOOK_REQUESTS.get(int(request_match[1]))
                if not row: return self.send_json({}, 404)
                if row['status'] in ['cancelled', 'rejected', 'available']: return self.send_json({}, 400)
                row['status'] = 'cancelled'
            return self.send_json(row)
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
                books.append(dict(MANIFEST["book"], id=6, title="A Quiet Orbit · Next Audio Test"))
            if not formats or "epub" in formats or "kepub" in formats:
                books.append({"id": 2, "title": EBOOK["title"], "authors": ["Orbit Test Reader"]})
            for entry in [book for book in DOCUMENTS.values() if book["id"] != 2]:
                if not formats or any(file['format'] in formats for file in entry['files']):
                    books.append({"id":entry['id'],"title":entry['title'],"authors":["Orbit Test Reader"]})
            if any(value in formats for value in ["mp3", "m4b", "m4a"]):
                books.extend({'id': 1000 + i, 'title': f'Orbit Audio {i:04}', 'authors': ['Synthetic test audio']} for i in range(CATALOG_SIZE))
            if 'epub' in formats:
                books.extend({'id': 2000 + i, 'title': f'Orbit Ebook {i:04}', 'authors': ['Orbit Test Reader']} for i in range(CATALOG_SIZE))
            query = data.get("q", "").casefold()
            items = [{"id": book["id"], "title": book["title"], "authors": book["authors"], "coverVersion": "fixture"} for book in books if query in (book["title"] + " " + " ".join(book["authors"])).casefold()]
            status_values = next((rule.get('value', []) for rule in data.get('filter', {}).get('rules', []) if rule.get('field') == 'readStatus'), None)
            if status_values is not None:
                statuses = ['unread', 'reading', 'read', 'want_to_read', 'on_hold', 'rereading', 'skimmed', 'abandoned']
                items = [row for row in items if BOOK_STATUS.get(row['id'], statuses[row['id'] % len(statuses)]) in status_values]
            specs = data.get('sort', [{'field': 'title', 'dir': 'asc'}])
            allowed_sorts = {'title', 'author', 'addedAt', 'series', 'seriesIndex', 'publishedDate', 'rating'}
            if any(spec.get('field') not in allowed_sorts or spec.get('dir') not in {'asc', 'desc'} for spec in specs):
                return self.send_json({}, 400)
            def sort_value(row, field):
                return {'title': row['title'].casefold(), 'author': (row['authors'] or [''])[0].casefold(),
                        'addedAt': row['id'], 'series': row['id'] % 3, 'seriesIndex': row['id'],
                        'publishedDate': row['id'] % 25, 'rating': row['id'] % 6}[field]
            items.sort(key=lambda row: row['id'])
            for spec in reversed(specs):
                items.sort(key=lambda row: sort_value(row, spec['field']), reverse=spec['dir'] == 'desc')
            page, size = data['pagination']['page'], data['pagination']['size']
            return self.send_json({"items": items[page*size:(page+1)*size], "total": len(items), "page": page, "size": size})
        match=re.fullmatch(r"/api/v1/books/files/(10[1-9])/progress",self.path)
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

    def do_PATCH(self):
        metadata = re.fullmatch(r'/api/v1/books/(\d+)/metadata', self.path)
        if metadata:
            if not self.authorized(): return
            data = self.body()
            allowed = {'title','subtitle','description','publisher','publishedDate','publishedYear','language','pageCount','seriesName','seriesIndex','seriesMemberships','isbn10','isbn13','rating','communityRatings','authors','genres','tags','googleBooksId','goodreadsId','amazonId','hardcoverId','hardcoverEditionId','openLibraryId','itunesId','audibleId','librofmId','koboId','audioMetadata','comicvineId','ranobedbId','lubimyczytacId','aladinId','comicMetadata','customMetadata'}
            if set(data) - allowed: return self.send_json({},400)
            with LOCK:
                current = METADATA.setdefault(int(metadata[1]),{})
                for key,value in data.items():
                    if key in ['audioMetadata','comicMetadata']: current.setdefault(key,{}).update(value)
                    else: current[key] = value
            return self.send_json({})
        match = re.fullmatch(r'/api/v1/books/(\d+)/status',self.path)
        if match:
            if not self.authorized(): return
            data=self.body()
            if set(data)!={'status'} or data['status'] not in ['unread','want_to_read','reading','on_hold','rereading','read','skimmed','abandoned']: return self.send_json({},400)
            BOOK_STATUS[int(match[1])] = data['status']
            return self.send_json({'status':data['status']})
        self.annotation_mutation()
    def do_DELETE(self):
        match = re.fullmatch(r'/api/v1/collections/(\d+)/books',self.path)
        if match:
            if not self.authorized(): return
            data=self.body()
            if set(data)!={'bookIds'}: return self.send_json({},400)
            for book_id in data['bookIds']: BOOK_COLLECTIONS.setdefault(book_id,{1}).discard(int(match[1]))
            return self.send_json({})
        self.annotation_mutation(delete=True)

    def do_GET(self):
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)
        if parsed.path == '/fixture/upload-sample.epub': return self.send_bytes(EPUB, 'application/epub+zip')
        if parsed.path == '/api/v1/auth/login-options':
            providers = [{'slug': 'fixture', 'displayName': 'Synthetic provider', 'enabled': True, 'clientId': 'orbit-fixture', 'scopes': 'openid profile'}] if OIDC_FIXTURE else []
            return self.send_json({'passwordLoginEnabled': True, 'allowRegistration': False, 'oidcProviders': providers})
        if parsed.path == '/fixture/authorize' and OIDC_FIXTURE:
            params = {key: values[0] for key, values in query.items() if len(values) == 1}
            state = params.get('state', '')
            started = OIDC_STATES.pop(state, None)
            if not started or time.time() - started > 600 or params.get('client_id') != 'orbit-fixture' or params.get('response_type') != 'code' or params.get('code_challenge_method') != 'S256' or params.get('redirect_uri') != 'bookorbit://oauth2-callback':
                return self.send_json({}, 400)
            code = secrets.token_urlsafe(32)
            OIDC_CODES[code] = {'state': state, 'nonce': params.get('nonce'), 'challenge': params.get('code_challenge'), 'created': started}
            target = html.escape(f"bookorbit://oauth2-callback?code={code}&state={state}", quote=True)
            return self.send_bytes(f'<html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head><body><h1>Synthetic provider</h1><p>Test sign-in only. No real identity provider is connected.</p><a href="{target}">Continue to Orbit Reader</a></body></html>'.encode(), 'text/html')
        if not self.authorized():
            return
        if parsed.path == '/api/v1/uploads/capabilities': return self.send_json(UPLOAD_CAPS)
        upload = re.fullmatch(r'/api/v1/uploads/([0-9a-f-]+)',parsed.path)
        if upload: return self.send_json(UPLOADS[upload[1]]) if upload[1] in UPLOADS else self.send_json({},404)
        if parsed.path == '/api/v1/book-requests':
            page = max(1, int(query.get('page', ['1'])[0])); limit = min(100, max(1, int(query.get('limit', ['30'])[0])))
            with LOCK:
                rows = [dict(row) for row in sorted(BOOK_REQUESTS.values(), key=lambda row: -row['id']) if (not query.get('status') or row['status'] == query['status'][0]) and (not query.get('mediaKind') or row['mediaKind'] == query['mediaKind'][0])]
            return self.send_json({'items': rows[(page-1)*limit:page*limit], 'total': len(rows)})
        match = re.fullmatch(r"/api/v1/(series|authors|collections|smart-scopes)", parsed.path)
        if match:
            kind = match[1]
            rows = [{'id': i + 1, 'name': ('Quiet Orbit' if kind == 'series' and i == 0 else f'{kind.title()} {i + 1:03}'), 'bookCount': 5, 'readCount': 2 if kind == 'series' else None, 'coverBookIds': [2, 3] if kind == 'series' else [], 'coverBookId': 2 if kind == 'authors' else None, 'mediaType': 'books', 'isOwner': True} for i in range(max(3, CATALOG_SIZE))]
            if kind in ['collections', 'smart-scopes']:
                return self.send_json(rows)
            q = query.get('q', [''])[0].casefold()
            rows = [row for row in rows if q in row['name'].casefold()]
            rows.sort(key=lambda row: row['name'], reverse=query.get('order', ['asc'])[0] == 'desc')
            page, size = int(query.get('page', ['0'])[0]), int(query.get('size', ['30'])[0])
            return self.send_json({'items': rows[page*size:(page+1)*size], 'total': len(rows), 'page': page, 'size': size})
        match = re.fullmatch(r'/api/v1/series/(\d+)/books/(\d+)/next',parsed.path)
        if match: return self.send_json({'next': {'bookId':6,'fileId':1,'format':'mp3','title':'A Quiet Orbit · Next Audio Test','seriesIndex':'2'} if int(match[2])==1 else None})
        match = re.fullmatch(r"/api/v1/(series|authors|collections|smart-scopes)/(\d+)/books", parsed.path)
        if match:
            rows = [dict(MANIFEST['book'], files=[{'id': 1, 'format': 'mp3'}], coverVersion='fixture', readStatus={'status': 'read'})]
            rows.extend(dict(book, authors=['Orbit Test Reader'], coverVersion='fixture', readStatus={'status': 'unread'}) for book in [EBOOK, PDF_BOOK, COMIC_BOOK, CONVERTED_BOOK])
            page, size = int(query.get('page', ['0'])[0]), int(query.get('size', ['30'])[0])
            return self.send_json({'items': rows[page*size:(page+1)*size], 'total': len(rows), 'page': page, 'size': size})
        if parsed.path == '/api/v1/user-statistics/activity-overview':
            today = datetime.date.today()
            days = [{'day': (today - datetime.timedelta(days=27-i)).isoformat(), 'totalSeconds': (i % 5)*1200, 'readingSeconds': (i % 5)*400, 'listeningSeconds': (i % 5)*800, 'sessionsCount': i % 5} for i in range(28)]
            return self.send_json({'timezone': 'UTC', 'snapshot': {'today': days[-1], 'lastSevenDays': {'totalSeconds': 14400, 'readingSeconds': 4800, 'listeningSeconds': 9600}, 'previousSevenDays': {'totalSeconds': 10800, 'readingSeconds': 3600, 'listeningSeconds': 7200}, 'currentStreak': 3, 'longestStreak': 7, 'completedBooksYtd': 4}, 'dailyActivity': {'days': days}, 'goal': {'year': today.year, 'goalBooks': 12, 'completedBooks': 4, 'projectedBooks': 6, 'status': 'behind'}, 'completion': {'months': [{'year': today.year, 'month': m, 'count': m % 3} for m in range(1, today.month+1)]}})
        if parsed.path == '/api/v1/user-statistics/reading-source-distribution':
            return self.send_json({'totalSeconds': 28800, 'slices': [{'bucket': 'bookorbit', 'readingSeconds': 7200}, {'bucket': 'android', 'readingSeconds': 21600}]})
        if parsed.path == '/api/v1/user-statistics/session-timeline':
            year, week = int(query.get('year', ['2026'])[0]), int(query.get('week', ['1'])[0])
            start = datetime.date.fromisocalendar(year, week, 1)
            rows = [{'sessionId': i+1, 'bookId': 1 if i%2 else 2, 'bookTitle': MANIFEST['book']['title'] if i%2 else EBOOK['title'], 'bookFormat': 'mp3' if i%2 else 'epub', 'bookSource': 'android', 'startedAt': f'{start.isoformat()}T12:00:00Z', 'endedAt': f'{start.isoformat()}T12:20:00Z', 'durationSeconds': 1200} for i in range(5)]
            return self.send_json({'year': year, 'week': week, 'weekStart': start.isoformat(), 'weekEnd': (start+datetime.timedelta(days=6)).isoformat(), 'items': rows})
        if parsed.path == "/api/v1/dashboard/widgets/reading-goal":
            return self.send_json({'goalBooks': 12, 'completedBooks': 4, 'year': 2026})
        if parsed.path == "/api/v1/dashboard/widgets/reading-streak":
            return self.send_json({'currentStreak': 3, 'longestStreak': 7, 'lastSevenDays': [False, True, False, False, True, True, True]})
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
        match=re.fullmatch(r"/api/v1/books/(\d+)",self.path)
        if match:
            if int(match[1]) in [1,6]:
                book = dict(MANIFEST['book'], authors=[{'name': name} for name in MANIFEST['book']['authors']], files=[{'id': asset['fileId'], 'format': asset['format'], 'filename': f'Part {asset["sequence"]+1}.mp3', 'sizeBytes': asset['sizeBytes']} for asset in ASSETS])
            else:
                if int(match[1]) not in DOCUMENTS: return self.send_json({},404)
                book = DOCUMENTS[int(match[1])]
            book = dict(book, id=int(match[1]), title='A Quiet Orbit · Next Audio Test' if int(match[1])==6 else book['title'])
            result = dict(book, seriesId=1, description='<p>A synthetic journey through a quiet observatory.</p><p>Explore the next chapter.</p><script>throw new Error("blocked")</script>', subtitle='Synthetic verification edition', seriesName='Quiet Orbit', seriesIndex='1', publisher='Orbit Test Press', publishedYear=2026, language='en', pageCount=120, isbn13='9780000000000', rating=BOOK_RATING.get(int(match[1]),4.5), genres=['Fiction', 'Adventure'], tags=['Synthetic'], collections=[{'id':i,'name':f'Collections {i:03}'} for i in sorted(BOOK_COLLECTIONS.get(int(match[1]),{1}))], readStatus={'status':BOOK_STATUS.get(int(match[1]),'reading')}, libraryName='Test library')
            result.update(METADATA.get(int(match[1]),{}))
            if result.get('authors') and isinstance(result['authors'][0],str): result['authors'] = [{'name': name} for name in result['authors']]
            return self.send_json(result)
        match=re.fullmatch(r"/api/v1/books/files/(10[1-9])/progress",self.path)
        if match: return self.send_json(READING_PROGRESS.get(int(match[1]),{"percentage":0,"cfi":None,"pageNumber":None,"textUpdatedAt":None}))
        match=re.fullmatch(r"/api/v1/cbz/files/(104|105)/pages(?:/([0-2]))?",self.path)
        if match:
            if match[2] is None: return self.send_json({"pageCount":3})
            return self.send_bytes(COMIC_IMAGES[int(match[2])],"image/png")
        extra = re.fullmatch(r'/api/v1/books/files/(\d+)/serve', self.path)
        if extra and int(extra[1]) in EXTRA_EBOOKS: return self.send_file(EXTRA_EBOOKS[int(extra[1])], 'application/octet-stream')
        if self.path == "/api/v1/books/files/106/serve": return self.send_bytes(FB2,"application/x-fictionbook+xml")
        if self.path == "/api/v1/books/files/102/serve": return self.send_bytes(PDF,"application/pdf")
        if self.path == "/api/v1/books/files/103/serve": return self.send_bytes(CBZ,"application/zip")
        if self.path == "/api/v1/books/files/101/serve":
            self.send_response(200)
            self.send_header("Content-Type", "application/epub+zip")
            self.send_header("Content-Length", len(EPUB))
            self.end_headers()
            return self.wfile.write(EPUB)
        match = re.fullmatch(r'/api/v1/audiobooks/(\d+)/(manifest|playback-state)',self.path)
        if match and int(match[1]) in [MANIFEST['book']['id'],6]:
            if match[2]=='playback-state': return self.send_json(STATE.get(int(match[1])))
            result = dict(MANIFEST,book=dict(MANIFEST['book'],id=int(match[1]),title='A Quiet Orbit · Next Audio Test' if int(match[1])==6 else MANIFEST['book']['title']))
            return self.send_json(result)
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
        if not self.authorized(): return
        match=re.fullmatch(r'/api/v1/audiobooks/(\d+)/playback-state',self.path)
        if not match or int(match[1]) not in [MANIFEST['book']['id'],6]: return self.send_json({},404)
        book_id=int(match[1]); data=self.body()
        with LOCK:
            state=STATE.get(book_id)
            if data['operationId']==OPERATION.get(book_id): return self.send_json(state)
            if data['baseRevision']!=(state['revision'] if state else 0): return self.send_json({},409)
            state={key:data[key] for key in ['assetId','positionMs','capturedAt','manifestRevision']}
            state['revision']=data['baseRevision']+1
            STATE[book_id]=state; OPERATION[book_id]=data['operationId']
            self.send_json(state)

    def log_message(self, format_string, *args):
        print(f"fixture {self.command} {urlparse(self.path).path} {args[1] if len(args) > 1 else ''}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1", help="Bind address. Use a trusted LAN interface only for emulator access.")
    parser.add_argument("--port", type=int, default=4890)
    parser.add_argument("--catalog-size", type=int, default=0, help="Additional synthetic books per format for scrolling tests")
    parser.add_argument("--upload-delay-seconds", type=float, default=0, help="Delay each synthetic chunk response to test pause/resume (0 to 5 seconds)")
    parser.add_argument("--oidc", action="store_true", help="Enable a synthetic browser-based OIDC flow for client tests")
    parser.add_argument("--requests-count", type=int, default=0, help="Seed synthetic personal requests for paging tests")
    parser.add_argument("--ebook-file", type=pathlib.Path, action="append", default=[], help="Additional local DRM-free ebook fixture outside the repository (up to three)")
    parser.add_argument("--book-dir", type=pathlib.Path, help="Private fixture directory with manifest.json, cover.jpg, and <fileId>.<format> audio files")
    args = parser.parse_args()
    if not 0 <= args.catalog_size <= 10000: parser.error("catalog-size must be between 0 and 10000")
    if not 0 <= args.requests_count <= 10000: parser.error("requests-count must be between 0 and 10000")
    if len(args.ebook_file) > 3: parser.error("At most three extra ebook fixtures are supported")
    for index, source in enumerate(args.ebook_file):
        source = source.resolve()
        format = source.suffix.lower().lstrip('.')
        if format not in ['epub','kepub','pdf','fb2','mobi','cbz'] or not source.is_file() or not 0 < source.stat().st_size <= 512 * 1024 * 1024: parser.error("Choose a supported ebook fixture below 512 MB")
        book_id, file_id = 8 + index, 107 + index
        EXTRA_EBOOKS[file_id] = source
        DOCUMENTS[book_id] = {'id':book_id,'title':source.stem,'authors':[{'name':'Local verification fixture'}],'files':[{'id':file_id,'format':format,'filename':source.name,'sizeBytes':source.stat().st_size,'fileHash':f'local-{source.stat().st_size}-{source.stat().st_mtime_ns}'}]}
    if not 0 <= args.upload_delay_seconds <= 5: parser.error("upload-delay-seconds must be between 0 and 5")
    UPLOAD_DELAY_SECONDS = args.upload_delay_seconds
    OIDC_FIXTURE = args.oidc
    for i in range(args.requests_count):
        BOOK_REQUESTS[i+1] = {'id': i+1, 'userId': 1, 'title': f'Synthetic request {i+1:03}', 'authors': ['Orbit Test Reader'], 'mediaKind': 'ebook' if i % 2 else 'audiobook', 'status': 'pending' if i % 3 else 'available', 'note': None, 'decisionNote': None, 'statusReason': None}
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
