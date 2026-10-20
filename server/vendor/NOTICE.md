# Third-party source

`hongguo.cjs` is a copy of `spider/js/红果果[短].js` from
https://github.com/woshishiq1/drpys/tree/22261adfa31435e3b3ef5a730b8a2a75bcaf1715
retrieved on 2026-09-21. Upstream license: GPL-3.0; see LICENSE.drpys.
The filename was changed for local loading. The original header and comments remain.
The source is loaded only by the Node content service, not bundled into the TV app.
The service adapter and HTTP layer are original project code.

## Local modifications (2026-10-20)

The pinned file is no longer an unmodified copy. Two fixes were applied inside
`hongguo.cjs`; both are in the media path and neither changes the data contracts:

- `remoteRange` now parses the total length with the new helper `parseContentTotal`,
  which tolerates `Content-Range: bytes 0-262143/*`, spaces around the slash and
  case variants. `prepareHeader` now fails with `媒体总长度无法确定` when the total
  really cannot be read, instead of silently using the 256KB head length as the
  file size (which truncated playback to 2 seconds and answered `bytes=300000-`
  with 416).
- `prepareCencHeader` now calls `restoreSampleEntry` instead of rewriting every
  `encv` to `hvc1` and every `enca` to `mp4a`. The original sample entry fourcc is
  read from the CENC `frma` box inside `sinf` (the same source the Kotlin client
  uses in `MediaCrypto.kt`), so H.264 content is no longer announced as HEVC.
  `hvc1` is only used when no `frma` box is present.

The other fixes of this round are in the original adapter (`server/provider.mjs`)
and HTTP layer (`server/http.mjs`).

The Android/tvOS project skeleton is derived from
@react-native-tvos/template-tv 0.81.0-0 (MIT); see LICENSE.template-tv.
