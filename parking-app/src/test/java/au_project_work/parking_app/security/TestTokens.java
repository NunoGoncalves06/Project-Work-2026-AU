package au_project_work.parking_app.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

import java.time.Instant;
import java.util.Date;
import java.util.List;

// tokens shaped like Cognito access tokens (RS256, kid, no typ, no aud), signed with keys made for this test run
final class TestTokens {

    // must match the cognito.* test properties: https://cognito-idp.<region>.amazonaws.com/<pool id>
    static final String ISSUER = "https://cognito-idp.eu-north-1.amazonaws.com/eu-north-1_Test";
    static final String CLIENT_ID = "test-client";

    static final RSAKey KEY = newKey();
    static final RSAKey OTHER_KEY = newKey(); // same key id, different key: a forged signature

    private TestTokens() {
    }

    static JWTClaimsSet.Builder claims() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject("user-1")
                .claim("client_id", CLIENT_ID)
                .claim("token_use", "access")
                .claim("username", "ana")
                .claim("cognito:groups", List.of("viewer"))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)));
    }

    static String sign(RSAKey key, JWTClaimsSet.Builder claims) {
        try {
            return sign(JWSAlgorithm.RS256, new RSASSASigner(key), claims);
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    // key confusion: HS256 with the pool's public key as the shared secret
    static String hs256WithPublicKey(JWTClaimsSet.Builder claims) {
        try {
            return sign(JWSAlgorithm.HS256, new MACSigner(KEY.toRSAPublicKey().getEncoded()), claims);
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    // alg "none": no signature at all
    static String unsigned(JWTClaimsSet.Builder claims) {
        return new PlainJWT(claims.build()).serialize();
    }

    private static String sign(JWSAlgorithm alg, JWSSigner signer, JWTClaimsSet.Builder claims) throws JOSEException {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(alg).keyID(KEY.getKeyID()).build(), claims.build());
        jwt.sign(signer);
        return jwt.serialize();
    }

    private static RSAKey newKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-key").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
