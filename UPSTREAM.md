# Upstream provenance

The extension module follows the current Keiyoushi `extensions-source` API and is built against:

- Repository: <https://github.com/keiyoushi/extensions-source>
- Commit: `b0fc6429905a707ea59e107dd40b37f6edd570ee`
- License: Apache License 2.0

The upstream repository is downloaded only at build time. Its build logic, source code, license,
and NOTICE files are not copied or relicensed as part of this MIT-licensed repository. Generated
build outputs may contain the notices and metadata produced by the upstream toolchain.

The Talebook comic contract used by this extension was merged in:

- Pull request: <https://github.com/talebook/talebook/pull/1012>
- Merge commit: `6d027fea896c54237131a20091a65c077d989087`

The extension consumes only the documented HTTP response fields and signed page URLs. It does not
copy Talebook server implementation code or expose Calibre/Talebook filesystem paths.
