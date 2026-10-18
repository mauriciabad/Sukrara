# Draft messages to other projects

**Nothing here has been sent.** Each text is a draft for the maintainer to adapt and post (or not) on the project's
own public channel: GitHub Discussions or an issue, never private contact details. Send them after draft 2 and the
public spec page, so the links are stable.

## xDrip+ (NightscoutFoundation/xDrip; the GluPro / libglupro authors)

> Hi! We've written an open, vendor-neutral protocol for sharing a live CGM stream with followers. It's end-to-end
> encrypted, runs over public Nostr relays or Bluetooth, and needs no server or account:
> <spec link>. Sukrara (Android/Mac, GPL-3.0) implements it, and there's an independent Python reference with test
> vectors.
>
> Two ways it could meet xDrip+:
> 1. **xDrip+ as a follower:** a new follower source next to Nightscout and Dexcom Share follow. It needs NIP-44 and
>    NIP-59 (one Java/Kotlin library), and the test vectors cover all of it.
> 2. **The other way round:** an OGS app can expose the Bluetooth SIG CGM Service (0x181F) read-only, so the GluPro
>    collector can show a shared stream. We matched GluPro's parser (Nordic library) for the field layout. Would you
>    accept a bridge that isn't a real sensor? What would you need it to say about itself?
>
> Feedback on the data model (mg/dL integers, rate in mg/dL/min, treatments, alerts) is very welcome.

## Juggluco (j-kaltes/Juggluco)

> Hi Jaap, Sukrara grew out of reading Juggluco's code (thank you!), and it still reads Juggluco's web server as one of
> its sources. We've now written down the sharing protocol it uses, as an open spec: <spec link>. Juggluco already has
> its own mirror. OGS is for the case where the follower is a different app, or is on another network, with no port
> forwarding. Would a Juggluco "share via OGS" option be interesting? The owner side needs secp256k1 Schnorr signing,
> NIP-44 and a websocket. We can help with test vectors.

## GlucoDataHandler (pachi81/GlucoDataHandler)

> Hi! GlucoDataHandler already gathers glucose from many sources. We'd like to suggest one more: an OGS follower
> source (<spec link>). It's end-to-end encrypted sharing over Nostr relays or Bluetooth, joined with a 63-character
> invite link and the wearer's approval. As a follower, GDH would show the stream on the watch, in Android Auto and
> in widgets without any cloud account. The follower side is about NIP-44, NIP-59 and a websocket, and the spec has
> a conformance checklist and test vectors.

## Nightscout (nightscout/cgm-remote-monitor)

> Hi! OGS (<spec link>) is a serverless sharing protocol whose data model is close to Nightscout's: `sgv` in mg/dL,
> directions from the rate, and treatments that map onto Correction Bolus, Carb Correction and Note. We'd value your
> review of the mapping (ogs-data D§2, D§6.3, D§9), and in particular how long-acting insulin should map. Two
> possible bridges: an OGS follower that serves a local Nightscout API (Sukrara does that), or a Nightscout plugin
> that publishes a site's entries as an OGS stream.

## LoopFollow / nightguard (iOS followers)

> Hi! For parents and partners following a CGM, OGS (<spec link>) offers an end-to-end encrypted alternative to
> Nightscout and Dexcom Share, with no server. A Swift follower exists (Sukrara for Mac, GPL-3.0). It needs NIP-44 and
> NIP-59, which rust-nostr's Swift package provides. Would an OGS source fit LoopFollow or nightguard? The spec has a
> conformance checklist for followers and a 25-step acceptance scenario with test vectors.

## Nostr developers (after the NIP PR)

> A note or long-form post (from a project account, **never from the stream keys**) linking the NIP PR, the reference
> and the privacy section. The ask: review the invite flow, the use of NIP-59, and the kind numbers.
