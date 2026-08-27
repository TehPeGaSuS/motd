---
title: Audio messages
layout: spec
work-in-progress: true
copyrights:
  -
    name: "Trevor Arjeski"
    email: "tmarjeski@gmail.com"
    period: "2026"
---

# Audio messages

This is a work-in-progress specification.

## Motivation

IRC has no standard audio-message type. Sending an HTTPS URL works everywhere,
but does not distinguish a short voice note from other audio or carry duration
without fetching the resource.

This specification defines a readable `PRIVMSG` format with optional MOTD
metadata. Audio bytes remain outside IRC and can be uploaded through
`soju.im/FILEHOST` or any other HTTPS file host.

## Architecture

An audio message is a normal `PRIVMSG` whose body has this canonical form:

    [voice 0:14 audio/ogg] https://files.example/voice.ogg

Encrypted file-host payloads add the `encrypted` flag:

    [voice encrypted 1:02 audio/ogg] https://files.example/voice.motdvoice#motd-key=...

An exact expiry may be included as an RFC 3339 timestamp:

    [voice 0:14 audio/ogg expires=2026-08-28T12:00:00Z] https://files.example/voice.ogg

Senders MUST include the readable body. Clients without this extension can
still display and copy it. Metadata tags, URL fragments, or filename extensions
MUST NOT be the only representation.

### Message body

The format is described by this abbreviated ABNF:

    audio-message = %s"[voice" [ SP %s"encrypted" ] SP duration SP media-type
                    [ SP %s"expires=" date-time ] %s"]" SP URI
    duration      = minutes %s":" seconds / hours %s":" minutes2 %s":" seconds
    minutes       = 1*DIGIT
    hours         = 1*DIGIT
    minutes2      = 2DIGIT
    seconds       = 2DIGIT

`SP`, `DIGIT`, and `URI` are defined by RFC 5234 and RFC 3986. `date-time` is
defined by RFC 3339. `media-type` uses the RFC 6838 type/subtype form.

Senders MUST:

- use `M:SS` below one hour and `H:MM:SS` at or above one hour;
- keep seconds and the non-leading minutes field in the range 00 through 59;
- use an `audio/*` media type describing the cleartext payload;
- use the `encrypted` flag only with the encrypted envelope below;
- omit `expires` unless the exact expiry is known; and
- send one URL without spaces.

Receivers MUST reject invalid or overflowing durations and malformed media
types as audio metadata, while continuing to render the message as ordinary
text. Receivers MAY recognize older MOTD messages containing a relative or
opaque `expires` value, but MUST NOT emit that legacy form.

The URL SHOULD use HTTPS. An encrypted audio URL MUST use HTTPS. File
content encryption does not protect HTTP routing metadata, and URL fragments
are excluded from the HTTP request.

### Client-only tag

When `message-tags` is negotiated and `CLIENTTAGDENY` permits it, senders SHOULD
attach this marker to the `PRIVMSG`:

    +trevarj.github.io/audio=1

The value is the format version. Receivers MUST ignore unknown values and render
the body normally. Receivers MUST NOT require the tag because servers,
bouncers, or history providers may strip client-only tags.

No new `CAP` capability is defined. `message-tags` and `CLIENTTAGDENY` already
advertise everything the server must do: relay the optional client-only tag.

Example:

    C: @+trevarj.github.io/audio=1 PRIVMSG alice :[voice 0:14 audio/ogg] https://files.example/voice.ogg
    S: @+trevarj.github.io/audio=1 :bob!user@example.org PRIVMSG alice :[voice 0:14 audio/ogg] https://files.example/voice.ogg

### Waveform fragment

A sender MAY append `motd-wave=<value>` to the URL fragment. `<value>` is
unpadded base64url encoding of:

1. one version byte, currently `0x01`;
2. one unsigned sample-count byte in the range 1 through 96; and
3. that many unsigned five-bit peaks in the range 0 through 31, concatenated
   least-significant bit first and packed least-significant bit first in each
   byte.

Waveforms are display hints. Receivers MUST ignore malformed values. Fragment
parameter order is insignificant.

### Encrypted file-host payload

The optional encrypted envelope protects stored audio from the file host. It
does not hide the key from the IRC server, bouncer, logs, or chat participants.

The uploaded object is:

1. ASCII `MOTDV`;
2. one version byte, currently `0x01`;
3. a 12-byte random AES-GCM nonce; and
4. AES-256-GCM ciphertext followed by its 16-byte authentication tag.

The additional authenticated data is UTF-8 `motd-voice-v1`. The random 32-byte
key is unpadded base64url in the URL fragment as `motd-key=<value>`. The original
cleartext media type remains in the readable message body. `motd-key` and
`motd-wave` MAY coexist in either order.

Receivers MUST authenticate the complete ciphertext before exposing or playing
cleartext and MUST delete partial output after authentication failure.

## File-host interaction

This specification does not define upload discovery or HTTP upload behavior.
Clients SHOULD use `soju.im/FILEHOST` when advertised and otherwise MAY use a
user-selected service. Clients SHOULD inspect HTTP response metadata before
playback and MUST enforce download limits independently of claims in IRC.

## Security considerations

Message text, client-only tags, media types, expiry values, waveform data, URLs,
and downloaded bytes are untrusted input.

Clients MUST NOT autoplay received audio. They MUST apply normal URL safety,
redirect, size, timeout, TLS, cache, and media-decoder protections. URL
fragments containing `motd-key` MUST NOT be sent in HTTP requests or telemetry.
Deleting an IRC message does not guarantee deletion from a file host, bouncer,
logs, caches, or recipients.
