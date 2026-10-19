package com.cleo.cleos.desktop

import java.math.BigInteger
import java.security.*
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/** A fresh identity for each QR. Neither private key nor certificate enters a profile. */
internal data class TransferCertificate(val context:SSLContext,val fingerprint:String) {
    companion object {
        fun create():TransferCertificate {
            val random=SecureRandom();val provider=BouncyCastleProvider()
            val pair=KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"),random) }.generateKeyPair()
            val name=X500Name("CN=Cleos temporary transfer");val now=System.currentTimeMillis()
            val signer=JcaContentSignerBuilder("SHA256withECDSA").setProvider(provider).build(pair.private)
            val holder=JcaX509v3CertificateBuilder(name,BigInteger(128,random),Date(now-300000),Date(now+86400000),name,pair.public).build(signer)
            val certificate=JcaX509CertificateConverter().setProvider(provider).getCertificate(holder)
            val password=ByteArray(24).also(random::nextBytes).joinToString(""){"%02x".format(it)}.toCharArray()
            try {
                val store=KeyStore.getInstance("PKCS12").apply { load(null,password);setKeyEntry("transfer",pair.private,password,arrayOf(certificate)) }
                val manager=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store,password) }
                val context=SSLContext.getInstance("TLS").apply { init(manager.keyManagers,null,random) }
                return TransferCertificate(context,MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString(""){"%02x".format(it)})
            } finally { password.fill('\u0000') }
        }
    }
}
