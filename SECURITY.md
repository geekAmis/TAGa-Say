# Security Policy

## Reporting a vulnerability

If you discover a security issue, **do not open a public issue**.
Report it privately to the project owner: <https://vk.ru/rf_dron>.

Please include:
- affected component and version;
- reproduction steps or proof of concept;
- impact assessment;
- whether you want to be credited.

We aim to acknowledge reports within 72 hours and to ship a fix for critical issues
as soon as possible. Coordinated disclosure is appreciated.

## Scope

In scope:
- Android client (`android/`);
- server (`server/`);
- cryptographic protocol and key management;
- authentication and authorization;
- metadata leakage.

Out of scope:
- social engineering;
- physical access to an unlocked device;
- vulnerabilities in third-party dependencies without a practical exploit path
  (please report upstream as well).

## Cryptographic notice

TAGa-Say does not implement its own primitives. It relies on:
- [libsignal](https://github.com/signalapp/libsignal) — X3DH, Double Ratchet, Sender Keys;
- [libsodium](https://libsodium.org) — XChaCha20-Poly1305, Argon2id, Ed25519/X25519;
- Android Keystore / StrongBox — hardware-backed key storage;
- WebRTC — media transport, with an additional E2EE layer.

## Supported versions

Only the latest release receives security fixes during the 1.x line.

## Release 1.0.0 — security status

1.0.0 is a **release candidate for closed deployments**, not a claim of
government-grade certification. Before handling data that can endanger people:

- commission an independent cryptographic audit and penetration test;
- review the threat model with your security service;
- deploy the relay in an isolated segment, with your own domain and certificate;
- disable Google services on devices where required by policy;
- verify the absence of `prompt.txt`, signing keys and `.env` in the build.

Known limitations: no WebSocket delivery, no multi-device, no audited
group/call protocols (UI scaffolding only), and the relay still uses a JSON
snapshot instead of PostgreSQL/Redis. **Sealed sender is implemented (1.0.1):
the relay does not persist the sender username for sealed envelopes.** See
`docs/release.md` for the full checklist.

