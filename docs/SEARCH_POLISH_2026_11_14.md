# Search polish validation — 2026-11-14

- Increased input/key/candidate typography and explicit focus contrast; equal-width two-column candidates.
- Replaced horizontal scroll wrapper in grid mode. Candidate heights adapt to system font scale.
- Full-width search results use landscape cover/title cards; selected title shown above grid.
- Result up-navigation targets visible back control. Back restores selected candidate and draft across panel recreation.
- Preserve server results for Latin/pinyin queries instead of discarding Chinese titles by substring matching. Existing Chinese title relevance tests still pass.
- Sparse first pages prefetch until 12 entries or page 5; normal pagination remains available afterward.

## Evidence

375 app tests and 370 core tests passed. Debug APK installed on emulator-5554 (1920×1080, font scale 1.5). Real cs search loaded 18 entries and down navigation increased to 26. Recorded remote focus before and after search/back was the same candidate: 重生八零：旺夫军嫂她C位出道. Final screenshots: outputs/search-polish/01-suggestions.png and 02-results.png. Final APK: outputs/hongguotv-0.15.1-search-polish-v2-debug.apk.

Decoder libraries retained from checksum-verified 0.15.1 release, unchanged decoder sources. Physical TV and subjective viewing-distance acceptance remain with the user. No release published.
