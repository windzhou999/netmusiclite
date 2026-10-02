package com.netmusiclite.data

import android.util.Base64
import org.json.JSONObject
import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 网易云 weapi / eapi 加密（移植自已验证的 ncm_decompile/ncm_crypto.py）
 * weapi: AES-128-CBC 双层 + RSA(encSecKey)
 * eapi:  AES-128-ECB + MD5，hex 大写
 */
object NcmCrypto {
    private const val DEBUG_LOG = false // release 置 false：eapi hex 很长，Log 拼接是纯开销
    private const val WEAPI_KEY = "0CoJUm6Qyw8W8jud"
    private const val EAPI_KEY = "e82ckenh8dichen8"
    private const val IV = "0102030405060708"
    private const val RSA_PUB = "010001"
    private const val RSA_MOD =
        "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"

    private val CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    private fun randomKey16(): String = buildString {
        repeat(16) { append(CHARS.random()) }
    }

    /** 字节转 hex（String.format("%02x", byte) 对负数会符号扩展成8位，必须 &0xFF） */
    private fun ByteArray.toHex(upper: Boolean = false): String = joinToString("") {
        val v = (it.toInt() and 0xFF)
        val s = v.toString(16).padStart(2, '0')
        if (upper) s.uppercase() else s
    }

    private fun aesCbcB64(text: String, key: String): String {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"), IvParameterSpec(IV.toByteArray()))
        return Base64.encodeToString(c.doFinal(text.toByteArray()), Base64.NO_WRAP)
    }

    private fun rsaHex(randomKey: String): String {
        val bytes = randomKey.reversed().toByteArray(Charsets.UTF_8)
        val base = BigInteger(1, bytes)
        val e = BigInteger(RSA_PUB, 16)
        val m = BigInteger(RSA_MOD, 16)
        return base.modPow(e, m).toString(16).padStart(256, '0')
    }

    fun weapi(data: JSONObject): Map<String, String> {
        val key = randomKey16()
        val params = aesCbcB64(aesCbcB64(data.toString(), WEAPI_KEY), key)
        return mapOf("params" to params, "encSecKey" to rsaHex(key))
    }

    fun eapi(url: String, data: JSONObject): Map<String, String> {
        val text = data.toString()
        val md5 = MessageDigest.getInstance("MD5")
            .digest("nobody${url}use${text}md5forencrypt".toByteArray())
            .toHex(upper = false)
        val payload = "$url-36cd479b6b5-$text-36cd479b6b5-$md5"
        val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(EAPI_KEY.toByteArray(), "AES"))
        val hex = c.doFinal(payload.toByteArray()).toHex(upper = true)
        if (DEBUG_LOG) android.util.Log.d("NcmEapi", "url=$url text=$text params=$hex")
        return mapOf("params" to hex)
    }
}
