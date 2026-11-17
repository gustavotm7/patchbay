# Known-answer vectors from the tinytuya codec (independent of our Kotlin). Run with the scratch venv.
import hmac, hashlib, struct, json
from tinytuya.core.message_helper import pack_message, TuyaMessage
from tinytuya.core.crypto_helper import AESCipher
from tinytuya.core import header as H

key = b"0123456789abcdef"
sess = bytes.fromhex("00112233445566778899aabbccddeeff")
iv = bytes.fromhex("a0a1a2a3a4a5a6a7a8a9aaab")
local_nonce = bytes.fromhex("101112131415161718191a1b1c1d1e1f")
remote_nonce = bytes.fromhex("202122232425262728292a2b2c2d2e2f")

def h(b): return b.hex()

# 3.4 client frame: DP_QUERY_NEW with "{}" encrypted under the session key, HMAC under the session key.
enc = AESCipher(sess).encrypt(b"{}", False)
msg = TuyaMessage(5, 0x10, None, enc, 0, True, H.PREFIX_55AA_VALUE, None)
print("V34_QUERY_ENC", h(enc))
print("V34_QUERY_FRAME", h(pack_message(msg, hmac_key=sess)))

# 3.4 device-style frame: a retcode (0) then the encrypted reply, HMAC over everything before it.
reply = b'{"data":{"dps":{"20":true}},"protocol":4,"t":1700000000}'
enc_reply = AESCipher(sess).encrypt(reply, False)
dev = TuyaMessage(5, 0x10, None, struct.pack(">I", 0) + enc_reply, 0, True, H.PREFIX_55AA_VALUE, None)
print("V34_DEVICE_ENC", h(enc_reply))
print("V34_DEVICE_FRAME", h(pack_message(dev, hmac_key=sess)))

# 3.5 client frame: GCM under the session key with a fixed IV.
msg = TuyaMessage(7, 0x0D, None, H.PROTOCOL_35_HEADER + b'{"protocol":5,"t":1,"data":{"dps":{"20":false}}}', 0, True, H.PREFIX_6699_VALUE, iv)
print("V35_CLIENT_FRAME", h(pack_message(msg, hmac_key=sess)))
# 3.5 device-style frame: the retcode sits inside the sealed plaintext.
dev = TuyaMessage(7, 0x10, 0, reply, 0, True, H.PREFIX_6699_VALUE, iv)
print("V35_DEVICE_FRAME", h(pack_message(dev, hmac_key=sess)))

# Session keys, exactly as XenonDevice._negotiate_session_key_generate_finalize does it.
xor = bytes(a ^ b for a, b in zip(local_nonce, remote_nonce))
print("SESSION_34", h(AESCipher(key).encrypt(xor, False, pad=False)))
print("SESSION_35", h(AESCipher(key).encrypt(xor, use_base64=False, pad=False, iv=local_nonce[:12])[12:28]))

# Negotiation messages: step 3 payload and the HMAC the device returns in step 2.
print("HMAC_LOCAL_NONCE", h(hmac.new(key, local_nonce, hashlib.sha256).digest()))
print("HMAC_REMOTE_NONCE", h(hmac.new(key, remote_nonce, hashlib.sha256).digest()))
