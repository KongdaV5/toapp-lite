# WebtoApp filter subscriptions

When ad blocking is enabled, generated apps periodically retrieve public text filter lists from their original maintainers. WebtoApp does not bundle or execute remote JavaScript from these lists. It parses only a limited, non-executable subset of network and cosmetic rules.

## Enabled subscriptions

1. EasyList
   - Source: https://easylist-downloads.adblockplus.org/easylist.txt
   - Project and licence: https://easylist.to/pages/licence.html

2. EasyList China
   - Source: https://easylist-downloads.adblockplus.org/easylistchina.txt
   - Project: https://easylist.to/pages/other-supplementary-filter-lists-and-easylist-variants.html

3. AdGuard Mobile Ads filter
   - Source: https://filters.adtidy.org/android/filters/11_optimized.txt
   - Project: https://github.com/AdguardTeam/AdguardFilters
   - Licence: GPL-3.0

4. CJX Annoyance List
   - Source: https://fastly.jsdelivr.net/gh/cjx82630/cjxlist/cjx-annoyance.txt
   - Project: https://github.com/cjx82630/cjxlist
   - Use is subject to the upstream project's current licence and notices.

## Update behaviour

- Each source is checked no more than once every three days when the generated app starts.
- A failed update leaves the last successful local copy in place.
- Invalid HTML responses and oversized files are rejected.
- Scriptlets, redirects, content transformation and other executable/advanced rules are ignored.

The bundled `bootstrap.txt` fallback is authored for WebtoApp and is not copied from the subscriptions above.
