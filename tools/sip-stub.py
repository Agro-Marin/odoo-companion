#!/usr/bin/env python3
"""A stand-in phone system for checking the softphone without an Asterisk.

It listens for SIP over TLS, answers REGISTER (and OPTIONS, SUBSCRIBE, PUBLISH,
NOTIFY) with 200 without a challenge, and on SIGUSR1 rings the last handset that
registered: an INVITE over that handset's own TLS connection, offering
DTLS-SRTP as the softphone requires, an ACK to its answer and a BYE a few
seconds later. Media does not flow -- nothing answers the DTLS handshake -- so it
proves registration, ringing, answering and hanging up, not audio. Every message
is printed.

    tools/sip-stub.py --cert server.pem --key server.key [--host 10.0.2.2]
    kill -USR1 <pid>    # ring

README.md, "Testing the softphone against a lab phone system", makes the
certificate and points an extension at it.
"""

import argparse
import asyncio
import hashlib
import re
import signal
import ssl
import time
import uuid


def log(direction, message, verbose):
    print(f"{time.strftime('%H:%M:%S')} {direction} {message.split(chr(13), 1)[0]}", flush=True)
    if verbose:
        print(message, flush=True)


def parse(raw):
    head, _, body = raw.partition("\r\n\r\n")
    lines = head.split("\r\n")
    headers = [(n.strip(), v.strip()) for n, _, v in (line.partition(":") for line in lines[1:])]
    return lines[0], headers, body


def get(headers, *names):
    wanted = {n.lower() for n in names}
    return [value for name, value in headers if name.lower() in wanted]


# A name-addr (<sip:...>) or a bare addr-spec (sip:...;tag=x): Linphone sends the
# second in To.
def uri_of(value):
    match = re.search(r"<([^>]+)>", value)
    return match.group(1) if match else value.split(";", 1)[0].strip()


class Stub:
    def __init__(self, host, fingerprint, hangup_after, verbose):
        self.host = host
        self.fingerprint = fingerprint
        self.hangup_after = hangup_after
        self.verbose = verbose
        self.registered = {}
        self.dialogs = {}

    def send(self, writer, message):
        log(">>", message, self.verbose)
        writer.write(message.encode())

    def respond(self, writer, headers, code, reason, extra=()):
        to = get(headers, "to", "t")[0]
        if ";tag=" not in to:
            to += f";tag={uuid.uuid4().hex[:8]}"
        lines = [f"SIP/2.0 {code} {reason}"]
        lines += [f"Via: {via}" for via in get(headers, "via", "v")]
        lines += [
            f"From: {get(headers, 'from', 'f')[0]}",
            f"To: {to}",
            f"Call-ID: {get(headers, 'call-id', 'i')[0]}",
            f"CSeq: {get(headers, 'cseq')[0]}",
            *extra,
            "Content-Length: 0",
        ]
        self.send(writer, "\r\n".join(lines) + "\r\n\r\n")

    async def handle(self, reader, writer):
        buffer = b""
        while chunk := await reader.read(65536):
            buffer += chunk
            while b"\r\n\r\n" in buffer:
                head, _, rest = buffer.partition(b"\r\n\r\n")
                length = re.search(rb"(?im)^(?:content-length|l)\s*:\s*(\d+)", head)
                size = int(length.group(1)) if length else 0
                if len(rest) < size:
                    break
                raw = (head + b"\r\n\r\n" + rest[:size]).decode(errors="replace")
                buffer = rest[size:]
                if raw.strip():
                    log("<<", raw, self.verbose)
                    self.on_message(writer, raw)
        writer.close()

    def on_message(self, writer, raw):
        start, headers, _ = parse(raw)
        if start.startswith("SIP/2.0"):
            self.on_response(writer, start, headers)
            return
        method = start.split(" ", 1)[0]
        if method == "REGISTER":
            contact = get(headers, "contact", "m")[0]
            aor = uri_of(get(headers, "to", "t")[0])
            self.registered[aor] = (writer, uri_of(contact))
            self.respond(writer, headers, 200, "OK", [f"Contact: {contact};expires=600"])
        elif method in ("OPTIONS", "SUBSCRIBE", "PUBLISH", "NOTIFY", "INFO", "UPDATE", "BYE", "CANCEL"):
            self.respond(writer, headers, 200, "OK", ["Expires: 600"] if method == "SUBSCRIBE" else [])
        elif method != "ACK":
            self.respond(writer, headers, 501, "Not Implemented")

    def on_response(self, writer, start, headers):
        code = int(start.split(" ")[1])
        cseq = get(headers, "cseq")[0]
        dialog = self.dialogs.get(get(headers, "call-id", "i")[0])
        if not dialog or "INVITE" not in cseq or code < 200:
            return
        contact = get(headers, "contact", "m")
        dialog.update(
            to=get(headers, "to", "t")[0], target=uri_of(contact[0]) if contact else dialog["target"]
        )
        number = int(cseq.split()[0])
        self.in_dialog(writer, dialog, "ACK", number)
        if code == 200:
            asyncio.get_running_loop().call_later(
                self.hangup_after, self.in_dialog, writer, dialog, "BYE", number + 1
            )

    def in_dialog(self, writer, dialog, method, number):
        lines = [
            f"{method} {dialog['target']} SIP/2.0",
            f"Via: SIP/2.0/TLS {self.host}:5061;branch=z9hG4bK{uuid.uuid4().hex[:12]};rport",
            "Max-Forwards: 70",
            f"From: {dialog['from']}",
            f"To: {dialog['to']}",
            f"Call-ID: {dialog['call_id']}",
            f"CSeq: {number} {method}",
            "Content-Length: 0",
        ]
        self.send(writer, "\r\n".join(lines) + "\r\n\r\n")

    def ring(self):
        if not self.registered:
            print("ring: no handset has registered", flush=True)
            return
        aor, (writer, uri) = list(self.registered.items())[-1]
        call_id = uuid.uuid4().hex
        caller = f'"Lab caller" <sip:5550100@{self.host}>;tag={uuid.uuid4().hex[:8]}'
        self.dialogs[call_id] = {"call_id": call_id, "from": caller, "to": f"<{aor}>", "target": uri}
        sdp = (
            "\r\n".join(
                [
                    "v=0",
                    f"o=stub 1 1 IN IP4 {self.host}",
                    "s=lab",
                    f"c=IN IP4 {self.host}",
                    "t=0 0",
                    "m=audio 40000 UDP/TLS/RTP/SAVPF 0 8 101",
                    "a=rtcp-mux",
                    "a=setup:actpass",
                    f"a=fingerprint:sha-256 {self.fingerprint}",
                    "a=rtpmap:0 PCMU/8000",
                    "a=rtpmap:8 PCMA/8000",
                    "a=rtpmap:101 telephone-event/8000",
                    "a=sendrecv",
                ]
            )
            + "\r\n"
        )
        lines = [
            f"INVITE {uri} SIP/2.0",
            f"Via: SIP/2.0/TLS {self.host}:5061;branch=z9hG4bK{uuid.uuid4().hex[:12]};rport",
            "Max-Forwards: 70",
            f"From: {caller}",
            f"To: <{aor}>",
            f"Call-ID: {call_id}",
            "CSeq: 1 INVITE",
            f"Contact: <sip:5550100@{self.host}:5061;transport=tls>",
            "Allow: INVITE, ACK, CANCEL, BYE, OPTIONS",
            "Content-Type: application/sdp",
            f"Content-Length: {len(sdp)}",
        ]
        self.send(writer, "\r\n".join(lines) + "\r\n\r\n" + sdp)


async def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n", 1)[0])
    parser.add_argument("--cert", required=True, help="server certificate (PEM)")
    parser.add_argument("--key", required=True, help="its private key (PEM)")
    parser.add_argument("--host", default="10.0.2.2", help="this host as the handset reaches it")
    parser.add_argument("--port", type=int, default=5061)
    parser.add_argument("--hangup-after", type=float, default=8.0, help="seconds")
    parser.add_argument("--verbose", action="store_true", help="print whole messages")
    args = parser.parse_args()

    context = ssl.create_default_context(ssl.Purpose.CLIENT_AUTH)
    context.load_cert_chain(args.cert, args.key)
    with open(args.cert, "rb") as pem:
        der = ssl.PEM_cert_to_DER_cert(pem.read().decode())
    digest = hashlib.sha256(der).hexdigest().upper()
    fingerprint = ":".join(digest[i : i + 2] for i in range(0, len(digest), 2))

    stub = Stub(args.host, fingerprint, args.hangup_after, args.verbose)
    asyncio.get_running_loop().add_signal_handler(signal.SIGUSR1, stub.ring)
    server = await asyncio.start_server(stub.handle, "0.0.0.0", args.port, ssl=context)
    print(f"listening on {args.port}; kill -USR1 to ring", flush=True)
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
