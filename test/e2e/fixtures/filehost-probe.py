#!/usr/bin/env python3
"""Prove Soju FILEHOST accepts and serves an empty audio upload."""

from __future__ import annotations

import argparse
import base64
import select
import socket
import ssl
import time
import urllib.parse
import urllib.request
from pathlib import Path


def recv_lines(sock: ssl.SSLSocket, predicate, label: str) -> tuple[str, list[str]]:
    lines: list[str] = []
    pending = b""
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        ready, _, _ = select.select([sock], [], [], min(0.5, deadline - time.monotonic()))
        if not ready:
            continue
        chunk = sock.recv(65536)
        if not chunk:
            raise AssertionError(f"Soju closed IRC while waiting for {label}")
        pending += chunk
        while b"\n" in pending:
            raw, pending = pending.split(b"\n", 1)
            line = raw.rstrip(b"\r").decode(errors="replace")
            if line.startswith("PING "):
                sock.sendall(("PONG " + line.split(" ", 1)[1] + "\r\n").encode())
            lines.append(line)
            if predicate(line):
                return line, lines
    raise AssertionError(f"timed out waiting for {label}; tail:\n" + "\n".join(lines[-20:]))


def advertised_filehost(host: str, port: int, username: str, password: str, network: str) -> str:
    context = ssl.create_default_context()
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    raw = socket.create_connection((host, port), timeout=10)
    sock = context.wrap_socket(raw, server_hostname=host)

    def send(line: str) -> None:
        sock.sendall((line + "\r\n").encode())

    try:
        send("CAP LS 302")
        send("NICK motdfilehostprobe")
        send("USER motdfilehostprobe 0 * :motd FILEHOST probe")
        recv_lines(sock, lambda line: " CAP " in line and " LS " in line, "CAP LS")
        send("CAP REQ :sasl")
        recv_lines(sock, lambda line: " CAP " in line and " ACK " in line, "CAP ACK")
        send("AUTHENTICATE PLAIN")
        recv_lines(sock, lambda line: "AUTHENTICATE +" in line, "AUTHENTICATE +")
        authcid = f"{username}/{network}"
        payload = base64.b64encode(f"\0{authcid}\0{password}".encode()).decode()
        send("AUTHENTICATE " + payload)
        recv_lines(sock, lambda line: " 903 " in line, "SASL success")
        send("CAP END")
        _, registration = recv_lines(sock, lambda line: " 005 " in line, "ISUPPORT")
        for line in registration:
            for token in line.split():
                if token.startswith("soju.im/FILEHOST="):
                    return token.split("=", 1)[1]
        raise AssertionError("Soju did not advertise soju.im/FILEHOST")
    finally:
        try:
            send("QUIT :FILEHOST probe complete")
        except OSError:
            pass
        sock.close()


def request(opener: urllib.request.OpenerDirector, req: urllib.request.Request, expected: int):
    with opener.open(req, timeout=15) as response:
        if response.status != expected:
            raise AssertionError(f"{req.method} returned HTTP {response.status}, expected {expected}")
        return response.headers, response.read()


def smoke(args: argparse.Namespace) -> None:
    fixture = args.file
    if not fixture.is_file() or fixture.stat().st_size != 0:
        raise AssertionError("fixture must be an existing zero-byte file")

    endpoint = advertised_filehost(args.irc_host, args.irc_port, args.username, args.password, args.network)
    parsed = urllib.parse.urlparse(endpoint)
    if parsed.scheme != "https" or parsed.hostname != args.http_host or parsed.port != args.http_port:
        raise AssertionError("advertised FILEHOST endpoint does not match the isolated stack")
    if parsed.path.rstrip("/") != "/uploads":
        raise AssertionError("advertised FILEHOST endpoint does not end in /uploads")

    context = ssl.create_default_context()
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    opener = urllib.request.build_opener(urllib.request.HTTPSHandler(context=context))

    request(opener, urllib.request.Request(endpoint, method="OPTIONS"), 204)
    credentials = base64.b64encode(f"{args.username}/{args.network}:{args.password}".encode()).decode()
    post = urllib.request.Request(endpoint, data=b"", method="POST")
    post.add_header("Authorization", "Basic " + credentials)
    post.add_header("Content-Type", "audio/ogg")
    post.add_header("Content-Disposition", f'attachment; filename="{fixture.name}"')
    headers, _ = request(opener, post, 201)
    location = headers.get("Location")
    if not location:
        raise AssertionError("FILEHOST response omitted Location")
    uploaded = urllib.parse.urljoin(endpoint, location)

    head_headers, head_body = request(opener, urllib.request.Request(uploaded, method="HEAD"), 200)
    if head_body or head_headers.get("Content-Length") != "0":
        raise AssertionError("HEAD did not describe a zero-byte upload")
    get_headers, get_body = request(opener, urllib.request.Request(uploaded, method="GET"), 200)
    if get_body or get_headers.get_content_type() != "audio/ogg":
        raise AssertionError("GET did not return the empty audio/ogg upload")
    print("FILEHOST_EMPTY_AUDIO_OK")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--irc-host", default="127.0.0.1")
    parser.add_argument("--irc-port", type=int, default=6697)
    parser.add_argument("--http-host", default="127.0.0.1")
    parser.add_argument("--http-port", type=int, default=6696)
    parser.add_argument("--username", default="motd")
    parser.add_argument("--password", default="motdtest")
    parser.add_argument("--network", default="libera")
    parser.add_argument("--file", required=True, type=Path)
    smoke(parser.parse_args())


if __name__ == "__main__":
    main()
