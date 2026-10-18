# Draft: a page on sukrara.mauri.app

**Not published.** To publish it, the maintainer copies this file to `docs/ogs.md` (the site build turns `docs/` into
sukrara.mauri.app) and links it from `docs/index.md`. The spec itself lives in a versioned repository (see docs/24 →
Publishing).

---

## Share your glucose without a cloud: the Open Glucose Stream

Sukrara shares your glucose with the people you choose. It does that through an **open protocol** called OGS, so
other apps can follow you too: a watch, a widget, Home Assistant, or another CGM app your family already uses.

- **Private.** Readings are encrypted on your phone. Only followers you approved can read them. Relays only pass
  them on, and can't read them.
- **No account, no server.** Public Nostr relays carry it when you're apart, and Bluetooth when you're close, even
  with no internet.
- **You decide.** Each follower joins with a one-time link and your *Allow*. Removing someone changes the key, so
  they get nothing new.
- **Open.** The spec, test vectors and an independent reference implementation are public:
  [the OGS specification](<spec repo link>).

For developers: the [conformance checklist](<link>) says what a follower, writer or owner app must do. The
[test vectors](<link>) let you check your app without ever touching real health data.

*Not a medical device. A follower's copy can be late. Don't base treatment decisions on it.*
