# Issue 61: compatible playback capability

Verified 2026-11-16 against main 52f8045. The title is one series, “无限白粥，朕可是忠臣啊” (7703065438208412697).

The mobile model request advertised device_level=3. Live responses for episodes 7703065456646556696 and 7703065456650767384 contained only bytevc2 variants, which the platform player correctly excludes. Repeated requests after session initialization reproduced an empty playable ladder. Initial anonymous registration/model calls also occasionally timed out; this change does not claim to fix network timeouts.

Request device_level=1 to negotiate platform-compatible variants. With the same request path, both episodes returned five bytevc1 variants and RemoteVideo.prepare plus a 65,536-byte read succeeded. The control series “朕可是忠臣” episodes 1 and 2 also succeeded. This verifies model parsing and video reads, not rendered playback on a physical TV.

Reproduce with `:core:run --args=--issue61`. The probe samples the reported title, a control, then the reported title again to distinguish cold-session failures. It prints counts and IDs, never video URLs or credentials. The request contract test checks the capability tier together with compressed-body/signature consistency.
