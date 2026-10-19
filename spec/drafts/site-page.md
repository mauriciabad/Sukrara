# Draft: a page on sukrara.mauri.app

**Not published.** To publish it, the maintainer:
1. picks the licence;
2. creates the spec repository (docs/24b → Publishing);
3. replaces `<spec repo>` below with its URL;
4. copies everything after the line into `docs/ogs.md` (the site build turns `docs/` into sukrara.mauri.app);
5. links it from `docs/index.md`.

---

## Share your glucose without a cloud: the Open Glucose Stream

Sukrara shares your glucose with the people you choose. We're turning the way it does that into an **open
standard**, the Open Glucose Stream (OGS), so any app can join in: a watch, a widget, Home Assistant, a clinic's
viewer, or another CGM app your family already uses.

- **Private by design.** Readings are encrypted on your phone, and only the people you approved can read them.
  Relays only pass them on. Nothing public says that a stream is yours: the keys that relays see change every day,
  and no name, serial number or account is in the data.
- **No account, no server.** Public Nostr relays carry it when you're apart, and Bluetooth when you're close, even
  with no internet.
- **You decide what, and for how long.** An invite says what it offers: whether they can add insulin and carbs, how
  much history they see, and for how many days. A babysitter for the weekend, a school trip, the clinic before your
  appointment: the access ends by itself, enforced by the encryption, not by a promise. Removing someone changes the
  keys, so they get nothing new and can't even recognise your stream any more.
- **Made for carers.** Parents can both manage a child's stream. Carers can acknowledge an alert, so the others know
  someone is on it. An optional emergency card lets a paramedic see your glucose for a day, and you're always told.
- **Open.** The specification, test vectors and an independent reference implementation are public:
  [the OGS specification](<spec repo>).

**Where things stand.** The current draft is **draft 3**, a proposal no app speaks yet. Sukrara 0.11 uses the
earlier draft 1, and moving to draft 3 means inviting your followers again. We'll say so clearly before it happens.

**For developers.** The [conformance checklist](<spec repo>/blob/main/spec/conformance.md) says what a reader,
contributor, publisher or administrator app must do. The [test vectors](<spec repo>/tree/main/test-vectors) let you
check your app byte for byte without ever touching real health data. The bridges section maps everything to
Nightscout, HL7 FHIR, Health Connect, HealthKit and Tidepool.

*Not a medical device. A follower's copy can be late. Don't base treatment decisions on it.*
