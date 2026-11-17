# Wycheproof test vectors (test data only)

Source: https://github.com/C2SP/wycheproof, `testvectors_v1/ed25519_test.json` and `x25519_test.json` from the `main` branch, fetched 2026-11-17.
SHA-256 of the files as stored here:

    ea80ffebac085e21cb9d3e903e7fa2b448d96702da5b31f061d0b83b430b74b3  ed25519_test.json
    35c3f5231cf25cc640b524d403461deee9e49441d5d915a3a25b2c8ff5adbe7d  x25519_test.json

Licence: Apache-2.0 (Project Wycheproof, Google / C2SP). Used only by `Curve25519WycheproofTest` to check our hand-written Ed25519 and X25519
against vectors produced by other implementations. Not a dependency: nothing from that project is linked or shipped.
