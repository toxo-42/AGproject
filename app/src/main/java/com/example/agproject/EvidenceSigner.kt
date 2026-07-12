package com.example.agproject

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * 증거자료(Phase E) 서명 — 평문 CSV + 분리 서명(.sig) 방식.
 *
 * 목표는 기밀성이 아니라 **무결성·진정성**이다: 이 CSV가 이 폰에서 나왔고,
 * 서명 이후 1바이트도 안 바뀌었다는 걸 제3자(경찰/보험사)가 검증할 수 있게 한다
 * (§진행상황_및_로드맵.md Phase E, 유력안 그대로 구현).
 *
 * 개인키는 Android Keystore 하드웨어 보안 저장소에서만 살고 앱 밖으로 절대 못 나간다
 * (KeyStore가 서명 연산만 대신 해주고 키 자체는 반환하지 않음). 최초 서명 시점에
 * 키가 없으면 자동 생성 — 이후엔 앱 재설치 전까지 같은 키를 계속 쓴다.
 *
 * 검증은 PC 쪽 `prototype/verify_signature.py`가 담당(공개키 + .sig + CSV로 확인).
 */
object EvidenceSigner {

  private const val KEY_ALIAS = "peob_evidence_signing_key"
  private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
  private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

  private fun getOrCreatePrivateKey(): PrivateKey {
    val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
    (keyStore.getKey(KEY_ALIAS, null) as? PrivateKey)?.let { return it }

    val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER)
    val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
      .setDigests(KeyProperties.DIGEST_SHA256)
      .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
      .build()
    generator.initialize(spec)
    return generator.generateKeyPair().private
  }

  /** 파일 전체를 서명하고, base64 문자열로 반환한다(.sig 파일 내용 그대로 쓰면 됨). */
  fun signFile(file: File): String {
    val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
    signature.initSign(getOrCreatePrivateKey())
    file.inputStream().use { input ->
      val buf = ByteArray(8192)
      var n: Int
      while (input.read(buf).also { n = it } > 0) {
        signature.update(buf, 0, n)
      }
    }
    return Base64.encodeToString(signature.sign(), Base64.NO_WRAP)
  }

  /** 검증용 공개키를 PEM(X.509 SubjectPublicKeyInfo) 문자열로 반환 — pubkey.pem 내용 그대로. */
  fun publicKeyPem(): String {
    // 개인키를 먼저 만들어야(없으면) 짝이 되는 인증서/공개키가 Keystore에 생긴다.
    getOrCreatePrivateKey()
    val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
    val encoded = keyStore.getCertificate(KEY_ALIAS).publicKey.encoded
    val base64 = Base64.encodeToString(encoded, Base64.NO_WRAP)
    val wrapped = base64.chunked(64).joinToString("\n")
    return "-----BEGIN PUBLIC KEY-----\n$wrapped\n-----END PUBLIC KEY-----\n"
  }
}
