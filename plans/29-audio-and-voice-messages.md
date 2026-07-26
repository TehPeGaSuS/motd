# Audio links and voice messages

## Summary

Issue #7 is implemented as one staged feature covering:

- Inline players for recognized audio links in all ordinary chat messages.
- Voice recording in channels and PMs, but not server buffers.
- Bouncer-aware and external hosting, optional host-blind encryption, and
  durable IRC fallbacks.
- DCC and true peer-key E2EE remain explicit non-goals.

## Implementation

- Introduce app-layer `AudioAttachment`, `AudioMetadata`,
  `VoiceRecordingState`, `VoiceSendState`, `PlaybackState`, `VoiceRecorder`,
  `VoiceMessageSender`, `VoicePrefs`, `AudioPlaybackController`, and
  `NetworkMediaRouteProvider` boundaries.
- Parse known audio extensions (`mp3`, `opus`, `ogg`, `oga`, `m4a`, `aac`,
  `wav`, `flac`, `webm`) immediately. Resolve extensionless HTTPS audio through
  bounded HEAD requests only when rich previews are enabled. HTTP audio requires
  confirmation before playback.
- Render up to three compact players per message with an expand control for the
  remainder. Preserve surrounding prose, but replace a voice-only fallback with
  the player.
- Provide play/pause, elapsed and total time, scrubbing, loading/error states,
  and `1x`/`1.5x`/`2x` for marked voice messages. Long press opens filename,
  host, URL, MIME, size, duration, expiry, and encryption details plus Copy link,
  Open externally, and Save actions.
- Pin Media3 `1.10.1`; add ExoPlayer, session, and data-source integration. A
  `MediaSessionService` owns one active item across screens and background
  playback, exposes system controls, and drives a persistent in-app mini-player.
  Completion stops without autoplay.
- Use speech/media audio attributes with automatic focus handling. Starting
  recording pauses playback, and message sound cues are suppressed while
  recording or voice playback is active.
- Keep a 128 MiB app-private LRU audio cache with a Clear audio cache setting.
  Encrypted downloads cache ciphertext; decrypted files remain temporary and
  are removed when replaced or during stale-file cleanup.
- Show a microphone when the composer is empty. Hold records, slide left
  cancels, slide up locks hands-free, and tap starts locked mode for
  accessibility. Releasing or stopping stages a preview with play, scrub,
  delete, destination, expiry, encryption, and Send controls.
- Request microphone permission only when recording starts. Auto-stop at 30
  minutes. When the app leaves the foreground, stop safely and stage the
  recording rather than continuing with a microphone service.
- Record Ogg/Opus on Android 10+ and MPEG-4/AAC on Android 8-9. Discard invalid
  or sub-second captures and sweep abandoned cache files.
- Send only after explicit confirmation: Send uploads, then publishes
  automatically; failures retain the local recording and expose retry,
  destination change, or delete.
- Prefer an HTTPS `soju.im/FILEHOST` endpoint advertised by the active network.
  Extend the IRC ready-state ISUPPORT snapshot to expose this token, probe
  `OPTIONS`, honor `Accept-Post`, stream authenticated `POST`, and resolve the
  `201 Location` result. The extension remains optional because it is
  work-in-progress. [Soju specification](https://codeberg.org/emersion/soju/src/branch/master/doc/ext/filehost.md)
- Resolve bouncer-child credentials through the root connection. Use Basic
  authentication for SASL PLAIN, client-certificate TLS for compatible EXTERNAL
  endpoints, and unauthenticated requests only when the endpoint accepts them.
  Never log authorization material.
- If FILEHOST is unavailable, prompt once for a dedicated remembered voice
  destination selected from existing binary upload backends. Temporary services
  remain available but require confirmation and display their expiry. FILEHOST
  failure never silently switches hosts.
- Route upload, metadata, and playback traffic through the source network's
  proxy or obfuscation path. Hold an embedded-proxy lease for background
  playback and reuse existing pinning, KeyChain, and hostname-verification
  policy where the media origin matches the IRC endpoint.
- Default encryption off with a per-recording override and a settings default.
  Host-blind mode uses a versioned AES-256-GCM envelope; the random key travels
  as `#motd-key=<base64url>` and is stripped from HTTP requests. Explain that
  the host cannot decrypt it but IRC servers and bouncers can see the key.
- Use stable visible wire forms with no proprietary client tags:
  - `[voice 0:14 audio/ogg] https://host/file.ogg`
  - `[voice 0:14 audio/ogg expires=72h] https://host/file.ogg`
  - `[voice encrypted 0:14 audio/ogg] https://host/file.motdvoice#motd-key=...`
- Add dedicated voice preferences to configuration backup/restore. Exclude cache
  contents, recordings, upload history, and encryption keys. No Room migration is
  required.

The visible fallback remains authoritative because IRCv3 client-only tags may be
blocked or unavailable in other clients and history paths. [IRCv3 message tags](https://ircv3.net/specs/extensions/message-tags)
Media3's service model supplies the agreed background player and notification
behavior. [Android background playback](https://developer.android.com/media/media3/session/background-playback)

## Test plan

- Unit-test URL extraction, extension and MIME classification, fallback grammar,
  expiry/encryption parsing, HTTP confirmation, multiple-link limits, and
  code-span exclusions.
- Mock HEAD redirects, MIME/length headers, timeout and downgrade rejection,
  proxy selection, FILEHOST discovery, authentication, `OPTIONS`, relative
  `Location`, 413 handling, cancellation, and explicit fallback selection.
- Test AES-GCM round trips, unique nonces, tamper/wrong-key rejection, fragment
  stripping, plaintext cleanup, and bounded cache eviction.
- Test recorder state transitions, gestures, permission denial, foreground exit,
  duration limit, short recordings, preview deletion, upload retry, and send
  acceptance/rejection.
- Test one-active-player behavior, seeking, speed controls, background
  controller reconnection, audio focus, cue suppression, mini-player state, and
  no autoplay.
- Add stable semantics tags for recording, preview, upload, player, scrubber,
  details, encryption, and cache controls.
- Run `:irc:test`, FOSS debug/release unit tests, FOSS lint, release assembly,
  and E2E test-source compilation through `nix develop`; do not run
  emulator/device E2E unless separately requested.

## Assumptions

- This plan records the design contract; implementation remains governed by the
  current code, tests, Gradle configuration, and repository policy.
- Public-host policies are rechecked while implementing, but temporary hosts
  remain opt-in and visibly expiring.
- Media3 `1.10.1` is the stable dependency target; Android's platform codec
  fallback avoids native codec dependencies. [Android supported formats](https://developer.android.com/media/platform/supported-formats)
