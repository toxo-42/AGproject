"""증거자료 CSV 무결성 검증 스크립트 (PC 전용, 앱에 이식되지 않음).

PeOb 앱이 내보내는 zip에는 4개 파일이 들어있다:
    pedal_<타임스탬프>.csv       평문 원시 로그
    pedal_<타임스탬프>.csv.sig   위 CSV에 대한 서명(base64, DER, ECDSA-SHA256/P-256)
    pubkey.pem                   서명 검증용 공개키(폰의 Android Keystore 인증서에서 추출)
    verify_signature.py          바로 이 스크립트(자기 자신도 zip에 동봉됨)

서명은 폰의 하드웨어 보안 저장소(Android Keystore)에만 있는 개인키로 만들어졌고,
그 개인키는 추출이 불가능하다. 그래서 이 스크립트로 검증했을 때 VALID가 나오면
"이 CSV는 서명 당시 그 폰에서 나왔고, 이후 1바이트도 바뀌지 않았다"는 게 보장된다.

⚠️ CSV 파일 자체는 평문이라 내용은 누구나 열람 가능하다(기밀성 없음 — 애초에
   목적이 그게 아니라 무결성·진정성이다. §진행상황_및_로드맵.md Phase E 참고).

⚠️ **외부 패키지 의존성 없음 — Python 3 표준 라이브러리(hashlib/base64)만 사용한다.**
   처음엔 `cryptography` 패키지로 만들었는데, 검증은 이 저장소도 uv도 없는 제3자
   (경찰/보험사) 컴퓨터에서 하는 거라 "pip install"조차 걸림돌이 될 수 있다는 피드백으로
   ECDSA(P-256) 서명 검증을 직접 구현했다(2026-07-14). 그래서 이 파일 하나만 있으면
   `python3 verify_signature.py <csv>` 로 바로 실행된다 — 설치할 것도, 인터넷도 필요 없다.

사용법 (제3자 검증, zip을 그대로 압축 해제한 폴더에서):
    python3 verify_signature.py pedal_20260714_032827.csv
    (같은 폴더의 pedal_20260714_032827.csv.sig 와 pubkey.pem 을 자동으로 찾는다)

    파일명이 다르면 직접 지정:
    python3 verify_signature.py my.csv --sig my.csv.sig --pubkey pubkey.pem
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import sys
from pathlib import Path

# ── secp256r1(P-256) 곡선 파라미터 (NIST 표준값, Android Keystore EC 키와 동일 곡선) ──
_P = 0xFFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF
_A = _P - 3
_N = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551
_GX = 0x6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296
_GY = 0x4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5


def _point_add(p1: tuple[int, int] | None, p2: tuple[int, int] | None) -> tuple[int, int] | None:
    """P-256 위의 점 덧셈(같은 점이면 자동으로 배가 공식 사용). None = 무한원점."""
    if p1 is None:
        return p2
    if p2 is None:
        return p1
    x1, y1 = p1
    x2, y2 = p2
    if x1 == x2 and (y1 + y2) % _P == 0:
        return None
    if p1 == p2:
        lam = (3 * x1 * x1 + _A) * pow(2 * y1, -1, _P) % _P
    else:
        lam = (y2 - y1) * pow((x2 - x1) % _P, -1, _P) % _P
    x3 = (lam * lam - x1 - x2) % _P
    y3 = (lam * (x1 - x3) - y1) % _P
    return (x3, y3)


def _scalar_mult(k: int, point: tuple[int, int]) -> tuple[int, int] | None:
    """더블-앤-애드로 k*point 계산."""
    result: tuple[int, int] | None = None
    addend = point
    while k:
        if k & 1:
            result = _point_add(result, addend)
        addend = _point_add(addend, addend)
        k >>= 1
    return result


def _der_read_tlv(data: bytes, idx: int) -> tuple[int, bytes, int]:
    """DER TLV(Tag-Length-Value) 하나를 읽어 (tag, value, 다음 idx)를 반환.
    짧은/긴 길이 인코딩 둘 다 지원(공개키 인증서·서명 둘 다 이 정도면 충분)."""
    tag = data[idx]
    idx += 1
    first_len = data[idx]
    idx += 1
    if first_len & 0x80 == 0:
        length = first_len
    else:
        n = first_len & 0x7F
        length = int.from_bytes(data[idx:idx + n], "big")
        idx += n
    value = data[idx:idx + length]
    idx += length
    return tag, value, idx


def _parse_ec_public_key_pem(pem_text: str) -> tuple[int, int]:
    """X.509 SubjectPublicKeyInfo PEM -> (x, y) 좌표. secp256r1 EC 키 전용."""
    lines = [ln.strip() for ln in pem_text.splitlines() if ln.strip() and "-----" not in ln]
    der = base64.b64decode("".join(lines))

    tag, seq_value, _ = _der_read_tlv(der, 0)
    if tag != 0x30:
        raise ValueError("공개키 형식이 예상과 다릅니다(SEQUENCE 아님)")

    # SEQUENCE { algorithm SEQUENCE, subjectPublicKey BIT STRING }
    _, _, idx = _der_read_tlv(seq_value, 0)          # algorithm identifier — 내용은 안 봄
    tag2, bit_string, _ = _der_read_tlv(seq_value, idx)
    if tag2 != 0x03:
        raise ValueError("공개키 형식이 예상과 다릅니다(BIT STRING 아님)")

    point = bit_string[1:]  # 첫 바이트는 '미사용 비트 수'(EC 키는 항상 0)
    if point[0] != 0x04:
        raise ValueError("압축되지 않은 EC 포인트(0x04로 시작)가 아닙니다")
    x = int.from_bytes(point[1:33], "big")
    y = int.from_bytes(point[33:65], "big")
    return x, y


def _parse_ecdsa_signature_der(sig_der: bytes) -> tuple[int, int]:
    """ECDSA-Sig-Value ::= SEQUENCE { r INTEGER, s INTEGER } -> (r, s)."""
    tag, seq_value, _ = _der_read_tlv(sig_der, 0)
    if tag != 0x30:
        raise ValueError("서명 형식이 예상과 다릅니다(SEQUENCE 아님)")
    tag_r, r_bytes, idx = _der_read_tlv(seq_value, 0)
    tag_s, s_bytes, _ = _der_read_tlv(seq_value, idx)
    if tag_r != 0x02 or tag_s != 0x02:
        raise ValueError("서명 형식이 예상과 다릅니다(INTEGER 아님)")
    return int.from_bytes(r_bytes, "big"), int.from_bytes(s_bytes, "big")


def _ecdsa_verify(pub_point: tuple[int, int], message: bytes, r: int, s: int) -> bool:
    """표준 ECDSA 검증 (FIPS 186-4). SHA-256 해시, P-256 곡선 고정."""
    if not (1 <= r < _N and 1 <= s < _N):
        return False
    e = int.from_bytes(hashlib.sha256(message).digest(), "big")
    w = pow(s, -1, _N)
    u1 = (e * w) % _N
    u2 = (r * w) % _N
    point = _point_add(_scalar_mult(u1, (_GX, _GY)), _scalar_mult(u2, pub_point))
    if point is None:
        return False
    return point[0] % _N == r % _N


def verify(csv_path: Path, sig_path: Path, pubkey_path: Path) -> bool:
    pub_point = _parse_ec_public_key_pem(pubkey_path.read_text())
    signature_der = base64.b64decode(sig_path.read_text().strip())
    r, s = _parse_ecdsa_signature_der(signature_der)
    data = csv_path.read_bytes()
    return _ecdsa_verify(pub_point, data, r, s)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("csv", type=Path, help="검증할 CSV 파일")
    parser.add_argument("--sig", type=Path, default=None, help="서명 파일(기본값: <csv>.sig)")
    parser.add_argument("--pubkey", type=Path, default=None, help="공개키 파일(기본값: 같은 폴더의 pubkey.pem)")
    args = parser.parse_args()

    csv_path: Path = args.csv
    sig_path: Path = args.sig or csv_path.with_name(csv_path.name + ".sig")
    pubkey_path: Path = args.pubkey or csv_path.with_name("pubkey.pem")

    for p, label in [(csv_path, "CSV"), (sig_path, "서명"), (pubkey_path, "공개키")]:
        if not p.exists():
            print(f"❌ {label} 파일을 찾을 수 없습니다: {p}")
            sys.exit(2)

    try:
        ok = verify(csv_path, sig_path, pubkey_path)
    except Exception as e:
        print(f"❌ 검증 중 오류: {e}")
        sys.exit(2)

    if ok:
        print(f"✅ VALID — {csv_path.name} 은(는) 서명 이후 변조되지 않았습니다.")
        sys.exit(0)
    else:
        print(f"🚨 INVALID — {csv_path.name} 이(가) 변조됐거나, 서명/공개키가 맞지 않습니다.")
        sys.exit(1)


if __name__ == "__main__":
    main()
