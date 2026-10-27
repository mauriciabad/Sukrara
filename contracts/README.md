# contracts — what both V1 apps agree on

The cross-platform specs, schemas and fixtures Sukrara V1's Android ([`apps/android`](../apps/android/)) and Mac
([`apps/macos`](../apps/macos/)) apps consume and are tested against, so the two never diverge: the alert rules spec
and timeline scenarios, the graph / card fixtures and the surface registry, the settings schema, durations, CSV
export fixtures, treatment form, the Nostr wire and identity protocols with their test vectors, and the brand assets.
A change here lands with both implementations in the same change.

Empty for now: each contract is brought over from the frozen PoC ([`poc/spikes`](../poc/spikes/)) when V1 starts
consuming it, so the PoC keeps its own frozen copy.
