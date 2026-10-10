"""ConsentLens text preprocessing, shared by data building, training and golden-vector export.

`ConsentLensText.kt` in :core mirrors this file exactly; the Kotlin unit test replays golden vectors produced here.
Any change must be made in both places and the golden vectors regenerated.
"""
import unicodedata

MODEL_ID = "ConsentLens-1"
MAX_BYTES = 128
VOCAB = 256  # token = UTF-8 byte value, 0 = padding (NUL is stripped, so text bytes are 1..255)

# Order is the model's output order. Do not reorder without retraining.
ROLES = ["ACCEPT", "REJECT", "MANAGE", "PURPOSE_NOTICE", "PRIVACY_LINK", "WITHDRAW_INFO", "OTHER"]

# Explicit whitespace set, so Python and Kotlin agree (their regex \s classes differ).
_SPACES = set("\t\n\u000b\u000c\r \u0085      　") | {
    chr(c) for c in range(0x2000, 0x200B)
}
_APOSTROPHES = {"‘": "'", "’": "'"}


def normalize(text: str) -> str:
    """NFKC, lower case, curly apostrophes to ', NUL removed, whitespace runs collapsed to one space, trimmed."""
    s = unicodedata.normalize("NFKC", text).lower()
    out = []
    pending_space = False
    for ch in s:
        ch = _APOSTROPHES.get(ch, ch)
        if ch == "\u0000":
            continue
        if ch in _SPACES:
            pending_space = bool(out)
            continue
        if pending_space:
            out.append(" ")
            pending_space = False
        out.append(ch)
    return "".join(out)


def encode(text: str) -> list[int]:
    """UTF-8 bytes of the normalized text, truncated to MAX_BYTES (may cut a multi-byte character), zero padded."""
    b = normalize(text).encode("utf-8")[:MAX_BYTES]
    ids = list(b)
    return ids + [0] * (MAX_BYTES - len(ids))
